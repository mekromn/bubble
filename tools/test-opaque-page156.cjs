const fs = require('fs');
const assert = require('assert');

const direct = fs.readFileSync('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt', 'utf8');
const gradle = fs.readFileSync('app/build.gradle.kts', 'utf8');

const version = Number((gradle.match(/versionCode\s*=\s*(\d+)/) || [])[1]);
assert.ok(version >= 156, 'Build 156+ versionCode required');
assert.match(gradle, /opaque-floating-page/, 'Build name must identify opaque floating-page candidate');

assert.match(direct, /TYPE_APPLICATION_OVERLAY/, 'Direct floating page must own an independent overlay window');
assert.match(direct, /PixelFormat\.OPAQUE/, 'Steady-state page window must advertise opaque composition');
assert.match(direct, /title\s*=\s*"Bubble opaque floating page"/, 'Page window needs a stable SurfaceFlinger diagnostic title');
assert.match(direct, /manager\.addView\(root, pageParams\)/, 'Page root must be attached through WindowManager, not only the chrome ViewRoot');
assert.match(direct, /parent\.transformMatrixToGlobal\(transform\)/, 'Independent page geometry must follow transformed chrome geometry');
assert.match(direct, /if \(!force && lastGeometry == geometry && pageParams\.alpha == desiredAlpha\) return/, 'Identical page WindowManager updates must be skipped');
assert.match(direct, /OnPreDrawListener/, 'Geometry sync must be draw/event driven rather than a polling loop');
assert.match(direct, /showEmbedded\(parent\)/, 'Previous direct same-ViewRoot path must remain a reliability fallback');
assert.match(direct, /RenderPolicy\.vote\(context, root, pageParams\)/, 'Opaque page window must retain the max-refresh/hardware policy');
assert.match(direct, /view\.capturePixels\(\)/, 'Transition-only Gecko capture path must remain available');

assert.ok(!/TextureView/.test(direct.replace(/no ImageReader\/AImage relay, Bubble native consumer, TextureView/, '')), 'Do not introduce a TextureView renderer');
assert.ok(!/ImageReader\s*\(/.test(direct), 'Steady-state direct path must not allocate ImageReader');
assert.ok(!/while\s*\(/.test(direct), 'Direct page host must not add a polling loop');
assert.ok(!/Timer\s*\(/.test(direct), 'Direct page host must not add a timer loop');

console.log('Bubble 156 opaque floating page guards passed: independent OPAQUE overlay, direct Gecko SurfaceView retained, event-driven geometry, embedded fallback preserved.');
