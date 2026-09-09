const fs = require('node:fs');
const assert = require('node:assert/strict');
const hook = fs.readFileSync('app/src/main/assets/chat-monitor/vault-history-hook.js', 'utf8');
const bridge = fs.readFileSync('app/src/main/assets/chat-monitor/vault-history-bridge.js', 'utf8');

// Reference behavior ported from the supplied ChatGPT Chat Continuity Vault 6.10.0.5 CRX:
// inj.js observes ChatGPT's own /backend-api/ fetches and caches their Bearer token; content.js
// reuses that token for GET /backend-api/conversation/<id>, falling back to /api/auth/session only
// when no live token has been observed. Bubble keeps the same auth logic entirely in MAIN world.
assert.match(hook, /function captureBackendBearer\(/,
  'MAIN-world hook must capture ChatGPT backend Bearer authentication');
assert.match(hook, /pathname\.includes\('\/backend-api\/'\)/,
  'Token capture must be limited to ChatGPT backend-api requests');
assert.match(hook, /authorization\.startsWith\('Bearer '\)/,
  'Only Bearer authorization values may seed the in-memory token cache');
assert.match(hook, /sessionToken = token/,
  'Observed Bearer token must remain available in the page-world cache');
assert.match(hook, /if \(sessionToken\) return sessionToken/,
  'A live token captured from ChatGPT must be preferred over another auth-session request');
assert.match(hook, /\/api\/auth\/session/,
  'The CRX auth-session fallback must remain available');
assert.match(hook, /\/backend-api\/conversation\/\$\{encodeURIComponent\(id\)\}/,
  'Full history must use the CRX conversation endpoint');
assert.match(hook, /headers\.Authorization = `Bearer \$\{token\}`/,
  'The captured/fallback token must authenticate only the page-world conversation request');
assert.equal(/learnedRequest|learnRequest\(|replayLearnedRequest\(/.test(hook), false,
  'The Build 79 request-recipe workaround must stay removed');

for (const forbidden of [/\bAuthorization\b/, /\bBearer\b/, /accessToken/, /sessionToken/, /document\.cookie/]) {
  assert.equal(forbidden.test(bridge), false,
    `Credentials must never leave MAIN world and reach the isolated/native bridge: ${forbidden}`);
}
console.log('Vault CRX-derived Bearer-token full-history and credential-boundary guards passed.');
