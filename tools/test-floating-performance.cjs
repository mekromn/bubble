const fs = require('node:fs');
const assert = require('node:assert/strict');

const live = fs.readFileSync('app/src/main/java/com/mekromn/bubble/LiveGeckoView.kt', 'utf8');
const floating = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt', 'utf8');
const page = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingGeckoWindow.kt', 'utf8');
const glass = fs.readFileSync('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt', 'utf8');
const render = fs.readFileSync('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');
const diagnostics = fs.readFileSync('app/src/main/java/com/mekromn/bubble/DiagnosticLog.kt', 'utf8');
const meter = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FrameMeter.kt', 'utf8');

assert.equal(/BACKEND_TEXTURE_VIEW/.test(floating + page), false,
  'Floating path must contain no TextureView backend');
assert.equal(/setViewBackend/.test(live), false,
  'LiveGeckoView must remain a thin lifecycle wrapper');
assert.match(floating, /FloatingGeckoWindow\(context\)/,
  'FloatingWindow must own the dedicated raw Gecko page sibling');
assert.match(page, /class DirectGeckoSurfaceHost[\s\S]*: View\(context\), GeckoDisplay\.NewSurfaceProvider/,
  'Floating render host must be a plain input View with Gecko surface-recovery support');
assert.equal(/class DirectGeckoSurfaceHost[\s\S]{0,120}SurfaceView/.test(page), false,
  'Direct floating renderer must not use SurfaceView as its page producer');
assert.equal(/import android\.view\.SurfaceView/.test(page), false,
  'Direct floating renderer must not even import SurfaceView');
assert.match(page, /SurfaceControl\.Builder\(\)[\s\S]*\.setBufferSize\(width, height\)[\s\S]*\.build\(\)/,
  'Floating page must own a real app-created SurfaceControl buffer layer');
assert.match(page, /val androidSurface = Surface\(control\)/,
  'Gecko producer Surface must be constructed directly from the app-owned SurfaceControl');
assert.match(page, /rootControl\.buildReparentTransaction\(control\)/,
  'Direct layer must attach through the public AttachedSurfaceControl root API');
assert.match(page, /\.setVisibility\(control, true\)/,
  'Direct compositor layer must be explicitly visible');
assert.match(page, /class RawSessionBridge[\s\S]*LiveGeckoView/,
  'Workspace compatibility adapter must remain explicit');
assert.equal(/addView\(view\s*,/.test(page), false,
  'RawSessionBridge must never enter the Window/View lifecycle');
assert.match(page, /fun hide\(\)[\s\S]*view\.releaseSession\(\)/,
  'Hiding the direct window must release GeckoDisplay ownership before removing its producer');
assert.match(page, /acquireDisplay\(\)/,
  'Floating raw path must acquire GeckoDisplay directly from GeckoSession');
assert.match(page, /GeckoDisplay\.SurfaceInfo\.Builder\(androidSurface\)/,
  'Floating raw path must publish the direct Android Surface to Gecko');
assert.match(page, /\.surfaceControl\(control\)/,
  'Direct path must publish the exact app-owned SurfaceControl to Gecko');
assert.match(page, /\.newSurfaceProvider\(this\)/,
  'Raw display must provide Gecko a surface-recovery callback');
assert.match(page, /gecko\.surfaceChanged\(info\)/,
  'Direct display must call GeckoDisplay.surfaceChanged with SurfaceInfo');
assert.match(page, /surfaceDestroyed\(\)/,
  'Direct display must tell Gecko when the producer Surface is destroyed');
assert.match(page, /releaseDisplay\(oldDisplay\)/,
  'Direct display must release GeckoDisplay when Workspace releases the session');
assert.match(page, /Could not publish direct Gecko Surface/,
  'Direct publication failures must be caught and diagnosed instead of crashing the process');
assert.match(page, /panZoomController\.onTouchEvent\(event\)/,
  'Touch input must still go directly to Gecko PanZoomController');
assert.match(page, /textInput\.setView\(this\)/,
  'Plain input host must remain Gecko SessionTextInput view');
assert.match(page, /textInput\?\.onCreateInputConnection|textInput\.onCreateInputConnection/,
  'IME connection must be forwarded to Gecko SessionTextInput');
assert.equal(/accessibility\.setView\(this\)/.test(page), false,
  'Plain input host must not replace the attached ViewParent as Gecko accessibility host');
assert.match(page, /check\(host is ViewParent\)[\s\S]*accessibilityHost = host[\s\S]*accessibility\.setView\(host\)/,
  'Raw Gecko accessibility must use its page ViewGroup/ViewParent host');
assert.match(page, /PixelFormat\.OPAQUE/,
  'Direct Gecko page Window must stay opaque');
assert.match(page, /manager\.addView\(root, layout\)/,
  'Direct page/input root must stay in its dedicated WindowManager window for this first A/B');
assert.match(page, /manager\.updateViewLayout\(root, layout\)/,
  'Direct page sibling must follow floating geometry');
assert.match(page, /SurfaceControl\.Transaction\(\)[\s\S]*\.setBufferSize\(control, w, h\)/,
  'Resize must update the producer buffer geometry directly at SurfaceControl level');
assert.match(page, /SurfaceControl\.Transaction\(\)\.reparent\(control, null\)\.apply\(\)/,
  'Producer teardown must explicitly detach the SurfaceControl from the compositor hierarchy');
assert.equal(/\.setCompositionOrder\s*\(/.test(page + live), false,
  'Direct display must not depend on failed same-window composition-order experiments');

assert.match(glass, /private var backdrop: Dialog\? = null/,
  'Hybrid glass must use one service-owned backdrop window');
assert.equal(/private var secondary: Dialog/.test(glass), false,
  'Hybrid must not keep a second blur overlay surface alive');
assert.match(glass, /ChromeMaskDrawable/,
  'CHAT must mask one backdrop to native chrome');
assert.match(glass, /Ui\.dp\(context, 52f\)/,
  'CHAT mask must match native 52dp header');
assert.match(glass, /Ui\.dp\(context, 48f\)/,
  'CHAT mask must match native 48dp utility strip');
assert.equal(/FLAG_BLUR_BEHIND|setBlurBehindRadius/.test(glass), false,
  'Screen-wide blur-behind APIs remain forbidden');
assert.match(glass, /RenderPolicy\.vote\(context, window\.decorView, attributes\)/,
  'The independent live-glass compositor window must share the max-refresh contract');

assert.match(floating, /FLAG_HARDWARE_ACCELERATED/,
  'Native floating chrome must remain hardware accelerated');
assert.match(page, /FLAG_HARDWARE_ACCELERATED/,
  'Direct Gecko page sibling must remain hardware accelerated');
assert.equal(/preferredDisplayModeId = mode\.modeId/.test(render), false,
  'Refresh-only Bubble policy must not pin a display mode id');
assert.match(render, /preferredDisplayModeId = 0/,
  'Any stale mode-id preference must be explicitly cleared');
assert.match(render, /preferredRefreshRate = rate/,
  'Fullscreen and overlay windows must use Android refresh-rate voting directly');
assert.match(render, /setRequestedFrameRate\(rate\)/,
  'Android 15+ per-View frame-rate voting must remain active');
assert.match(render, /setFrameRateBoostOnTouchEnabled\(true\)/,
  'Android 15+ Bubble windows must keep touch frame-rate boost enabled');
assert.match(render, /setFrameRatePowerSavingsBalanced\(false\)/,
  'Android 15+ Bubble windows must favor refresh smoothness over balanced power saving');
assert.match(page, /Surface\.FRAME_RATE_COMPATIBILITY_AT_LEAST/,
  'Android 16 direct webpage Surface must preserve the at-least high-refresh contract');
assert.match(page, /surface\.setFrameRate\(/,
  'Direct webpage producer must receive Surface.setFrameRate, not only a Window/View hint');
assert.match(meter, /RenderPolicy\.vote\(a, a\.window\.decorView, attributes\)/,
  'Fullscreen must retain the exact Build-115 max-refresh policy');
assert.match(diagnostics, /const val ENABLED = false/,
  'Performance builds must keep persistent crash diagnostics parked unless explicitly re-enabled');
const activeHigh = workspace.match(/session\.setActive\(true\); session\.setPriorityHint\(GeckoSession\.PRIORITY_HIGH\)/g) || [];
assert.ok(activeHigh.length >= 2,
  'Preserve current resident-tab priority policy: Voice and ordinary resident tabs stay active/high-priority');

console.log('Floating direct-compositor A/B: Gecko -> Surface(SurfaceControl), no SurfaceView/TextureView page producer, same Build-115 Gecko/runtime/input/refresh policy.');
