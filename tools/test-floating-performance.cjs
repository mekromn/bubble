const fs = require('node:fs');
const assert = require('node:assert/strict');

const live = fs.readFileSync('app/src/main/java/com/mekromn/bubble/LiveGeckoView.kt', 'utf8');
const floating = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt', 'utf8');
const page = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingGeckoWindow.kt', 'utf8');
const native = fs.readFileSync('app/src/main/cpp/bubble_ahb.cpp', 'utf8');
const glass = fs.readFileSync('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt', 'utf8');
const render = fs.readFileSync('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');
const diagnostics = fs.readFileSync('app/src/main/java/com/mekromn/bubble/DiagnosticLog.kt', 'utf8');
const meter = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FrameMeter.kt', 'utf8');

assert.equal(/BACKEND_TEXTURE_VIEW/.test(floating + page), false,
  'Floating path must contain no TextureView backend');
assert.equal(/SurfaceView/.test(page), false,
  'Combined native-buffer experiment must not use SurfaceView as the floating page producer');
assert.match(page, /class NativeBufferHost[\s\S]*: View\(context\), GeckoDisplay\.NewSurfaceProvider/,
  'Only a plain input/IME/accessibility View may remain in the floating page Window');
assert.match(page, /NativeAhbBridge\.nativeCreate/,
  'Floating page must create the combined native ANativeWindow/AHardwareBuffer pipeline');
assert.match(page, /GeckoDisplay\.SurfaceInfo\.Builder\(androidSurface\)/,
  'Gecko must render to the native producer Surface returned from ANativeWindow');
assert.equal(/\.surfaceControl\(.*\)/.test(page), false,
  'Gecko producer Surface must not be the display SurfaceControl itself in this relay design');
assert.match(page, /panZoomController\.onTouchEvent\(event\)/,
  'Touch input must still go directly to Gecko APZ');
assert.match(page, /textInput\.setView\(this\)/,
  'Plain input host must remain the Gecko SessionTextInput target');
assert.match(page, /accessibility\.setView\(parentView\)/,
  'Gecko accessibility must remain on the attached ViewParent host');
assert.match(page, /Build\.VERSION\.SDK_INT < 36/,
  'Combined experiment must explicitly require Android 16 for release-fence APIs');

for (const required of [
  'AImageReader_newWithUsage',
  'AImageReader_getWindow',
  'ANativeWindow_toSurface',
  'AImageReader_acquireLatestImageAsync',
  'AImage_getHardwareBuffer',
  'ASurfaceControl_fromJava',
  'ASurfaceTransaction_setBufferWithRelease',
  'AImage_deleteAsync',
  'AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE',
  'AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER',
  'AHARDWAREBUFFER_USAGE_FRONT_BUFFER',
  'AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY',
  'ANativeWindow_setSharedBufferMode',
  'ANativeWindow_setAutoRefresh',
  'ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_AT_LEAST',
  'ANATIVEWINDOW_CHANGE_FRAME_RATE_ALWAYS',
  'dlopen("libnativewindow.so"',
  'dlsym(',
]) {
  assert.ok(native.includes(required), `Native zero-copy/front-buffer path must contain ${required}`);
}

const usageBlock = native.match(/constexpr uint64_t kConsumerUsage\s*=([\s\S]*?);/);
assert.ok(usageBlock, 'Consumer usage block must exist');
assert.ok(usageBlock[1].includes('AHARDWAREBUFFER_USAGE_FRONT_BUFFER'),
  'Consumer allocation must request front-buffer semantics');
assert.ok(usageBlock[1].includes('AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY'),
  'Consumer allocation must request HWC overlay eligibility at the same time');
assert.ok(usageBlock[1].includes('AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER'),
  'Consumer allocation must remain GPU render-target capable');
assert.ok(usageBlock[1].includes('AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE'),
  'Consumer allocation must remain SurfaceFlinger GPU-composition compatible when HWC falls back');

assert.match(native, /frontBuffer\.setUsage\(renderer->producerWindow, kProducerUsage\)[\s\S]*frontBuffer\.setSharedBufferMode\(renderer->producerWindow, true\)[\s\S]*frontBuffer\.setAutoRefresh\(renderer->producerWindow, true\)/,
  'Resolved LL-NDK controls must force usage + shared-buffer mode + auto-refresh together');
assert.match(native, /FrontBufferApi& frontBuffer = frontBufferApi\(\);[\s\S]*if \(!frontBuffer\.ready\(\)\) return 0;/,
  'Native pipeline must fail closed if any front-buffer LL-NDK symbol is unavailable');
assert.equal(/extern "C" int ANativeWindow_set(?:Usage|SharedBufferMode|AutoRefresh)/.test(native), false,
  'Front-buffer LL-NDK controls must not be direct unresolved app-stub references');
assert.match(native, /description\.usage[\s\S]*kRequiredPresentedUsage/,
  'Physical test must refuse buffers that lost front-buffer/HWC usage flags');
assert.match(native, /ASurfaceTransaction_setPosition\(transaction, renderer->outputControl, 0, 0\)[\s\S]*ASurfaceTransaction_setScale\(transaction, renderer->outputControl, 1\.0f, 1\.0f\)/,
  'Presented page layer must remain 1:1 and unscaled for maximum HWC eligibility');

for (const forbidden of [
  'AHardwareBuffer_lock(',
  'AHardwareBuffer_lockPlanes(',
  'ANativeWindow_lock(',
  'ANativeWindow_unlockAndPost(',
  'memcpy(',
  'memmove(',
  'glReadPixels(',
]) {
  assert.equal(native.includes(forbidden), false,
    `Native zero-copy path must not introduce CPU/readback copy primitive ${forbidden}`);
}
assert.match(native, /AImageReader_acquireLatestImageAsync[\s\S]*ASurfaceTransaction_setBufferWithRelease/,
  'Same AImageReader frame must flow directly into SurfaceFlinger without a pixel conversion stage');
assert.match(native, /releaseFrame[\s\S]*AImage_deleteAsync\(lease->image, releaseFenceFd\)/,
  'SurfaceFlinger release fence must gate returning the exact buffer to AImageReader');
assert.match(native, /ASurfaceTransaction_setEnableBackPressure\(transaction, renderer->outputControl, false\)/,
  'Latency path intentionally avoids compositor backpressure');

assert.equal(/setViewBackend/.test(live), false,
  'LiveGeckoView must remain a thin lifecycle wrapper');
assert.match(floating, /FloatingGeckoWindow\(context\)/,
  'FloatingWindow must continue owning one dedicated Gecko page sibling');
assert.match(page, /class RawSessionBridge[\s\S]*LiveGeckoView/,
  'Workspace compatibility adapter must remain explicit and detached');
assert.equal(/addView\(view\s*,/.test(page), false,
  'RawSessionBridge must never enter the Window/View lifecycle');
assert.match(page, /acquireDisplay\(\)/,
  'Floating native-buffer path must still acquire GeckoDisplay directly from GeckoSession');
assert.match(page, /releaseDisplay\(oldDisplay\)/,
  'Floating path must release GeckoDisplay when Workspace releases the session');

assert.match(glass, /private var backdrop: Dialog\? = null/,
  'Floating glass must remain one service-owned backdrop window');
assert.equal(/private var secondary: Dialog/.test(glass), false,
  'Floating glass must not keep a second blur window alive');
assert.equal(/FLAG_BLUR_BEHIND|setBlurBehindRadius/.test(glass), false,
  'Screen-wide blur-behind remains forbidden');
assert.match(glass, /RenderPolicy\.vote\(context, window\.decorView, attributes\)/,
  'Glass window must retain the maximum-refresh contract');

assert.equal(/preferredDisplayModeId = mode\.modeId/.test(render), false,
  'Refresh-only Bubble policy must not pin a display mode id');
assert.match(render, /preferredRefreshRate = rate/,
  'Fullscreen and overlay Windows must continue requesting maximum refresh');
assert.match(render, /setRequestedFrameRate\(rate\)/,
  'Android 15+ View frame-rate voting must remain active');
assert.match(render, /Surface\.FRAME_RATE_COMPATIBILITY_AT_LEAST/,
  'Android 16 ordinary Surface producers must keep the at-least refresh policy');
assert.match(meter, /RenderPolicy\.vote\(a, a\.window\.decorView, attributes\)/,
  'Fullscreen must keep the same maximum-refresh policy');
assert.match(diagnostics, /const val ENABLED = false/,
  'Persistent diagnostics remain implemented but disabled');
const activeHigh = workspace.match(/session\.setActive\(true\); session\.setPriorityHint\(GeckoSession\.PRIORITY_HIGH\)/g) || [];
assert.ok(activeHigh.length >= 2,
  'Resident ChatGPT/Voice sessions must remain active and high priority');

console.log('Floating combined path: shared ANativeWindow front buffer -> same AHardwareBuffer -> HWC-eligible ASurfaceControl, fenced zero-copy, runtime-resolved LL-NDK controls, no SurfaceView/TextureView/CPU readback.');
