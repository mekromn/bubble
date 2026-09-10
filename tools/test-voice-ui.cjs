const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const source = fs.readFileSync('app/src/main/assets/chat-monitor/voice-ui.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));

assert.doesNotThrow(() => new vm.Script(source, {filename: 'voice-ui.js'}), 'Google Voice page enhancement must remain valid JavaScript');
const script = manifest.content_scripts.find(item => item.js.includes('voice-ui.js'));
assert.ok(script, 'Google Voice copy-number script must be registered');
assert.deepEqual(script.matches, ['https://voice.google.com/*'], 'Voice UI enhancement must stay exact Google Voice origin scoped');
assert.equal(script.all_frames, false, 'Voice UI enhancement must stay in the top document');
assert.equal(source.includes("window !== window.top || location.origin !== 'https://voice.google.com'"), true,
  'Runtime must independently enforce exact Voice origin and top-frame scope');

assert.match(source, /bubble-voice-copy-number/, 'A stable one-copy-control id is required');
assert.match(source, /actionRow\.insertBefore\(button, found\.call\)/,
  'Copy number control must sit immediately before the existing call control');
assert.match(source, /navigator\.clipboard\.writeText\(phone\)/,
  'One tap must use the browser clipboard API when available');
assert.match(source, /document\.execCommand\('copy'\)/,
  'Gecko-compatible local fallback must remain available');
assert.match(source, /aria-label', 'Copy phone number'/,
  'The new control must be accessible');
assert.match(source, /digits\.length >= 7 && digits\.length <= 15/,
  'Only plausible phone-number candidates may be copied');
assert.equal(/fetch\s*\(|XMLHttpRequest|sendNativeMessage|localStorage|sessionStorage/.test(source), false,
  'Copy-number page UI must remain local-only with no network/native/storage side channel');
assert.equal(/\.click\s*\(/.test(source), false,
  'Voice UI enhancement must not synthesize clicks on Google Voice controls');

console.log('Exact-origin Google Voice one-tap copy-number UI guard passed.');
