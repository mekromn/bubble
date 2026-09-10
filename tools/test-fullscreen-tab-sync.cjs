const fs = require('node:fs');
const assert = require('node:assert/strict');

const tray = fs.readFileSync('app/src/main/java/com/mekromn/bubble/TabTray.kt', 'utf8');
const sync = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FullscreenSurfaceSync.kt', 'utf8');
const live = fs.readFileSync('app/src/main/java/com/mekromn/bubble/LiveGeckoView.kt', 'utf8');

assert.match(tray, /setOnDismissListener\s*\{[\s\S]*syncSelectedSurfaceNow\(\)/,
  'Closing the fullscreen tab tray must immediately reconcile the selected GeckoSession');
assert.match(sync, /workspace\.selected[\s\S]*workspace\.attachSurface\(geckoView, session\)/,
  'Fullscreen sync must attach the already-selected resident session directly to the existing GeckoView');
assert.match(sync, /else if \(geckoView\.session != null\)[\s\S]*workspace\.detachSurface\(geckoView\)/,
  'Fullscreen sync must detach stale ownership when the selected session is unavailable');
assert.equal(/override fun setSession\(/.test(live), false,
  'Fullscreen LiveGeckoView must not reintroduce diagnostic setSession overrides');
assert.equal(/override fun releaseSession\(/.test(live), false,
  'Fullscreen LiveGeckoView must not reintroduce diagnostic releaseSession overrides');
assert.match(live, /override fun hasWindowFocus\(\): Boolean[\s\S]*if \(isAttachedToWindow\) return super\.hasWindowFocus\(\)/,
  'Attached fullscreen GeckoView must continue using Android real window focus');
assert.match(live, /floating\?\.geckoView === this && workspace\?\.floatingVisible == true/,
  'Detached raw floating bridge must report focus while it owns the visible interactive floating page');

console.log('Tab/focus sync: fullscreen chooser rebinds immediately and detached raw floating Gecko receives native focus parity without attaching its bridge.');
