const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const copy = fs.readFileSync('app/src/main/assets/chat-monitor/voice-copy.js', 'utf8');
const dark = fs.readFileSync('app/src/main/assets/chat-monitor/voice-dark.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');

assert.doesNotThrow(() => new vm.Script(copy, {filename: 'voice-copy.js'}), 'Google Voice copy helper must remain valid JavaScript');
assert.doesNotThrow(() => new vm.Script(dark, {filename: 'voice-dark.js'}), 'Google Voice dark cleanup must remain valid JavaScript');
const script = manifest.content_scripts.find(item => item.js.includes('voice-copy.js'));
assert.ok(script, 'Google Voice copy helper must be registered');
assert.deepEqual(script.matches, ['https://voice.google.com/*'], 'Voice helpers must stay exact Google Voice origin scoped');
assert.equal(script.all_frames, false, 'Voice helpers must stay in the top document');
assert.deepEqual(script.js, ['voice-copy.js', 'voice-dark.js'],
  'Voice runtime must contain only the simple copy helper and dark cleanup; no notification page router');
assert.equal(copy.includes("window !== window.top || location.origin !== 'https://voice.google.com'"), true,
  'Copy helper must independently enforce exact Voice origin and top-frame scope');
assert.equal(dark.includes("window !== window.top || location.origin !== 'https://voice.google.com'"), true,
  'Voice dark cleanup must independently enforce exact Voice origin and top-frame scope');

assert.match(workspace, /ensureBuiltIn\("resource:\/\/android\/assets\/chat-monitor\/", "chat-monitor@bubble\.local"\)/,
  'Bubble must continue installing the packaged monitor through ensureBuiltIn');
assert.equal(manifest.version, '3.1',
  'Built-in extension version must advance so existing profiles drop the route experiments');

assert.match(copy, /bubble-voice-copy-number/, 'A stable one-copy-control id is required');
assert.match(copy, /function activeHeaderPhone\(\)/,
  'Copy placement must derive directly from a visible phone-number-only element in the top header band');
assert.match(copy, /!\/\^\[\+\\d\\s\(\)\.\-\]\+\$\/u\.test\(text\)/,
  'Only phone-number-only visible text may become a copy target');
assert.match(copy, /found\.node\.append\(makeButton\(found\.phone\)\)/,
  'Copy button must be inline immediately after the exact visible number element');
assert.match(copy, /const found = activeHeaderPhone\(\)/,
  'Copy action must re-resolve the visible header number instead of trusting stale cached data');
assert.match(copy, /navigator\.clipboard\.writeText\(found\.phone\.display\)/,
  'One tap must copy exactly the currently visible header number');
assert.equal(/function\s+(?:callButton|callMetadata|unreadRows|routeNewest|selectedThread|deepPhone)\b/.test(copy), false,
  'Simple copy helper must not implement call-control or conversation-routing helpers');

assert.match(dark, /bubble-voice-bright-control/,
  'Voice dark cleanup must identify isolated bright Material controls');
assert.match(dark, /bubble-force-amoled/,
  'Voice cleanup must recognize AMOLED mode');
assert.equal(/voice-route\.js|voice-ui\.js/.test(JSON.stringify(script.js)), false,
  'Retired Voice route/UI scripts must not execute');
assert.equal(/fetch\s*\(|XMLHttpRequest|sendNativeMessage|localStorage|sessionStorage/.test(copy + dark), false,
  'Voice copy and dark-cleanup scripts must remain local-only');

console.log('Google Voice: simple visible-header copy helper + dark/AMOLED cleanup; no automatic conversation routing.');
