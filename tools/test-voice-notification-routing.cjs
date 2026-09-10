const fs = require('node:fs');
const assert = require('node:assert/strict');

const voice = fs.readFileSync('app/src/main/java/com/mekromn/bubble/VoiceNotifications.kt', 'utf8');

assert.match(voice, /if \(tabId != null\) putExtra\(BrowserActivity\.EXTRA_TAB, tabId\)/,
  'Voice Android notification click PendingIntent must carry the durable Bubble tab id');
assert.match(voice, /Policy\.isVoice\(item\.targetUrl\)[\s\S]*openTarget\(context, item\)[\s\S]*clickVoiceWhenActive\(item\)/,
  'Google Voice notification taps must foreground the target tab before firing the site notification click');
assert.match(voice, /workspace\?\.selectedId == expectedTab[\s\S]*workspace\.chatVisible[\s\S]*tab\?\.session\?\.isOpen == true/,
  'Voice deep-link click must wait for the exact resident tab to be selected, visible and open');
assert.match(voice, /item\.web\.click\(\)[\s\S]*postDelayed\([\s\S]*item\.web\.dismiss\(\)/,
  'WebNotification dismissal must happen after, never before, Google Voice receives notificationclick');
assert.equal(/item\.web\.click\(\)\s*\};\s*runCatching\s*\{\s*item\.web\.dismiss\(\)\s*\};\s*openTarget/.test(voice), false,
  'The old click-dismiss-open ordering is forbidden because it races Google Voice deep linking');
assert.match(voice, /VoiceNotifications\.handle\([\s\S]*intent\.getStringExtra\(BrowserActivity\.EXTRA_TAB\)/,
  'Receiver must preserve the durable tab id even if the in-memory WebNotification map is gone');

console.log('Google Voice notification routing: exact tab foreground first, native notificationclick second, delayed dismiss last.');
