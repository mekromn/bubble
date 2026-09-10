const fs = require('node:fs');
const assert = require('node:assert/strict');

const live = fs.readFileSync('app/src/main/java/com/mekromn/bubble/LiveGeckoView.kt', 'utf8');
const floating = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt', 'utf8');
const glass = fs.readFileSync('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt', 'utf8');
const render = fs.readFileSync('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');

// FloatingWindow's historical TextureView request is only a marker. LiveGeckoView translates it to
// SurfaceView, keeps that view INVISIBLE while temporarily attached to translucent chrome so no
// SurfaceView surface can be created there, then promotes the same GeckoView into a separate opaque
// overlay window and makes it VISIBLE only after WindowManager owns the dedicated page window.
assert.match(floating, /setViewBackend\(GeckoView\.BACKEND_TEXTURE_VIEW\)/,
  'Floating marker call must remain stable');
assert.match(live, /super\.setViewBackend\(BACKEND_SURFACE_VIEW\)/,
  'Floating page must instantiate Gecko SurfaceView');
assert.equal(/super\.setViewBackend\(BACKEND_TEXTURE_VIEW\)/.test(live), false,
  'Floating path must never instantiate Gecko TextureView');
assert.match(live, /visibility = View\.INVISIBLE[\s\S]*super\.setViewBackend\(BACKEND_SURFACE_VIEW\)/,
  'Floating SurfaceView must remain invisible before first chrome attachment');
assert.match(live, /host\.removeView\(this\)[\s\S]*manager\.addView\(this, params\)[\s\S]*visibility = View\.VISIBLE/,
  'SurfaceView may become visible only after dedicated WindowManager attachment');
assert.match(live, /PixelFormat\.OPAQUE/,
  'Dedicated Gecko page window must be opaque for the direct compositor path');
assert.match(live, /WindowManager\.LayoutParams\.TYPE_APPLICATION_OVERLAY/,
  'Dedicated Gecko page must own an application-overlay window');
assert.match(live, /ViewTreeObserver\.OnPreDrawListener/,
  'Original page slot must drive geometry synchronization without a timer');
assert.match(live, /manager\.updateViewLayout\(this, params\)/,
  'Dedicated Gecko window must follow real page-slot geometry');
assert.match(live, /x == lastX && y == lastY && width == lastWidth && height == lastHeight/,
  'Geometry synchronization must skip redundant WindowManager writes');
assert.equal(/setZOrderOnTop|setCompositionOrder/.test(live), false,
  'Dedicated page window must not rely on failed same-window SurfaceView Z-order tricks');
assert.equal(/removeCallbacksAndMessages\(null\)/.test(live), false,
  'Dedicated Gecko cleanup must not erase unrelated handler work');
assert.match(live, /Direct Gecko window failed:/,
  'Dedicated-window attachment failure must be visible on the real device');

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
assert.match(glass, /state\.x == x && state\.y == y && state\.width == width && state\.height == height/,
  'Single blur window must skip redundant motion geometry writes');

assert.match(floating, /FLAG_HARDWARE_ACCELERATED/,
  'Native floating chrome must remain hardware accelerated');
assert.match(live, /FLAG_HARDWARE_ACCELERATED/,
  'Dedicated Gecko overlay must remain hardware accelerated');
assert.match(render, /preferredDisplayModeId = mode\.modeId/,
  'Floating windows must continue voting for the fastest supported display mode');
assert.match(render, /setRequestedFrameRate\(rate\)/,
  'Android 15+ frame-rate voting must remain active');
const activeHigh = workspace.match(/session\.setActive\(true\); session\.setPriorityHint\(GeckoSession\.PRIORITY_HIGH\)/g) || [];
assert.ok(activeHigh.length >= 2,
  'Preserve current resident-tab priority policy: Voice and ordinary resident tabs stay active/high-priority');

console.log('Dedicated Gecko fast path: surface-less chrome staging -> opaque SurfaceView overlay + anchored geometry + one masked glass surface + max refresh + resident high priority.');
