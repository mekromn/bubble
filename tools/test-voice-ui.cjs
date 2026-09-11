const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const source = fs.readFileSync('app/src/main/assets/chat-monitor/voice-ui.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');

assert.doesNotThrow(() => new vm.Script(source, {filename: 'voice-ui.js'}), 'Google Voice page enhancement must remain valid JavaScript');
const script = manifest.content_scripts.find(item => item.js.includes('voice-ui.js'));
assert.ok(script, 'Google Voice page enhancement must be registered');
assert.deepEqual(script.matches, ['https://voice.google.com/*'], 'Voice UI enhancement must stay exact Google Voice origin scoped');
assert.equal(script.all_frames, false, 'Voice UI enhancement must stay in the top document');
assert.equal(source.includes("window !== window.top || location.origin !== 'https://voice.google.com'"), true,
  'Runtime must independently enforce exact Voice origin and top-frame scope');

// ensureBuiltIn does not replace an installed package with the same version. Every shipped Voice UI
// change therefore needs a real built-in extension version bump so existing Bubble profiles receive it.
assert.match(workspace, /ensureBuiltIn\("resource:\/\/android\/assets\/chat-monitor\/", "chat-monitor@bubble\.local"\)/,
  'Bubble must continue installing the packaged monitor through ensureBuiltIn');
assert.equal(manifest.version, '2.8',
  'Built-in extension version must advance so existing Bubble profiles receive the exact-header copy fix');
assert.match(source, /SCRIPT_VERSION = '2\.8'/,
  'Voice UI execution marker must match the packaged extension generation');

assert.match(source, /bubble-voice-copy-number/, 'A stable one-copy-control id is required');
assert.match(source, /function headerCallControl\(\)/,
  'Copy placement must be anchored from the active conversation call control');
assert.match(source, /function visibleHeaderPhone\(call\)/,
  'A compact visible number on the same header row as the call control must be preferred');
assert.match(source, /function callMetadataPhones\(call\)/,
  'Name-only headers may use only call/header metadata as the number fallback');
assert.match(source, /if \(metadata\.length && metadata\.some\(item => item\.digits !== visiblePhone\.phone\.digits\)\) return null/,
  'Visible header number and call metadata disagreement must fail closed instead of copying the wrong number');
assert.match(source, /info\.phoneNode\.append\(button\)/,
  'When the number is visibly rendered, the copy button must be inline immediately after that exact number element');
assert.match(source, /const current = resolveHeaderNumber\(\)/,
  'Copy action must re-resolve the active header on every tap so stale conversation numbers cannot be copied');
assert.equal(/selectedThreadRoots|phoneForHeader|deepPhone/.test(source), false,
  'Copy-number resolution must not scan selected rows, message history, or broad descendant text for arbitrary numbers');
assert.match(source, /navigator\.clipboard\.writeText\(current\.phone\.display\)/,
  'One tap must copy the freshly-verified active-header number');
assert.match(source, /document\.execCommand\('copy'\)/,
  'Gecko-compatible local clipboard fallback must remain available');
assert.match(source, /aria-label', 'Copy phone number'/,
  'The copy control must remain accessible');
assert.match(source, /digits\.length >= 7 && digits\.length <= 15/,
  'Only plausible phone-number candidates may be copied');

assert.match(source, /function unreadRows\(\)/,
  'Voice notification return must retain a semantic unread-conversation discovery path');
assert.match(source, /\[aria-label\*="unread" i\]/,
  'Unread routing must prefer accessible semantics rather than Google minified class names');
assert.match(source, /function revealConversationList\(\)/,
  'Mobile one-pane Voice must be able to reveal the conversation list before choosing an unread thread');
assert.match(source, /if \(!selected\) target\.click\(\)/,
  'Fallback may click only the chosen unread conversation row when it is not already selected');
assert.match(source, /document\.addEventListener\('visibilitychange'/,
  'Unread fallback must run when the protected Voice client becomes foreground-visible');

assert.equal(/fetch\s*\(|XMLHttpRequest|sendNativeMessage|localStorage|sessionStorage/.test(source), false,
  'Voice UI fallback must remain local-only with no network/native/storage side channel');
assert.equal(/\.click\(\)[\s\S]{0,80}(?:call|dial|phone)/iu.test(source), false,
  'The fallback must never synthesize a Voice call/dial action');

console.log('Exact-origin Google Voice active-header copy control and local unread-thread fallback passed.');
