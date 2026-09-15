'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const read = p => fs.readFileSync(p, 'utf8');

const picker = read('app/src/main/java/com/mekromn/bubble/ArchivePickerActivity.kt');
const floating = read('app/src/main/java/com/mekromn/bubble/FloatingFileActivity.kt');
const gradle = read('app/build.gradle.kts');

const version = Number((gradle.match(/versionCode = (\d+)/) || [])[1]);
assert.ok(Number.isFinite(version) && version >= 148,
  `Direct Attach requires Bubble versionCode >= 148, got ${version}`);
assert.match(picker, /button\("Attach", "Attach original files without compression"\)/,
  'The post-selection dialog must expose the third Attach button');
assert.match(picker, /const val RESULT_LOCAL_PATHS = "bubble\.archive\.local\.paths"/,
  'Direct Attach must be able to return multiple originals');

const direct = picker.split('private fun attachWithoutCompression()')[1]
  .split('private fun shareableUri')[0];
assert.ok(direct, 'Direct Attach implementation missing');
assert.match(direct, /UploadStaging\.prepare\(this, tabId, requestId, originals, job\)/,
  'Internal Gecko attachment must stage selected provider bytes verbatim');
assert.match(direct, /RESULT_LOCAL_PATHS/);
for (const forbidden of ['ArchiveEngine.createZip', 'ArchiveCompression', 'archiveOutput(', 'Deflater']) {
  assert.equal(direct.includes(forbidden), false, `Direct Attach must not enter compression/archive path: ${forbidden}`);
}

assert.match(picker, /ArchiveEngine\.createZip\(/,
  'The existing Compress & Attach path must remain intact');
assert.match(floating, /getStringArrayListExtra\(ArchivePickerActivity\.RESULT_LOCAL_PATHS\)/,
  'Floating file prompt must accept all direct-attach originals');
assert.match(floating, /finishRequest\(request, files\.map\(Uri::fromFile\), null\)/,
  'All staged originals must be confirmed back to the original Gecko FilePrompt');

console.log(`Bubble ${version} direct Attach path: original multi-file bytes, zero compression, existing archive path retained.`);
