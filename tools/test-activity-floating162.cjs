'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const read=p=>fs.readFileSync(p,'utf8');
const strip=s=>s.replace(/\/\*[\s\S]*?\*\//g,'').replace(/\/\/.*$/gm,'');

const activity=strip(read('app/src/main/java/com/mekromn/bubble/FloatingBrowserActivity.kt'));
const service=strip(read('app/src/main/java/com/mekromn/bubble/BubbleService.kt'));
const bubble=strip(read('app/src/main/java/com/mekromn/bubble/RestingBubbleWindow.kt'));
const manifest=read('app/src/main/AndroidManifest.xml');
const styles=read('app/src/main/res/values/styles.xml');
const direct=strip(read('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt'));

assert.match(activity,/class FloatingBrowserActivity : Activity\(\)/);
assert.ok(!/window\.setType\(WindowManager\.LayoutParams\.TYPE_APPLICATION_OVERLAY\)/.test(activity),
  'A PhoneWindow with an Activity token must remain an application-window type');
assert.ok(!/TYPE_APPLICATION_OVERLAY/.test(activity),
  'Expanded Activity must never try to masquerade as an overlay window');
assert.match(activity,/setContentView\(root\)/);
assert.match(activity,/RendererArena\.createHost\(this\)/);
assert.match(activity,/workspace\.attachSurface\(view, session\)/);
assert.match(activity,/FLAG_NOT_TOUCH_MODAL/);
assert.match(activity,/attrs\.width = box\.width/);
assert.match(activity,/attrs\.height = box\.height/);
assert.match(activity,/window\.attributes = attrs/);
assert.match(styles,/Theme\.Bubble\.FloatingActivity/);
assert.match(styles,/android:windowIsFloating">true/);
assert.match(styles,/android:windowIsTranslucent">true/);
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

assert.match(direct,/parent\.addView\(root, 0, FrameLayout\.LayoutParams\(-1, -1\)\)/);
assert.ok(!/TextureView|ImageReader|PixelCopy/.test(direct), 'No new page-copy renderer in direct host');

console.log('Bubble 162 guards passed: expanded Bubble is a real floating Activity application window, Build-158 direct Gecko stays single-ViewRoot, minimized access alone uses TYPE_APPLICATION_OVERLAY.');
