const fs = require('node:fs');
const assert = require('node:assert/strict');
const hookSource = fs.readFileSync('app/src/main/assets/chat-monitor/download-hook.js', 'utf8');
const bridgeSource = fs.readFileSync('app/src/main/assets/chat-monitor/download-bridge.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));

const hook = manifest.content_scripts.find(item => item.js.includes('download-hook.js'));
assert.ok(hook, 'Download main-world hook must be registered');
assert.deepEqual(hook.matches, ['http://*/*', 'https://*/*']);
assert.equal(hook.all_frames, false, 'Download ownership starts with the top document only');
assert.equal(hook.world, 'MAIN', 'Page activation must be observed in the page world');
assert.equal(hook.run_at, 'document_start');

const bridge = manifest.content_scripts.find(item => item.js.includes('download-bridge.js'));
assert.ok(bridge, 'Blob isolated native bridge must be registered');
assert.deepEqual(bridge.matches, ['http://*/*', 'https://*/*']);
assert.equal(bridge.all_frames, false, 'Native relay remains top-document only');
assert.notEqual(bridge.world, 'MAIN', 'Native messaging must remain in the isolated extension world');
assert.equal(bridge.run_at, 'document_start');

assert.match(hookSource, /startsWith\('blob:'\)/, 'Blob downloads must still be owned by the existing bridge');
assert.match(hookSource, /preventDefault\(\)/, 'Owned download activations must prevent duplicate Gecko handling');
assert.match(hookSource, /CustomEvent/, 'Main-world Blob hook relays local metadata to the isolated extension world');
assert.match(bridgeSource, /event:\s*'blob-download'/, 'Native Blob event name is stable');
assert.match(bridgeSource, /sendNativeMessage/, 'Only the isolated bridge talks to native Gecko');
assert.match(bridgeSource, /filename/, 'Suggested filename metadata is forwarded');

// GitHub release assets linked with target=_blank are downloads, not useful tabs. Intercept only a
// genuine user activation and exact GitHub download endpoints; ordinary GitHub navigation must not
// be broadened into this path.
assert.match(hookSource, /event\.isTrusted/, 'Same-tab GitHub reroute must require a genuine user click');
assert.match(hookSource, /anchor\.target\.toLowerCase\(\) === '_blank'/, 'Only _blank download links need popup suppression');
assert.match(hookSource, /host !== 'github\.com'/, 'github.com matching must stay exact');
assert.match(hookSource, /\/releases\/download\//, 'GitHub release asset paths must be recognized');
assert.match(hookSource, /release-assets\.githubusercontent\.com/, 'Redirected GitHub release asset host must be recognized');
assert.match(hookSource, /location\.assign\(uri\)/, 'Download-only popup activation must stay in the opener session');
assert.equal(hookSource.includes('raw.githubusercontent.com'), false, 'Raw source links must keep normal browser navigation');
assert.equal(hookSource.includes('github.com.evil'), false, 'No lookalike host exception may be introduced');

for (const [label, source] of [['hook', hookSource], ['bridge', bridgeSource]]) {
  for (const forbidden of ['FileReader', 'arrayBuffer(', '.text()', 'document.cookie', 'localStorage', 'sessionStorage']) {
    assert.equal(source.includes(forbidden), false, `Blob bytes/page data must not cross ${label}: ${forbidden}`);
  }
}
console.log('Download ownership is top-frame and metadata-only; GitHub release assets stay in the opener without blank tabs.');
