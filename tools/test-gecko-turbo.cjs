const fs = require('node:fs');
const assert = require('node:assert/strict');

const turbo = fs.readFileSync('app/src/main/java/com/mekromn/bubble/GeckoTurboPolicy.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');

for (const required of [
  '.remoteDebuggingEnabled(false)',
  '.consoleOutput(false)',
  '.debugLogging(false)',
  '.aboutConfigEnabled(false)',
  '.extensionsProcessEnabled(false)',
  '.extensionsWebAPIEnabled(false)',
  '.loginAutofillEnabled(false)',
  '.enterpriseRootsEnabled(false)',
  '.webManifest(false)',
  '.translationsOfferPopup(false)',
  '.fontInflation(false)',
  '.inputAutoZoomEnabled(false)',
  '.javaScriptEnabled(true)',
  '.webFontsEnabled(true)',
]) {
  assert.ok(turbo.includes(required), `Turbo Gecko profile must contain ${required}`);
}

assert.match(turbo, /Build\.VERSION\.SDK_INT >= 29[\s\S]*appZygoteProcessEnabled\(true\)/,
  'App Zygote preload must be enabled only on Android 10+');
assert.match(workspace, /GeckoRuntime\.create\(app, GeckoTurboPolicy\.settings\(\)\)/,
  'Workspace must create Gecko from the explicit Turbo profile');

// Preserve the current full-web/security path. These are deliberate non-optimizations.
assert.equal(/fissionEnabled\(false\)/.test(turbo), false,
  'Turbo must not disable Fission/site isolation');
assert.equal(/webFontsEnabled\(false\)/.test(turbo), false,
  'Turbo must not trade web typography/fidelity for speed');
assert.equal(/javaScriptEnabled\(false\)/.test(turbo), false,
  'Turbo must not disable JavaScript/Wasm workloads');
assert.equal(/glMsaaLevel\(0\)/.test(turbo), false,
  'WebGL MSAA is an optional benchmark toggle, not part of safe Turbo v1');
assert.equal(/lowMemoryDetection\(false\)/.test(turbo), false,
  'Turbo must preserve Gecko low-memory handling for the resident-tab workload');

console.log('Gecko Turbo v1: safe runtime cuts + App Zygote with full-web/security invariants passed.');
