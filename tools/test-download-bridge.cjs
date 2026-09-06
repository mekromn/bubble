const fs = require('node:fs');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/chat-monitor/download-bridge.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));

const bridge = manifest.content_scripts.find(item => item.js.includes('download-bridge.js'));
assert.ok(bridge, 'Blob download bridge must be registered');
assert.deepEqual(bridge.matches, ['http://*/*', 'https://*/*']);
assert.equal(bridge.all_frames, false, 'Generated download ownership starts with the top document only');
assert.notEqual(bridge.world, 'MAIN', 'Native messaging must remain in the isolated extension world');
assert.equal(bridge.run_at, 'document_start');

assert.match(source, /startsWith\('blob:'\)/, 'Only Blob links are intercepted');
assert.match(source, /event:\s*'blob-download'/, 'Native event name is stable');
assert.match(source, /filename/, 'Suggested filename metadata is forwarded');
for (const forbidden of ['FileReader', 'arrayBuffer(', '.text()', 'document.cookie', 'localStorage', 'sessionStorage']) {
  assert.equal(source.includes(forbidden), false, `Blob bytes/page data must not cross messaging: ${forbidden}`);
}
console.log('Blob download bridge is top-frame, Blob-only and metadata-only; bytes stay in Gecko.');
