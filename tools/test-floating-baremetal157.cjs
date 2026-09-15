const fs = require('fs');
const assert = require('assert');

const direct = fs.readFileSync('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt', 'utf8');
const background = fs.readFileSync('app/src/main/java/com/mekromn/bubble/EmbeddedPageBackground.kt', 'utf8');
const render = fs.readFileSync('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt', 'utf8');
const policy = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingPerformancePolicy.kt', 'utf8');
const bridge = fs.readFileSync('app/src/main/java/com/mekromn/bubble/NativePerformanceBridge.kt', 'utf8');
const touch = fs.readFileSync('app/src/main/java/com/mekromn/bubble/PageTouchDispatch.kt', 'utf8');
const native = fs.readFileSync('app/src/main/cpp/floating_perf.cpp', 'utf8');
const cmake = fs.readFileSync('app/src/main/cpp/CMakeLists.txt', 'utf8');
const meter = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FrameMeter.kt', 'utf8');
const gradle = fs.readFileSync('app/build.gradle.kts', 'utf8');

const version = Number((gradle.match(/versionCode\s*=\s*(\d+)/) || [])[1]);
assert.ok(version >= 157, 'Build 157+ versionCode required');
assert.match(gradle, /opaque-floating-page-baremetal/, 'Build name must identify bare-metal floating candidate');

// Seam/geometry invariants.
assert.match(direct, /PixelFormat\.OPAQUE/, 'Page remains independently opaque');
assert.match(direct, /SOFT_INPUT_ADJUST_NOTHING/, 'Independent page must not be double-resized by IME');
assert.match(direct, /setCanPlayMoveAnimation\(false\)/, 'Independent page window must not receive platform move animation');
assert.match(render, /TYPE_APPLICATION_OVERLAY[\s\S]*setCanPlayMoveAnimation\(false\)/, 'All Bubble overlay windows must skip WMS move animation');
assert.match(direct, /addOnLayoutChangeListener\(layoutChange\)/, 'Page slot changes need event-driven layout synchronization');
assert.match(direct, /OnPreDrawListener/, 'Pre-draw synchronization remains as a transformed-geometry backstop');
assert.match(background, /SEAM_GUARD_PX\s*=\s*2f/, 'Chrome must underpaint a two-physical-pixel seam guard');
assert.match(background, /cutout\.inset\(SEAM_GUARD_PX, SEAM_GUARD_PX\)/, 'Seam guard must be under the opaque page, not crop page pixels');

// Public Android 16 ADPF integration: bind the real Gecko SurfaceView, no page copy/pump.
assert.match(direct, /surfaceView\(root\).*FloatingPerformancePolicy\.bind/s, 'ADPF must bind Mozilla real SurfaceView');
assert.match(direct, /FloatingPerformancePolicy\.unbind\(\)/, 'ADPF session must follow Surface lifetime');
assert.match(policy, /SurfaceHolder\.Callback/, 'Performance session must track the producer Surface lifetime');
assert.match(policy, /Thread\(\{[\s\S]*NativePerformanceBridge\.nativeStart\(surface, rate, mainTid\)/, 'Native ADPF setup/thread discovery must run off main');
assert.match(policy, /generation == ticket/, 'An in-flight ADPF setup must be rejected after Surface replacement');
assert.match(policy, /Bubble-floating-ADPF-setup/, 'ADPF setup worker must be explicit and short-lived');
assert.match(bridge, /nativeNotifyInteraction/, 'Gesture workload hint bridge must exist');
assert.match(touch, /ACTION_DOWN/, 'Touch path remains gesture-start scoped');
assert.match(touch, /FloatingPerformancePolicy\.interactionStart\(\)/, 'Floating touch-down must pre-announce scroll workload');

assert.match(native, /APerformanceHint_isFeatureSupported\(APERF_HINT_SESSIONS\)/, 'ADPF session support must be feature-gated');
assert.match(native, /APERF_HINT_GRAPHICS_PIPELINE/, 'Graphics-pipeline ADPF mode required');
assert.match(native, /APERF_HINT_SURFACE_BINDING/, 'Surface-bound ADPF mode required');
assert.match(native, /APERF_HINT_AUTO_CPU/, 'Automatic CPU timing support must be checked');
assert.match(native, /APERF_HINT_AUTO_GPU/, 'Automatic GPU timing support must be checked');
assert.match(native, /ASessionCreationConfig_setNativeSurfaces/, 'Real producer surface must be associated with ADPF');
assert.match(native, /ASessionCreationConfig_setUseAutoTiming/, 'ADPF automatic timing must avoid an app-side per-frame meter');
assert.match(native, /APerformanceHint_notifyWorkloadIncrease/, 'Gesture-start workload increase hint required');
assert.match(cmake, /bubble_ahb\.cpp floating_perf\.cpp/, 'ADPF bridge must ship in the optimized native library');
assert.match(meter, /FloatingPerformancePolicy\.diagnostics\(\)/, 'User-triggered local meter must expose ADPF result');

// Fidelity/per-frame safety. This build may change scheduling/geometry, never page quality.
assert.ok(!/TextureView\s*\(/.test(direct), 'Do not introduce a TextureView renderer');
assert.ok(!/setFixedSize\(/.test(direct), 'Do not force a reduced page buffer size');
assert.ok(!/postDelayed[\s\S]*interactionStart/.test(policy), 'No periodic workload-hint loop');
assert.ok(!/ACTION_MOVE[\s\S]*interactionStart/.test(touch), 'Never send ADPF hints for each move event');
assert.ok(!/getMyMemoryState[\s\S]*nativeNotifyInteraction/.test(policy.split('fun interactionStart()')[1]?.split('fun diagnostics()')[0] || ''), 'Gesture hot path must not query ActivityManager');
assert.ok(!/-ffast-math/.test(cmake), 'Do not trade numerical fidelity for speed');

console.log('Bubble 157 guards passed: seam hardening, no WMS move animation, off-main Surface-bound Android 16 ADPF auto timing, gesture-only boost, fidelity preserved.');
