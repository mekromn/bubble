'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const read = p => fs.readFileSync(p, 'utf8');

const picker = read('app/src/main/java/com/mekromn/bubble/ArchivePickerActivity.kt');
const floating = read('app/src/main/java/com/mekromn/bubble/FloatingFileActivity.kt');
const manifest = read('app/src/main/AndroidManifest.xml');

assert.match(picker, /Intent\(Intent\.ACTION_OPEN_DOCUMENT\)/,
  'Select and Compress must delegate selection to Android DocumentsUI');
assert.match(picker, /Intent\.EXTRA_ALLOW_MULTIPLE, true/,
  'The stock picker must retain multi-file selection');
assert.match(picker, /buildCompressionUi\(null\)/,
  'Bubble compression UI must appear only after DocumentsUI returns selected URIs');
assert.match(picker, /ArchiveCompression\.values\(\)/,
  'Existing compression-level choices must remain available');
assert.match(picker, /text = "Paths"/);
assert.match(picker, /text = "Archive 1"/);
assert.match(picker, /Save a copy to Downloads\/Bubble/);
assert.match(picker, /"Compress & Attach"/);
assert.match(picker, /providerRelativePath/,
  'Paths mode should preserve trustworthy external-storage provider paths without raw filesystem browsing');

for (const forbidden of [
  'RecyclerView', 'GridLayoutManager', 'LinearLayoutManager',
  'requestAllFilesAccess', 'isExternalStorageManager', 'ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION',
  'Providers…', 'Local access', 'currentDir', 'selectedLocal'
]) {
  assert.equal(picker.includes(forbidden), false, `Custom file-browser residue must be removed: ${forbidden}`);
}

assert.match(floating, /Opening Android Files…/,
  'The browser prompt handoff should describe the actual system picker');
assert.match(manifest, /android:label="Select and Compress"/);
assert.equal(/MANAGE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE/.test(manifest), false,
  'The stock-picker flow must not require broad storage permissions');

console.log('Bubble 147 stock-document-picker-first compression architecture passed.');
