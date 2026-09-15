'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const read=p=>fs.readFileSync(p,'utf8');
const relay=read('app/src/main/java/com/mekromn/bubble/FloatingGeckoWindow.kt');
const direct=read('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt');
const chrome=read('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt');
const browser=read('app/src/main/java/com/mekromn/bubble/BrowserActivity.kt');
const mask=read('app/src/main/java/com/mekromn/bubble/EmbeddedPageBackground.kt');
const geometry=read('app/src/main/java/com/mekromn/bubble/EmbeddedSurfacePlacement.kt');
const selector=read('app/src/main/java/com/mekromn/bubble/RendererArena.kt');
const handoff=read('app/src/main/java/com/mekromn/bubble/FullscreenHandoff.kt');

// Both direct production and relay fallback now live in FloatingWindow's one ViewRoot. There must be
// no second page WindowManager owner/synchronizer in either renderer.
assert(!/TYPE_APPLICATION_OVERLAY|updateViewLayout|removeViewImmediate/.test(relay));
assert(!/TYPE_APPLICATION_OVERLAY|updateViewLayout|removeViewImmediate|OnPreDrawListener|OnLayoutChangeListener/.test(direct));
assert.match(direct,/parent\.addView\(root, 0, FrameLayout\.LayoutParams\(-1, -1\)\)/);

// User-confirmed relay remains intact as a fallback, but it is not the selected steady renderer.
assert.match(relay,/parent.addView\(root, 0,/);
assert.match(relay,/: SurfaceView\(context\),/);
assert.match(relay,/setZOrderOnTop\(false\)/);
assert.match(relay,/transaction.reparent\(control, anchor\).setLayer\(control, 1\)/);
assert.match(relay,/applyTransactionOnDraw\(tx\)/);
assert.match(relay,/RendererArena.Transport.RELAY_LATEST_BP/);
assert.match(relay,/capturePagePixels/);

// Production steady state delegates page production to Mozilla GeckoView's constructor-owned
// SurfaceView. Same ViewRoot does not mean a copied View texture or Bubble-owned page buffer.
assert.match(selector,/var transport: Transport = RendererArena\.Transport\.DIRECT_GECKO_SURFACE|var transport: Transport = Transport\.DIRECT_GECKO_SURFACE/);
assert.match(direct,/LiveGeckoView\(context\)/);
assert(!/setViewBackend\(/.test(direct));
assert.match(direct,/SurfaceView/);
assert.match(direct,/RendererArena\.Transport\.DIRECT_GECKO_SURFACE/);
assert.match(direct,/view\.capturePixels\(\)/);
assert(!/NativeAhbBridge|AImageReader|nativeCreate|nativeGetProducerSurface|GeckoDisplay\.SurfaceInfo|panZoomController\.onTouchEvent|textInput\.setView|accessibility\.setView/.test(direct));
assert(!/class DirectSurfaceHost|requestNewSurface/.test(direct));

// Floating chrome owns host selection and the single card geometry. A drag/resize is one WM update.
assert.match(chrome,/RendererArena\.createHost\(context\)/);
assert.match(chrome,/RendererArena\.createFallback\(context\)/);
assert.match(chrome,/current\.transport==RendererArena\.Transport\.DIRECT_GECKO_SURFACE/);
assert.match(chrome,/FullscreenHandoff\.launchFromFloating\(context,root,geckoWindow,intent\)/);
assert.match(chrome,/manager\.updateViewLayout\(root,params\)/);
assert.match(chrome,/setUpdateListener \{ geckoWindow\?\.geometryChanged\(\) \}/);
assert.match(chrome,/coverForReveal\(true\)/);assert.match(chrome,/coverForReveal\(false\)/);

// Snapshot machinery remains transition-only; steady direct browsing never routes through it.
assert.match(handoff,/captureFloatingFrame/);
assert.match(handoff,/finishIntoFullscreen/);

// Floating->fullscreen must survive either Android lifecycle ordering for a reused SINGLE_TOP Activity.
assert.match(browser,/private var fullscreenEntryScheduled = false/);
assert.match(browser,/private fun continueFullscreenEntryIfNeeded\(\)/);
assert.match(browser,/override fun onNewIntent[\s\S]*continueFullscreenEntryIfNeeded\(\)/);
assert.match(browser,/override fun onStart[\s\S]*continueFullscreenEntryIfNeeded\(\)/);

// Card background clips exactly around the same-ViewRoot Gecko child; no offscreen page cache.
assert.match(mask,/canvas\.clipOutRect\(cutout\)/);
assert(!/SEAM_GUARD_PX/.test(mask));
assert(!/canvas.saveLayer|Bitmap\.createBitmap|LAYER_TYPE_HARDWARE|PorterDuff/.test(mask+relay+direct));
assert(!/getLocationInSurface|transformMatrixToGlobal/.test(geometry));
assert.match(geometry,/opacity \*= current.alpha/);

const native=read('app/src/main/cpp/bubble_ahb.cpp');
assert.match(native,/if \(!s->hasSubmittedBuffer\) \{[\s\S]*ASurfaceTransaction_setColor[\s\S]*s->hasSubmittedBuffer = true/);
const runtime=read('app/src/androidTest/java/com/mekromn/bubble/RelayLatestBpRuntimeTest.kt');
for(const requirement of ['same-window','dragged-page','resized-cyan','scrolled-B','ime-visible','native-control-over-page','geometry-alpha-hidden'])assert(runtime.includes(requirement));
const hybrid=read('app/src/androidTest/java/com/mekromn/bubble/HybridDirectRuntimeTest.kt');
for(const requirement of ['direct-floating-live','fullscreen-return','direct-floating-return','relayIdle','same GeckoSession','fullscreen-source-hidden'])assert(hybrid.includes(requirement));
console.log('Hybrid page guards: one floating ViewRoot, original Mozilla SurfaceView direct path, relay fallback, input/IME and transition safety are preserved.');
