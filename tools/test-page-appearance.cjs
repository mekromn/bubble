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
assert.match(source, /requestedMode === 'amoled' \? 'bubble-force-amoled'/,
  'AMOLED selection must install its distinct class');

console.log('Per-tab AMOLED black appearance mode guard passed.');
