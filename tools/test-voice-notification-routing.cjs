const fs = require('node:fs');
const assert = require('node:assert/strict');

const voice = fs.readFileSync('app/src/main/java/com/mekromn/bubble/VoiceNotifications.kt', 'utf8');
const appearance = fs.readFileSync('app/src/main/java/com/mekromn/bubble/PageAppearance.kt', 'utf8');

assert.match(voice, /VOICE_MESSAGES_URL = "https:\/\/voice\.google\.com\/u\/0\/messages"/,
  'Google Voice notification destination must be the fixed messages URL');
assert.match(voice, /putExtra\(TARGET_URL, targetUrl\)/,
  'Notification PendingIntent must carry a durable target URL even if in-memory notification state is lost');
assert.match(voice, /if \(Policy\.isVoice\(item\.targetUrl\)\)[\s\S]*item\.web\.dismiss\(\)[\s\S]*openVoiceMessages\(context, item\.tabId\)/,
  'Voice taps must dismiss the web notice and open the fixed Messages page instead of notificationclick deep-linking');
assert.match(voice, /workspace\.select\(tab\.id\)[\s\S]*workspace\.navigate\(VOICE_MESSAGES_URL\)[\s\S]*NotificationReturnActivity\.pending\(context, tab\.id, FloatingMode\.CHAT\)/,
  'Existing resident Voice tab must be selected, navigated to /u/0/messages, and surfaced');
assert.match(voice, /Intent\.ACTION_VIEW; data = Uri\.parse\(VOICE_MESSAGES_URL\)/,
  'Process-recreation fallback must still open the exact messages URL');
assert.equal(/clickVoiceWhenActive|prepareNotificationClick|routeNotification|installServiceWorker|VoiceRichNotification/.test(voice), false,
  'Rich/deep-link notification experiments must not be in the active Voice notification path');
assert.equal(/VoicePageBridge\.bind/.test(appearance), false,
  'Per-page binding must not install the retired notification routing bridge');
assert.match(voice, /sequenceOf\(title, tag, senderFragment\)/,
  'Sender phone extraction must continue rejecting arbitrary message-body numbers');

console.log('Google Voice notifications: restored simple Android path; every tap opens exact /u/0/messages in the Voice tab.');
