'use strict';
const fs = require('fs');
const assert = require('assert');

const direct = fs.readFileSync('app/src/main/java/com/mekromn/bubble/DirectGeckoWindow.kt', 'utf8');
const background = fs.readFileSync('app/src/main/java/com/mekromn/bubble/EmbeddedPageBackground.kt', 'utf8');
const floating = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingWindow.kt', 'utf8');
const touch = fs.readFileSync('app/src/main/java/com/mekromn/bubble/PageTouchDispatch.kt', 'utf8');
const policy = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FloatingPerformancePolicy.kt', 'utf8');
const gradle = fs.readFileSync('app/build.gradle.kts', 'utf8');

const version = Number((gradle.match(/versionCode\s*=\s*(\d+)/) || [])[1]);
assert.ok(version >= 158, 'Build 158+ versionCode required after workflow version injection');
assert.match(gradle, /single-viewroot/, 'Build name must identify the single-ViewRoot candidate');

// One browser card, one Window/ViewRoot. Gecko's SurfaceView remains a native child surface.
assert.match(direct, /parent\.addView\(root, 0, FrameLayout\.LayoutParams\(-1, -1\)\)/,
    'Direct Gecko must attach into FloatingWindow pageContainer');
assert.match(direct, /RenderPolicy\.vote\(context, root\)/,
    'The shared ViewRoot must retain the max-refresh policy');
assert.match(direct, /surfaceView\(root\).*FloatingPerformancePolicy\.bind/s,
    'ADPF experiment must remain bound to Mozilla real SurfaceView for a clean architecture test');
assert.match(direct, /if \(container === parent && root\.parent === parent\) return true/,
    'Repeated Workspace renders must not redo renderer setup');

// The independent page WindowManager path must be physically gone, not disabled behind a branch.
for (const forbidden of [
    'TYPE_APPLICATION_OVERLAY', 'WindowManager', 'updateViewLayout', 'addView(root, pageParams)',
    'OnPreDrawListener', 'OnLayoutChangeListener', 'transformMatrixToGlobal(transform)',
    'OnBackInvokedCallback', 'SOFT_INPUT_ADJUST_NOTHING', 'setCanPlayMoveAnimation'
]) {
    assert.ok(!direct.includes(forbidden), `Independent page-window mechanism remains: ${forbidden}`);
}

// No second renderer/copy path was substituted.
assert.ok(!direct.includes('TextureView'), 'No TextureView page path');
assert.ok(!direct.includes('ImageReader'), 'No ImageReader page relay');
assert.ok(!direct.includes('PixelCopy'), 'No steady direct-host PixelCopy');
assert.ok(!direct.includes('RenderPolicy.voteTree'), 'Do not recursively re-vote the tree on geometry changes');
assert.ok(!/postDelayed\(/.test(direct), 'No timer loop in direct page host');
assert.ok(!/while\s*\(/.test(direct), 'No polling loop in direct page host');

// Cross-window seam compensation is dead because page and chrome now share ViewRoot geometry.
assert.ok(!background.includes('SEAM_GUARD_PX'), 'Remove obsolete cross-window seam underpaint');
assert.match(background, /canvas\.clipOutRect\(cutout\)/, 'Chrome still avoids painting over Gecko');

// FloatingWindow should own the only panel WindowManager geometry update.
assert.match(floating, /manager\.updateViewLayout\(root,params\)/,
    'FloatingWindow remains sole browser-card WindowManager geometry owner');
assert.match(touch, /requestUnbufferedDispatch/, 'Retain the selected unbuffered page-input policy');
assert.match(policy, /SurfaceHolder\.Callback/, 'Retain Surface-lifetime-owned ADPF policy');

console.log('Bubble 158 guards passed: one floating ViewRoot, direct Gecko SurfaceView, no second page window/sync loop, fidelity preserved.');
