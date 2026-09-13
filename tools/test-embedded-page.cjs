'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const read=p=>fs.readFileSync(p,'utf8');
const relay=read('app/src/main/java/com/mekromn/bubble/FloatingGeckoWindow.kt');
const direct=read('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt');
const chrome=read('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt');
const mask=read('app/src/main/java/com/mekromn/bubble/EmbeddedPageBackground.kt');
const geometry=read('app/src/main/java/com/mekromn/bubble/EmbeddedSurfacePlacement.kt');
const selector=read('app/src/main/java/com/mekromn/bubble/RendererArena.kt');
const handoff=read('app/src/main/java/com/mekromn/bubble/FullscreenHandoff.kt');

// Neither page host may recreate the old independent WindowManager page window.
for(const page of [relay,direct]) assert(!/TYPE_APPLICATION_OVERLAY|updateViewLayout|removeViewImmediate/.test(page));

// User-confirmed relay remains intact as fallback.
assert.match(relay,/parent.addView\(root, 0,/);
assert.match(relay,/: SurfaceView\(context\),/);
assert.match(relay,/setZOrderOnTop\(false\)/);
assert.match(relay,/transaction.reparent\(control, anchor\).setLayer\(control, 1\)/);
assert.match(relay,/applyTransactionOnDraw\(tx\)/);
assert.match(relay,/RendererArena.Transport.RELAY_LATEST_BP/);
assert.match(relay,/capturePagePixels/);

// Production steady state delegates the whole direct lifecycle to Mozilla GeckoView.
// GeckoView's explicit SurfaceView backend is the path that supplies its real Surface
// and matching SurfaceControl internally; Bubble must not reproduce input/display plumbing.
assert.match(selector,/var transport: Transport = Transport.DIRECT_GECKO_SURFACE/);
assert.match(direct,/LiveGeckoView\(context\)/);
assert.match(direct,/setViewBackend\(GeckoView\.BACKEND_SURFACE_VIEW\)/);
assert.match(direct,/RendererArena\.Transport\.DIRECT_GECKO_SURFACE/);
assert.match(direct,/view\.capturePixels\(\)/);
assert(!/NativeAhbBridge|AImageReader|nativeCreate|nativeGetProducerSurface|GeckoDisplay\.SurfaceInfo|panZoomController\.onTouchEvent|textInput\.setView|accessibility\.setView/.test(direct));
assert(!/class DirectSurfaceHost|SurfaceHolder\.Callback|requestNewSurface/.test(direct));

// Actual floating chrome owns the host selection and can fall back without page reload.
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

assert.match(mask,/canvas.clipOutRect\(cutout\)/);
assert(!/canvas.saveLayer|Bitmap\.createBitmap|LAYER_TYPE_HARDWARE|PorterDuff/.test(mask+relay+direct));
assert(!/getLocationInSurface|transformMatrixToGlobal/.test(geometry));
assert.match(geometry,/opacity \*= current.alpha/);

const native=read('app/src/main/cpp/bubble_ahb.cpp');
assert.match(native,/if \(!s->hasSubmittedBuffer\) \{[\s\S]*ASurfaceTransaction_setColor[\s\S]*s->hasSubmittedBuffer = true/);
const runtime=read('app/src/androidTest/java/com/mekromn/bubble/RelayLatestBpRuntimeTest.kt');
for(const requirement of ['same-window','dragged-page','resized-cyan','scrolled-B','ime-visible','native-control-over-page','geometry-alpha-hidden'])assert(runtime.includes(requirement));
const hybrid=read('app/src/androidTest/java/com/mekromn/bubble/HybridDirectRuntimeTest.kt');
for(const requirement of ['direct-floating','fullscreen-return','direct-floating-return','relayIdle','same GeckoSession'])assert(hybrid.includes(requirement));
console.log('Hybrid page guards: Mozilla GeckoView SurfaceView steady state, relay fallback preserved, one floating ViewRoot, snapshot-only fullscreen morphs.');
