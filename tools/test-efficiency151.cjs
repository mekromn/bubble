'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const read = p => fs.readFileSync(p, 'utf8');

const gradle = read('app/build.gradle.kts');
const list = read('app/src/main/java/com/mekromn/bubble/ConversationList.kt');
const geometry = read('app/src/main/java/com/mekromn/bubble/FloatingPanelGeometry.kt');
const service = read('app/src/main/java/com/mekromn/bubble/BubbleService.kt');
const tray = read('app/src/main/java/com/mekromn/bubble/TabTray.kt');
const meter = read('app/src/main/java/com/mekromn/bubble/FrameMeter.kt');
const audit = read('docs/EFFICIENCY_AUDIT.md');

const version = Number((gradle.match(/versionCode\s*=\s*(\d+)/) || [])[1]);
assert.ok(version >= 151, 'Build 151+ must retain Phase-B efficiency invariants');

assert.match(list, /private val rowCache = HashMap<String, Row>\(\)/,
  'chooser must retain stable per-tab row models');
assert.equal(/workspace\.tabs\.sortedByDescending\s*\{/.test(list), false,
  'chooser should not allocate/sort a fresh tab list on each refresh');
assert.match(list, /for \(tab in workspace\.tabs\) if \(tab\.pinned\) append\(tab\)/);
assert.match(list, /rows\.indices\.all \{ rows\[it\] === next\[it\] \}/,
  'chooser should skip DiffUtil when exact row identities/order are unchanged');
assert.match(list, /private fun opaqueOver/);
assert.match(list, /VisualEffects\.transparencyEnabled\(\)/,
  'opaque mode must precompose persistent chooser row colors');

assert.match(geometry, /private val cache = HashMap<FloatingMode, FloatingPanelState>\(\)/);
assert.match(geometry, /private var preferences: SharedPreferences\? = null/);
assert.match(geometry, /cache\[mode\]\?\.let \{ return it \}/,
  'panel geometry reads must hit process cache before preferences');

assert.match(service, /NOTIFICATION_DEBOUNCE_MS = 250L/);
assert.match(service, /scheduleNotificationUpdate\(\)/);
assert.match(service, /main\.postDelayed\(notificationTask, NOTIFICATION_DEBOUNCE_MS\)/,
  'non-urgent foreground summary work must be debounced');
assert.match(service, /updateNotification\(force = true\)/,
  'mode/park/access transitions must retain an immediate path');

assert.match(tray, /format = if \(transparent\) PixelFormat\.TRANSLUCENT else PixelFormat\.OPAQUE/,
  'opaque-mode fullscreen chooser must advertise opaque composition');
assert.match(tray, /win\.setBackgroundBlurRadius\(0\)/,
  'opaque-mode fullscreen chooser must turn compositor blur off');
assert.match(tray, /if \(!VisualEffects\.transparencyEnabled\(\).*blurListener != null\) return/,
  'blur listener must not be registered while transparency is disabled');

assert.match(meter, /Process\.getElapsedCpuTime\(\)/);
assert.match(meter, /Debug\.getPss\(\)/);
assert.match(meter, /art\.gc\.bytes-allocated/);
assert.match(meter, /currentThermalStatus/);
assert.match(meter, /BATTERY_PROPERTY_CURRENT_NOW/);
assert.match(meter, /if \(thread != null\) return/,
  'efficiency meter must remain explicit/user-triggered rather than an idle loop');

assert.match(audit, /Build 151 — Phase B/);
assert.match(audit, /Never lower Gecko\/WebRender page resolution/);

// Phase B must remove redundant work, not page fidelity or rendering capability.
const production = [list, geometry, service, tray, meter].join('\n');
for (const forbidden of ['setResolution(', 'setFrameRate(60', 'setImageQuality', 'setBitDepth', 'TextureView(']) {
  assert.equal(production.includes(forbidden), false,
    `Phase B must not introduce a quality-reducing shortcut: ${forbidden}`);
}

console.log('Bubble 151+ Phase-B efficiency guards passed: chooser reuse/opaque composition, cached geometry, debounced service summary, dormant local ledger.');
