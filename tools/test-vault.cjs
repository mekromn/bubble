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

// The normal DOM Vault path must remain local-only and never perform networking or credential work.
for (const forbidden of [/\bfetch\s*\(/, /XMLHttpRequest/, /document\.cookie/, /\bAuthorization\b/, /\bBearer\b/, /\.click\s*\(/]) {
  assert.equal(forbidden.test(source), false, `Vault source crossed its local-only boundary: ${forbidden}`);
}

assert.match(source, /const MAX_HANDOFF_CHARS = 70000/, 'Page bridge must preserve the extension 70k continuity budget');
assert.match(native, /MAX_HANDOFF_CHARS = 70_000/, 'Native handoff policy must preserve the extension 70k continuity budget');
assert.match(source, /const CHUNK_CHARS = 48000/, 'Long snapshots must remain chunked');
assert.match(source, /vault-snapshot-begin/);
assert.match(source, /vault-snapshot-chunk/);
assert.match(source, /vault-snapshot-end/);
assert.match(source, /HANDOFF_MARKER/);
assert.match(source, /setComposerText\(composer, 'go'\)/, 'Fresh standalone Go continuation should load lowercase go');
assert.equal(/send-button|submit-button|aria-label[^\n]*Send/iu.test(source), false, 'Ordinary Vault continuity must never auto-submit');
assert.equal(native.includes('There is no cloud endpoint, credential capture, analytics, or'), true,
  'Native Vault must explicitly remain free of cloud/credential/analytics behavior');
assert.equal(native.includes('automatic pruning.'), true, 'Native Vault must explicitly retain saved chats until user deletion');
assert.match(native, /val first = chat\.messages\.take\(2\)/, 'Size-aware handoff must preserve the opening two messages');
assert.match(native, /for \(record in chat\.messages\.drop\(2\)\.asReversed\(\)\)/,
  'Size-aware handoff must scan newest remaining messages first');
assert.match(native, /tail\.addFirst\(record\)/, 'Newest retained context must be restored in chronological order');

// Full-history sync may authenticate only inside the exact-origin MAIN-world hook. It must request
// only the active conversation and never export credentials to the isolated/native bridge.
const historyMain = manifest.content_scripts.find(item => item.js.includes('vault-history-hook.js'));
const historyIsolated = manifest.content_scripts.find(item => item.js.includes('vault-history-bridge.js'));
assert.ok(historyMain && historyIsolated, 'Full-history observer and isolated bridge must stay registered');
assert.deepEqual(historyMain.matches, ['https://chatgpt.com/*']);
assert.equal(historyMain.world, 'MAIN'); assert.equal(historyMain.run_at, 'document_start'); assert.equal(historyMain.all_frames, false);
assert.deepEqual(historyIsolated.matches, ['https://chatgpt.com/*']);
assert.notEqual(historyIsolated.world, 'MAIN'); assert.equal(historyIsolated.all_frames, false);
assert.match(historyHook, /response\.clone\(\)/, 'Passive history observer must inspect a clone without consuming page responses');
assert.match(historyHook, /originalFetch\.apply\(this, args\)/, 'History observer must preserve ordinary page fetch calls exactly');
assert.match(historyHook, /xhrSend\.apply\(this, args\)/, 'History observer must preserve page XHR sends exactly');
assert.match(historyHook, /current_node|currentNode/, 'History observer must preserve the active branch from mapping data');
assert.match(historyHook, /\/backend-api\/conversation\//, 'Explicit full sync must request only the active conversation endpoint');
assert.match(historyHook, /\/api\/auth\/session/, 'Full sync may obtain the current page session token only in MAIN world when cookie auth is insufficient');
assert.match(historyHook, /headers\.Authorization = `Bearer \$\{token\}`/, 'Bearer use must stay local to the same-origin request');
assert.match(historyHook, /credentials: 'include'/, 'Conversation sync must remain same-origin authenticated');
assert.equal(/new\s+XMLHttpRequest\s*\(/.test(historyHook), false, 'Full history sync must not create an extra XHR path');
for (const forbidden of [/document\.cookie/, /localStorage/, /sessionStorage/, /sendNativeMessage/, /\.click\s*\(/, /scrollIntoView|scrollTo|scrollBy/]) {
  assert.equal(forbidden.test(historyHook), false, `MAIN-world history hook crossed its allowed boundary: ${forbidden}`);
}
for (const forbidden of [/\bAuthorization\b/, /\bBearer\b/, /accessToken/, /document\.cookie/, /localStorage/, /sessionStorage/]) {
  assert.equal(forbidden.test(historyBridge), false, `Isolated Vault bridge must never receive credentials: ${forbidden}`);
}
assert.match(historyBridge, /sendNativeMessage\('bubbleVault'/, 'Full history must cross only the dedicated local Vault namespace');
assert.match(historyBridge, /snapshot\.source/, 'Full-history provenance must be forwarded to native merge logic');
assert.match(historyBridge, /archive-full-history/, 'Manual archive must force an explicit full-sync request');
assert.match(historyBridge, /\[0, 1200, 3500, 8000\]/, 'Cold-start local replay retries must remain bounded');

// Short/virtualized snapshots now enter a serialized merge lane rather than being rejected by count.
assert.match(snapshotPolicy, /vaultLoaded && incomingCount >= 1/,
  'Snapshot admission must wait for index load but accept non-empty virtualized tails');
assert.match(bridge, /VaultSnapshotPolicy\.accepts\(vault\.loaded, incomingCount\)/,
  'Native bridge must use merge-lane admission rather than saved-count rejection');
assert.match(native, /VaultTranscriptMerge\.merge\(existing\?\.messages\.orEmpty\(\), incomingMessages, authoritative\)/,
  'Native Vault must merge partial snapshots into durable history');
assert.match(mergePolicy, /touchesSavedTail/, 'Merge must align a virtualized incoming window against the saved tail');
assert.match(mergePolicy, /saved \+ incoming/, 'A genuinely new alternating turn must be appendable without dropping saved history');
assert.match(mergePolicy, /authoritative\) return incoming/, 'A server full-history branch may replace an obsolete edited/regenerated branch');

// Continue-in-new-chat keeps the staged handoff serialized before opening the new tab.
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

// Your Chats: normal tap is plain New Chat; long press explicitly full-syncs/stages current history.
assert.match(tabTray, /setOnClickListener \{ newChat\(\) \}/, 'Normal New Chat must stay plain');
assert.match(tabTray, /setOnLongClickListener/, 'New Chat must expose the continuity long-press action');
assert.match(tabTray, /ChatVaultControls\.archive\(current\.id\)/, 'Long press should full-sync current tab before staging when possible');
assert.match(tabTray, /stageForUrlAndThen\(current\.url, current\.profileId\)/, 'Long press must stage the current profile-scoped chat before opening');

// Manual archive-all and background maintenance policy.
assert.match(vaultUi, /Archive all open chats now/, 'Vault UI must expose manual full archive of every open chat');
assert.match(vaultUi, /ChatTabMaintenance\.archiveAll\(workspace\)/, 'Archive-all UI must use the shared full-sync engine');
assert.match(workspace, /else -> ensureSession\(tab\)/, 'Restored open ChatGPT tabs must be made resident unless explicitly suspended');
assert.match(maintenance, /IDLE_CHECK_MS = 30L \* 60L \* 1000L/, 'Idle checks must run on the requested 30-minute interval');
assert.match(maintenance, /state\.unchangedChecks >= 2/, 'Two unchanged 30-minute checks must end the idle-check epoch');
assert.match(maintenance, /previousSelected/, 'Selecting a tab must reset its idle epoch');
assert.match(maintenance, /GENERATION_STALL_MS = 30L \* 60L \* 1000L/, 'Generation watchdog must use 30 minutes of no output');
assert.match(maintenance, /generationProgress/, 'Assistant output progress must reset the watchdog timer');
assert.match(maintenance, /session\.reload\(GeckoSession\.LOAD_FLAGS_BYPASS_CACHE\)/, 'Only a stalled still-generating tab gets the watchdog refresh');

// Exact assistant transcript-command automation: full sync -> attach -> requestSubmit, for any live tab.
const transcriptScript = manifest.content_scripts.find(item => item.js.includes('vault-transcript-command.js'));
assert.ok(transcriptScript, 'Transcript command must actually be loaded by the built-in extension');
assert.deepEqual(transcriptScript.matches, ['https://chatgpt.com/*']); assert.equal(transcriptScript.all_frames, false);
assert.match(transcriptCommand, /new Set\(\['GET_CHAT_TRANSCIPT', 'GET_CHAT_TRANSCRIPT'\]\)/,
  'Both the requested legacy spelling and canonical transcript command must be exact triggers');
assert.match(transcriptCommand, /commandOf\(assistantNodes\(\)\.at\(-1\)\)/, 'Only the latest completed assistant turn may trigger the command');
assert.match(transcriptCommand, /sync: true/, 'Transcript upload must full-sync the Vault before export');
assert.match(transcriptCommand, /new File\(/, 'Transcript must be attached as a real Markdown File');
assert.match(transcriptCommand, /DataTransfer\(\)/, 'Transcript file must be injected through the page file input');
assert.match(transcriptCommand, /form\.requestSubmit\(button\)/, 'Exact transcript command may submit the prepared attachment through the form API');
assert.equal(/\.click\s*\(/.test(transcriptCommand), false, 'Transcript automation must not fabricate a click');
assert.equal(/document\.cookie|\bAuthorization\b|\bBearer\b|accessToken/.test(transcriptCommand), false,
  'Transcript command content script must never receive credentials');

// Companion skill should proactively preserve project continuity without repeatedly re-requesting a recent transcript.
assert.match(skill, /continuity-first behavior/i);
assert.match(skill, /GET_CHAT_TRANSCRIPT/);
assert.match(skill, /Bias toward consulting the transcript when continuity matters/i);
assert.match(skill, /avoid needless repeated requests/i);
assert.match(skill, /logic and idea retention/i);

console.log('Continuity Vault cumulative merge, full-history sync, archive-all, background maintenance, transcript command, continuation UX, and companion skill guards passed.');
