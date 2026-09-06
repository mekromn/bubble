const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/chat-monitor/foreground.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));
function environment(protocol, frame = false, hostname = 'example.com', port = '') {
  const handlers = {};
  const activation = {isActive: false, hasBeenActive: false};
  const document = {hidden: true, visibilityState: 'hidden', hasFocus: () => false, webkitHidden: true};
  const window = {addEventListener(type, callback) {handlers[type] = callback;}};
  window.top = frame ? {} : window;
  vm.runInNewContext(source, {window, document, location: {protocol, hostname, port}, navigator: {userActivation: activation}, Object});
  return {window, document, handlers, activation};
}
for (const protocol of ['https:', 'http:', 'about:', 'blob:', 'data:']) for (const frame of [false, true]) {
  const page = environment(protocol, frame);
  assert.equal(page.document.hidden, false);
  assert.equal(page.document.visibilityState, 'visible');
  assert.equal(page.document.hasFocus(), true);
  assert.equal(page.document.webkitHidden, false);
  assert.equal(page.activation.isActive, false, 'User gesture security must remain real');
  let suppressed = false;
  page.handlers.blur({target: {}, stopImmediatePropagation() {suppressed = true;}});
  assert.equal(suppressed, false, 'Element blur must be preserved');
  page.handlers.blur({target: page.window, stopImmediatePropagation() {suppressed = true;}});
  assert.equal(suppressed, true);
  assert.equal(page.handlers.pagehide, undefined, 'Page cleanup must remain real');
  assert.equal(page.handlers.unload, undefined);
}
for (const protocol of ['file:', 'chrome:', 'resource:', 'moz-extension:']) {
  const other = environment(protocol);
  assert.equal(other.document.hidden, true); assert.equal(other.document.hasFocus(), false); assert.deepEqual(other.handlers, {});
}
// Google Voice must see genuine background visibility/focus. Its page notification logic can
// suppress alerts when a browser lies that the document is perpetually visible. Bubble keeps Voice
// alive natively through GeckoSession active/high-priority state instead of this JS shim.
for (const frame of [false, true]) {
  const voice = environment('https:', frame, 'voice.google.com', '');
  assert.equal(voice.document.hidden, true, 'Google Voice hidden state must stay real');
  assert.equal(voice.document.visibilityState, 'hidden', 'Google Voice visibility state must stay real');
  assert.equal(voice.document.hasFocus(), false, 'Google Voice focus state must stay real');
  assert.deepEqual(voice.handlers, {}, 'Google Voice visibility/blur events must not be intercepted');
}
const voice443 = environment('https:', false, 'voice.google.com', '443');
assert.equal(voice443.document.hidden, true);
const voiceHttp = environment('http:', false, 'voice.google.com', '');
assert.equal(voiceHttp.document.hidden, false, 'Only exact HTTPS Google Voice is exempt');
const voiceLookalike = environment('https:', false, 'voice.google.com.evil.example', '');
assert.equal(voiceLookalike.document.hidden, false, 'Lookalike hosts must not receive Voice policy');

const foreground = manifest.content_scripts.find(item => item.js.includes('foreground.js'));
assert.deepEqual(foreground.matches, ['http://*/*', 'https://*/*']);
assert.equal(foreground.all_frames, true); assert.equal(foreground.world, 'MAIN'); assert.equal(foreground.run_at, 'document_start');
const monitor = manifest.content_scripts.find(item => item.js.includes('monitor.js'));
assert.deepEqual(monitor.matches, ['https://chatgpt.com/*'], 'Reply lifecycle native monitor must not be broadened');
assert.equal(monitor.all_frames, false); assert.notEqual(monitor.world, 'MAIN');

// Android's blur-behind API is screen-wide by definition and produced visible blur outside the
// Bubble window on the target Pixel. The glass implementation must use shape-clipped Window
// background blur instead. Keep this source guard so the bad API cannot quietly return later.
const glass = fs.readFileSync('app/src/main/java/com/mekromn/bubble/OverlayGlass.kt', 'utf8');
assert.equal(/\.setBlurBehindRadius\s*\(/.test(glass), false, 'Screen-wide blur-behind API is forbidden for Bubble glass');
assert.equal(/WindowManager\.LayoutParams\.FLAG_BLUR_BEHIND/.test(glass), false, 'FLAG_BLUR_BEHIND is forbidden for Bubble glass');
assert.equal(/\.setBackgroundBlurRadius\s*\(/.test(glass), true, 'Bubble glass must use shape-clipped Window background blur');
console.log('All-web foreground compatibility, frame scope, real gesture boundaries and ChatGPT-only reply monitor passed.');
console.log('Google Voice receives real visibility/focus so background notification decisions are not suppressed.');
console.log('Glass guard: shape-clipped Window background blur only; screen-wide blur-behind is forbidden.');
require('./test-download-bridge.cjs');
require('./test-monitor.cjs');
