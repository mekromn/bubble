'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const read = p => fs.readFileSync(p, 'utf8');

const effects = read('app/src/main/java/com/mekromn/bubble/VisualEffects.kt');
const app = read('app/src/main/java/com/mekromn/bubble/BubbleApp.kt');
const ui = read('app/src/main/java/com/mekromn/bubble/Ui.kt');
const glass = read('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt');
const bubble = read('app/src/main/java/com/mekromn/bubble/GlassBubble.kt');
const sheet = read('app/src/main/java/com/mekromn/bubble/ControlsSheet.kt');
const access = read('app/src/main/java/com/mekromn/bubble/AccessMenu.kt');
const gradle = read('app/build.gradle.kts');

assert.match(gradle, /versionCode = 149/);
assert.match(effects, /KEY_TRANSPARENCY/);
assert.match(effects, /getBoolean\(KEY_TRANSPARENCY, true\)/,
  'Existing glass look should remain the default until user disables it');
assert.match(app, /VisualEffects\.initialize\(this\)/,
  'Visual mode must be known before the first Activity/window draws');
assert.match(access, /Transparency and blur/);
assert.match(access, /VisualEffects\.setTransparency/);
assert.match(access, /recreate\(\)/,
  'Current Activity should rebuild immediately after the mode changes');

assert.match(ui, /val SURFACE: Int get\(\) = if \(VisualEffects\.transparencyEnabled\(\)\)/);
assert.match(ui, /!transparent -> intArrayOf\(0xff383838/,
  'Floating panel fallback must be fully opaque with transparency off');
assert.match(glass, /if \(!VisualEffects\.transparencyEnabled\(\)\) \{\s*release\(\)\s*return false/s,
  'Opaque mode must remove blur windows/listeners, not merely set blur radius to zero');
assert.match(bubble, /alpha=if \(transparent\).*else 1f/,
  'Resting bubble opacity slider must not reintroduce cross-window blending in opaque mode');
assert.match(sheet, /val dim = if \(VisualEffects\.transparencyEnabled\(\)\) \.32f else 0f/,
  'Opaque mode must remove full-screen dim-behind blending');

// Performance mode may remove compositor effects, never browser fidelity or hardware policy.
for (const forbidden of ['setResolution', 'scaleX=.5', 'scaleY=.5', 'setFrameRate(60', 'geckoAbi = "x86"']) {
  assert.equal(effects.includes(forbidden), false, `Visual-effects preference must not lower webpage fidelity: ${forbidden}`);
}

console.log('Bubble 149 global opaque-mode guards passed: persistent chrome opaque, blur windows released, dim blend removed, Gecko fidelity untouched.');
