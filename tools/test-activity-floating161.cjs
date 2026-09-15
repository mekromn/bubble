'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const read=p=>fs.readFileSync(p,'utf8');
const strip=s=>s.replace(/\/\*[\s\S]*?\*\//g,'').replace(/\/\/.*$/gm,'');

const activity=strip(read('app/src/main/java/com/mekromn/bubble/FloatingBrowserActivity.kt'));
const service=strip(read('app/src/main/java/com/mekromn/bubble/BubbleService.kt'));
const bubble=strip(read('app/src/main/java/com/mekromn/bubble/RestingBubbleWindow.kt'));
const manifest=read('app/src/main/AndroidManifest.xml');
const direct=strip(read('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt'));

assert.match(activity,/class FloatingBrowserActivity : Activity\(\)/);
assert.match(activity,/window\.setType\(WindowManager\.LayoutParams\.TYPE_APPLICATION_OVERLAY\)/,
  'Expanded UI must use the Activity\'s own PhoneWindow in overlay layer');
assert.match(activity,/setContentView\(root\)/);
assert.match(activity,/RendererArena\.createHost\(this\)/);
assert.match(activity,/workspace\.attachSurface\(view, session\)/);
assert.match(activity,/FLAG_NOT_TOUCH_MODAL/);
assert.match(activity,/window\.attributes = attrs/);
assert.ok(!/FloatingPriorityAnchorActivity/.test(activity+service+manifest), 'No helper/anchor Activity is allowed');
assert.ok(!/WindowManager\.addView\(root/.test(activity), 'Expanded Activity must not add a second interactive WindowManager root');

assert.match(service,/FloatingBrowserActivity::class\.java/);
assert.match(service,/activityHostReady/);
assert.match(service,/RestingBubbleWindow/);
assert.ok(!/FloatingWindow\(this, workspace\)/.test(service), 'Service must not build the old expanded overlay window');

assert.match(bubble,/TYPE_APPLICATION_OVERLAY/);
assert.match(bubble,/service\.openExpanded\(FloatingMode\.CHAT/);
assert.match(bubble,/service\.openExpanded\(FloatingMode\.CHOOSER/);
assert.match(manifest,/\.FloatingBrowserActivity/);
assert.match(manifest,/Theme\.Bubble\.FloatingActivity/);
assert.match(manifest,/taskAffinity="\$\{applicationId\}\.floating_activity"/);

// Build-158 single-ViewRoot direct page transport remains the selected renderer inside the Activity.
assert.match(direct,/parent\.addView\(root, 0, FrameLayout\.LayoutParams\(-1, -1\)\)/);
assert.ok(!/TextureView|ImageReader|PixelCopy/.test(direct), 'No new page-copy renderer in direct host');

console.log('Bubble 161 guards passed: expanded chooser/chat is the actual Activity PhoneWindow, direct Gecko stays one ViewRoot, minimized access alone remains service overlay.');
