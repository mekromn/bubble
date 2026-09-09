const fs = require('node:fs');
const assert = require('node:assert/strict');

const page = fs.readFileSync('app/src/main/assets/chat-monitor/vault.js', 'utf8');
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

assert.match(page, /event: 'vault-pending-transcript-begin'/,
  'Fresh page must request the staged transcript file');
assert.match(page, /new File\(/,
  'Fresh page must reconstruct a real Markdown File');
assert.match(page, /new DataTransfer\(\)/,
  'Fresh page must attach the transcript through ChatGPT’s real file input');
assert.match(page, /input\[type="file"\]/,
  'Fresh page must use the live ChatGPT upload control');
assert.match(page, /attachmentPrompt\(file\.name\)/,
  'A concise continuity instruction must accompany the full attachment');
assert.match(page, /event: 'vault-transcript-complete'/,
  'Native temporary export must be released after successful attachment');
const attachmentAt = page.indexOf("event: 'vault-pending-transcript-begin'");
const fallbackAt = page.indexOf("typeof response.text !== 'string'");
assert.ok(attachmentAt >= 0 && fallbackAt > attachmentAt,
  'Complete transcript attachment must be primary; size-limited text may only be fallback');
assert.equal(/requestSubmit|\.click\s*\(/.test(page), false,
  'New-chat handoff may prepare the composer but must not auto-send it');

assert.equal(/PREVIEW_MESSAGES|takeLast\s*\(|omitted from this preview/.test(vaultUi), false,
  'Vault saved-chat viewer must not hide older messages behind an arbitrary preview cutoff');
assert.match(vaultUi, /chat\.messages\.forEachIndexed/,
  'Vault saved-chat viewer must render the complete stored message list');
assert.equal(readiness.includes('eligible for 15-minute hibernation'), false,
  'Resident ChatGPT tabs must not advertise the removed 15-minute auto-hibernation policy');
assert.match(readiness, /ChatGPT idle · kept resident in background/);

console.log('Full staged-chat Markdown attachment, complete Vault viewer, and resident idle-status guards passed.');
