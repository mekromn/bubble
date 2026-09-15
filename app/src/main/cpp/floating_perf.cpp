#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/performance_hint.h>
#include <dirent.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {
constexpr int kSessions = 1 << 0;
constexpr int kGraphicsPipeline = 1 << 1;
constexpr int kSurfaceBinding = 1 << 2;
constexpr int kAutoCpu = 1 << 3;
constexpr int kAutoGpu = 1 << 4;
constexpr int kCreated = 1 << 5;

constexpr int kUnsupportedSessions = -1;
constexpr int kUnsupportedGraphics = -2;
constexpr int kUnsupportedAutoTiming = -3;
constexpr int kManagerUnavailable = -4;
constexpr int kSurfaceUnavailable = -5;
constexpr int kConfigUnavailable = -6;

struct PerfState {
    jlong id = 0;
    APerformanceHintSession* session = nullptr;
    ANativeWindow* window = nullptr;
    int featureBits = 0;
    int createStatus = 0;
    int threadCount = 0;
    int interactions = 0;

    ~PerfState() {
        if (session) APerformanceHint_closeSession(session);
        if (window) ANativeWindow_release(window);
    }
};

std::mutex perfMutex;
std::unordered_map<jlong, std::unique_ptr<PerfState>> perfStates;
std::atomic<jlong> nextPerfId{1};
std::atomic<int> lastFeatureBits{0};
std::atomic<int> lastCreateStatus{0};
std::atomic<int> lastThreadCount{0};
std::atomic<int> lastInteractions{0};

std::string threadName(pid_t tid) {
    std::ifstream in("/proc/self/task/" + std::to_string(tid) + "/comm");
    std::string name;
    std::getline(in, name);
    return name;
}

int criticalThreadScore(const std::string& name) {
    // Linux comm names are short. Favor the threads most likely to sit on the Android/UI/Gecko
    // presentation path; never pull generic workers into the graphics hint session.
    if (name.find("RenderThread") != std::string::npos) return 100;
    if (name.find("Compositor") != std::string::npos) return 95;
    if (name.find("WRRender") != std::string::npos || name.find("WebRender") != std::string::npos) return 90;
    if (name == "Renderer" || name.find("Renderer") != std::string::npos) return 85;
    if (name.find("GeckoMain") != std::string::npos) return 80;
    if (name.find("APZ") != std::string::npos) return 75;
    return 0;
}

std::vector<pid_t> graphicsThreads(pid_t callerTid, int maximum) {
    std::vector<pid_t> result;
    if (callerTid > 0) result.push_back(callerTid);
    if (maximum <= 1) return result;

    std::vector<std::pair<int, pid_t>> candidates;
    DIR* dir = opendir("/proc/self/task");
    if (!dir) return result;
    while (dirent* entry = readdir(dir)) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        const pid_t tid = static_cast<pid_t>(std::strtol(entry->d_name, nullptr, 10));
        if (tid <= 0 || tid == callerTid) continue;
        const int score = criticalThreadScore(threadName(tid));
        if (score > 0) candidates.emplace_back(score, tid);
    }
    closedir(dir);
    std::sort(candidates.begin(), candidates.end(), [](const auto& a, const auto& b) {
        return a.first != b.first ? a.first > b.first : a.second < b.second;
    });
    for (const auto& candidate : candidates) {
        if (static_cast<int>(result.size()) >= maximum) break;
        result.push_back(candidate.second);
    }
    return result;
}

int featureBits() {
    int bits = 0;
    if (APerformanceHint_isFeatureSupported(APERF_HINT_SESSIONS)) bits |= kSessions;
    if (APerformanceHint_isFeatureSupported(APERF_HINT_GRAPHICS_PIPELINE)) bits |= kGraphicsPipeline;
    if (APerformanceHint_isFeatureSupported(APERF_HINT_SURFACE_BINDING)) bits |= kSurfaceBinding;
    if (APerformanceHint_isFeatureSupported(APERF_HINT_AUTO_CPU)) bits |= kAutoCpu;
    if (APerformanceHint_isFeatureSupported(APERF_HINT_AUTO_GPU)) bits |= kAutoGpu;
    return bits;
}

void rememberAttempt(int bits, int status, int threadCount) {
    lastFeatureBits.store(bits, std::memory_order_relaxed);
    lastCreateStatus.store(status, std::memory_order_relaxed);
    lastThreadCount.store(threadCount, std::memory_order_relaxed);
    lastInteractions.store(0, std::memory_order_relaxed);
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_mekromn_bubble_NativePerformanceBridge_nativeStart(
        JNIEnv* env, jobject, jobject surfaceObject, jfloat frameRate, jint callerTid) {
    if (!surfaceObject || frameRate <= 0.0f) {
        rememberAttempt(0, kSurfaceUnavailable, 0);
        return 0;
    }

    int bits = featureBits();
    if ((bits & kSessions) == 0) {
        rememberAttempt(bits, kUnsupportedSessions, 0);
        return 0;
    }
    if ((bits & kGraphicsPipeline) == 0 || (bits & kSurfaceBinding) == 0) {
        rememberAttempt(bits, kUnsupportedGraphics, 0);
        return 0;
    }
    const bool autoCpu = (bits & kAutoCpu) != 0;
    const bool autoGpu = (bits & kAutoGpu) != 0;
    if (!autoCpu && !autoGpu) {
        // Do not create an unmanaged hint session that would require a Java/JNI call every frame.
        // The production policy is deliberately callback-free while scrolling.
        rememberAttempt(bits, kUnsupportedAutoTiming, 0);
        return 0;
    }

    APerformanceHintManager* manager = APerformanceHint_getManager();
    if (!manager) {
        rememberAttempt(bits, kManagerUnavailable, 0);
        return 0;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surfaceObject);
    if (!window) {
        rememberAttempt(bits, kSurfaceUnavailable, 0);
        return 0;
    }

    int maximum = APerformanceHint_getMaxGraphicsPipelineThreadsCount(manager);
    if (maximum <= 0) maximum = 1;
    auto tids = graphicsThreads(static_cast<pid_t>(callerTid), maximum);
    if (tids.empty()) {
        ANativeWindow_release(window);
        rememberAttempt(bits, kUnsupportedGraphics, 0);
        return 0;
    }

    ASessionCreationConfig* config = ASessionCreationConfig_create();
    if (!config) {
        ANativeWindow_release(window);
        rememberAttempt(bits, kConfigUnavailable, static_cast<int>(tids.size()));
        return 0;
    }

    const int64_t targetNanos = static_cast<int64_t>(std::llround(1000000000.0 / frameRate));
    ASessionCreationConfig_setTids(config, tids.data(), tids.size());
    ASessionCreationConfig_setTargetWorkDurationNanos(config, std::max<int64_t>(1, targetNanos));
    ASessionCreationConfig_setGraphicsPipeline(config, true);
    ASessionCreationConfig_setNativeSurfaces(config, &window, 1, nullptr, 0);
    if (APerformanceHint_isFeatureSupported(APERF_HINT_POWER_EFFICIENCY))
        ASessionCreationConfig_setPreferPowerEfficiency(config, false);
    ASessionCreationConfig_setUseAutoTiming(config, autoCpu, autoGpu);

    APerformanceHintSession* session = nullptr;
    const int status = APerformanceHint_createSessionUsingConfig(manager, config, &session);
    ASessionCreationConfig_release(config);
    if (status != 0 || !session) {
        ANativeWindow_release(window);
        rememberAttempt(bits, status != 0 ? status : kManagerUnavailable, static_cast<int>(tids.size()));
        return 0;
    }

    // Keep the producer's frame-rate contract explicit on the same ANativeWindow associated with
    // the ADPF session. This is one setup vote per Surface lifetime, never a per-frame operation.
    ANativeWindow_setFrameRateWithChangeStrategy(
        window,
        frameRate,
        ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_AT_LEAST,
        ANATIVEWINDOW_CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);

    auto state = std::make_unique<PerfState>();
    state->id = nextPerfId.fetch_add(1);
    state->session = session;
    state->window = window;
    state->featureBits = bits | kCreated;
    state->createStatus = status;
    state->threadCount = static_cast<int>(tids.size());
    APerformanceHint_notifyWorkloadReset(session, true, true, "bubble-floating-page");

    const jlong id = state->id;
    {
        std::lock_guard<std::mutex> lock(perfMutex);
        perfStates.emplace(id, std::move(state));
    }
    rememberAttempt(bits | kCreated, status, static_cast<int>(tids.size()));
    return id;
}

extern "C" JNIEXPORT void JNICALL Java_com_mekromn_bubble_NativePerformanceBridge_nativeNotifyInteraction(
        JNIEnv*, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(perfMutex);
    const auto it = perfStates.find(id);
    if (it == perfStates.end() || !it->second->session) return;
    ++it->second->interactions;
    lastInteractions.store(it->second->interactions, std::memory_order_relaxed);
    // One hint at gesture start is enough to pre-announce the scroll/fling burst. The framework
    // rate-limits these hints; Bubble does not call into ADPF for individual webpage frames.
    APerformanceHint_notifyWorkloadIncrease(it->second->session, true, true, "scroll-gesture");
}

extern "C" JNIEXPORT void JNICALL Java_com_mekromn_bubble_NativePerformanceBridge_nativeStop(
        JNIEnv*, jobject, jlong id) {
    std::unique_ptr<PerfState> state;
    {
        std::lock_guard<std::mutex> lock(perfMutex);
        const auto it = perfStates.find(id);
        if (it == perfStates.end()) return;
        state = std::move(it->second);
        perfStates.erase(it);
    }
    // PerfState destructor closes the hint session before releasing the ANativeWindow reference.
}

extern "C" JNIEXPORT jintArray JNICALL Java_com_mekromn_bubble_NativePerformanceBridge_nativeStatus(
        JNIEnv* env, jobject, jlong id) {
    jint values[4] = {
        lastFeatureBits.load(std::memory_order_relaxed),
        lastCreateStatus.load(std::memory_order_relaxed),
        lastThreadCount.load(std::memory_order_relaxed),
        lastInteractions.load(std::memory_order_relaxed)
    };
    if (id != 0) {
        std::lock_guard<std::mutex> lock(perfMutex);
        const auto it = perfStates.find(id);
        if (it != perfStates.end()) {
            values[0] = it->second->featureBits;
            values[1] = it->second->createStatus;
            values[2] = it->second->threadCount;
            values[3] = it->second->interactions;
        }
    }
    jintArray result = env->NewIntArray(4);
    if (result) env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}
