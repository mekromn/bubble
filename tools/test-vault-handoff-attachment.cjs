const fs = require('node:fs');
const assert = require('node:assert/strict');

const page = fs.readFileSync('app/src/main/assets/chat-monitor/vault.js', 'utf8');
const command = fs.readFileSync('app/src/main/assets/chat-monitor/vault-transcript-command.js', 'utf8');
const bridge = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVaultBridge.kt', 'utf8');
const exporter = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatTranscriptExport.kt', 'utf8');
const vaultUi = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVaultUi.kt', 'utf8');
const readiness = fs.readFileSync('app/src/main/java/com/mekromn/bubble/TabReadiness.kt', 'utf8');

assert.match(exporter, /fun beginById\(/,
  'Fresh-chat continuity must export the exact staged Vault chat, not infer it from the new blank URL');
assert.match(bridge, /"vault-pending-transcript-begin"/,
  'Fresh ChatGPT tabs must be able to request the staged transcript attachment');
assert.match(bridge, /pending\?\.optString\("sourceId"\) != sourceId/,
  'Attachment export must remain bound to the exact profile-scoped pending source chat');
assert.match(bridge, /ChatTranscriptExports\.beginById\(context, vault, sourceId, profileId\)/,
  'Native bridge must export the staged source by ID');

for (const [name, script] of [['new-chat handoff', page], ['agent transcript command', command]]) {
  assert.match(script, /new DataTransfer\(\)/,
    `${name} must attach the transcript through ChatGPT’s live upload machinery`);
  assert.match(script, /document\.querySelector\('input#upload-files'\)/,
    `${name} must prefer ChatGPT’s generic upload-files input rather than the first arbitrary file input`);
  assert.match(script, /function isGeneralFileInput\(/,
    `${name} must filter file inputs explicitly`);
  assert.match(script, /upload-\(\?:photos\|camera\)/,
    `${name} must explicitly exclude photo and camera inputs from Markdown attachment`);
  assert.match(script, /function injectByDrop\(/,
    `${name} must retain bounded composer drag/drop fallback`);
  assert.match(script, /new DragEvent\(/,
    `${name} drag/drop fallback must carry the actual File object`);
  assert.match(script, /function injectByPaste\(/,
    `${name} must retain composer paste fallback`);
  assert.match(script, /new ClipboardEvent\('paste'/,
    `${name} paste fallback must carry the File object`);
}

assert.match(page, /event: 'vault-pending-transcript-begin'/,
  'Fresh page must request the staged transcript file');
assert.match(page, /new File\(/,
  'Fresh page must reconstruct a real Markdown File');
assert.equal(page.includes('20_000'), false,
  'Fresh handoff must not stall for 20 seconds waiting for an arbitrary file input');
assert.equal(page.includes('45_000'), false,
  'Fresh handoff must not stall for 45 seconds waiting for an attachment chip');
assert.match(page, /attachmentPrompt\(file\.name\)/,
  'A concise continuity instruction must accompany the full attachment');
assert.match(page, /event: 'vault-transcript-complete'/,
  'Native temporary export must be released after successful attachment');
const attachmentAt = page.indexOf("event: 'vault-pending-transcript-begin'");
const fallbackAt = page.indexOf("typeof response.text !== 'string'");
assert.ok(attachmentAt >= 0 && fallbackAt > attachmentAt,
  'Complete transcript attachment must be primary; size-limited text may only be fallback');
assert.equal(/requestSubmit|\.click\s*\(/.test(page), false,
  'New-chat handoff may prepare the composer but must not auto-send or synthesize a click');

assert.equal(command.includes('15_000;\n    while (!input'), false,
  'Agent transcript upload must not wait on an arbitrary first file input');
assert.match(command, /form\.requestSubmit\(button\)/,
  'Exact agent transcript command should still auto-submit after the generic file attachment is ready');

assert.equal(/PREVIEW_MESSAGES|takeLast\s*\(|omitted from this preview/.test(vaultUi), false,
  'Vault saved-chat viewer must not hide older messages behind an arbitrary preview cutoff');
assert.match(vaultUi, /chat\.messages\.forEachIndexed/,
  'Vault saved-chat viewer must render the complete stored message list');
assert.equal(readiness.includes('eligible for 15-minute hibernation'), false,
  'Resident ChatGPT tabs must not advertise the removed 15-minute auto-hibernation policy');
assert.match(readiness, /ChatGPT idle · kept resident in background/);

console.log('Generic-file staged-chat and agent transcript attachment guards passed.');
