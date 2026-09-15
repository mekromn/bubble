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

// The relay fallback remains a same-ViewRoot renderer. Build 156 intentionally allows only the
// direct renderer to own a second exact-size OPAQUE overlay page window.
assert(!/TYPE_APPLICATION_OVERLAY|updateViewLayout|removeViewImmediate/.test(relay));
assert.match(direct,/TYPE_APPLICATION_OVERLAY/);
assert.match(direct,/PixelFormat\.OPAQUE/);
assert.match(direct,/manager\.addView\(root, pageParams\)/);
assert.match(direct,/showEmbedded\(parent\)/,
  'independent opaque page must retain the previous same-ViewRoot direct fallback');

// User-confirmed relay remains intact as fallback.
assert.match(relay,/parent.addView\(root, 0,/);
assert.match(relay,/: SurfaceView\(context\),/);
assert.match(relay,/setZOrderOnTop\(false\)/);
assert.match(relay,/transaction.reparent\(control, anchor\).setLayer\(control, 1\)/);
assert.match(relay,/applyTransactionOnDraw\(tx\)/);
assert.match(relay,/RendererArena.Transport.RELAY_LATEST_BP/);
assert.match(relay,/capturePagePixels/);

// Production steady state still delegates actual page production to Mozilla GeckoView's original
// SurfaceView. The new Window boundary must not replace or emulate Gecko's Surface backend.
assert.match(selector,/var transport: Transport = RendererArena\.Transport\.DIRECT_GECKO_SURFACE|var transport: Transport = Transport\.DIRECT_GECKO_SURFACE/);
assert.match(direct,/LiveGeckoView\(context\)/);
assert(!/setViewBackend\(/.test(direct));
assert.match(direct,/original SurfaceView|constructor already created and wired the direct SurfaceView backend|constructor-wired direct SurfaceView/);
assert.match(direct,/RendererArena\.Transport\.DIRECT_GECKO_SURFACE/);
assert.match(direct,/view\.capturePixels\(\)/);
assert(!/NativeAhbBridge|AImageReader|nativeCreate|nativeGetProducerSurface|GeckoDisplay\.SurfaceInfo|panZoomController\.onTouchEvent|textInput\.setView|accessibility\.setView/.test(direct));
assert(!/class DirectSurfaceHost|SurfaceHolder\.Callback|requestNewSurface/.test(direct));

// Actual floating chrome owns the host selection and can still fall back without page reload.
assert.match(chrome,/RendererArena\.createHost\(context\)/);
assert.match(chrome,/RendererArena\.createFallback\(context\)/);
assert.match(chrome,/current\.transport==RendererArena\.Transport\.DIRECT_GECKO_SURFACE/);
assert.match(chrome,/FullscreenHandoff\.launchFromFloating\(context,root,geckoWindow,intent\)/);
assert.match(chrome,/setUpdateListener \{ geckoWindow\?\.geometryChanged\(\) \}/);
assert.match(chrome,/coverForReveal\(true\)/);assert.match(chrome,/coverForReveal\(false\)/);

// Snapshot machinery is transition-only; direct browsing does not route through it.
assert.match(handoff,/captureFloatingFrame/);
assert.match(handoff,/finishIntoFullscreen/);
assert(!/makeClipRevealAnimation/.test(handoff));

// Floating->fullscreen must survive either Android lifecycle ordering for a reused SINGLE_TOP
// Activity, and a frozen transition frame may never remain above a live fullscreen browser.
assert.match(browser,/private var fullscreenEntryScheduled = false/);
assert.match(browser,/private fun continueFullscreenEntryIfNeeded\(\)/);
assert.match(browser,/override fun onNewIntent[\s\S]*continueFullscreenEntryIfNeeded\(\)/);
assert.match(browser,/override fun onStart[\s\S]*continueFullscreenEntryIfNeeded\(\)/);
assert.match(handoff,/EXPAND_WATCHDOG_MS = 2200L/);
assert.match(handoff,/scheduleExpandWatchdog\(overlay\)/);
assert.match(handoff,/private fun clearExpandWatchdog\(\)/);
assert.match(handoff,/fun cancelAll\(\)[\s\S]*clearExpandWatchdog\(\)/);

assert.match(mask,/canvas.clipOutRect\(cutout\)/);
assert(!/canvas.saveLayer|Bitmap\.createBitmap|LAYER_TYPE_HARDWARE|PorterDuff/.test(mask+relay+direct));
assert(!/getLocationInSurface|transformMatrixToGlobal/.test(geometry));
assert.match(geometry,/opacity \*= current.alpha/);

const native=read('app/src/main/cpp/bubble_ahb.cpp');
assert.match(native,/if \(!s->hasSubmittedBuffer\) \{[\s\S]*ASurfaceTransaction_setColor[\s\S]*s->hasSubmittedBuffer = true/);
const runtime=read('app/src/androidTest/java/com/mekromn/bubble/RelayLatestBpRuntimeTest.kt');
for(const requirement of ['same-window','dragged-page','resized-cyan','scrolled-B','ime-visible','native-control-over-page','geometry-alpha-hidden'])assert(runtime.includes(requirement));
const hybrid=read('app/src/androidTest/java/com/mekromn/bubble/HybridDirectRuntimeTest.kt');
for(const requirement of ['direct-floating-live','fullscreen-return','direct-floating-return','relayIdle','same GeckoSession','fullscreen-source-hidden'])assert(hybrid.includes(requirement));
console.log('Hybrid page guards: original Mozilla GeckoView SurfaceView wiring, Build-156 opaque direct page window, lifecycle-safe fullscreen return, relay fallback and snapshot-only morphs are preserved.');
