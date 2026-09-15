'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const read=p=>fs.readFileSync(p,'utf8');
const page=read('app/src/main/java/com/mekromn/bubble/FloatingGeckoWindow.kt');
const bridge=read('app/src/main/java/com/mekromn/bubble/NativeAhbBridge.kt');
const native=read('app/src/main/cpp/bubble_ahb.cpp');
const live=read('app/src/main/java/com/mekromn/bubble/LiveGeckoView.kt');
const render=read('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt');
const glass=read('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt');
const floating=read('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt');
const ws=read('app/src/main/java/com/mekromn/bubble/Workspace.kt');
assert.match(native,/kMaxImages = 6;/);assert.match(native,/kDrainLimit = 4;/);
assert.match(native,/kOutputBackpressure = true;/);
assert.match(native,/ASurfaceTransaction_setEnableBackPressure\(tx, s->output, kOutputBackpressure\)/);
assert.match(native,/AImageReader_newWithUsage\(width, height, AIMAGE_FORMAT_PRIVATE,[\s\S]*kConsumerUsage, kMaxImages/);
assert.match(native,/i < kDrainLimit/);
for(const fn of ['AImageReader_acquireNextImageAsync','AImage_getHardwareBuffer','ANativeWindow_toSurface','ASurfaceControl_fromJava','ASurfaceTransaction_setBufferWithRelease'])assert(native.includes(fn));
assert.match(native,/AImage_deleteAsync\(image, releaseFenceFd\)/);
assert.match(native,/if \(latest\) discard\(latest, latestFence\)/);
assert.match(native,/std::thread\(run, s\)\.detach\(\)/);
assert.match(native,/changed.wait\(lock/); // No periodic idle polling for deferred destruction.
assert.match(native,/s->workerFinished.load\(\) && s->leases.load\(\) == 0/);
assert.match(native,/kMaximumLiveGenerations = 8/);
const destroy=native.split('Java_com_mekromn_bubble_NativeAhbBridge_nativeDestroy')[1].split('extern "C"')[0];
assert(!/join\(|wait\(|acquireNext|AImageReader_delete\(/.test(destroy));
for(const forbidden of ['nativePump','framePump','AHARDWAREBUFFER_USAGE_FRONT_BUFFER','setSharedBufferMode','setAutoRefresh','acquireLatestImageAsync','AHardwareBuffer_lock(','glReadPixels(','memcpy(','dlopen(','dlsym('])assert(!(native+page+bridge).includes(forbidden),forbidden);
assert.match(page,/creator.execute/);assert.match(page,/generation != ticket/);
assert.match(page,/detachAndRelease\(it, !creating\)/);
assert.match(page,/fun unbind[\s\S]*releasePipeline\(\)/);
assert.match(page,/accessibility.setView\(parentView\)/);
assert.match(page,/panZoomController.onTouchEvent\(event\)/);
assert.match(page,/textInput.setView\(this\)/);
assert(!/BACKEND_TEXTURE_VIEW|addView\(view,/.test(page));
assert(!/setViewBackend/.test(live));
assert.match(live,/override fun hasWindowFocus/);
assert.match(render,/preferredRefreshRate = rate/);
assert.match(render,/Surface.FRAME_RATE_COMPATIBILITY_AT_LEAST/);

// Blur is platform compositor blur only: no screen-wide blur-behind, screenshot cache, or software blur.
assert(!/FLAG_BLUR_BEHIND|setBlurBehindRadius/.test(glass));
assert.match(glass,/private var primary: BlurWindow\? = null/);
assert.match(glass,/private var secondary: BlurWindow\? = null/);
assert.match(glass,/FloatingMode\.CHAT -> updateChat/);
assert.match(glass,/Ui\.dp\(context, 52f\)/);
assert.match(glass,/Ui\.dp\(context, 48f\)/);
assert.match(glass,/Shape\.TOP/); assert.match(glass,/Shape\.BOTTOM/);
assert.match(glass,/source\.y \+ height - bottom/);
assert(!/ChromeMaskDrawable/.test(glass), 'A transparent drawable hole must not masquerade as a split SF blur region');
assert(!/RenderPolicy\.vote\(context, window\.decorView/.test(glass), 'Blur-only windows must not cast high-refresh votes');
assert.match(glass,/if \(state\.x == x && state\.y == y && state\.width == width && state\.height == height\) return/,
  'Identical blur geometry must not cause a WindowManager relayout');
assert.match(floating,/internal fun crossWindowBlurChanged\(enabled:Boolean\)/);
assert(!/val nowBlur=OverlayGlass\.available\(manager\)/.test(floating),
  'Normal Workspace renders must not poll cross-window blur state');

assert.match(ws,/session.setActive\(true\); session.setPriorityHint\(GeckoSession.PRIORITY_HIGH\)/);
assert.match(read('app/src/main/java/com/mekromn/bubble/DiagnosticLog.kt'),/const val ENABLED = false/);
console.log('Relay fallback ownership and bounded, listener-driven compositor blur invariants passed (not physical runtime proof).');
