'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const read=p=>fs.readFileSync(p,'utf8');
const strip=s=>s.replace(/\/\*[\s\S]*?\*\//g,'').replace(/\/\/.*$/gm,'');
const anchor=read('app/src/main/java/com/mekromn/bubble/FloatingPriorityAnchorActivity.kt');
const anchorCode=strip(anchor);
const arena=read('app/src/main/java/com/mekromn/bubble/RendererArenaActivity.kt');
const manifest=read('app/src/main/AndroidManifest.xml');
const styles=read('app/src/main/res/values/styles.xml');
const direct=read('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt');
const floating=read('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt');

assert.match(anchorCode,/class FloatingPriorityAnchorActivity : Activity\(\)/);
assert.match(anchorCode,/width = 1/); assert.match(anchorCode,/height = 1/);
assert.match(anchorCode,/FLAG_NOT_TOUCHABLE/); assert.match(anchorCode,/FLAG_NOT_TOUCH_MODAL/);
assert.match(anchorCode,/ActivityManager\.getMyMemoryState/);
assert.ok(!/Handler|postDelayed|Choreographer|RenderPolicy|Gecko|setFrameRate|preferredRefreshRate/.test(anchorCode),
  'Anchor must be zero-work and renderer/frame-rate neutral');
assert.match(arena,/Open zero-work TOP-state anchor/);
assert.match(arena,/FloatingPriorityAnchorActivity::class\.java/);
assert.match(manifest,/\.FloatingPriorityAnchorActivity/);
assert.match(manifest,/Theme\.Bubble\.PriorityAnchor/);
assert.match(styles,/Theme\.Bubble\.PriorityAnchor/);

// Build 158 renderer architecture must remain physically unchanged by this causal probe.
assert.ok(!/TYPE_APPLICATION_OVERLAY|WindowManager|updateViewLayout/.test(strip(direct)));
assert.match(direct,/parent\.addView\(root, 0, FrameLayout\.LayoutParams\(-1, -1\)\)/);
assert.match(floating,/manager\.updateViewLayout\(root,params\)/);

console.log('Bubble 159 top-state probe guards passed: 1x1 zero-work Activity only; Build-158 renderer remains unchanged.');
