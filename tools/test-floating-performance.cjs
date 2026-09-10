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

// Floating rendering bypasses GeckoView's display backend entirely. Workspace still sees a tiny
// GeckoView-shaped bookkeeping adapter, but that adapter must NEVER attach to a Window/View tree:
// GeckoView's private session/display lifecycle would then diverge from the raw GeckoDisplay owner.
assert.equal(/BACKEND_TEXTURE_VIEW/.test(floating + page), false,
  'Floating path must contain no TextureView backend');
assert.equal(/setViewBackend/.test(live), false,
  'LiveGeckoView must remain a thin lifecycle wrapper');
assert.match(floating, /FloatingGeckoWindow\(context\)/,
  'FloatingWindow must own the dedicated raw Gecko page sibling');
assert.match(page, /class RawGeckoSurfaceView[\s\S]*SurfaceView/,
  'Floating page must be a plain Android SurfaceView');
assert.match(page, /class RawSessionBridge[\s\S]*LiveGeckoView/,
  'Workspace compatibility adapter must remain explicit');
assert.equal(/addView\(view\s*,/.test(page), false,
  'RawSessionBridge must never enter the Window/View lifecycle');
assert.match(page, /fun hide\(\)[\s\S]*view\.releaseSession\(\)/,
  'Hiding the raw window must release GeckoDisplay ownership before removing the Surface');
assert.match(page, /acquireDisplay\(\)/,
  'Floating raw path must acquire GeckoDisplay directly from GeckoSession');
assert.match(page, /GeckoDisplay\.SurfaceInfo\.Builder\(androidSurface\)/,
  'Floating raw path must publish the actual Android Surface to Gecko');
assert.match(page, /\.surfaceControl\(surfaceControl\)/,
  'API 29+ SurfaceView path must publish its SurfaceControl to Gecko');
assert.match(page, /\.newSurfaceProvider\(this\)/,
  'Raw display must provide Gecko a surface-recovery callback');
assert.match(page, /gecko\.surfaceChanged\(builder\.build\(\)\)/,
  'Raw display must call GeckoDisplay.surfaceChanged with SurfaceInfo');
assert.match(page, /surfaceDestroyed\(\)/,
  'Raw display must tell Gecko when the Android Surface is destroyed');
assert.match(page, /releaseDisplay\(oldDisplay\)/,
  'Raw display must release GeckoDisplay when Workspace releases the session');
assert.match(page, /Could not publish raw Gecko Surface/,
  'SurfaceHolder publication failures must be caught and diagnosed instead of crashing the process');
assert.match(page, /panZoomController\.onTouchEvent\(event\)/,
  'Touch input must go directly to Gecko PanZoomController');
assert.match(page, /textInput\.setView\(this\)/,
  'Raw SurfaceView must become Gecko SessionTextInput view');
assert.match(page, /textInput\?\.onCreateInputConnection|textInput\.onCreateInputConnection/,
  'IME connection must be forwarded to Gecko SessionTextInput');
assert.equal(/accessibility\.setView\(this\)/.test(page), false,
  'Raw SurfaceView must never be the Gecko accessibility event host because SurfaceView is not ViewParent');
assert.match(page, /check\(host is ViewParent\)[\s\S]*accessibilityHost = host[\s\S]*accessibility\.setView\(host\)/,
  'Raw Gecko accessibility must use its page ViewGroup/ViewParent host');
assert.match(page, /PixelFormat\.OPAQUE/,
  'Raw Gecko page window must be opaque');
assert.match(page, /setZOrderOnTop\(true\)/,
  'Dedicated page-only SurfaceView must compose above its own opaque overlay Window');
assert.match(page, /manager\.addView\(root, layout\)/,
  'Raw SurfaceView must be attached in its own WindowManager root');
assert.match(page, /manager\.updateViewLayout\(root, layout\)/,
  'Raw page sibling must follow floating geometry directly');
assert.match(floating, /pageBox\(rectangle\)/,
  'FloatingWindow must derive the page rectangle from its authoritative geometry');
assert.match(floating, /geckoWindow\?\.sync\(pageBox\(fitted\)\)/,
  'Move/resize must synchronously move the raw page sibling');
assert.equal(/\.setCompositionOrder\s*\(/.test(page + live), false,
  'Raw display must not depend on the failed same-window Android-16 composition-order experiment');

// Build-84-style single compositor-window glass. CHAT uses one masked drawable with transparent center.
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

assert.match(floating, /FLAG_HARDWARE_ACCELERATED/,
  'Native floating chrome must remain hardware accelerated');
assert.match(page, /FLAG_HARDWARE_ACCELERATED/,
  'Raw Gecko page sibling must remain hardware accelerated');
assert.match(render, /preferredDisplayModeId = mode\.modeId/,
  'Both window paths must vote for the fastest same-resolution display mode');
assert.match(render, /setRequestedFrameRate\(rate\)/,
  'Android 15+ per-View frame-rate voting must remain active');
assert.match(render, /Surface\.FRAME_RATE_COMPATIBILITY_AT_LEAST/,
  'Android 16 UI surfaces must request an at-least high refresh contract');
assert.match(render, /surface\.setFrameRate\(/,
  'Real SurfaceView producers must receive Surface.setFrameRate, not only a window hint');
assert.match(render, /if \(!created\.voted\) applySurfaceRate/,
  'Surface frame-rate contracts must not be spammed during resize callbacks');
assert.match(meter, /RenderPolicy\.vote\(a, a\.window\.decorView, attributes\)/,
  'Fullscreen must share the exact same max-refresh policy as floating mode');
assert.match(diagnostics, /const val ENABLED = false/,
  'Performance builds must keep persistent crash diagnostics parked unless explicitly re-enabled');
const activeHigh = workspace.match(/session\.setActive\(true\); session\.setPriorityHint\(GeckoSession\.PRIORITY_HIGH\)/g) || [];
assert.ok(activeHigh.length >= 2,
  'Preserve current resident-tab priority policy: Voice and ordinary resident tabs stay active/high-priority');

console.log('Floating raw speed path: no TextureView + ViewParent a11y + producer Surface max-refresh + shared fullscreen max-refresh + one masked glass surface + diagnostics parked.');
