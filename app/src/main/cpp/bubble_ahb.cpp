#include <jni.h>

#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <android/surface_control_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <atomic>
#include <cstdint>
#include <dlfcn.h>
#include <mutex>
#include <new>
#include <unistd.h>

namespace {

constexpr int kMaxImages = 4;

constexpr uint64_t kConsumerUsage =
    AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
    AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
    AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
    AHARDWAREBUFFER_USAGE_FRONT_BUFFER;

constexpr uint64_t kProducerUsage =
    AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
    AHARDWAREBUFFER_USAGE_FRONT_BUFFER;

constexpr uint64_t kRequiredPresentedUsage =
    AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY |
    AHARDWAREBUFFER_USAGE_FRONT_BUFFER;

// nativePump() status codes. Positive values are the cumulative number of successfully submitted
// AHardwareBuffers. Zero means the shared ImageReader currently has no acquirable buffer.
constexpr jint kPumpNoBuffer = 0;
constexpr jint kPumpMaxImages = -2;
constexpr jint kPumpAcquireError = -3;
constexpr jint kPumpHardwareBufferError = -4;
constexpr jint kPumpUsageMismatch = -5;
constexpr jint kPumpTransactionError = -6;

using SetUsageFn = int (*)(ANativeWindow*, uint64_t);
using SetSharedBufferModeFn = int (*)(ANativeWindow*, bool);
using SetAutoRefreshFn = int (*)(ANativeWindow*, bool);

struct FrontBufferApi {
    void* library = nullptr;
    SetUsageFn setUsage = nullptr;
    SetSharedBufferModeFn setSharedBufferMode = nullptr;
    SetAutoRefreshFn setAutoRefresh = nullptr;

    bool ready() const {
        return setUsage != nullptr && setSharedBufferMode != nullptr && setAutoRefresh != nullptr;
    }
};

void* resolveNativeWindowSymbol(void* library, const char* name) {
    void* symbol = dlsym(RTLD_DEFAULT, name);
    if (symbol == nullptr && library != nullptr) symbol = dlsym(library, name);
    return symbol;
}

FrontBufferApi& frontBufferApi() {
    static FrontBufferApi api = [] {
        FrontBufferApi resolved{};
        resolved.library = dlopen("libnativewindow.so", RTLD_NOW | RTLD_LOCAL);
        resolved.setUsage = reinterpret_cast<SetUsageFn>(
            resolveNativeWindowSymbol(resolved.library, "ANativeWindow_setUsage"));
        resolved.setSharedBufferMode = reinterpret_cast<SetSharedBufferModeFn>(
            resolveNativeWindowSymbol(resolved.library, "ANativeWindow_setSharedBufferMode"));
        resolved.setAutoRefresh = reinterpret_cast<SetAutoRefreshFn>(
            resolveNativeWindowSymbol(resolved.library, "ANativeWindow_setAutoRefresh"));
        return resolved;
    }();
    return api;
}

struct FrameLease {
    AImage* image = nullptr;
};

struct Renderer {
    std::mutex mutex;
    std::atomic<bool> alive{true};
    AImageReader* reader = nullptr;
    ANativeWindow* producerWindow = nullptr;
    ASurfaceControl* outputControl = nullptr;
    float frameRate = 0.0f;
    uint64_t callbackCount = 0;
    uint64_t pumpCount = 0;
    uint64_t submittedCount = 0;
    media_status_t lastAcquireStatus = AMEDIA_OK;
    uint64_t lastUsage = 0;
};

void releaseFrame(void* context, int releaseFenceFd) {
    auto* lease = static_cast<FrameLease*>(context);
    if (lease == nullptr) {
        if (releaseFenceFd >= 0) close(releaseFenceFd);
        return;
    }
    AImage_deleteAsync(lease->image, releaseFenceFd);
    delete lease;
}

void applyFrameRateLocked(Renderer* renderer) {
    if (renderer == nullptr || renderer->frameRate <= 0.0f) return;

    if (renderer->producerWindow != nullptr) {
        ANativeWindow_setFrameRateWithChangeStrategy(
            renderer->producerWindow,
            renderer->frameRate,
            ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_AT_LEAST,
            ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS);
    }

    if (renderer->outputControl != nullptr) {
        ASurfaceTransaction* transaction = ASurfaceTransaction_create();
        if (transaction == nullptr) return;
        ASurfaceTransaction_setFrameRateWithChangeStrategy(
            transaction,
            renderer->outputControl,
            renderer->frameRate,
            ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_AT_LEAST,
            ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS);
        ASurfaceTransaction_setEnableBackPressure(transaction, renderer->outputControl, false);
        ASurfaceTransaction_apply(transaction);
        ASurfaceTransaction_delete(transaction);
    }
}

jint presentLatestImageLocked(Renderer* renderer, AImageReader* reader) {
    if (renderer == nullptr || reader == nullptr || renderer->outputControl == nullptr) {
        return kPumpAcquireError;
    }

    AImage* image = nullptr;
    int acquireFenceFd = -1;
    const media_status_t acquireStatus =
        AImageReader_acquireLatestImageAsync(reader, &image, &acquireFenceFd);
    renderer->lastAcquireStatus = acquireStatus;

    if (acquireStatus == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE || image == nullptr) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        return kPumpNoBuffer;
    }
    if (acquireStatus == AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        return kPumpMaxImages;
    }
    if (acquireStatus != AMEDIA_OK) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        if (image != nullptr) AImage_delete(image);
        return kPumpAcquireError;
    }

    AHardwareBuffer* buffer = nullptr;
    if (AImage_getHardwareBuffer(image, &buffer) != AMEDIA_OK || buffer == nullptr) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        AImage_delete(image);
        return kPumpHardwareBufferError;
    }

    AHardwareBuffer_Desc description{};
    AHardwareBuffer_describe(buffer, &description);
    renderer->lastUsage = description.usage;

    if ((description.usage & kRequiredPresentedUsage) != kRequiredPresentedUsage) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        AImage_delete(image);
        return kPumpUsageMismatch;
    }

    auto* lease = new (std::nothrow) FrameLease{image};
    if (lease == nullptr) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        AImage_delete(image);
        return kPumpHardwareBufferError;
    }

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    if (transaction == nullptr) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        AImage_delete(image);
        delete lease;
        return kPumpTransactionError;
    }

    ASurfaceTransaction_setBufferWithRelease(
        transaction,
        renderer->outputControl,
        buffer,
        acquireFenceFd,
        lease,
        releaseFrame);

    int32_t dataSpace = ADATASPACE_UNKNOWN;
    if (AImage_getDataSpace(image, &dataSpace) == AMEDIA_OK) {
        ASurfaceTransaction_setBufferDataSpace(
            transaction,
            renderer->outputControl,
            static_cast<ADataSpace>(dataSpace));
    }

    if (description.width > 0 && description.height > 0) {
        const ARect crop{
            0,
            0,
            static_cast<int32_t>(description.width),
            static_cast<int32_t>(description.height)};
        ASurfaceTransaction_setCrop(transaction, renderer->outputControl, crop);
        ASurfaceTransaction_setPosition(transaction, renderer->outputControl, 0, 0);
        ASurfaceTransaction_setScale(transaction, renderer->outputControl, 1.0f, 1.0f);
    }

    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
    renderer->submittedCount++;
    return static_cast<jint>(renderer->submittedCount > INT32_MAX ? INT32_MAX : renderer->submittedCount);
}

void onImageAvailable(void* context, AImageReader* callbackReader) {
    auto* renderer = static_cast<Renderer*>(context);
    if (renderer == nullptr || !renderer->alive.load(std::memory_order_acquire)) return;

    std::lock_guard<std::mutex> guard(renderer->mutex);
    if (!renderer->alive.load(std::memory_order_relaxed) || renderer->reader != callbackReader ||
        renderer->outputControl == nullptr) {
        return;
    }
    renderer->callbackCount++;
    (void)presentLatestImageLocked(renderer, callbackReader);
}

Renderer* fromHandle(jlong handle) {
    return reinterpret_cast<Renderer*>(static_cast<uintptr_t>(handle));
}

void destroyRenderer(Renderer* renderer) {
    if (renderer == nullptr) return;
    if (renderer->reader != nullptr) {
        AImageReader_setImageListener(renderer->reader, nullptr);
        AImageReader_delete(renderer->reader);
        renderer->reader = nullptr;
        renderer->producerWindow = nullptr;
    }
    if (renderer->outputControl != nullptr) {
        ASurfaceControl_release(renderer->outputControl);
        renderer->outputControl = nullptr;
    }
    delete renderer;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mekromn_bubble_NativeAhbBridge_nativeCreate(
    JNIEnv* env,
    jobject,
    jint width,
    jint height,
    jobject javaSurfaceControl,
    jfloat frameRate) {
    if (width <= 0 || height <= 0 || javaSurfaceControl == nullptr) return 0;

    FrontBufferApi& frontBuffer = frontBufferApi();
    if (!frontBuffer.ready()) return 0;

    auto* renderer = new (std::nothrow) Renderer();
    if (renderer == nullptr) return 0;
    renderer->frameRate = frameRate;

    renderer->outputControl = ASurfaceControl_fromJava(env, javaSurfaceControl);
    if (renderer->outputControl == nullptr) {
        delete renderer;
        return 0;
    }

    const media_status_t createStatus = AImageReader_newWithUsage(
        width,
        height,
        AIMAGE_FORMAT_PRIVATE,
        kConsumerUsage,
        kMaxImages,
        &renderer->reader);
    if (createStatus != AMEDIA_OK || renderer->reader == nullptr) {
        destroyRenderer(renderer);
        return 0;
    }

    if (AImageReader_getWindow(renderer->reader, &renderer->producerWindow) != AMEDIA_OK ||
        renderer->producerWindow == nullptr) {
        destroyRenderer(renderer);
        return 0;
    }

    if (frontBuffer.setUsage(renderer->producerWindow, kProducerUsage) != 0 ||
        frontBuffer.setSharedBufferMode(renderer->producerWindow, true) != 0 ||
        frontBuffer.setAutoRefresh(renderer->producerWindow, true) != 0) {
        destroyRenderer(renderer);
        return 0;
    }

    ANativeWindow_tryAllocateBuffers(renderer->producerWindow);

    AImageReader_ImageListener listener{};
    listener.context = renderer;
    listener.onImageAvailable = onImageAvailable;
    if (AImageReader_setImageListener(renderer->reader, &listener) != AMEDIA_OK) {
        destroyRenderer(renderer);
        return 0;
    }

    {
        std::lock_guard<std::mutex> guard(renderer->mutex);
        applyFrameRateLocked(renderer);
    }

    return static_cast<jlong>(reinterpret_cast<uintptr_t>(renderer));
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_mekromn_bubble_NativeAhbBridge_nativeGetProducerSurface(
    JNIEnv* env,
    jobject,
    jlong handle) {
    Renderer* renderer = fromHandle(handle);
    if (renderer == nullptr || !renderer->alive.load(std::memory_order_acquire)) return nullptr;
    std::lock_guard<std::mutex> guard(renderer->mutex);
    if (renderer->producerWindow == nullptr) return nullptr;
    return ANativeWindow_toSurface(env, renderer->producerWindow);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mekromn_bubble_NativeAhbBridge_nativePump(
    JNIEnv*,
    jobject,
    jlong handle) {
    Renderer* renderer = fromHandle(handle);
    if (renderer == nullptr || !renderer->alive.load(std::memory_order_acquire)) {
        return kPumpAcquireError;
    }
    std::lock_guard<std::mutex> guard(renderer->mutex);
    if (!renderer->alive.load(std::memory_order_relaxed) || renderer->reader == nullptr) {
        return kPumpAcquireError;
    }
    renderer->pumpCount++;
    const jint status = presentLatestImageLocked(renderer, renderer->reader);
    if (status <= 0 && renderer->submittedCount > 0) {
        return static_cast<jint>(renderer->submittedCount > INT32_MAX ? INT32_MAX : renderer->submittedCount);
    }
    return status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mekromn_bubble_NativeAhbBridge_nativeSetFrameRate(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat frameRate) {
    Renderer* renderer = fromHandle(handle);
    if (renderer == nullptr || !renderer->alive.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> guard(renderer->mutex);
    if (!renderer->alive.load(std::memory_order_relaxed)) return;
    renderer->frameRate = frameRate;
    applyFrameRateLocked(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mekromn_bubble_NativeAhbBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle) {
    Renderer* renderer = fromHandle(handle);
    if (renderer == nullptr) return;

    renderer->alive.store(false, std::memory_order_release);
    AImageReader* readerSnapshot = renderer->reader;
    if (readerSnapshot != nullptr) {
        AImageReader_setImageListener(readerSnapshot, nullptr);
    }

    {
        std::lock_guard<std::mutex> guard(renderer->mutex);
        FrontBufferApi& frontBuffer = frontBufferApi();
        if (renderer->producerWindow != nullptr && frontBuffer.ready()) {
            frontBuffer.setAutoRefresh(renderer->producerWindow, false);
            frontBuffer.setSharedBufferMode(renderer->producerWindow, false);
        }
        if (renderer->reader != nullptr) {
            AImageReader_delete(renderer->reader);
            renderer->reader = nullptr;
            renderer->producerWindow = nullptr;
        }
        if (renderer->outputControl != nullptr) {
            ASurfaceControl_release(renderer->outputControl);
            renderer->outputControl = nullptr;
        }
    }
    delete renderer;
}
