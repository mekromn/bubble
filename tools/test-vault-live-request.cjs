const fs = require('node:fs');
const assert = require('node:assert/strict');
const hook = fs.readFileSync('app/src/main/assets/chat-monitor/vault-history-hook.js', 'utf8');
const bridge = fs.readFileSync('app/src/main/assets/chat-monitor/vault-history-bridge.js', 'utf8');

assert.match(hook, /let learnedRequest = null/,
  'Full-history sync must remember the exact successful live ChatGPT request');
assert.match(hook, /function learnRequest\(/,
  'Successful conversation responses must teach Bubble the live request recipe');
assert.match(hook, /function replayLearnedRequest\(/,
  'Explicit full sync must be able to replay the learned request');
const learnedAt = hook.indexOf('const learned = await replayLearnedRequest(id)');
const legacyAt = hook.indexOf('consumeFullResponse(await requestConversation(id)');
assert.ok(learnedAt >= 0 && legacyAt > learnedAt,
  'The live ChatGPT request must be tried before the legacy hard-coded backend fallback');
assert.match(hook, /XMLHttpRequest\.prototype\.setRequestHeader/,
  'XHR-based ChatGPT clients must also teach the request recipe');
assert.match(hook, /FORBIDDEN_REPLAY_HEADERS/,
  'Browser-controlled request headers must not be replayed manually');
assert.match(hook, /failures\.filter\(Boolean\)\.join\(';'\)/,
  'Full-sync failures should preserve enough local diagnostics to identify endpoint/auth drift');
for (const forbidden of [/\bAuthorization\b/, /\bBearer\b/, /accessToken/, /document\.cookie/]) {
  assert.equal(forbidden.test(bridge), false,
    `Credentials must never leave MAIN world and reach the isolated/native bridge: ${forbidden}`);
}
console.log('Vault live-request learning and credential-boundary guards passed.');
