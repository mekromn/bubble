const fs = require('node:fs');
const assert = require('node:assert/strict');
const hookSource = fs.readFileSync('app/src/main/assets/chat-monitor/download-hook.js', 'utf8');
const bridgeSource = fs.readFileSync('app/src/main/assets/chat-monitor/download-bridge.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));

const hook = manifest.content_scripts.find(item => item.js.includes('download-hook.js'));
assert.ok(hook, 'Blob main-world hook must be registered');
assert.deepEqual(hook.matches, ['http://*/*', 'https://*/*']);
assert.equal(hook.all_frames, false, 'Generated download ownership starts with the top document only');
assert.equal(hook.world, 'MAIN', 'Programmatic page anchor activation must be observed in the page world');
assert.equal(hook.run_at, 'document_start');

const bridge = manifest.content_scripts.find(item => item.js.includes('download-bridge.js'));
assert.ok(bridge, 'Blob isolated native bridge must be registered');
assert.deepEqual(bridge.matches, ['http://*/*', 'https://*/*']);
assert.equal(bridge.all_frames, false, 'Native relay remains top-document only');
assert.notEqual(bridge.world, 'MAIN', 'Native messaging must remain in the isolated extension world');
assert.equal(bridge.run_at, 'document_start');

assert.match(hookSource, /startsWith\('blob:'\)/, 'Only Blob links are intercepted');
assert.match(hookSource, /preventDefault\(\)/, 'The Gecko default Blob activation is owned exactly once');
assert.match(hookSource, /CustomEvent/, 'Main-world hook relays local metadata to the isolated extension world');
assert.match(bridgeSource, /event:\s*'blob-download'/, 'Native event name is stable');
assert.match(bridgeSource, /sendNativeMessage/, 'Only the isolated bridge talks to native Gecko');
assert.match(bridgeSource, /filename/, 'Suggested filename metadata is forwarded');
for (const [label, source] of [['hook', hookSource], ['bridge', bridgeSource]]) {
  for (const forbidden of ['FileReader', 'arrayBuffer(', '.text()', 'document.cookie', 'localStorage', 'sessionStorage']) {
    assert.equal(source.includes(forbidden), false, `Blob bytes/page data must not cross ${label}: ${forbidden}`);
  }
}
console.log('Blob download ownership is top-frame, Blob-only and metadata-only; bytes stay in Gecko.');
