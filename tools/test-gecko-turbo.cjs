const fs = require('node:fs');
const assert = require('node:assert/strict');

const turbo = fs.readFileSync('app/src/main/java/com/mekromn/bubble/GeckoTurboPolicy.kt', 'utf8');
const workspace = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Workspace.kt', 'utf8');

for (const required of [
  '.remoteDebuggingEnabled(false)',
  '.consoleOutput(false)',
  '.debugLogging(false)',
  '.configFilePath(startupConfigPath)',
]) {
  assert.ok(turbo.includes(required), `Build-84 Gecko baseline must contain ${required}`);
}

for (const forbidden of [
  'appZygoteProcessEnabled',
  'aboutConfigEnabled(',
  'extensionsProcessEnabled(',
  'extensionsWebAPIEnabled(',
  'loginAutofillEnabled(',
  'enterpriseRootsEnabled(',
  'webManifest(',
  'translationsOfferPopup(',
  'fontInflation(',
  'inputAutoZoomEnabled(',
  'javaScriptEnabled(',
  'webFontsEnabled(',
  'fissionEnabled(',
  'glMsaaLevel(',
  'lowMemoryDetection(',
]) {
  assert.equal(turbo.includes(forbidden), false,
    `Hybrid baseline must leave Gecko default alone: ${forbidden}`);
}

assert.match(workspace, /GeckoRuntime\.create\(app, GeckoTurboPolicy\.settings\(\)\)/,
  'Workspace must create Gecko from the Build-84-compatible policy wrapper');

assert.match(workspace, /GeckoTurboPolicy\.prepare\(app\)/);
assert.match(turbo, /UploadStaging\.io\.execute/);
assert.match(turbo, /app\.noBackupFilesDir/);
assert.ok(!turbo.includes('/data/local/tmp'));
console.log('Gecko hardware policy: explicit pre-start private config, logging off, other runtime settings preserved.');
