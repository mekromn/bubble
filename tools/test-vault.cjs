const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const source = fs.readFileSync('app/src/main/assets/chat-monitor/vault.js', 'utf8');
const historyHook = fs.readFileSync('app/src/main/assets/chat-monitor/vault-history-hook.js', 'utf8');
const historyBridge = fs.readFileSync('app/src/main/assets/chat-monitor/vault-history-bridge.js', 'utf8');
const composerFix = fs.readFileSync('app/src/main/assets/chat-monitor/vault-composer-fix.js', 'utf8');
const transcriptCommand = fs.readFileSync('app/src/main/assets/chat-monitor/vault-transcript-command.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));
const native = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVault.kt', 'utf8');
const bridge = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVaultBridge.kt', 'utf8');
const snapshotPolicy = fs.readFileSync('app/src/main/java/com/mekromn/bubble/VaultSnapshotPolicy.kt', 'utf8');
const mergePolicy = fs.readFileSync('app/src/main/java/com/mekromn/bubble/VaultTranscriptMerge.kt', 'utf8');
const maintenance = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatTabMaintenance.kt', 'utf8');
const vaultUi = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVaultUi.kt', 'utf8');
const tabTray = fs.readFileSync('app/src/main/java/com/mekromn/bubble/TabTray.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');
const appearance = fs.readFileSync('app/src/main/java/com/mekromn/bubble/PageAppearance.kt', 'utf8');
const skill = fs.readFileSync('skills/bubble-chat-transcript/SKILL.md', 'utf8');

for (const [name, script] of Object.entries({source, historyHook, historyBridge, composerFix, transcriptCommand})) {
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

for (const forbidden of [/\bfetch\s*\(/, /XMLHttpRequest/, /document\.cookie/, /\bAuthorization\b/, /\bBearer\b/, /\.click\s*\(/]) {
  assert.equal(forbidden.test(source), false, `Vault source crossed its local-only boundary: ${forbidden}`);
}

assert.match(source, /const MAX_HANDOFF_CHARS = 70000/);
assert.match(native, /MAX_HANDOFF_CHARS = 70_000/);
assert.match(source, /const CHUNK_CHARS = 48000/);
assert.match(source, /vault-snapshot-begin/);
assert.match(source, /vault-snapshot-chunk/);
assert.match(source, /vault-snapshot-end/);
assert.match(source, /HANDOFF_MARKER/);
assert.match(source, /setComposerText\(composer, 'go'\)/);
assert.equal(/send-button|submit-button|aria-label[^\n]*Send/iu.test(source), false);
assert.equal(native.includes('There is no cloud endpoint, credential capture, analytics, or'), true);
assert.equal(native.includes('automatic pruning.'), true);
assert.match(native, /val first = chat\.messages\.take\(2\)/);
assert.match(native, /for \(record in chat\.messages\.drop\(2\)\.asReversed\(\)\)/);
assert.match(native, /tail\.addFirst\(record\)/);

const historyMain = manifest.content_scripts.find(item => item.js.includes('vault-history-hook.js'));
const historyIsolated = manifest.content_scripts.find(item => item.js.includes('vault-history-bridge.js'));
assert.ok(historyMain && historyIsolated);
assert.deepEqual(historyMain.matches, ['https://chatgpt.com/*']);
assert.equal(historyMain.world, 'MAIN'); assert.equal(historyMain.run_at, 'document_start'); assert.equal(historyMain.all_frames, false);
assert.deepEqual(historyIsolated.matches, ['https://chatgpt.com/*']);
assert.notEqual(historyIsolated.world, 'MAIN'); assert.equal(historyIsolated.all_frames, false);
assert.match(historyHook, /response\.clone\(\)/);
assert.match(historyHook, /originalFetch\.apply\(this, (?:args|arguments)\)/,
  'History observer must preserve ordinary page fetch calls exactly');
assert.match(historyHook, /xhrSend\.apply\(this, args\)/);
assert.match(historyHook, /current_node|currentNode/);
assert.match(historyHook, /\/backend-api\/conversation\//);
assert.match(historyHook, /\/api\/auth\/session/);
assert.match(historyHook, /headers\.Authorization = `Bearer \$\{token\}`/);
assert.match(historyHook, /credentials: 'include'/);
assert.equal(/new\s+XMLHttpRequest\s*\(/.test(historyHook), false);
for (const forbidden of [/document\.cookie/, /localStorage/, /sessionStorage/, /sendNativeMessage/, /\.click\s*\(/, /scrollIntoView|scrollTo|scrollBy/]) {
  assert.equal(forbidden.test(historyHook), false, `MAIN-world history hook crossed its allowed boundary: ${forbidden}`);
}
for (const forbidden of [/\bAuthorization\b/, /\bBearer\b/, /accessToken/, /document\.cookie/, /localStorage/, /sessionStorage/]) {
  assert.equal(forbidden.test(historyBridge), false, `Isolated Vault bridge must never receive credentials: ${forbidden}`);
}
assert.match(historyBridge, /sendNativeMessage\('bubbleVault'/);
assert.match(historyBridge, /snapshot\.source/);
assert.match(historyBridge, /archive-full-history/);
assert.match(historyBridge, /\[0, 1200, 3500, 8000\]/);

assert.match(snapshotPolicy, /vaultLoaded && incomingCount >= 1/);
assert.match(bridge, /VaultSnapshotPolicy\.accepts\(vault\.loaded, incomingCount\)/);
assert.match(native, /VaultTranscriptMerge\.merge\(existing\?\.messages\.orEmpty\(\), incomingMessages, authoritative\)/);
assert.match(mergePolicy, /touchesSavedTail/);
assert.match(mergePolicy, /saved \+ incoming/);
assert.match(mergePolicy, /authoritative\) return incoming/);

const continueStart = vaultUi.indexOf('Continue in a new ChatGPT chat');
const stageAt = vaultUi.indexOf('vault.stage(id, chat.profileId)', continueStart);
const handoffAt = vaultUi.indexOf('vault.handoff(id)', continueStart);
const createAt = vaultUi.indexOf('workspace.create(Policy.HOME, chat.profileId)', continueStart);
assert.ok(continueStart >= 0 && stageAt > continueStart && handoffAt > stageAt && createAt > handoffAt);
const composerScript = manifest.content_scripts.find(item => item.js.includes('vault-composer-fix.js'));
assert.ok(composerScript);
assert.deepEqual(composerScript.matches, ['https://chatgpt.com/*']); assert.equal(composerScript.all_frames, false);
assert.match(composerFix, /DataTransfer\(\)/);
assert.match(composerFix, /ClipboardEvent\('paste'/);
assert.match(composerFix, /execCommand\('insertText'/);
assert.match(composerFix, /inputType: 'insertFromPaste'/);
assert.match(composerFix, /attempts >= 180/);
assert.match(composerFix, /if \(busy \|\| loadedSourceId \|\| routeChatId\(\)/);
assert.equal(/send-button|submit-button|aria-label[^\n]*Send/iu.test(composerFix), false);
assert.equal(/\.click\s*\(/.test(composerFix), false);

assert.match(tabTray, /setOnClickListener \{ newChat\(\) \}/);
assert.match(tabTray, /setOnLongClickListener/);
assert.match(tabTray, /ChatVaultControls\.archive\(current\.id\)/);
assert.match(tabTray, /stageForUrlAndThen\(current\.url, current\.profileId\)/);

assert.match(vaultUi, /Archive all open chats now/);
assert.match(vaultUi, /ChatTabMaintenance\.archiveAll\(workspace\)/);
assert.match(workspace, /else -> ensureSession\(tab\)/);
assert.match(maintenance, /IDLE_CHECK_MS = 30L \* 60L \* 1000L/);
assert.match(maintenance, /state\.unchangedChecks >= 2/);
assert.match(maintenance, /previousSelected/);
assert.match(maintenance, /GENERATION_STALL_MS = 30L \* 60L \* 1000L/);
assert.match(maintenance, /generationProgress/);
assert.match(maintenance, /session\.reload\(GeckoSession\.LOAD_FLAGS_BYPASS_CACHE\)/);

const transcriptScript = manifest.content_scripts.find(item => item.js.includes('vault-transcript-command.js'));
assert.ok(transcriptScript);
assert.deepEqual(transcriptScript.matches, ['https://chatgpt.com/*']); assert.equal(transcriptScript.all_frames, false);
assert.match(transcriptCommand, /new Set\(\['GET_CHAT_TRANSCIPT', 'GET_CHAT_TRANSCRIPT'\]\)/);
assert.match(transcriptCommand, /commandOf\(assistantNodes\(\)\.at\(-1\)\)/);
assert.match(transcriptCommand, /sync: true/);
assert.match(transcriptCommand, /new File\(/);
assert.match(transcriptCommand, /DataTransfer\(\)/);
assert.match(transcriptCommand, /form\.requestSubmit\(button\)/);
assert.equal(/\.click\s*\(/.test(transcriptCommand), false);
assert.equal(/document\.cookie|\bAuthorization\b|\bBearer\b|accessToken/.test(transcriptCommand), false);

assert.match(skill, /continuity-first behavior/i);
assert.match(skill, /GET_CHAT_TRANSCRIPT/);
assert.match(skill, /Bias toward consulting the transcript when continuity matters/i);
assert.match(skill, /avoid needless repeated requests/i);
assert.match(skill, /logic and idea retention/i);

console.log('Continuity Vault cumulative merge, CRX-derived full-history sync, archive-all, background maintenance, transcript command, continuation UX, and companion skill guards passed.');
