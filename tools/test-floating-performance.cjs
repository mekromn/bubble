const fs = require('node:fs');
const assert = require('node:assert/strict');

const live = fs.readFileSync('app/src/main/java/com/mekromn/bubble/LiveGeckoView.kt', 'utf8');
const floating = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt', 'utf8');
const glass = fs.readFileSync('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt', 'utf8');
const render = fs.readFileSync('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');

// Fullscreen GeckoView uses GeckoView's default SurfaceView fast path, but the interactive
// TYPE_APPLICATION_OVERLAY must remain a TextureView so it composites/clips/moves as a normal View.
assert.equal(/override fun setViewBackend\(backend: Int\)/.test(live), false,
  'LiveGeckoView must not globally coerce the floating overlay backend');
assert.match(floating, /setViewBackend\(GeckoView\.BACKEND_TEXTURE_VIEW\)/,
  'Floating browser overlay must use TextureView so Gecko pixels render inside TYPE_APPLICATION_OVERLAY');
assert.equal(/setViewBackend\(GeckoView\.BACKEND_SURFACE_VIEW\)/.test(floating), false,
  'Floating overlay must not force SurfaceView after the transparent-page regression');

assert.match(glass, /floating\.mode == FloatingMode\.CHAT/,
  'Overlay blur policy must distinguish browser CHAT mode from native-only UI modes');
assert.match(glass, /Ui\.dp\(context, 52f\)/,
  'CHAT blur must be limited to the native 52dp header');
assert.match(glass, /Ui\.dp\(context, 48f\)/,
  'CHAT blur must be limited to the native 48dp utility strip');
assert.match(glass, /Gecko page/,
  'Blur policy must explicitly identify the Gecko page region');
assert.match(glass, /region between them has no blur window underneath it at all/,
  'The rendered Gecko page region must never be a blur target');
assert.equal(/FLAG_BLUR_BEHIND|setBlurBehindRadius/.test(glass), false,
  'Full-screen blur-behind APIs are forbidden');
assert.match(glass, /state\.shape != shape \|\| state\.corner != corner/,
  'Live UI blur must cache shape state rather than allocate a new drawable every motion frame');
assert.match(glass, /state\.blur != blurRadius/,
  'Live UI blur must avoid redundant blur-radius configuration');
assert.match(glass, /state\.x == x && state\.y == y && state\.width == w && state\.height == h/,
  'Blur windows must skip redundant geometry writes');

assert.match(floating, /FLAG_HARDWARE_ACCELERATED/,
  'Floating overlay must remain hardware accelerated');
assert.match(render, /preferredDisplayModeId = mode\.modeId/,
  'Floating window must continue voting for the fastest supported display mode');
assert.match(render, /setRequestedFrameRate\(rate\)/,
  'Android 15+ frame-rate voting must remain active');
const activeHigh = workspace.match(/session\.setActive\(true\); session\.setPriorityHint\(GeckoSession\.PRIORITY_HIGH\)/g) || [];
assert.ok(activeHigh.length >= 2,
  'Preserve the current resident-tab priority policy: Voice and ordinary resident tabs remain active/high-priority');

console.log('Floating browser path: fullscreen SurfaceView default, overlay TextureView, live UI-only blur, cached compositor state, hardware acceleration, refresh voting and resident high priority passed.');
