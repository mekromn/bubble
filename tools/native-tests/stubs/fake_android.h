#pragma once
#include <jni.h>
#include <atomic>
#include <cassert>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>
using ADataSpace = int;
using media_status_t = int;
constexpr int ADATASPACE_UNKNOWN=0, AMEDIA_OK=0, AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE=1,
    AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED=2, AIMAGE_FORMAT_PRIVATE=34;
constexpr uint64_t AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE=1, AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY=2;
constexpr int ANDROID_LOG_INFO=4, ANDROID_LOG_ERROR=6;
constexpr int ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_AT_LEAST=2, ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS=1,
    ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE=2;
struct AHardwareBuffer { int id; };
struct ANativeWindow {};
struct ASurfaceControl {};
struct AImageReader;
struct AImage { AImageReader* owner; AHardwareBuffer buffer; int acquireFence,space; };
struct AImageReader_ImageListener { void* context; void(*onImageAvailable)(void*,AImageReader*); };
struct AImageReader {
    std::mutex mutex; std::deque<AImage*> queue;
    int acquired=0, maximum=6; ANativeWindow window;
    AImageReader_ImageListener listener{};
};
using Callback=void(*)(void*,int);
struct Pending { void* context; Callback callback; int bufferId,acquireFence; };
struct ASurfaceTransaction { std::vector<Pending> buffers; };
inline std::mutex presentedMutex;
inline std::deque<Pending> presented;
inline std::atomic<int> transactionCreates{0},transactionDeletes{0}, acquireCalls{0}, spaceWrites{0}, opacityWrites{0}, readerDeletes{0};
inline std::vector<int> returnedImages,returnedFences;
inline int __android_log_print(int,const char*,const char*,...) { return 0; }
inline int AImageReader_newWithUsage(int,int,int,uint64_t,int maximum,AImageReader** out) {
    *out=new AImageReader; (*out)->maximum=maximum; return 0;
}
inline void AImageReader_delete(AImageReader* r) { if(!r)return;assert(r->acquired==0);for(auto* i:r->queue)delete i;delete r;readerDeletes++; }
inline int AImageReader_getWindow(AImageReader* r,ANativeWindow** w) { *w=&r->window; return 0; }
inline int AImageReader_setImageListener(AImageReader* r,AImageReader_ImageListener* l) {
    std::lock_guard<std::mutex> lock(r->mutex);r->listener=l?*l:AImageReader_ImageListener{};return 0;
}
inline int AImageReader_acquireNextImageAsync(AImageReader* r,AImage** image,int* fence) {
    acquireCalls++;std::lock_guard<std::mutex> lock(r->mutex);
    if(r->acquired==r->maximum)return AMEDIA_IMGREADER_MAX_IMAGES_ACQUIRED;
    if(r->queue.empty())return AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE;
    *image=r->queue.front();r->queue.pop_front();r->acquired++;*fence=(*image)->acquireFence;return 0;
}
inline void AImage_deleteAsync(AImage* image,int fence) {
    assert(image);std::lock_guard<std::mutex> lock(image->owner->mutex);
    assert(image->owner->acquired>0);image->owner->acquired--;
    returnedImages.push_back(image->buffer.id);returnedFences.push_back(fence);delete image;
}
inline int AImage_getHardwareBuffer(AImage* image,AHardwareBuffer** b) { *b=&image->buffer;return 0; }
inline int AImage_getDataSpace(AImage* image,int32_t* s) { *s=image->space;return 0; }
inline ASurfaceControl* ASurfaceControl_fromJava(JNIEnv*,jobject) { return new ASurfaceControl; }
inline void ASurfaceControl_release(ASurfaceControl* s) { delete s; }
inline jobject ANativeWindow_toSurface(JNIEnv*,ANativeWindow*) { return nullptr; }
inline int ANativeWindow_setFrameRateWithChangeStrategy(ANativeWindow*,float,int,int) { return 0; }
inline ASurfaceTransaction* ASurfaceTransaction_create() { transactionCreates++;return new ASurfaceTransaction; }
inline void ASurfaceTransaction_delete(ASurfaceTransaction* t) { transactionDeletes++;delete t; }
inline void ASurfaceTransaction_setBufferWithRelease(ASurfaceTransaction* t,ASurfaceControl*,AHardwareBuffer* b,int f,void* c,Callback cb) {
    t->buffers.push_back({c,cb,b->id,f});
}
inline void ASurfaceTransaction_apply(ASurfaceTransaction* t) {
    std::lock_guard<std::mutex> lock(presentedMutex);
    for(auto& b:t->buffers)presented.push_back(b);
    t->buffers.clear(); // Models AOSP Transaction::apply state reset, not physical rendering.
}
inline void ASurfaceTransaction_setBufferDataSpace(ASurfaceTransaction*,ASurfaceControl*,ADataSpace) { spaceWrites++; }
inline void ASurfaceTransaction_setBufferTransparency(ASurfaceTransaction*,ASurfaceControl*,int) { opacityWrites++; }
inline void ASurfaceTransaction_setFrameRateWithChangeStrategy(ASurfaceTransaction*,ASurfaceControl*,float,int,int) {}
inline void ASurfaceTransaction_setEnableBackPressure(ASurfaceTransaction*,ASurfaceControl*,bool enabled) { assert(enabled); }
inline void ASurfaceTransaction_setPosition(ASurfaceTransaction*,ASurfaceControl*,int,int) {}
inline void ASurfaceTransaction_setScale(ASurfaceTransaction*,ASurfaceControl*,float,float) {}
inline void ASurfaceTransaction_reparent(ASurfaceTransaction*,ASurfaceControl*,ASurfaceControl*) {}
