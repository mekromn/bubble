const fs = require('node:fs');
const assert = require('node:assert/strict');

const live = fs.readFileSync('app/src/main/java/com/mekromn/bubble/LiveGeckoView.kt', 'utf8');
const floating = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt', 'utf8');
const glass = fs.readFileSync('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt', 'utf8');
const render = fs.readFileSync('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');

// The historical floating call is retained as a stable trigger, but LiveGeckoView must translate it
// into Gecko's direct SurfaceView and fix SurfaceView Z-order before WindowManager attachment.
assert.match(floating, /setViewBackend\(GeckoView\.BACKEND_TEXTURE_VIEW\)/,
  'Floating A/B trigger must remain stable');
assert.match(live, /super\.setViewBackend\(BACKEND_SURFACE_VIEW\)/,
  'Floating render path must instantiate Gecko SurfaceView');
assert.match(live, /check\(!isAttachedToWindow\)/,
  'SurfaceView Z-order must be configured before overlay attachment');
assert.match(live, /setZOrderOnTop\(true\)/,
  'Floating SurfaceView must be promoted above the translucent overlay window');
assert.equal(/super\.setViewBackend\(BACKEND_TEXTURE_VIEW\)/.test(live), false,
  'Hybrid must never actually instantiate Gecko TextureView');

// Return to Build-84-style single compositor-window glass. CHAT uses one masked drawable with a fully
// transparent middle instead of keeping separate top/bottom overlay windows alive.
assert.match(glass, /private var backdrop: Dialog\? = null/,
  'Hybrid glass must use one service-owned backdrop window');
assert.equal(/private var secondary: Dialog/.test(glass), false,
  'Hybrid must not keep a second blur overlay surface alive');
assert.match(glass, /ChromeMaskDrawable/,
  'CHAT must mask one backdrop to native chrome rather than blur page pixels visually');
assert.match(glass, /Ui\.dp\(context, 52f\)/,
  'CHAT mask must match the native 52dp header');
assert.match(glass, /Ui\.dp\(context, 48f\)/,
  'CHAT mask must match the native 48dp utility strip');
assert.equal(/FLAG_BLUR_BEHIND|setBlurBehindRadius/.test(glass), false,
  'Screen-wide blur-behind APIs remain forbidden');
assert.match(glass, /state\.x == x && state\.y == y && state\.width == width && state\.height == height/,
  'Single blur window must skip redundant motion geometry writes');

assert.match(floating, /FLAG_HARDWARE_ACCELERATED/,
  'Floating overlay must remain hardware accelerated');
assert.match(render, /preferredDisplayModeId = mode\.modeId/,
  'Floating window must continue voting for the fastest supported display mode');
assert.match(render, /setRequestedFrameRate\(rate\)/,
  'Android 15+ frame-rate voting must remain active');
const activeHigh = workspace.match(/session\.setActive\(true\); session\.setPriorityHint\(GeckoSession\.PRIORITY_HIGH\)/g) || [];
assert.ok(activeHigh.length >= 2,
  'Preserve current resident-tab priority policy: Voice and ordinary resident tabs stay active/high-priority');

console.log('Hybrid fast path: direct top-Z SurfaceView + one masked glass surface + max refresh + resident high priority.');
