#include <jni.h>
#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
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
constexpr const char* kTag = "BubbleRelayBP";
struct State;
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
    int wakeFd = -1;
    std::atomic<bool> stopping{false}, workerFinished{false}, cleanupQueued{false};
    std::atomic<int> leases{0};
    std::atomic<float> requestedRate{0};
    float appliedRate = -1;
    uint64_t submitted = 0;
    void wake() const {
        uint64_t one = 1;
        if (wakeFd >= 0) (void)write(wakeFd, &one, sizeof(one)); // Nonblocking; coalescing is intentional.
    }
    ~State() {
        // Normal cleanup is on the cleanup thread; construction failures are
        // already off-main. A State cannot die while a release callback owns it.
        if (reader) AImageReader_delete(reader);
        if (output) ASurfaceControl_release(output);
        if (wakeFd >= 0) close(wakeFd);
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
                __android_log_print(ANDROID_LOG_INFO, kTag, "generation=%lld retired cleanly", static_cast<long long>(s->id));
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
struct Lease { std::shared_ptr<State> state; AImage* image; };
void releaseFrame(void* context, int releaseFenceFd) {
    std::unique_ptr<Lease> lease(static_cast<Lease*>(context));
    auto s = lease->state;
    AImage_deleteAsync(lease->image, releaseFenceFd); // Transfers the release FD; never reuse early.
    totalReleased++; totalOutstanding--; s->leases--;
    s->wake();
    maybeCleanup(s);
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
    const auto count = ++totalErrors;
    if (count < 8 || (count & (count - 1)) == 0)
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s status=%d total=%lld", stage, status, static_cast<long long>(count));
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
    AImage* latest = nullptr; int latestFence = -1;
    for (int i = 0; i < kDrainLimit && !s->stopping.load(); ++i) {
        AImage* image = nullptr; int fence = -1;
        const media_status_t status = AImageReader_acquireNextImageAsync(s->reader, &image, &fence);
        // Classify status FIRST. A null image must not conceal MAX_IMAGES/errors.
        if (status == AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE || status == AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED) {
            discard(image, fence); break;
        }
        if (status != AMEDIA_OK || !image) { error("acquire", status); discard(image, fence); break; }
        if (latest) discard(latest, latestFence);
        latest = image; latestFence = fence;
    }
    if (!latest) return;
    if (s->stopping.load()) { discard(latest, latestFence); return; }
    AHardwareBuffer* buffer = nullptr;
    const media_status_t status = AImage_getHardwareBuffer(latest, &buffer);
    if (status != AMEDIA_OK || !buffer) { error("hardware buffer", status); discard(latest, latestFence); return; }
    ASurfaceTransaction* tx = ASurfaceTransaction_create();
    if (!tx) { error("buffer transaction", -1); discard(latest, latestFence); return; }
    s->leases++; totalOutstanding++;
    ASurfaceTransaction_setBufferWithRelease(tx, s->output, buffer, latestFence,
                                             new Lease{s, latest}, releaseFrame);
    int32_t space = 0;
    if (AImage_getDataSpace(latest, &space) == AMEDIA_OK && space != 0)
        ASurfaceTransaction_setBufferDataSpace(tx, s->output, static_cast<ADataSpace>(space));
    ASurfaceTransaction_setBufferTransparency(tx, s->output, ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    ASurfaceTransaction_apply(tx); ASurfaceTransaction_delete(tx);
    totalSubmitted++;
    if (++s->submitted == 1) __android_log_print(ANDROID_LOG_INFO, kTag,
        "generation=%lld first submitted; latest/bp images=6 drain=4 worker=1", static_cast<long long>(s->id));
    s->wake(); // Handle coalesced reader callbacks without spinning when the queue is empty.
}
void run(const std::shared_ptr<State>& s) {
    pthread_setname_np(pthread_self(), "BubbleRelayRx"); workers++;
    while (!s->stopping.load()) {
        pollfd fd{s->wakeFd, POLLIN, 0};
        const int ready = poll(&fd, 1, -1);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0 || (fd.revents & (POLLERR | POLLHUP | POLLNVAL))) {
            error("consumer eventfd", errno); break;
        }
        uint64_t count = 0; (void)read(s->wakeFd, &count, sizeof(count));
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
    s->wakeFd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (s->wakeFd < 0) { error("create eventfd", errno); return 0; }
    ASurfaceTransaction* tx = ASurfaceTransaction_create();
    if (!tx) return 0;
    ASurfaceTransaction_setEnableBackPressure(tx, s->output, kOutputBackpressure);
    ASurfaceTransaction_setPosition(tx, s->output, 0, 0);
    ASurfaceTransaction_setScale(tx, s->output, 1.0f, 1.0f);
    ASurfaceTransaction_apply(tx); ASurfaceTransaction_delete(tx);
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
