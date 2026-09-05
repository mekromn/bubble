const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/chat-monitor/foreground.js', 'utf8');
function environment(origin) {
  const handlers = {};
  const document = {hidden: true, visibilityState: 'hidden', hasFocus: () => false};
  const window = {addEventListener(type, callback) {handlers[type] = callback;}};
  window.top = window;
  vm.runInNewContext(source, {window, document, location: {origin}, Object});
  return {window, document, handlers};
}
const chat = environment('https://chatgpt.com');
assert.equal(chat.document.hidden, false);
assert.equal(chat.document.visibilityState, 'visible');
assert.equal(chat.document.hasFocus(), true);
let suppressed = false;
chat.handlers.blur({target: {}, stopImmediatePropagation() {suppressed = true;}});
assert.equal(suppressed, false);
chat.handlers.blur({target: chat.window, stopImmediatePropagation() {suppressed = true;}});
assert.equal(suppressed, true);
for (const origin of ['http://chatgpt.com', 'https://chatgpt.com.evil.test', 'https://accounts.google.com']) {
  const other = environment(origin);
  assert.equal(other.document.hidden, true);
  assert.equal(other.document.hasFocus(), false);
  assert.deepEqual(other.handlers, {});
}
console.log('Foreground shim: exact origin, visibility/focus and element-event isolation passed.');
require('./test-monitor.cjs');
