#include <jni.h>
#include <android/data_space.h>
#include <algorithm>
#include <pthread.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <android/surface_control_jni.h>
#include <android/sync.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>
#include <sys/eventfd.h>
#include <poll.h>
#include <unistd.h>

namespace {
int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
std::string quote(const char* text) {
    std::string out = "\"";
    if (text) for (const char* c=text; *c; ++c) {
        if (*c=='"' || *c=='\\') out+='\\';
        if (static_cast<unsigned char>(*c)>=32) out+=*c;
    }
    return out+'"';
}
jstring text(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }
int64_t fenceTime(int fd);
struct Sample {
    int64_t acquired=0, submitted=0, imageTimestamp=0, latch=0, completed=0, present=-1;
    uint64_t bufferId=0;
    int64_t acquireCallNs=0, applyCallNs=0, releaseCallbackNs=0;
    int outstandingDepth=0;
    int presentFd=-1;
    ~Sample() { if (presentFd>=0) close(presentFd); }
};
struct State : std::enable_shared_from_this<State> {
    AImageReader* reader=nullptr;
    ANativeWindow* window=nullptr; // Borrowed from reader, never separately released.
    ASurfaceControl* output=nullptr;
    int wakeFd=-1, drainLimit=1;
    std::thread worker;
    std::atomic<bool> stopping{false}, measuring{false};
    std::atomic<int> outstanding{0}, completions{0}, lastStatus{0};
    std::atomic<uint64_t> callbacks{0}, acquired{0}, submitted{0}, discarded{0}, released{0}, noBuffer{0}, maxImages{0}, errors{0};
    std::mutex dataMutex;
    std::condition_variable changed;
    std::vector<std::shared_ptr<Sample>> samples;
    std::deque<std::shared_ptr<Sample>> pendingFences;
    uint64_t unresolvedFenceDrops=0;
    uint64_t usage=0;
    uint32_t format=0, width=0, height=0;
    bool samplesTruncated=false;
    ~State() { if (wakeFd>=0) close(wakeFd); }
    void wake() const {
        uint64_t one=1;
        // Nonblocking eventfd: EAGAIN means there is already a pending wakeup.
        if (wakeFd>=0) (void)write(wakeFd,&one,sizeof(one));
    }
};
std::mutex registryMutex;
std::unordered_map<jlong,std::shared_ptr<State>> registry;
std::vector<std::shared_ptr<State>> quarantine;
std::atomic<jlong> nextHandle{1};
std::shared_ptr<State> lookup(jlong h) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it=registry.find(h); return it==registry.end()?nullptr:it->second;
}
struct Lease { std::shared_ptr<State> state; AImage* image; std::shared_ptr<Sample> sample; };
struct Complete { std::shared_ptr<State> state; std::shared_ptr<Sample> sample; };
void releaseFrame(void* context, int fence) {
    std::unique_ptr<Lease> lease(static_cast<Lease*>(context));
    if(lease->sample){std::lock_guard<std::mutex> lock(lease->state->dataMutex);lease->sample->releaseCallbackNs=nowNs();}
    // Transfer ownership of the compositor's release fence back to ImageReader.
    AImage_deleteAsync(lease->image,fence);
    lease->state->released++;
    lease->state->outstanding--;
    lease->state->changed.notify_all();
    lease->state->wake();
}
void completeFrame(void* context, ASurfaceTransactionStats* stats) {
    std::unique_ptr<Complete> complete(static_cast<Complete*>(context));
    if (complete->sample) {
        std::lock_guard<std::mutex> lock(complete->state->dataMutex);
        complete->sample->latch=ASurfaceTransactionStats_getLatchTime(stats);
        complete->sample->completed=nowNs();
        complete->sample->presentFd=ASurfaceTransactionStats_getPresentFenceFd(stats);
        if (complete->sample->presentFd>=0) {
            // Bounded diagnostic ownership: never keep a file descriptor for every measured frame.
            if (complete->state->pendingFences.size()>=32) {
                auto oldest=complete->state->pendingFences.front();
                complete->state->pendingFences.pop_front();
                oldest->present=fenceTime(oldest->presentFd);
                if (oldest->present<0) complete->state->unresolvedFenceDrops++;
                close(oldest->presentFd); oldest->presentFd=-1;
            }
            complete->state->pendingFences.push_back(complete->sample);
        }
    }
    complete->state->completions--;
    complete->state->changed.notify_all();
    complete->state->wake();
}
void available(void* context, AImageReader*) {
    auto* s=static_cast<State*>(context);
    s->callbacks++;
    if (!s->stopping.load()) s->wake();
}
void discard(AImage* image, int fence) {
    if (image) AImage_deleteAsync(image,fence);
    else if (fence>=0) close(fence);
}
void consume(const std::shared_ptr<State>& s) {
    AImage* newest=nullptr;
    int newestFence=-1;
    int64_t acquiredAt=0, acquireDuration=0;
    // Deliberately bounded. Never use acquireLatestImageAsync's open-ended drain loop here.
    for (int i=0;i<s->drainLimit && !s->stopping.load();++i) {
        AImage* image=nullptr; int fence=-1;
        const int64_t acquireStart=nowNs();
        const media_status_t status=AImageReader_acquireNextImageAsync(s->reader,&image,&fence);
        acquireDuration+=nowNs()-acquireStart;
        s->lastStatus=status;
        if (status==AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) { s->noBuffer++; discard(image,fence); break; }
        if (status==AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED) { s->maxImages++; discard(image,fence); break; }
        if (status!=AMEDIA_OK || !image) { s->errors++; discard(image,fence); break; }
        s->acquired++;
        if (newest) { discard(newest,newestFence); s->discarded++; }
        newest=image; newestFence=fence; acquiredAt=nowNs();
    }
    if (!newest) return;
    if (s->stopping.load()) { discard(newest,newestFence); return; }
    AHardwareBuffer* buffer=nullptr;
    if (AImage_getHardwareBuffer(newest,&buffer)!=AMEDIA_OK || !buffer) {
        s->errors++; discard(newest,newestFence); return;
    }
    AHardwareBuffer_Desc desc{}; AHardwareBuffer_describe(buffer,&desc);
    s->usage=desc.usage; s->format=desc.format; s->width=desc.width; s->height=desc.height;
    std::shared_ptr<Sample> sample;
    if (s->measuring.load()) {
        sample=std::make_shared<Sample>();
        sample->acquired=acquiredAt;sample->acquireCallNs=acquireDuration;
        AImage_getTimestamp(newest,&sample->imageTimestamp);
        AHardwareBuffer_getId(buffer,&sample->bufferId);
        std::lock_guard<std::mutex> lock(s->dataMutex);
        if (s->samples.size()<16384) s->samples.push_back(sample);
        else { s->samplesTruncated=true; sample.reset(); }
    }
    ASurfaceTransaction* tx=ASurfaceTransaction_create();
    if (!tx) { s->errors++; discard(newest,newestFence); return; }
    s->outstanding++;if(sample)sample->outstandingDepth=s->outstanding.load();
    ASurfaceTransaction_setBufferWithRelease(tx,s->output,buffer,newestFence,new Lease{s,newest,sample},releaseFrame);
    int32_t space=0;
    if (AImage_getDataSpace(newest,&space)==AMEDIA_OK && space!=0)
        ASurfaceTransaction_setBufferDataSpace(tx,s->output,static_cast<ADataSpace>(space));
    ASurfaceTransaction_setBufferTransparency(tx,s->output,ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
    if (sample) {
        sample->submitted=nowNs();
        s->completions++;
        ASurfaceTransaction_setOnComplete(tx,new Complete{s,sample},completeFrame);
    }
    const int64_t applyStart=nowNs();ASurfaceTransaction_apply(tx);
    if(sample){std::lock_guard<std::mutex> lock(s->dataMutex);sample->applyCallNs=nowNs()-applyStart;}
    ASurfaceTransaction_delete(tx);
    s->submitted++;
    // Drain already-queued frames even if listener notifications coalesced; releases wake us as well.
    s->wake();
}
void resolveFences(const std::shared_ptr<State>& s) {
    std::lock_guard<std::mutex> lock(s->dataMutex);
    for (auto it=s->pendingFences.begin();it!=s->pendingFences.end();) {
        const int64_t timestamp=fenceTime((*it)->presentFd);
        if (timestamp>0) {
            (*it)->present=timestamp; close((*it)->presentFd); (*it)->presentFd=-1;
            it=s->pendingFences.erase(it);
        } else ++it;
    }
}
void runWorker(const std::shared_ptr<State>& s) {
    pthread_setname_np(pthread_self(),"BubbleProbeRx");
    while (!s->stopping.load()) {
        pollfd fd{s->wakeFd,POLLIN,0};
        const int result=poll(&fd,1,-1);
        if (result<=0) continue;
        uint64_t count=0; (void)read(s->wakeFd,&count,sizeof(count));
        resolveFences(s);
        if (!s->stopping.load()) consume(s);
    }
}
int64_t fenceTime(int fd) {
    if (fd<0) return -1;
    struct sync_file_info* info=sync_file_info(fd);
    if (!info) return -1;
    int64_t result=-1;
    if (info->status==1) {
        const struct sync_fence_info* fences=sync_get_fence_info(info);
        for (uint32_t i=0;i<info->num_fences;++i)
            result=std::max(result,static_cast<int64_t>(fences[i].timestamp_ns));
    }
    sync_file_info_free(info);
    return result;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL Java_com_mekromn_bubble_probe_NativeProbe_capabilities(JNIEnv* env,jclass,jint w,jint h) {
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major=0,minor=0;
    std::ostringstream out; out<<"{\"geckoFrontBuffer\":\"NOT_IMPLEMENTED_ENGINE_INTEGRATION_REQUIRED\",\"geckoGraphicsBackend\":\"not_measured\"";
    if (d!=EGL_NO_DISPLAY && eglInitialize(d,&major,&minor)) {
        const char* ext=eglQueryString(d,EGL_EXTENSIONS);
        out<<",\"eglVersion\":"<<quote(eglQueryString(d,EGL_VERSION))<<",\"eglExtensions\":"<<quote(ext);
        out<<",\"mutableRenderBufferExtension\":"<<(ext && std::strstr(ext,"EGL_KHR_mutable_render_buffer")?"true":"false");
        out<<",\"frontBufferAutoRefreshExtension\":"<<(ext && std::strstr(ext,"EGL_ANDROID_front_buffer_auto_refresh")?"true":"false");
        EGLConfig c=nullptr; EGLint n=0;
        const EGLint attrs[]={EGL_SURFACE_TYPE,EGL_WINDOW_BIT|EGL_MUTABLE_RENDER_BUFFER_BIT_KHR,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_NONE};
        const bool mutableConfig=eglChooseConfig(d,attrs,&c,1,&n)==EGL_TRUE && n>0;
        out<<",\"mutableWindowConfig\":"<<(mutableConfig?"true":"false");
        const EGLint pbufAttrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_NONE};
        if (eglChooseConfig(d,pbufAttrs,&c,1,&n) && n>0) {
            const EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE}, pa[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
            EGLContext ctx=eglCreateContext(d,c,EGL_NO_CONTEXT,ca); EGLSurface p=eglCreatePbufferSurface(d,c,pa);
            if(ctx!=EGL_NO_CONTEXT && p!=EGL_NO_SURFACE && eglMakeCurrent(d,p,p,ctx)) {
                out<<",\"probeGlesRenderer\":"<<quote(reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
                out<<",\"probeGlesVersion\":"<<quote(reinterpret_cast<const char*>(glGetString(GL_VERSION)));
            }
            eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            if(p!=EGL_NO_SURFACE) eglDestroySurface(d,p);
            if(ctx!=EGL_NO_CONTEXT) eglDestroyContext(d,ctx);
        }
        eglTerminate(d);
    } else out<<",\"eglInitializeFailed\":true";
    AHardwareBuffer_Desc desc{}; desc.width=w;desc.height=h;desc.layers=1;desc.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage=AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER|AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY|AHARDWAREBUFFER_USAGE_FRONT_BUFFER;
    AHardwareBuffer* b=nullptr;
    const int supported=AHardwareBuffer_isSupported(&desc);
    const int allocated=supported?AHardwareBuffer_allocate(&desc,&b):-1;
    out<<",\"frontOverlayAllocationSupported\":"<<(supported?"true":"false")<<",\"frontOverlayAllocationResult\":"<<allocated;
    if(b) AHardwareBuffer_release(b);
    out<<",\"note\":\"Capability/allocation probe only; not Gecko negotiation, HWC assignment, or scanout proof\"}";
    return text(env,out.str());
}

extern "C" JNIEXPORT jlong JNICALL Java_com_mekromn_bubble_probe_NativeProbe_create(JNIEnv* env,jclass,jint w,jint h,jobject control,jfloat rate,jint max,jint drain,jboolean bp) {
    if(w<=0 || h<=0 || max<3 || max>8 || drain<1 || drain>4 || !control) return 0;
    auto s=std::make_shared<State>(); s->drainLimit=drain;
    s->output=ASurfaceControl_fromJava(env,control);
    if(!s->output) return 0;
    const uint64_t usage=AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE|AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    const media_status_t status=AImageReader_newWithUsage(w,h,AIMAGE_FORMAT_PRIVATE,usage,max,&s->reader);
    if(status!=AMEDIA_OK || !s->reader || AImageReader_getWindow(s->reader,&s->window)!=AMEDIA_OK) {
        if(s->reader) AImageReader_delete(s->reader);
        ASurfaceControl_release(s->output); return 0;
    }
    s->wakeFd=eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC);
    if(s->wakeFd<0) {AImageReader_delete(s->reader);ASurfaceControl_release(s->output);return 0;}
    ASurfaceTransaction* tx=ASurfaceTransaction_create();
    ASurfaceTransaction_setEnableBackPressure(tx,s->output,bp);
    if(rate>0) {
        ANativeWindow_setFrameRateWithChangeStrategy(s->window,rate,ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_AT_LEAST,ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS);
        ASurfaceTransaction_setFrameRateWithChangeStrategy(tx,s->output,rate,ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_AT_LEAST,ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS);
    }
    ASurfaceTransaction_apply(tx);ASurfaceTransaction_delete(tx);
    AImageReader_ImageListener listener{s.get(),available};
    if(AImageReader_setImageListener(s->reader,&listener)!=AMEDIA_OK) {AImageReader_delete(s->reader);ASurfaceControl_release(s->output);return 0;}
    const jlong id=nextHandle++;
    {std::lock_guard<std::mutex> lock(registryMutex);registry[id]=s;}
    s->worker=std::thread(runWorker,s);
    return id;
}
extern "C" JNIEXPORT jobject JNICALL Java_com_mekromn_bubble_probe_NativeProbe_surface(JNIEnv* env,jclass,jlong h) {
    auto s=lookup(h); return s?ANativeWindow_toSurface(env,s->window):nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_com_mekromn_bubble_probe_NativeProbe_measuring(JNIEnv*,jclass,jlong h,jboolean active) {
    auto s=lookup(h);if(s)s->measuring=active;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_mekromn_bubble_probe_NativeProbe_finish(JNIEnv* env,jclass,jlong h) {
    auto s=lookup(h);if(!s)return text(env,"{\"error\":\"invalid_handle\"}");
    s->measuring=false;s->stopping=true;s->wake();
    if(s->worker.joinable())s->worker.join(); // Off-main, externally watchdog-protected trial process.
    AImageReader_setImageListener(s->reader,nullptr);
    ASurfaceTransaction* tx=ASurfaceTransaction_create();
    ASurfaceTransaction_reparent(tx,s->output,nullptr);
    ASurfaceTransaction_apply(tx);ASurfaceTransaction_delete(tx);
    ASurfaceControl_release(s->output);s->output=nullptr;
    {
        std::unique_lock<std::mutex> lock(s->dataMutex);
        s->changed.wait_for(lock,std::chrono::milliseconds(1500),[&]{return s->outstanding.load()==0 && s->completions.load()==0;});
    }
    const bool drained=s->outstanding.load()==0;
    if(drained){AImageReader_delete(s->reader);s->reader=nullptr;s->window=nullptr;}
    std::ostringstream out;
    out<<"{\"acquiredTotal\":"<<s->acquired<<",\"submittedTotal\":"<<s->submitted<<",\"discardedTotal\":"<<s->discarded
       <<",\"releasedTotal\":"<<s->released<<",\"callbacksTotal\":"<<s->callbacks<<",\"noBufferTotal\":"<<s->noBuffer
       <<",\"maxImagesTotal\":"<<s->maxImages<<",\"errorsTotal\":"<<s->errors<<",\"lastAcquireStatus\":"<<s->lastStatus
       <<",\"outstandingAtTeardown\":"<<s->outstanding<<",\"pendingCompletions\":"<<s->completions
       <<",\"teardown\":"<<quote(drained?"DRAINED":"RETAINED_UNTIL_TRIAL_PROCESS_EXIT")
       <<",\"bufferWidth\":"<<s->width<<",\"bufferHeight\":"<<s->height<<",\"bufferFormat\":"<<s->format<<",\"bufferUsage\":"<<s->usage
       <<",\"sampleColumns\":[\"acquireNs\",\"submitNs\",\"imageTimestampNs\",\"latchNs\",\"completionCallbackNs\",\"presentFenceSignalNs\",\"hardwareBufferId\",\"acquireCallNs\",\"applyCallNs\",\"releaseCallbackNs\",\"outstandingDepth\"],\"samples\":[";
    {
        std::lock_guard<std::mutex> lock(s->dataMutex);
        bool first=true;
        for(auto& f:s->samples){
            if (f->presentFd>=0) f->present=fenceTime(f->presentFd);
            if(f->presentFd>=0){close(f->presentFd);f->presentFd=-1;}
            if(!first)out<<',';first=false;
            out<<'['<<f->acquired<<','<<f->submitted<<','<<f->imageTimestamp<<','<<f->latch<<','<<f->completed<<','<<f->present<<','<<f->bufferId<<','<<f->acquireCallNs<<','<<f->applyCallNs<<','<<f->releaseCallbackNs<<','<<f->outstandingDepth<<']';
        }
        out<<"],\"unresolvedFenceDrops\":"<<s->unresolvedFenceDrops<<",\"samplesTruncated\":"<<(s->samplesTruncated?"true":"false")<<'}';
    }
    {std::lock_guard<std::mutex> lock(registryMutex);registry.erase(h);if(!drained)quarantine.push_back(s);}
    return text(env,out.str());
}
