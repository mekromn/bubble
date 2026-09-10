const fs = require('node:fs');
const assert = require('node:assert/strict');

const live = fs.readFileSync('app/src/main/java/com/mekromn/bubble/LiveGeckoView.kt', 'utf8');
const floating = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt', 'utf8');
const page = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingGeckoWindow.kt', 'utf8');
const glass = fs.readFileSync('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt', 'utf8');
const render = fs.readFileSync('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');

// Gecko must never enter the translucent native chrome tree. FloatingWindow owns a separate page
// sibling window from birth, and that sibling uses Gecko's SurfaceView backend directly.
assert.equal(/BACKEND_TEXTURE_VIEW/.test(floating), false,
  'FloatingWindow must never request Gecko TextureView');
assert.equal(/setViewBackend/.test(live), false,
  'LiveGeckoView must not perform backend/window surgery itself');
assert.match(floating, /FloatingGeckoWindow\(context\)/,
  'FloatingWindow must own the dedicated Gecko page sibling');
assert.match(page, /setViewBackend\(GeckoView\.BACKEND_SURFACE_VIEW\)/,
  'Dedicated floating page must instantiate Gecko SurfaceView directly');
assert.equal(/BACKEND_TEXTURE_VIEW/.test(page), false,
  'Dedicated floating page must contain no TextureView fallback');
assert.match(page, /PixelFormat\.OPAQUE/,
  'Dedicated Gecko page window must be opaque for the direct compositor path');
assert.match(page, /WindowManager\.LayoutParams\.TYPE_APPLICATION_OVERLAY/,
  'Dedicated Gecko page must own an application-overlay window');
assert.match(page, /manager\.addView\(view, layout\)/,
  'Gecko SurfaceView must be born directly into its own WindowManager root');
assert.match(page, /manager\.updateViewLayout\(view, layout\)/,
  'Dedicated Gecko page must follow floating geometry directly');
assert.match(floating, /pageBox\(rectangle\)/,
  'FloatingWindow must derive the exact page rectangle from its own authoritative geometry');
assert.match(floating, /panel\.y\+top/,
  'Dedicated page must start below the 52dp native header');
assert.match(floating, /panel\.height-top-bottom/,
  'Dedicated page must stop above the 48dp native utility strip');
assert.match(floating, /geckoWindow\?\.sync\(pageBox\(fitted\)\)/,
  'Floating move/resize must synchronously move the dedicated page sibling');
assert.match(floating, /geckoWindow\?\.hide\(\)/,
  'Non-chat transitions must explicitly remove the page sibling');
assert.equal(/\.setZOrderOnTop\s*\(|\.setCompositionOrder\s*\(/.test(page + live), false,
  'Dedicated sibling architecture must not call same-window SurfaceView Z-order APIs');

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
assert.match(page, /FLAG_HARDWARE_ACCELERATED/,
  'Dedicated Gecko page sibling must remain hardware accelerated');
assert.match(render, /preferredDisplayModeId = mode\.modeId/,
  'Floating windows must continue voting for the fastest supported display mode');
assert.match(render, /setRequestedFrameRate\(rate\)/,
  'Android 15+ frame-rate voting must remain active');
const activeHigh = workspace.match(/session\.setActive\(true\); session\.setPriorityHint\(GeckoSession\.PRIORITY_HIGH\)/g) || [];
assert.ok(activeHigh.length >= 2,
  'Preserve current resident-tab priority policy: Voice and ordinary resident tabs stay active/high-priority');

console.log('Floating fast path: first-class opaque SurfaceView sibling + direct geometry + one masked glass surface + max refresh + resident high priority.');
