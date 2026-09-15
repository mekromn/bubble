'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const read = p => fs.readFileSync(p, 'utf8');

const gradle = read('app/build.gradle.kts');
const render = read('app/src/main/java/com/mekromn/bubble/RenderPolicy.kt');
const direct = read('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt');
const staging = read('app/src/main/java/com/mekromn/bubble/UploadStaging.kt');
const archive = read('app/src/main/java/com/mekromn/bubble/ArchiveEngine.kt');
const service = read('app/src/main/java/com/mekromn/bubble/BubbleService.kt');
const audit = read('docs/EFFICIENCY_AUDIT.md');

assert.match(gradle, /versionCode = 150/);
assert.match(gradle, /versionName = "0\.7\.22-efficiency-audit"/);

assert.match(render, /data class RateKey/);
assert.match(render, /rateCache/);
assert.match(render, /viewVotes/);
assert.match(render, /if \(old == rate\) false/,
  'identical View frame-rate requests must be suppressed');
assert.match(render, /SurfaceHolder\.Callback/,
  'Surface lifecycle must remain the source of producer re-votes');
assert.match(render, /FRAME_RATE_COMPATIBILITY_AT_LEAST/,
  'Android 16 UI/scrolling producer contract must be retained');

const geometry = direct.split('override fun geometryChanged()')[1].split('override fun coverForReveal')[0];
assert.ok(geometry, 'Direct renderer geometry method missing');
assert.equal(geometry.includes('RenderPolicy.voteTree'), false,
  'geometry-only frames must not recursively re-vote the Gecko tree');
assert.equal(geometry.includes('applyRate'), false,
  'legacy per-geometry frame-rate re-vote helper must stay removed');
assert.match(direct, /RenderPolicy\.vote\(context, root\)/,
  'direct renderer must still establish its initial high-refresh contract');

assert.equal((staging.match(/ByteArray\(64 \* 1024\)/g) || []).length, 1,
  'direct attachment request should own exactly one bounded transfer buffer');
const prepare = staging.split('fun prepare(')[1];
assert.ok(prepare.indexOf('val buffer = ByteArray(64 * 1024)') < prepare.indexOf('for ((index, uri) in selected.withIndex())'),
  'staging buffer must be allocated outside the per-file loop');

assert.match(archive, /PROGRESS_INTERVAL_NS = 50_000_000L/);
assert.match(archive, /report\(index, item, force = true\)/,
  'each completed archive entry must still force a final progress update');
assert.match(archive, /val total = sources\.sumOf/,
  'archive total should avoid an intermediate mapped list');
assert.match(archive, /zip\.setLevel\(compression\.level\)/,
  'user-selected compression level must remain unchanged');

assert.match(service, /data class NotificationState/);
assert.match(service, /private var channelReady = false/);
assert.match(service, /for \(tab in workspace\.tabs\)/,
  'notification counts should be computed in one tab pass');
assert.equal(service.includes('workspace.tabs.count { it.generating }'), false);
assert.equal(service.includes('workspace.tabs.count { it.unread }'), false);
assert.match(service, /lastNotificationState == state/,
  'unchanged foreground notification state must be a no-op');

assert.match(audit, /Hard invariants/);
assert.match(audit, /Never lower Gecko\/WebRender page resolution/);
assert.match(audit, /Build 150 — Phase A/);

console.log('Bubble 150 Phase-A efficiency invariants passed: output-identical render/service/file-path work reductions.');
