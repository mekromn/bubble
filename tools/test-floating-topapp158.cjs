'use strict';
const fs = require('fs');
const assert = require('assert');

const activity = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingTopAppAnchorActivity.kt','utf8');
const touch = fs.readFileSync('app/src/main/java/com/mekromn/bubble/PageTouchDispatch.kt','utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');
const styles = fs.readFileSync('app/src/main/res/values/styles.xml','utf8');
const gradle = fs.readFileSync('app/build.gradle.kts','utf8');

assert.match(gradle,/versionCode\s*=\s*157/,'Probe must remain same install version as current 157 for direct swapping');
assert.match(activity,/width = 1/);
assert.match(activity,/height = 1/);
assert.match(activity,/alpha = 0f/);
assert.match(activity,/FLAG_NOT_TOUCHABLE/);
assert.match(activity,/FLAG_NOT_FOCUSABLE/);
assert.match(activity,/FLAG_NOT_TOUCH_MODAL/);
assert.match(activity,/ActivityManager\.getMyMemoryState/);
assert.match(touch,/floatingVisible == true[\s\S]*FloatingTopAppAnchorActivity\.ensure/);
assert.match(manifest,/FloatingTopAppAnchorActivity/);
assert.match(manifest,/android:noHistory="true"/);
assert.match(manifest,/Theme\.Bubble\.TopAppAnchor/);
assert.match(styles,/windowIsFloating">true/);
assert.match(styles,/Theme\.Bubble\.TopAppAnchor/);

// This probe must not modify page quality or add a frame loop.
assert.ok(!/postDelayed\(/.test(activity));
assert.ok(!/Choreographer/.test(activity));
assert.ok(!/TextureView/.test(activity));
assert.ok(!/setFixedSize\(/.test(activity));
assert.ok(!/ACTION_MOVE[\s\S]*FloatingTopAppAnchorActivity\.ensure/.test(touch));

console.log('Bubble top-app probe guards passed: same v157 install identity, 1x1 fully transparent application Activity, gesture-start only, no frame loop.');
