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
#include <mutex>
#include <new>
#include <unistd.h>

// These are LL-NDK/libnativewindow entry points used by Android's EGL mutable-render-buffer path.
// Some NDK header revisions expose them only through the nativewindow VNDK superset, so keep the
// stable C declarations here while linking libnativewindow explicitly.
extern "C" int ANativeWindow_setUsage(ANativeWindow* window, uint64_t usage);
extern "C" int ANativeWindow_setSharedBufferMode(ANativeWindow* window, bool sharedBufferMode);
extern "C" int ANativeWindow_setAutoRefresh(ANativeWindow* window, bool autoRefresh);

namespace {

constexpr int kMaxImages = 4;

// Consumer allocation requirements. FRONT_BUFFER asks gralloc for front-buffer semantics;
// COMPOSER_OVERLAY makes this allocation eligible for direct HWC presentation when submitted via
// ASurfaceTransaction_setBufferWithRelease. GPU_SAMPLED_IMAGE remains required because SurfaceFlinger
// may still fall back to GPU composition. GPU_FRAMEBUFFER guarantees the allocation is writable as a
// GPU color target by Gecko's EGL/WebRender producer.
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

struct FrameLease {
    AImage* image = nullptr;
};

struct Renderer {
    std::mutex mutex;
    std::atomic<bool> alive{true};
    AImageReader* reader = nullptr;
    ANativeWindow* producerWindow = nullptr;  // Owned by AImageReader; never release directly.
    ASurfaceControl* outputControl = nullptr;
    float frameRate = 0.0f;
};

void releaseFrame(void* context, int releaseFenceFd) {
    auto* lease = static_cast<FrameLease*>(context);
    if (lease == nullptr) {
        if (releaseFenceFd >= 0) close(releaseFenceFd);
        return;
    }
    // Return exactly the same AHardwareBuffer to AImageReader only after SurfaceFlinger says it is
    // safe to reuse. AImage_deleteAsync consumes the release fence and keeps this path zero-copy.
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
        // Prefer newest-frame latency. Shared/front-buffer mode already allows simultaneous producer
        // and consumer access; compositor backpressure would only re-introduce queue latency.
        ASurfaceTransaction_setEnableBackPressure(transaction, renderer->outputControl, false);
        ASurfaceTransaction_apply(transaction);
        ASurfaceTransaction_delete(transaction);
    }
}

void onImageAvailable(void* context, AImageReader* callbackReader) {
    auto* renderer = static_cast<Renderer*>(context);
    if (renderer == nullptr || !renderer->alive.load(std::memory_order_acquire)) return;

    std::lock_guard<std::mutex> guard(renderer->mutex);
    if (!renderer->alive.load(std::memory_order_relaxed) || renderer->reader != callbackReader ||
        renderer->outputControl == nullptr) {
        return;
    }

    AImage* image = nullptr;
    int acquireFenceFd = -1;
    const media_status_t acquireStatus =
        AImageReader_acquireLatestImageAsync(callbackReader, &image, &acquireFenceFd);
    if (acquireStatus != AMEDIA_OK || image == nullptr) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        return;
    }

    AHardwareBuffer* buffer = nullptr;
    if (AImage_getHardwareBuffer(image, &buffer) != AMEDIA_OK || buffer == nullptr) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        AImage_delete(image);
        return;
    }

    AHardwareBuffer_Desc description{};
    AHardwareBuffer_describe(buffer, &description);

    // Never silently run the physical test without the requested front-buffer/HWC eligibility.
    if ((description.usage & kRequiredPresentedUsage) != kRequiredPresentedUsage) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        AImage_delete(image);
        return;
    }

    auto* lease = new (std::nothrow) FrameLease{image};
    if (lease == nullptr) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        AImage_delete(image);
        return;
    }

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    if (transaction == nullptr) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        AImage_delete(image);
        delete lease;
        return;
    }

    // SurfaceFlinger takes ownership of acquireFenceFd. The release callback receives a new fence
    // that is handed directly back to AImageReader through AImage_deleteAsync().
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

    // Keep buffer size == layer crop size. No scaling/rotation/intermediate texture is requested,
    // which maximizes the chance that Hardware Composer can assign a physical overlay plane.
    if (description.width > 0 && description.height > 0) {
        const ARect crop{
            0,
            0,
            static_cast<int32_t>(description.width),
            static_cast<int32_t>(description.height)};
        ASurfaceTransaction_setCrop(transaction, renderer->outputControl, &crop);
        ASurfaceTransaction_setPosition(transaction, renderer->outputControl, 0, 0);
        ASurfaceTransaction_setScale(transaction, renderer->outputControl, 1.0f, 1.0f);
    }

    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
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

    // This is the BufferQueue mechanism used underneath EGL_KHR_mutable_render_buffer and
    // EGL_ANDROID_front_buffer_auto_refresh: the producer and consumer share the first allocation,
    // while auto-refresh allows the consumer/compositor to revisit it without waiting for a new
    // conventional back-buffer swap. Fail closed if either request is rejected.
    if (ANativeWindow_setUsage(renderer->producerWindow, kProducerUsage) != 0 ||
        ANativeWindow_setSharedBufferMode(renderer->producerWindow, true) != 0 ||
        ANativeWindow_setAutoRefresh(renderer->producerWindow, true) != 0) {
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

    // Stop new callbacks first. Do not hold renderer->mutex while unregistering/deleting AImageReader:
    // a callback may already have observed alive=true and be waiting for the same mutex.
    renderer->alive.store(false, std::memory_order_release);
    AImageReader* readerSnapshot = renderer->reader;
    if (readerSnapshot != nullptr) {
        AImageReader_setImageListener(readerSnapshot, nullptr);
    }

    {
        std::lock_guard<std::mutex> guard(renderer->mutex);
        if (renderer->producerWindow != nullptr) {
            ANativeWindow_setAutoRefresh(renderer->producerWindow, false);
            ANativeWindow_setSharedBufferMode(renderer->producerWindow, false);
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
