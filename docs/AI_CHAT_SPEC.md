# Bubble — AI Chat Workspace Specification

## 1. Product direction

Bubble is now an **AI-chat workspace browser first**, with ChatGPT as the first fully supported AI service.

The primary experience is no longer “many arbitrary browser tabs, each optionally becoming its own head.” The primary experience is:

1. open several ChatGPT conversations at once;
2. keep those conversations as live as Android and the installed WebView permit;
3. collapse the entire ChatGPT workspace into **one floating Bubble**;
4. continue using other Android apps while ChatGPT conversations keep generating or waiting in the background;
5. receive native Android notifications, including sound, when a ChatGPT conversation finishes generating or otherwise needs attention;
6. tap the workspace bubble or a notification and restore the exact conversation immediately.

Other AI chat providers can be added later through the same adapter architecture. ChatGPT is the v1 AI target and must receive first-class validation before additional providers are added.

This document **supersedes conflicting generic-browser behavior** in `PRODUCT_SPEC.md`, `UX_SPEC.md`, `ARCHITECTURE.md`, or old issues when the behavior concerns supported AI chat pages. The generic browser engine remains useful infrastructure and fallback navigation, but AI-chat workflow is the product priority.

## 2. Supported ChatGPT origin

First-class AI support is restricted to an explicit origin allowlist.

Initial ChatGPT origin:

- `https://chatgpt.com`

Legacy/redirecting OpenAI chat origins may be accepted for navigation, but all site-specific integration must resolve to and validate the final trusted ChatGPT origin before enabling adapter behavior.

Never enable ChatGPT-specific JavaScript/native messaging on arbitrary pages, subresource origins, ads, redirects, or user-controlled lookalike domains.

## 3. Core mental model

### 3.1 AI workspace

A `ChatWorkspace` is a durable collection of logical AI chat tabs belonging to one provider/profile.

For the initial implementation:

```text
ChatWorkspace
- workspaceId
- provider: CHATGPT
- profileId
- tabIds[]
- lastActiveTabId
- collapsedToBubble
- unreadCompletedCount
- generatingCount
- notificationsEnabled
- soundEnabled
- createdAt
- updatedAt
```

A logical chat remains a normal durable `TabId`; workspace membership is additional metadata, not a replacement for tab identity.

### 3.2 One bubble, many chats

All ChatGPT tabs in the primary workspace collapse into **one workspace bubble** by default.

Do not create one system overlay head per ChatGPT tab in the normal AI workflow.

The workspace bubble represents the collection and shows concise aggregate state:

- ChatGPT/provider identity;
- number of open chats;
- number currently generating;
- unread completed-reply count;
- connection/recovery warning only when meaningful.

A future explicit “break this chat out” action may create a separate head, but it is not part of the initial ChatGPT workflow.

## 4. Live-chat residency policy

The user goal is for every ChatGPT tab to stay active as though it remained foregrounded. Android and Chromium/WebView do not provide an absolute guarantee that arbitrary background renderer processes will remain alive forever, so Bubble must implement the strongest honest approximation.

### 4.1 ChatGPT live tabs are protected by Bubble policy

While a ChatGPT workspace is active:

- every workspace ChatGPT tab defaults to `keepRendererAlive = true`;
- the normal LRU renderer pool must not voluntarily hibernate, save-and-destroy, or evict those ChatGPT renderers merely to meet the normal warm-tab target;
- Bubble must not call `WebView.onPause()` or `WebView.pauseTimers()` simply because the browser Activity is no longer topmost;
- renderer sessions remain live until the user closes/removes the chat, disables live mode, the site/session itself ends, or Android forces reclamation;
- the workspace foreground service/overlay lifecycle should keep Bubble at the highest platform-compliant process importance available for this user-visible floating workflow;
- renderer/process death must be detected and recovered immediately from the best durable state/URL rather than silently turning the chat into a dead tab.

### 4.2 No false guarantee

Bubble must never claim that it can override Android low-memory killing, WebView renderer termination, network loss, server-side timeouts, account/session expiry, or provider-side suspension.

UI language should say **Live**, **Keeping live**, or **Recovered**, not “cannot be killed.”

### 4.3 Visibility compatibility

Do not globally spoof Page Visibility APIs or inject generic anti-throttling hacks.

First test ChatGPT under the live-residency policy above. If ChatGPT itself proves to pause generation solely because of document visibility/background lifecycle state, a **ChatGPT-only, origin-gated compatibility shim** may be implemented as a separately testable feature. It must:

- run only on the trusted ChatGPT origin;
- be minimal and reversible;
- never weaken browser security boundaries;
- never be used to defeat account, abuse-prevention, access-control, rate-limit, or provider safety mechanisms;
- be removable independently if ChatGPT changes its frontend behavior.

## 5. ChatGPT site adapter architecture

Create a provider-neutral interface so future AI services do not contaminate core browser code.

Suggested boundary:

```text
AiChatAdapter
- providerId
- matchesTrustedOrigin(url)
- classifyConversation(url)
- installPageMonitor(engineSession)
- removePageMonitor(engineSession)
- observeChatState(tabId): Flow<AiChatState>
- buildDisplayMetadata(tabId)
```

Initial implementation:

```text
ChatGptAdapter : AiChatAdapter
```

Suggested packages:

- `ai/model`
- `ai/workspace`
- `ai/adapter`
- `ai/chatgpt`
- `ai/notifications`
- `ui/aiworkspace`
- `heads/workspace`

The generic browser engine must not contain selectors or ChatGPT-specific assumptions.

## 6. Chat lifecycle model

Each supported AI tab should expose a small provider-neutral state machine:

```text
AiChatState
- IDLE
- USER_SUBMITTED
- GENERATING
- COMPLETE_UNREAD
- COMPLETE_READ
- ERROR
- RECOVERING
```

The adapter should prefer robust semantic signals over brittle visual selectors. Implementation may observe provider DOM/runtime state, but the native side should receive only the minimum event/state needed for workspace behavior.

Do not scrape or persist full conversation text merely to implement notifications.

## 7. Secure ChatGPT page monitoring

ChatGPT completion detection should be local to the device and origin-scoped.

Preferred implementation:

- inject only on validated `https://chatgpt.com` pages;
- use AndroidX WebKit origin-restricted WebMessage APIs when available;
- allow messages only from the trusted ChatGPT origin;
- expose a tiny event schema such as `generation-started`, `generation-finished`, `generation-error`, `conversation-id-changed`;
- never expose an unrestricted `addJavascriptInterface` object to arbitrary internet content;
- validate every native message before acting on it;
- tear down/reinstall the monitor on navigation as needed.

The adapter should tolerate ChatGPT frontend changes: unsupported/unknown DOM state must degrade to “state unknown,” not crash or fabricate completion.

## 8. Native Android ChatGPT notifications

Bubble must implement its **own native notification pipeline** for ChatGPT response-completion events rather than depending on the website to provide general reply-completion web push.

### 8.1 Notification channel

Create a dedicated Android notification channel:

- name: `ChatGPT replies`
- audible by default;
- user-configurable sound/vibration through normal Android channel settings;
- optional Bubble-provided distinct AI chime later, but do not copy proprietary notification audio;
- separate from the low-noise foreground-service notification.

### 8.2 When to notify

Notify when a ChatGPT tab transitions from `GENERATING` to `COMPLETE_UNREAD` and that conversation is not currently the visible foreground conversation.

Do not notify repeatedly for the same completed response.

If several conversations finish near each other:

- issue per-conversation child notifications;
- group them under a ChatGPT workspace summary;
- maintain an unread-completed count on the workspace bubble.

### 8.3 Notification contents

Default privacy-preserving notification:

- app/provider: ChatGPT
- conversation title when available and user allows it;
- text: `Reply complete` or equivalent;
- ChatGPT/Bubble iconography;
- timestamp.

Do not include the generated answer text, prompt text, URLs, account identifiers, or other browsing contents on the lock screen by default.

Provide a privacy setting for hiding conversation titles as well.

### 8.4 Notification actions

Tapping a notification must restore the **exact `TabId`** and mark that completion read only after the tab is actually presented.

Optional actions after the basic flow is stable:

- Open chat
- Dismiss notification
- Mute this chat

## 9. Workspace bubble UX

The one ChatGPT bubble is the signature minimized state.

### 9.1 Appearance

Use a refined provider/workspace icon, not a generic letter circle.

State overlays:

- small numeric badge for open chat count when useful;
- subtle animated progress/ring while one or more chats are generating;
- unread-completion badge when replies finish;
- warning/recovery indicator only when at least one live chat requires attention.

Avoid visually noisy simultaneous badges. Prioritize, in order: error/recovery, unread completion, generating, tab count.

### 9.2 Tap

Default tap behavior:

- if exactly one chat exists, restore it directly;
- if multiple chats exist and one was most recently active, restore the workspace to that chat;
- preserve quick access to the chat switcher from the restored workspace.

A setting may later make tap open the compact workspace chooser first.

### 9.3 Long press / expanded chooser

Long press or an explicit expand gesture should show a compact ChatGPT conversation chooser with:

- favicon/provider icon;
- conversation title;
- generating/completed/unread state;
- last activity time;
- tap to restore exact chat;
- close chat;
- mute/unmute notifications for that chat.

This chooser must remain usable with many concurrent chats.

## 10. AI-first browser shell

When the current site is ChatGPT, prioritize AI workflow actions over generic browser controls.

Primary phone UI should make these immediately available:

- current conversation title;
- workspace/chat switcher;
- new ChatGPT chat;
- collapse workspace to bubble;
- unread/generating status;
- back/forward as secondary browser navigation;
- normal browser overflow for generic actions.

Generic bookmarks/history/downloads remain useful infrastructure but should not dominate the ChatGPT experience.

## 11. Authentication and profile behavior

All normal ChatGPT tabs in one workspace use the same normal WebView profile so one sign-in is shared naturally across chats.

Do not store OpenAI credentials directly in Bubble.

Use the WebView/profile cookie and storage mechanisms plus Android Autofill/password-manager integration.

Private/alternate-account AI workspaces may be added later only with genuine profile isolation.

## 12. Foreground service behavior

When the ChatGPT workspace is collapsed and at least one live chat remains open:

- keep the existing platform-compliant special-use foreground service active because the user-visible overlay bubble is active;
- the service owns the lightweight workspace bubble and aggregate notification state;
- WebViews remain owned by the browser/session engine, not by one service per chat;
- the foreground-service notification remains low-noise and separate from reply-completion notifications.

Suggested foreground notification:

`ChatGPT workspace live • 4 chats • 2 generating`

Do not leak chat titles or URLs in this ongoing notification by default.

## 13. Memory-pressure behavior

The live ChatGPT policy overrides Bubble's normal voluntary LRU eviction, but correctness under real pressure still matters.

Priority order under memory pressure:

1. never destroy a ChatGPT live renderer merely to satisfy the normal renderer-pool warm target;
2. evict/hibernate unrelated generic browser tabs first;
3. release nonessential thumbnails/caches;
4. compact bounded state snapshots;
5. if Android kills a ChatGPT renderer anyway, mark the chat `RECOVERING`, recreate it, and restore the best available page/session state;
6. surface recovery state in the workspace bubble/chooser rather than silently pretending it stayed live.

An optional future advanced setting may cap live AI renderers, but the default ChatGPT product goal is **all workspace chats live** and there must be no small arbitrary app-defined count cap.

## 14. Response-completion reliability tests

ChatGPT v1 cannot be considered complete until device tests cover at least:

- one ChatGPT tab generating while Bubble is foreground;
- one generating while another ChatGPT tab is foreground;
- several ChatGPT tabs generating concurrently;
- collapse to the single workspace bubble while generation continues;
- use another Android app until each reply completes;
- audible notification fires exactly once per completed reply;
- notification tap restores the exact chat;
- unread count clears correctly;
- screen off/on during generation;
- network loss/reconnect;
- WebView renderer kill/recovery;
- Bubble Activity process recreation;
- 5, 10, and as many concurrent live ChatGPT tabs as the test device can sustain without Bubble voluntarily hibernating them;
- long-running chats and long responses;
- ChatGPT frontend navigation between conversation URLs without losing `TabId` association.

Pixel 9 Pro XL / Android 16 remains the first-class device validation target.

## 15. Non-goals for the first ChatGPT milestone

Do not delay the core live-workspace milestone for:

- Claude, Gemini, Copilot, Perplexity or other providers;
- cross-provider unified inbox;
- cloud sync;
- native ChatGPT API replacement of the website;
- automated prompt sending;
- scraping or indexing full conversation contents;
- autonomous background interaction with ChatGPT;
- multiple ChatGPT accounts in one workspace.

The first milestone is narrow: **multiple real ChatGPT web chats, live concurrently, one collapsible bubble, reliable local completion notifications with sound, and exact-chat restoration.**
