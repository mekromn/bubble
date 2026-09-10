const fs = require('node:fs');
const assert = require('node:assert/strict');

const live = fs.readFileSync('app/src/main/java/com/mekromn/bubble/LiveGeckoView.kt', 'utf8');
const floating = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt', 'utf8');
const glass = fs.readFileSync('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt', 'utf8');
const render = fs.readFileSync('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');

assert.match(live, /override fun setViewBackend\(backend: Int\)/,
  'LiveGeckoView must own the backend choice so floating callers cannot silently select TextureView');
assert.match(live, /super\.setViewBackend\(BACKEND_SURFACE_VIEW\)/,
  'Every live GeckoView must resolve to the direct SurfaceView backend');
assert.equal(/super\.setViewBackend\(backend\)/.test(live), false,
  'LiveGeckoView must not pass a slower caller-selected backend through');

assert.match(glass, /floating\.mode == FloatingMode\.CHAT/,
  'Overlay blur policy must distinguish browser CHAT mode from native-only UI modes');
assert.match(glass, /Ui\.dp\(context, 52f\)/,
  'CHAT blur must be limited to the native 52dp header');
assert.match(glass, /Ui\.dp\(context, 48f\)/,
  'CHAT blur must be limited to the native 48dp utility strip');
assert.match(glass, /The page region in between has no blur/,
  'Source contract must explicitly preserve a zero-blur Gecko page region');
assert.equal(/FLAG_BLUR_BEHIND|setBlurBehindRadius/.test(glass), false,
  'Full-screen blur-behind APIs are forbidden');

assert.match(floating, /FLAG_HARDWARE_ACCELERATED/,
  'Floating overlay must remain hardware accelerated');
assert.match(render, /preferredDisplayModeId = mode\.modeId/,
  'Floating window must continue voting for the fastest supported display mode');
assert.match(render, /setRequestedFrameRate\(rate\)/,
  'Android 15+ frame-rate voting must remain active');
assert.match(workspace, /session\.setActive\(true\); session\.setPriorityHint\(GeckoSession\.PRIORITY_HIGH\)/,
  'Resident browser sessions must retain active/high-priority scheduling');

console.log('Floating browser fast path: SurfaceView, UI-only blur, hardware acceleration and refresh voting passed.');
