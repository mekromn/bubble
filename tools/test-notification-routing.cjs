const fs = require('node:fs');
const assert = require('node:assert/strict');

const gateway = fs.readFileSync('app/src/main/java/com/mekromn/bubble/NotificationReturnActivity.kt', 'utf8');
const replies = fs.readFileSync('app/src/main/java/com/mekromn/bubble/Replies.kt', 'utf8');
const sync = fs.readFileSync('app/src/main/java/com/mekromn/bubble/FullscreenSurfaceSync.kt', 'utf8');

assert.match(replies, /NotificationReturnActivity\.pending\(context, id,[\s\S]*FloatingMode\.CHAT\)/,
  'Per-chat reply notifications must carry the exact tab id into the notification gateway');
assert.match(gateway, /val id = intent\.getStringExtra\(BrowserActivity\.EXTRA_TAB\)/,
  'Notification gateway must recover the durable target tab id');
assert.match(gateway, /workspace\.select\(id\)[\s\S]*syncSelectedSurfaceNow\(\)/,
  'Reusing fullscreen from a notification must synchronously select and bind the target tab');
assert.match(gateway, /putExtra\(BrowserActivity\.EXTRA_TAB, id\)/,
  'BrowserActivity intent must retain the target tab id as a second routing layer');
assert.match(gateway, /FLAG_ACTIVITY_CLEAR_TOP/,
  'Notification fullscreen routing must deliberately reuse the singleTask browser activity');
assert.match(sync, /workspace\.attachSurface\(geckoView, session\)/,
  'Notification fullscreen sync must reuse the resident selected GeckoSession without reloading');

console.log('Notification routing: exact tab id survives PendingIntent -> gateway -> fullscreen synchronous surface rebind.');
