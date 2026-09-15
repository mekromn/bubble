'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const read=p=>fs.readFileSync(p,'utf8');
const strip=s=>s.replace(/\/\*[\s\S]*?\*\//g,'').replace(/\/\/.*$/gm,'');

const anchor=strip(read('app/src/main/java/com/mekromn/bubble/FloatingPriorityAnchorActivity.kt'));
const service=strip(read('app/src/main/java/com/mekromn/bubble/BubbleService.kt'));
const floating=strip(read('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt'));
const manifest=read('app/src/main/AndroidManifest.xml');
const styles=read('app/src/main/res/values/styles.xml');

assert.match(anchor,/class FloatingPriorityAnchorActivity : Activity\(\)/);
assert.match(anchor,/width = 1/); assert.match(anchor,/height = 1/);
assert.match(anchor,/FLAG_NOT_TOUCHABLE/); assert.match(anchor,/FLAG_NOT_TOUCH_MODAL/);
assert.match(anchor,/@Volatile private var wanted = false/);
assert.match(anchor,/if \(!wanted\) \{\s*finishAndRemoveTask\(\)/);
assert.match(anchor,/fun setWanted\(value: Boolean\)/);
assert.ok(!/Handler|postDelayed|Choreographer|Gecko|RenderPolicy|setFrameRate|preferredRefreshRate/.test(anchor),
  'Priority anchor must remain zero-work and renderer/frame-rate neutral');

assert.match(service,/onFloatingModeChanged\(mode: FloatingMode\?\)/);
assert.match(service,/mode == FloatingMode\.CHAT/);
assert.match(service,/FloatingPriorityAnchorActivity::class\.java/);
assert.match(service,/FLAG_ACTIVITY_NEW_TASK/);
assert.match(service,/FloatingPriorityAnchorActivity\.setWanted\(wanted\)/);
assert.match(service,/catch \(_: RuntimeException\) \{\s*priorityAnchorRequested = false\s*FloatingPriorityAnchorActivity\.setWanted\(false\)/);
assert.match(service,/private fun removeSurfaces\(\) \{\s*setPriorityAnchor\(false\)/);

assert.match(floating,/service\.onFloatingModeChanged\(mode\)/);
assert.match(floating,/service\.onFloatingModeChanged\(next\)/);
assert.match(manifest,/\.FloatingPriorityAnchorActivity/);
assert.match(manifest,/android:taskAffinity="\$\{applicationId\}\.priority_anchor"/);
assert.match(styles,/Theme\.Bubble\.PriorityAnchor/);

console.log('Bubble 160 auto TOP-state guards passed: Build-158 floating CHAT drives a race-safe 1x1 zero-work resumed Activity and cancels late launches when CHAT closes.');
