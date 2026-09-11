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

namespace {

constexpr int kMaxImages = 4;

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

    // This producer ANativeWindow is consumed by AImageReader, so Android documents that this vote
    // does not itself select the physical display mode. Keep it anyway as producer intent; the
    // ASurfaceControl vote below is the display-facing contract.
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
        // Prefer newest-frame latency. AImageReader already bounds the queue and acquireLatestImage
        // discards stale producer frames; disabling compositor backpressure avoids forcing every stale
        // intermediate frame to be shown before a newer frame can replace it.
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

    AHardwareBuffer_Desc description{};
    AHardwareBuffer_describe(buffer, &description);
    if (description.width > 0 && description.height > 0) {
        const ARect crop{
            0,
            0,
            static_cast<int32_t>(description.width),
            static_cast<int32_t>(description.height)};
        ASurfaceTransaction_setCrop(transaction, renderer->outputControl, &crop);
    }

    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}

Renderer* fromHandle(jlong handle) {
    return reinterpret_cast<Renderer*>(static_cast<uintptr_t>(handle));
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

    // PRIVATE keeps the producer GPU-native. GPU_SAMPLED_IMAGE is mandatory for buffers submitted
    // through ASurfaceTransaction_setBufferWithRelease because SurfaceFlinger may GPU-compose them.
    const uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    const media_status_t createStatus = AImageReader_newWithUsage(
        width,
        height,
        AIMAGE_FORMAT_PRIVATE,
        usage,
        kMaxImages,
        &renderer->reader);
    if (createStatus != AMEDIA_OK || renderer->reader == nullptr) {
        ASurfaceControl_release(renderer->outputControl);
        delete renderer;
        return 0;
    }

    if (AImageReader_getWindow(renderer->reader, &renderer->producerWindow) != AMEDIA_OK ||
        renderer->producerWindow == nullptr) {
        AImageReader_delete(renderer->reader);
        ASurfaceControl_release(renderer->outputControl);
        delete renderer;
        return 0;
    }

    ANativeWindow_tryAllocateBuffers(renderer->producerWindow);

    AImageReader_ImageListener listener{};
    listener.context = renderer;
    listener.onImageAvailable = onImageAvailable;
    if (AImageReader_setImageListener(renderer->reader, &listener) != AMEDIA_OK) {
        AImageReader_delete(renderer->reader);
        ASurfaceControl_release(renderer->outputControl);
        delete renderer;
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
    std::lock_guard<std::mutex> guard(renderer->mutex);

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
    // AImageReader_delete unregisters its dedicated callback thread. FrameLease objects already handed
    // to SurfaceFlinger own only their AImage and are released independently by releaseFrame().
    delete renderer;
}
