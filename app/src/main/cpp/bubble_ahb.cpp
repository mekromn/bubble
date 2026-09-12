#include <jni.h>
#include "relay_wake.h"
#include "relay_capacity.h"
#include <array>
#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include "relay_logging.h"
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <android/surface_control_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <atomic>
#include <cerrno>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <poll.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <unistd.h>

// The chosen, tested relay_latest_bp policy. These are acquisition limits, NOT
// an assertion about the number of allocations or concurrent drain workers.
namespace {
constexpr int kMaxImages = 6;
constexpr int kDrainLimit = 4;
constexpr bool kOutputBackpressure = true;
constexpr size_t kMaximumLiveGenerations = 8;
constexpr uint64_t kConsumerUsage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                                    AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
struct State;
struct Lease {
    // Callback context storage is bounded, but image ownership still lasts until
    // the compositor release callback. Never reused as pixel-buffer ownership.
    std::atomic<bool> occupied{false};
    std::shared_ptr<State> state;
    AImage* image = nullptr;
};
void maybeCleanup(const std::shared_ptr<State>& state);
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<State>> active, retiring;
std::atomic<jlong> nextId{1};
std::atomic<int64_t> totalSubmitted{0}, totalReleased{0}, totalOutstanding{0}, totalErrors{0}, workers{0};
struct State {
    jlong id = 0;
    std::mutex resourceMutex;
    AImageReader* reader = nullptr;
    ANativeWindow* producer = nullptr; // Borrowed from reader.
    ASurfaceControl* output = nullptr; // Independent reference from fromJava().
    bubble::RelayWake event;
    bubble::RelayCapacity capacity;
    ASurfaceTransaction* frameTransaction = nullptr; // One consumer owns and reuses this object.
    std::array<Lease, kMaxImages> leaseSlots;
    int32_t appliedDataSpace = ADATASPACE_UNKNOWN;
    std::atomic<bool> stopping{false}, workerFinished{false}, cleanupQueued{false};
    std::atomic<int> leases{0};
    std::atomic<float> requestedRate{0};
    float appliedRate = -1;
#if BUBBLE_RELAY_LOGGING
    uint64_t submitted = 0;
#endif
    void wake() { (void)event.notify(); }
    ~State() {
        // Normal cleanup is on the cleanup thread; construction failures are
        // already off-main. A State cannot die while a release callback owns it.
        if (reader) AImageReader_delete(reader);
        if (output) ASurfaceControl_release(output);
        if (frameTransaction) ASurfaceTransaction_delete(frameTransaction);
    }
};
std::shared_ptr<State> lookup(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto it = active.find(id);
    return it == active.end() ? nullptr : it->second;
}

// One process-wide sleeping cleanup thread, not one stranded thread per old tab.
// Retired readers remain owned until their last compositor lease has returned.
// There is deliberately no timeout followed by an unsafe reader destruction.
class CleanupQueue {
    std::mutex mutex;
    std::condition_variable changed;
    std::deque<std::shared_ptr<State>> pending;
 public:
    CleanupQueue() {
        std::thread([this] {
            pthread_setname_np(pthread_self(), "BubbleRelayGC");
            for (;;) {
                std::shared_ptr<State> s;
                {
                    std::unique_lock<std::mutex> lock(mutex);
                    changed.wait(lock, [this] { return !pending.empty(); });
                    s = std::move(pending.front()); pending.pop_front();
                }
                // The image listener was unregistered by the exited consumer.
                { std::lock_guard<std::mutex> resource(s->resourceMutex);
                  AImageReader_delete(s->reader); s->reader = nullptr; s->producer = nullptr; }
                {
                    std::lock_guard<std::mutex> lock(registryMutex);
                    retiring.erase(s->id);
                }
                BUBBLE_RELAY_LOG(ANDROID_LOG_INFO, "generation=%lld retired cleanly", static_cast<long long>(s->id));
            }
        }).detach();
    }
    void push(const std::shared_ptr<State>& s) {
        { std::lock_guard<std::mutex> lock(mutex); pending.push_back(s); }
        changed.notify_one();
    }
};
CleanupQueue& cleanupQueue() {
    // Process-lifetime executor: never destroyed while detached callbacks exist.
    static auto* queue = new CleanupQueue();
    return *queue;
}
void maybeCleanup(const std::shared_ptr<State>& s) {
    if (s->workerFinished.load() && s->leases.load() == 0 && !s->cleanupQueued.exchange(true))
        cleanupQueue().push(s);
}
void releaseFrame(void* context, int releaseFenceFd) {
    auto* lease = static_cast<Lease*>(context);
    auto s = std::move(lease->state);
    AImage* image = lease->image;
    lease->image = nullptr;
    // Publish the empty bookkeeping slot before returning image capacity. From
    // here onward this callback accesses only locals, never that slot again.
    lease->occupied.store(false, std::memory_order_release);
    AImage_deleteAsync(image, releaseFenceFd); // Transfers the release FD; never reuse pixels early.
    totalReleased++; totalOutstanding--; s->leases--;
    // A returned buffer is not a new image. Wake only a capacity waiter, after
    // the reader has received the image/fence. Retirement retains its own path.
    if (s->capacity.returned() && !s->stopping.load()) s->wake();
    maybeCleanup(s);
}
Lease* reserveLease(const std::shared_ptr<State>& s, AImage* image) {
    for (auto& slot : s->leaseSlots) {
        bool available = false;
        if (slot.occupied.compare_exchange_strong(available, true, std::memory_order_acquire)) {
            slot.state = s; slot.image = image; return &slot;
        }
    }
    // At most kMaxImages are acquired. A release frees its bookkeeping slot
    // before returning an image, so an acquired image must always have a slot.
    return nullptr;
}
void imageAvailable(void* context, AImageReader*) {
    auto* s = static_cast<State*>(context);
    if (!s->stopping.load()) s->wake(); // No acquisition or GPU waits on the reader callback thread.
}
void discard(AImage* image, int fence) {
    if (image) AImage_deleteAsync(image, fence);
    else if (fence >= 0) close(fence);
}
void error(const char* stage, int status) {
    ++totalErrors; // Retain error/lifetime verification; no text or IO in the release path.
#if BUBBLE_RELAY_LOGGING
    const auto count = totalErrors.load();
    if (count < 8 || (count & (count - 1)) == 0)
        BUBBLE_RELAY_LOG(ANDROID_LOG_ERROR, "%s status=%d total=%lld", stage, status, static_cast<long long>(count));
#else
    (void)stage; (void)status;
#endif
}
void voteRate(const std::shared_ptr<State>& s) {
    const float rate = s->requestedRate.load();
    if (rate <= 0 || rate == s->appliedRate) return;
    ASurfaceTransaction* tx = ASurfaceTransaction_create();
    if (!tx) { error("rate transaction", -1); return; }
    ANativeWindow_setFrameRateWithChangeStrategy(s->producer, rate,
        ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_AT_LEAST, ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS);
    ASurfaceTransaction_setFrameRateWithChangeStrategy(tx, s->output, rate,
        ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_AT_LEAST, ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS);
    ASurfaceTransaction_apply(tx); ASurfaceTransaction_delete(tx); s->appliedRate = rate;
}
void consume(const std::shared_ptr<State>& s) {
    s->capacity.beginPass();
    AImage* latest = nullptr; int latestFence = -1;
    bool reachedDrainLimit = true;
    for (int i = 0; i < kDrainLimit && !s->stopping.load(); ++i) {
        AImage* image = nullptr; int fence = -1;
        const uint64_t before = s->capacity.beforeAcquire();
        const media_status_t status = AImageReader_acquireNextImageAsync(s->reader, &image, &fence);
        // Classify status FIRST. A null image must not conceal MAX_IMAGES/errors.
        if (status == AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED) {
            reachedDrainLimit = false; discard(image, fence);
            // Covers a callback returning capacity after acquire observed MAX
            // but before the waiter could be registered. No polling or sleep.
            if (s->capacity.waitAfterBlockedAcquire(before)) s->wake();
            break;
        }
        if (status == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) {
            reachedDrainLimit = false; discard(image, fence); break;
        }
        if (status != AMEDIA_OK || !image) {
            reachedDrainLimit = false; error("acquire", status); discard(image, fence);
            // Retain the old release-driven recovery for a transient acquire
            // failure. With no outstanding image, only a new frame can retry;
            // do not invent an unbounded error polling loop.
            if (s->capacity.waitAfterBlockedAcquire(before)) s->wake();
            break;
        }
        if (latest) discard(latest, latestFence);
        latest = image; latestFence = fence;
    }
    if (!latest) return;
    if (s->stopping.load()) { discard(latest, latestFence); return; }
    AHardwareBuffer* buffer = nullptr;
    const media_status_t status = AImage_getHardwareBuffer(latest, &buffer);
    if (status != AMEDIA_OK || !buffer) {
        error("hardware buffer", status); discard(latest, latestFence);
        // MAX may have armed a waiter after acquiring this image. Returning it
        // here creates capacity without a SurfaceControl callback of its own.
        const bool capacityRetry = s->capacity.returned();
        // A full pass can also leave queued images after this local rejection.
        // Do not strand that backlog waiting for a callback that cannot exist.
        if (capacityRetry || reachedDrainLimit) s->wake();
        return;
    }
    ASurfaceTransaction* tx = s->frameTransaction;
    Lease* lease = reserveLease(s, latest);
    if (!lease) {
        error("lease slot invariant", -1); discard(latest, latestFence);
        const bool capacityRetry = s->capacity.returned();
        // A full pass can also leave queued images after this local rejection.
        // Do not strand that backlog waiting for a callback that cannot exist.
        if (capacityRetry || reachedDrainLimit) s->wake();
        return;
    }
    s->leases++; totalOutstanding++;
    ASurfaceTransaction_setBufferWithRelease(tx, s->output, buffer, latestFence, lease, releaseFrame);
    int32_t space = ADATASPACE_UNKNOWN;
    if (AImage_getDataSpace(latest, &space) != AMEDIA_OK) space = ADATASPACE_UNKNOWN;
    // Includes a transition back to UNKNOWN; don't retain the previous frame's colorspace.
    if (space != s->appliedDataSpace) {
        ASurfaceTransaction_setBufferDataSpace(tx, s->output, static_cast<ADataSpace>(space));
        s->appliedDataSpace = space;
    }
    // AOSP Transaction::apply() clears submitted state/callback registrations.
    // The callback owns its Lease independently; reuse is only on this worker.
    ASurfaceTransaction_apply(tx);
    totalSubmitted++;
#if BUBBLE_RELAY_LOGGING
    if (++s->submitted == 1) BUBBLE_RELAY_LOG(ANDROID_LOG_INFO,
        "generation=%lld first submitted; latest/bp images=6 drain=4 worker=1", static_cast<long long>(s->id));
#endif
    // Only a full bounded pass can leave an unobserved backlog after coalesced
    // notifications. EMPTY waits for new images; MAX_IMAGES waits for release.
    if (reachedDrainLimit) s->wake();
}
void run(const std::shared_ptr<State>& s) {
    pthread_setname_np(pthread_self(), "BubbleRelayRx"); workers++;
    while (!s->stopping.load()) {
        pollfd fd{s->event.fd(), POLLIN, 0};
        const int ready = poll(&fd, 1, -1);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0 || (fd.revents & (POLLERR | POLLHUP | POLLNVAL))) {
            error("consumer eventfd", errno); break;
        }
        if (!s->event.beginPass()) { error("consumer wake read", errno); break; }
        if (s->stopping.load()) break;
        voteRate(s); consume(s);
    }
    s->stopping = true;
    AImageReader_setImageListener(s->reader, nullptr);
    ASurfaceTransaction* tx = ASurfaceTransaction_create();
    if (tx) {
        ASurfaceTransaction_reparent(tx, s->output, nullptr);
        ASurfaceTransaction_apply(tx); ASurfaceTransaction_delete(tx);
    }
    ASurfaceTransaction_delete(s->frameTransaction); s->frameTransaction = nullptr;
    ASurfaceControl_release(s->output); s->output = nullptr;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        active.erase(s->id); retiring[s->id] = s;
    }
    workers--;
    s->workerFinished = true;
    maybeCleanup(s);
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_mekromn_bubble_NativeAhbBridge_nativeCreate(
        JNIEnv* env, jobject, jint width, jint height, jobject control, jfloat rate) {
    if (width <= 0 || height <= 0 || !control) return 0;
    auto s = std::make_shared<State>();
    // A driver that never releases buffers must not leak an unlimited pool on every tab switch.
    { std::lock_guard<std::mutex> lock(registryMutex);
      if (active.size() + retiring.size() >= kMaximumLiveGenerations) {
          error("retired-generation safety limit", -1); return 0;
      }
    }
    s->output = ASurfaceControl_fromJava(env, control);
    if (!s->output) return 0;
    const auto status = AImageReader_newWithUsage(width, height, AIMAGE_FORMAT_PRIVATE,
                                                  kConsumerUsage, kMaxImages, &s->reader);
    if (status != AMEDIA_OK || !s->reader || AImageReader_getWindow(s->reader, &s->producer) != AMEDIA_OK) {
        error("create reader", status); return 0;
    }
    if (!s->event.open()) { error("create eventfd", errno); return 0; }
    s->frameTransaction = ASurfaceTransaction_create();
    ASurfaceTransaction* tx = s->frameTransaction;
    if (!tx) return 0;
    ASurfaceTransaction_setEnableBackPressure(tx, s->output, kOutputBackpressure);
    ASurfaceTransaction_setPosition(tx, s->output, 0, 0);
    ASurfaceTransaction_setScale(tx, s->output, 1.0f, 1.0f);
    ASurfaceTransaction_setBufferTransparency(tx, s->output, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    ASurfaceTransaction_apply(tx);
    s->requestedRate = rate;
    AImageReader_ImageListener listener{s.get(), imageAvailable};
    if (AImageReader_setImageListener(s->reader, &listener) != AMEDIA_OK) return 0;
    s->id = nextId++;
    { std::lock_guard<std::mutex> lock(registryMutex); active.emplace(s->id, s); }
    try { std::thread(run, s).detach(); }
    catch (...) {
        AImageReader_setImageListener(s->reader, nullptr);
        std::lock_guard<std::mutex> lock(registryMutex); active.erase(s->id); return 0;
    }
    s->wake();
    return s->id;
}
extern "C" JNIEXPORT jobject JNICALL Java_com_mekromn_bubble_NativeAhbBridge_nativeGetProducerSurface(
        JNIEnv* env, jobject, jlong id) {
    auto s = lookup(id); if (!s) return nullptr;
    std::lock_guard<std::mutex> resource(s->resourceMutex);
    return s->producer ? ANativeWindow_toSurface(env, s->producer) : nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_com_mekromn_bubble_NativeAhbBridge_nativeSetFrameRate(
        JNIEnv*, jobject, jlong id, jfloat rate) {
    auto s = lookup(id); if (s) { s->requestedRate = rate; s->wake(); }
}
extern "C" JNIEXPORT void JNICALL Java_com_mekromn_bubble_NativeAhbBridge_nativeDestroy(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<State> s;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto it = active.find(id); if (it == active.end()) return;
        s = it->second; retiring[id] = s; active.erase(it);
    }
    // Nonblocking: no worker join, image acquisition, fence wait, or reader destruction on UI.
    s->stopping = true; s->wake();
}
extern "C" JNIEXPORT jlongArray JNICALL Java_com_mekromn_bubble_NativeAhbBridge_nativeDebugStats(JNIEnv* env, jobject) {
    jlong a, r;
    { std::lock_guard<std::mutex> lock(registryMutex); a = active.size(); r = retiring.size(); }
    jlong values[] = {a, r, totalSubmitted.load(), totalReleased.load(), totalOutstanding.load(), workers.load(), totalErrors.load(), kMaxImages, kDrainLimit, kOutputBackpressure ? 1 : 0};
    jlongArray result = env->NewLongArray(10);
    if (result) env->SetLongArrayRegion(result, 0, 10, values);
    return result;
}
