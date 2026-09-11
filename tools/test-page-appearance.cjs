const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const native = fs.readFileSync('app/src/main/java/com/mekromn/bubble/PageAppearance.kt', 'utf8');
const source = fs.readFileSync('app/src/main/assets/chat-monitor/appearance.js', 'utf8');

assert.doesNotThrow(() => new vm.Script(source, {filename: 'appearance.js'}),
  'Page appearance content script must remain valid JavaScript');

assert.match(native, /AMOLED\("amoled", "AMOLED black"\)/,
  'Native per-tab appearance enum must expose AMOLED black');
assert.match(native, /PageAppearanceMode\.entries\.forEach/,
  'Appearance panel must render every mode including AMOLED without a parallel hard-coded list');
assert.match(native, /QuickPanel\.open\([^\n]+, 380\)/,
  'Appearance panel must be tall enough for four modes');

assert.match(source, /new Set\(\['default', 'dark', 'amoled', 'light'\]\)/,
  'Content script must accept the AMOLED wire value');
assert.match(source, /bubble-force-amoled/,
  'AMOLED mode must have a distinct document class');
assert.match(source, /html\.bubble-force-amoled:not\(\.bubble-needs-invert\)[\s\S]*background: #000 !important/,
  'Native-dark AMOLED pages must use true black document/background surfaces');
assert.match(source, /html\.bubble-force-amoled \.\$\{SURFACE_CLASS\}[\s\S]*background-color: #000 !important/,
  'AMOLED mixed-theme surfaces must be true black');
assert.match(source, /requestedMode === 'dark' \|\| requestedMode === 'amoled'/,
  'AMOLED must share the dark-forcing/inversion behavior rather than becoming a cosmetic-only mode');
assert.match(source, /mode === 'amoled' \? 'bubble-force-amoled'/,
  'AMOLED selection must install its distinct class');

assert.match(source, /browser\.runtime\.connectNative\('bubbleAppearance'\)/,
  'Appearance startup must use the connection-based native path');
assert.match(source, /browser\.runtime\.sendNativeMessage\('bubbleAppearance'/,
  'Appearance startup must simultaneously keep the one-shot native path');
assert.match(source, /Use both Gecko native-messaging paths in parallel/,
  'Refresh races must be handled by redundant native handshakes rather than a delayed fallback only');
assert.match(source, /window\.addEventListener\('pageshow',[\s\S]*requestMode\(0, true\)/,
  'Page-show after refresh must re-verify the saved native appearance mode');
assert.match(source, /window\.addEventListener\('focus',[\s\S]*requestMode\(0, true\)/,
  'Foreground return must re-verify the saved native appearance mode');
assert.match(source, /document\.addEventListener\('visibilitychange',[\s\S]*requestMode\(0, true\)/,
  'Visibility restoration must re-verify the saved native appearance mode');
assert.match(source, /const reassert = \(\) =>/,
  'Appearance engine must be able to reassert a site-overwritten root theme class');
assert.equal(/if \(installed\) return;[\s\S]*sendNativeMessage/.test(source), false,
  'Installed state must never permanently suppress later native appearance verification');

console.log('Per-tab AMOLED black and refresh-stable appearance mode guard passed.');
