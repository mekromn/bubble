const fs = require('node:fs');
const assert = require('node:assert/strict');

const source = fs.readFileSync('app/src/main/assets/chat-monitor/vault.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync('app/src/main/assets/chat-monitor/manifest.json', 'utf8'));
const native = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVault.kt', 'utf8');
const bridge = fs.readFileSync('app/src/main/java/com/mekromn/bubble/ChatVaultBridge.kt', 'utf8');

const vaultScript = manifest.content_scripts.find(item => item.js.includes('vault.js'));
assert.ok(vaultScript, 'Vault content script must stay registered');
assert.deepEqual(vaultScript.matches, ['https://chatgpt.com/*'], 'Vault must stay exact ChatGPT-origin scoped');
assert.equal(vaultScript.all_frames, false, 'Vault may read only the top ChatGPT document');
assert.equal(source.includes("location.origin !== 'https://chatgpt.com'"), true, 'Runtime exact-origin guard is required');
assert.equal(source.includes('window !== window.top'), true, 'Runtime top-frame guard is required');

assert.match(source, /sendNativeMessage\('bubbleVault'/, 'Vault must use its dedicated native app namespace');
assert.match(bridge, /NATIVE_APP = "bubbleVault"/, 'Native bridge namespace must match the page bridge');
assert.match(bridge, /!Policy\.isChat\(sender\.url\)/, 'Native bridge must revalidate exact ChatGPT origin');
assert.match(bridge, /sender\.session !== session/, 'Native bridge must stay bound to the exact GeckoSession');
assert.match(bridge, /!sender\.isTopLevel/, 'Native bridge must reject child frames');

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
assert.match(native, /first two \+ most recent/i, 'Size-aware handoff must keep opening and newest context');

console.log('Continuity Vault origin, local-only storage, chunking, 70k handoff, and no-auto-send guards passed.');
