const fs = require('node:fs');
const assert = require('node:assert/strict');

const voice = fs.readFileSync('app/src/main/java/com/mekromn/bubble/VoiceNotifications.kt', 'utf8');
const bridge = fs.readFileSync('app/src/main/java/com/mekromn/bubble/VoicePageBridge.kt', 'utf8');
const rich = fs.readFileSync('app/src/main/java/com/mekromn/bubble/VoiceRichNotification.kt', 'utf8');

assert.match(voice, /if \(tabId != null\) putExtra\(BrowserActivity\.EXTRA_TAB, tabId\)/,
  'Voice Android notification click PendingIntent must carry the durable Bubble tab id');
assert.match(voice, /Policy\.isVoice\(item\.targetUrl\)[\s\S]*openTarget\(context, item\)[\s\S]*clickVoiceWhenActive\(item\)/,
  'Google Voice notification taps must foreground the target tab before firing the site notification click');
assert.match(voice, /workspace\?\.selectedId == expectedTab[\s\S]*workspace\.chatVisible[\s\S]*tab\?\.session\?\.isOpen == true/,
  'Voice deep-link click must wait for the exact resident tab to be selected, visible and open');
assert.match(voice, /VoicePageBridge\.prepareNotificationClick\(expectedTab\)[\s\S]*item\.web\.click\(\)/,
  'Exact Voice tab must be reserved before Google service-worker notificationclick executes');
assert.match(voice, /VoicePageBridge\.routeNotification\(expectedTab, item\.contact, item\.text\)/,
  'Native must send sender/phone/message identity to the exact Voice page as a deterministic fallback');
assert.match(voice, /item\.web\.click\(\)[\s\S]*postDelayed\([\s\S]*item\.web\.dismiss\(\)/,
  'WebNotification dismissal must happen after, never before, Google Voice receives notificationclick');
assert.equal(/item\.web\.click\(\)\s*\};\s*runCatching\s*\{\s*item\.web\.dismiss\(\)\s*\};\s*openTarget/.test(voice), false,
  'The old click-dismiss-open ordering is forbidden because it races Google Voice deep linking');
assert.match(voice, /VoicePageBridge\.installServiceWorker\(runtime, workspace\)/,
  'GeckoRuntime must install a ServiceWorkerDelegate so clients.openWindow(messageUrl) is not dropped');
assert.match(bridge, /setServiceWorkerDelegate\([\s\S]*onOpenWindow\(url: String\)/,
  'Voice bridge must handle service-worker openWindow requests');
assert.match(bridge, /pendingServiceWorkerTabId[\s\S]*workspace\.tabs\.firstOrNull \{ it\.id == id && Policy\.isVoice\(it\.url\) \}/,
  'Service-worker openWindow must prefer the exact Voice tab reserved by the notification tap');
assert.match(bridge, /VoicePageBridge|bubbleVoice/,
  'Voice native bridge namespace must stay explicit');
assert.match(voice, /VoicePageBridge\.lookupPhone\(tabId, item\.contact, item\.text\)/,
  'Missing notification phone numbers must be locally enriched from the resident Voice conversation list');
assert.match(voice, /sequenceOf\(title, tag, senderFragment\)/,
  'Sender phone extraction must not mine arbitrary message-body numbers');

assert.match(rich, /Notification\.MessagingStyle/,
  'Voice messages must use Android conversation-style notifications');
assert.match(rich, /Person\.Builder\(\)/,
  'Rich Voice notifications must expose the sender as an Android Person');
assert.match(rich, /builder\.setSubText\(\(phone \?: "Google Voice"\)/,
  'Phone number must be surfaced in the notification subtext when known');
assert.match(rich, /web\.imageUrl/,
  'Gecko WebNotification imageUrl must be used as the contact-avatar source');
assert.match(rich, /initialIcon\(contactName\)/,
  'A local contact-initial icon must exist when Voice does not provide or load an avatar');
assert.match(rich, /setLargeIcon\(personIcon\)/,
  'Contact icon must be displayed as the notification large icon');
assert.match(voice, /VoiceRichNotification\.loadAvatar\(web\)/,
  'Avatar loading must upgrade the already-posted Voice notification');
assert.match(voice, /build\(true\)/,
  'Rich notification enrichment must update without re-alerting the user');
assert.match(voice, /VoiceNotifications\.handle\([\s\S]*intent\.getStringExtra\(BrowserActivity\.EXTRA_TAB\)/,
  'Receiver must preserve the durable tab id even if the in-memory WebNotification map is gone');

console.log('Google Voice notifications: rich Person/avatar/message UI + exact tab + service-worker URL + local conversation fallback.');
