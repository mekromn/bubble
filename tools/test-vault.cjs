const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const source = fs.readFileSync('app/src/main/assets/chat-monitor/vault.js', 'utf8');
const historyHook = fs.readFileSync('app/src/main/assets/chat-monitor/vault-history-hook.js', 'utf8');
const historyBridge = fs.readFileSync('app/src/main/assets/chat-monitor/vault-history-bridge.js', 'utf8');
const composerFix = fs.readFileSync('app/src/main/assets/chat-monitor/vault-composer-fix.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));
const native = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVault.kt', 'utf8');
const bridge = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVaultBridge.kt', 'utf8');
const snapshotPolicy = fs.readFileSync('app/src/main/java/com/mekromn/bubble/VaultSnapshotPolicy.kt', 'utf8');
const vaultUi = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVaultUi.kt', 'utf8');
const appearance = fs.readFileSync('app/src/main/java/com/mekromn/bubble/PageAppearance.kt', 'utf8');

for (const [name, script] of Object.entries({source, historyHook, historyBridge, composerFix})) {
  assert.doesNotThrow(() => new vm.Script(script, {filename: name}), `${name} must remain valid JavaScript`);
}

const vaultScript = manifest.content_scripts.find(item => item.js.includes('vault.js'));
assert.ok(vaultScript, 'Vault content script must stay registered');
assert.deepEqual(vaultScript.matches, ['https://chatgpt.com/*'], 'Vault must stay exact ChatGPT-origin scoped');
assert.equal(vaultScript.all_frames, false, 'Vault may read only the top ChatGPT document');
assert.equal(source.includes("location.origin !== 'https://chatgpt.com'"), true, 'Runtime exact-origin guard is required');
assert.equal(source.includes('window !== window.top'), true, 'Runtime top-frame guard is required');

assert.match(source, /sendNativeMessage\('bubbleVault'/, 'Vault must use its dedicated native app namespace');
assert.match(bridge, /NATIVE_APP = "bubbleVault"/, 'Native bridge namespace must match the page bridge');
assert.match(bridge, /sender\.session !== session/, 'Native bridge must stay bound to the exact GeckoSession');
assert.match(bridge, /!sender\.isTopLevel/, 'Native bridge must reject child frames');
assert.match(bridge, /!Policy\.isChat\(sender\.url\)/, 'Native bridge must revalidate exact ChatGPT origin');
assert.match(appearance, /Workspace\.peek\(\)\?\.tabs\?\.firstOrNull \{ it\.id == tabId \}\?\.profileId/,
  'Vault profile identity must come from the durable native tab, not webpage data');
assert.match(bridge, /vault\.begin\(tabId, profileId, payload\)/,
  'Every saved snapshot must be bound to the owning Bubble profile');
assert.match(bridge, /vault\.stage\(it, profileId\)/,
  'Page-triggered staging must require the owning Bubble profile');
assert.match(bridge, /vault\.pendingResponse\(profileId\)/,
  'Pending continuity must be filtered by the requesting Bubble profile');
assert.match(native, /summary\.profileId == profileId/,
  'Native URL staging must not cross Bubble profile boundaries');
assert.match(native, /if \(summary\.profileId != profileId\) return null/,
  'Pending handoff delivery must reject another Bubble profile');

for (const forbidden of [
  /\bfetch\s*\(/,
  /XMLHttpRequest/,
  /document\.cookie/,
  /\bAuthorization\b/,
  /\bBearer\b/,
  /\.click\s*\(/,
]) {
  assert.equal(forbidden.test(source), false, `Vault source contains forbidden network/credential/synthetic-send behavior: ${forbidden}`);
}

assert.match(source, /const MAX_HANDOFF_CHARS = 70000/, 'Page bridge must preserve the extension 70k continuity budget');
assert.match(native, /MAX_HANDOFF_CHARS = 70_000/, 'Native handoff policy must preserve the extension 70k continuity budget');
assert.match(source, /const CHUNK_CHARS = 48000/, 'Long snapshots must remain chunked');
assert.match(source, /vault-snapshot-begin/);
assert.match(source, /vault-snapshot-chunk/);
assert.match(source, /vault-snapshot-end/);
assert.match(source, /HANDOFF_MARKER/);
assert.match(source, /setComposerText\(composer, 'go'\)/, 'Fresh standalone Go continuation should load lowercase go');
assert.equal(/send-button|submit-button|aria-label[^\n]*Send/iu.test(source), false, 'Vault must never auto-submit the composer');
assert.equal(native.includes('There is no cloud endpoint, credential capture, analytics, or'), true,
  'Native Vault must explicitly remain free of cloud/credential/analytics behavior');
assert.equal(native.includes('automatic pruning.'), true, 'Native Vault must explicitly retain saved chats until user deletion');
assert.match(native, /val first = chat\.messages\.take\(2\)/, 'Size-aware handoff must preserve the opening two messages');
assert.match(native, /for \(record in chat\.messages\.drop\(2\)\.asReversed\(\)\)/,
  'Size-aware handoff must scan newest remaining messages first');
assert.match(native, /tail\.addFirst\(record\)/, 'Newest retained context must be restored in chronological order');

// Long ChatGPT threads are virtualized. The MAIN-world observer may inspect only responses the page
// already fetched: it must not generate its own authenticated request, scroll the page, or capture
// credentials. The isolated bridge then forwards the full active chain into the same local Vault.
const historyMain = manifest.content_scripts.find(item => item.js.includes('vault-history-hook.js'));
const historyIsolated = manifest.content_scripts.find(item => item.js.includes('vault-history-bridge.js'));
assert.ok(historyMain && historyIsolated, 'Full-history observer and isolated bridge must stay registered');
assert.deepEqual(historyMain.matches, ['https://chatgpt.com/*']);
assert.equal(historyMain.world, 'MAIN'); assert.equal(historyMain.run_at, 'document_start'); assert.equal(historyMain.all_frames, false);
assert.deepEqual(historyIsolated.matches, ['https://chatgpt.com/*']);
assert.notEqual(historyIsolated.world, 'MAIN'); assert.equal(historyIsolated.all_frames, false);
assert.match(historyHook, /response\.clone\(\)/, 'History observer must inspect a clone without consuming ChatGPT response bodies');
assert.match(historyHook, /originalFetch\.apply\(this, args\)/, 'History observer must preserve the page fetch call exactly');
assert.match(historyHook, /xhrSend\.apply\(this, args\)/, 'History observer must preserve page XHR sends exactly');
assert.match(historyHook, /current_node|currentNode/, 'History observer must preserve the active branch when mapping data is available');
assert.equal(/\bfetch\s*\(/.test(historyHook), false, 'History observer must never initiate an extra fetch');
assert.equal(/new\s+XMLHttpRequest\s*\(/.test(historyHook), false, 'History observer must never initiate an extra XHR');
for (const forbidden of [/document\.cookie/, /\bAuthorization\b/, /\bBearer\b/, /sendNativeMessage/, /\.click\s*\(/, /scrollIntoView|scrollTo|scrollBy/]) {
  assert.equal(forbidden.test(historyHook), false, `MAIN-world history observer crossed its passive boundary: ${forbidden}`);
}
assert.match(historyBridge, /sendNativeMessage\('bubbleVault'/, 'Full history must cross only the dedicated local Vault namespace');
assert.match(historyBridge, /passive-full-history/, 'Full-history snapshots must be distinguishable in diagnostics');
assert.match(historyBridge, /\[0, 1200, 3500, 8000\]/, 'Initial full-history capture must replay across native index cold start without extra network');

// A short DOM tail after refresh must never erase a larger saved transcript, including during a
// cold-start race before the native index is loaded.
assert.match(snapshotPolicy, /vaultLoaded && incomingCount >= 1 && incomingCount >= savedCount\.coerceAtLeast\(0\)/,
  'Vault snapshot policy must be monotonic and index-load gated');
assert.match(bridge, /VaultSnapshotPolicy\.accepts\(vault\.loaded, savedCount, incomingCount\)/,
  'Native bridge must apply the monotonic policy before accepting snapshot chunks');
assert.match(bridge, /ignoredTransfers/, 'Rejected partial transfers must have all later chunks/end ignored');

// Continue-in-new-chat must stage first, then wait for the serialized handoff task before opening
// the tab. The page fallback supports ChatGPT textarea and contenteditable/ProseMirror variants.
const continueStart = vaultUi.indexOf('Continue in a new ChatGPT chat');
const stageAt = vaultUi.indexOf('vault.stage(id, chat.profileId)', continueStart);
const handoffAt = vaultUi.indexOf('vault.handoff(id)', continueStart);
const createAt = vaultUi.indexOf('workspace.create(Policy.HOME, chat.profileId)', continueStart);
assert.ok(continueStart >= 0 && stageAt > continueStart && handoffAt > stageAt && createAt > handoffAt,
  'Continuation must stage first and open the new tab only from the later handoff callback');
const composerScript = manifest.content_scripts.find(item => item.js.includes('vault-composer-fix.js'));
assert.ok(composerScript, 'Composer recovery helper must stay registered');
assert.deepEqual(composerScript.matches, ['https://chatgpt.com/*']); assert.equal(composerScript.all_frames, false);
assert.match(composerFix, /DataTransfer\(\)/, 'Composer fallback should try the editor paste transaction path');
assert.match(composerFix, /ClipboardEvent\('paste'/, 'Composer fallback should support ProseMirror paste handling');
assert.match(composerFix, /execCommand\('insertText'/, 'Composer fallback should retain Gecko contenteditable insertion');
assert.match(composerFix, /inputType: 'insertFromPaste'/, 'Composer fallback must emit editor-compatible input semantics');
assert.match(composerFix, /attempts >= 180/, 'Slow New Chat/editor startup must not silently exhaust after a few seconds');
assert.match(composerFix, /if \(busy \|\| loadedSourceId \|\| routeChatId\(\)/,
  'Staged handoff insertion must be limited to a fresh New Chat route');
assert.equal(/send-button|submit-button|aria-label[^\n]*Send/iu.test(composerFix), false, 'Composer fallback must never auto-submit');
assert.equal(/\.click\s*\(/.test(composerFix), false, 'Composer fallback must never synthesize a click');

console.log('Continuity Vault exact-origin, profile isolation, cumulative full-history capture, robust staged composer handoff, local-only storage, 70k handoff, and no-auto-send guards passed.');
