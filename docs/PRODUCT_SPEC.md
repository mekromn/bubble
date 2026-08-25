# Bubble — Product Specification

## 1. Product definition

Bubble is a full Android web browser centered on persistent **floating tab heads**. A browser tab can be minimized into a small draggable overlay icon, remain accessible while the user is in other Android apps, and be restored later to the exact logical tab.

The product must feel like a normal modern browser when used normally and like a highly capable floating-workflow tool when tabs are minimized.

The product is not a collection of separate mini-browser windows. A tab remains one logical browser session while its presentation changes between browser UI and floating head.

## 2. Primary user promise

The user can:

- open as many logical tabs as desired without encountering an arbitrary app-defined limit;
- minimize any tab into an independent draggable head;
- drag each head freely around the screen and leave it where desired;
- use other Android apps while heads remain visible;
- tap a head and immediately return to that exact logical tab;
- create, close, reorder, search, group and restore tabs without losing the head relationship;
- survive Activity recreation, renderer crashes and normal process reclamation without losing the durable session list.

“No arbitrary limit” means Bubble must never impose a fixed tab/head count such as 10, 20, 50 or 100. Android and device resources can impose practical limits, so Bubble must degrade by hibernating renderers rather than refusing to create tabs.

## 3. Product principles

### 3.1 Heads and renderers are separate concepts

A visible floating head is lightweight native UI. It must not require a permanently live WebView. The logical tab/session is durable independently of WebView residency.

### 3.2 Free placement first

Heads are freely draggable. They must not be forcibly snapped to an edge after every drag. Optional edge snapping, docking and stacking can be enabled by the user.

### 3.3 Graceful resource management

Bubble preserves fast switching using a small adaptive warm renderer pool, then suspends or hibernates older tabs under memory pressure. Memory policy is dynamic and LRU-based, not count-based.

### 3.4 Browser-grade behavior

Web pages should behave as users expect from a normal browser: navigation, downloads, uploads, permissions, popups, external intents, full-screen media, history, bookmarks, find in page and desktop mode must be handled intentionally.

### 3.5 Privacy and security by design

Bubble does not weaken TLS, silently grant origin permissions, expose app files to web content, or pretend a mode is private when it is not isolated.

## 4. Supported platform target

Production baseline:

- Android phone/tablet app.
- `minSdk` 26 unless implementation evidence shows a compelling reason to raise it.
- `targetSdk` 36 for the 2026 release line.
- Compile against the latest stable Android SDK that is proven in CI; do not depend on preview-only APIs for core behavior.
- AndroidX WebKit stable line; as of the specification date, 1.16.0 is stable and supports useful modern WebView lifecycle/navigation APIs.
- First-class validation on Pixel 9 Pro XL / Android 16.

## 5. Browser shell

Bubble must provide a polished browser shell with:

- omnibox supporting URLs and search queries;
- configurable default search engine;
- back, forward, reload/stop, home and tab switcher;
- visible page loading progress;
- security indicator for HTTPS versus insecure HTTP;
- page title and favicon;
- normal tab creation, duplication, closure and reordering;
- tab search;
- reopen recently closed tab;
- history;
- bookmarks/favorites;
- share page;
- copy URL;
- find in page;
- desktop-site toggle per tab;
- page zoom controls and reset;
- open in external app when appropriate;
- print when supported by Android print services;
- full-screen media support;
- keyboard/IME behavior that works with the omnibox and page forms;
- adaptive phone/tablet layouts and Android 16 edge-to-edge behavior.

## 6. Tab behavior

Every tab has a stable UUID and durable metadata. Tab identity must not change merely because its WebView is destroyed and recreated.

Required tab operations:

- New tab
- New head directly
- Duplicate tab
- Close tab
- Close other tabs
- Close tabs to left/right where the UI exposes ordering
- Reopen closed tab
- Minimize to head
- Restore from head
- Pin / protect from automatic cleanup
- Mute when engine support permits
- Move into or out of a tab group
- Convert normal tab to private only by opening a new isolated private session; never relabel an existing normal session as private

Tabs may be reordered independently of head coordinates.

## 7. Floating-head behavior

### 7.1 Head creation

A tab becomes a head when the user:

- taps the minimize-to-head action;
- long-presses a link and chooses “Open in floating tab”;
- shares a URL to Bubble and selects/open-mode is configured as “Head”;
- opens an external URL with Bubble when the user preference is “Open external links as heads.”

Overlay permission is requested only when the feature is first needed, with a clear explanation. The rest of the browser must remain usable if permission is declined.

### 7.2 Head appearance

Each head should display:

- page/site favicon when available;
- deterministic fallback icon or first letter when no favicon exists;
- private-mode marker for private heads;
- loading state/ring while the tab is loading or being resurrected;
- optional audio indicator when reliable engine state is available;
- subtle selected/active state only when relevant.

The head must remain readable at supported sizes and meet touch-target accessibility requirements.

### 7.3 Head interactions

Default contract:

- **Tap:** restore/open the associated tab.
- **Drag:** move the head freely.
- **Release:** keep the head where placed, constrained only enough to prevent it becoming irretrievably off-screen.
- **Long press:** open a compact actions menu.
- **Drag to close target:** close the logical tab after a clear visual affordance; provide undo where practical.

Long-press actions should include at minimum: Restore, Close, Pin, Duplicate, Share, Copy URL and site/tab info.

### 7.4 Position persistence

Persist position in a resolution-independent form, not raw absolute pixels alone. Store normalized coordinates plus edge affinity / last display identity where useful. On rotation, inset changes or display-size changes, clamp the head into the usable region while preserving intent.

### 7.5 Multiple heads

There is no hard cap. Each head is independently movable and independently restorable.

Heads may overlap because free placement is user-controlled. Optional stacking is supported but must not happen unexpectedly. An intentional drop-and-hold or explicit “Stack” action is preferred over automatic grouping.

Optional user settings:

- free placement (default)
- edge magnetism strength
- snap to edge
- remember exact positions
- head size
- head opacity when idle
- auto-dim delay
- stack behavior
- tap action (restore full browser vs future floating-card mode)

### 7.6 Many-head usability

When many heads exist, Bubble must remain manageable through the in-app tab/head manager even if the physical overlay becomes crowded. The manager can filter “Heads only”, search by title/domain, restore all, hide all overlays temporarily, or close selected heads. Hiding overlays must not close tabs.

## 8. Session and renderer lifecycle

Every logical tab has two independent state axes:

### Presentation state

- `BROWSER` — represented in the normal browser UI
- `HEAD` — represented as an overlay head
- future optional `FLOATING_CARD`

### Renderer residency

- `ACTIVE` — currently visible interactive WebView
- `WARM` — live WebView retained for fast switching
- `SAVED` — live renderer released; bounded WebView navigation state snapshot is available
- `HIBERNATED` — durable metadata and best-effort state exist on disk; no WebView is resident
- `RECOVERING` — renderer was killed/crashed or state restoration is in progress

Presentation state must not force residency state. A `HEAD` can be `WARM`, `SAVED` or `HIBERNATED`.

## 9. Adaptive memory policy

Do not implement `MAX_TABS`.

Use:

- device memory class;
- current memory pressure / `onTrimMemory` signals;
- renderer crash/termination signals;
- last-used timestamps;
- pinned status;
- active media status when reliably detectable;
- whether a tab is visible;
- whether a tab is private;
- state snapshot size.

Suggested policy:

1. Active visible tab is protected.
2. A small adaptive LRU set stays warm for instant switching.
3. Older normal tabs are state-saved and destroyed.
4. Under stronger pressure, discard expensive snapshots and retain canonical URL/history metadata.
5. Pinned tabs receive preferential residency but are still recoverable if Android kills the process.
6. A tab whose renderer crashes enters `RECOVERING`; recreate it and restore best available state rather than crashing the app.

WebView state snapshots must be bounded. Use `WebViewCompat.saveState` with a size cap when the installed WebView supports `SAVE_STATE`; otherwise use platform `saveState` carefully and never place a huge multi-tab session into Activity saved-instance state.

Process-death fallback is always the persisted canonical URL/title/session metadata even when exact renderer state cannot be reconstructed.

## 10. Persistence

Use Room for durable structured browser data and DataStore for settings.

Suggested Room entities:

- `TabEntity`
- `TabGroupEntity`
- `ClosedTabEntity`
- `HeadPlacementEntity`
- `HistoryEntryEntity`
- `BookmarkEntity`
- `DownloadEntity`
- `SitePermissionDecisionEntity` when decisions are managed by Bubble rather than the WebView profile

Use app-private bounded files/cache for:

- favicon data;
- optional per-tab WebView state blobs;
- generated thumbnails if tab previews are implemented.

Do not store passwords. Use Android/WebView Autofill integration and the user’s password manager.

## 11. Process and crash recovery

Required behavior:

- Activity recreation retains logical active tab and visible page context.
- Normal app process death does not erase the tab list.
- Renderer process death is handled via `WebViewClient.onRenderProcessGone`; the dead WebView is disposed and replaced.
- After an app crash, startup offers/restores the last durable session without duplicating tabs.
- Overlay heads are reconstructed when the app/service legitimately resumes and permission remains granted.
- Force-stop is respected; Bubble must not try to circumvent Android force-stop semantics.
- After device reboot, do not depend on illegal background foreground-service starts. Restore heads on the next legitimate user launch unless a future platform-compliant mechanism is explicitly implemented and tested.

## 12. External-link workflows

Bubble can register as an HTTP/HTTPS browser.

User preference for links opened from other apps:

- Open in browser
- Open as head
- Ask each time

Also expose a share target / explicit action for “Open in Bubble head.”

`intent:`, `mailto:`, `tel:`, `market:` and other external schemes must be validated and launched only through intentional, safe handling. Unknown schemes should not be executed blindly.

## 13. New windows and link context menu

Handle `target=_blank`, `window.open()` and `WebChromeClient.onCreateWindow` intentionally. A real new web window should become a new logical Bubble tab rather than disappearing or replacing the source tab.

Link long-press menu:

- Open
- Open in new tab
- Open in floating tab
- Copy link
- Share link
- Download link when applicable

Image context menu can add save/share image once safely implemented.

## 14. Downloads

Production download support must include:

- HTTP(S) downloads initiated from a page;
- filename/content-disposition handling;
- current User-Agent, cookies and referer where required by the download;
- Android notification/progress through a supported download mechanism;
- Downloads UI inside Bubble;
- open/share downloaded file through `FileProvider`/content URI, never raw file URI;
- explicit handling for blob/data downloads rather than silently failing.

Do not request broad storage permission if modern scoped storage / MediaStore / SAF can satisfy the feature.

## 15. Uploads and capture

Support `<input type=file>` via `WebChromeClient.onShowFileChooser` and Activity Result APIs.

Honor accepted MIME types and multiple-selection modes. Validate returned URIs before exposing them to WebView. Camera capture should be offered only after the normal Android runtime permission flow and explicit user action.

## 16. Web permissions

Camera, microphone and geolocation permission requests must:

- show the requesting origin;
- map web permissions to Android runtime permissions;
- never grant more resources than requested;
- default to deny if the origin/permission mapping is unclear;
- support per-site allow/block decisions where practical;
- allow users to clear site permissions.

## 17. Private browsing

Private browsing must use true WebView profile isolation when `WebViewFeature.MULTI_PROFILE` is supported.

Private rules:

- separate cookies/storage/history from the normal profile;
- no normal browsing-history entries;
- private heads are allowed while the private session exists;
- private heads are not durable across app process death unless a design can guarantee privacy semantics;
- on closing the last private tab, destroy live WebViews and delete/clear the private profile data according to supported APIs;
- if the installed WebView cannot provide required isolation, hide/disable private mode with an explanatory capability message rather than faking it.

## 18. Site compatibility

WebView configuration should behave like a browser, not a locked-down embedded document viewer, while retaining security boundaries.

Required capabilities include JavaScript, DOM storage, cookies, first-party session persistence, media, viewport behavior and safe new-window handling.

HTTP browsing must work. Do **not** globally disable cleartext traffic in a way that prevents the browser from visiting `http://` URLs. Instead, visibly mark insecure pages and keep HTTPS as the normal default/search path.

Mixed active content should remain blocked unless a narrowly justified compatibility setting is added later.

## 19. User settings

Settings should include:

### Browser
- search engine
- homepage/new-tab behavior
- default external-link mode
- default desktop/mobile mode
- JavaScript toggle only if exposed with strong warning; default on
- cookie/site-data controls
- clear browsing data
- download behavior/location

### Tabs and memory
- restore previous session
- warm-tab aggressiveness: Balanced / Keep more tabs live / Save memory
- auto-hibernate delay where applicable
- protect/pin tabs from normal LRU eviction preference

### Floating heads
- head size
- idle opacity
- free placement
- edge snap on/off
- magnetism strength
- stacking behavior
- tap action
- show favicon
- show loading indicator
- haptic feedback

### Appearance
- system/light/dark
- dynamic color optional
- toolbar position if implemented

## 20. Accessibility

- All browser controls and heads have meaningful TalkBack labels.
- Heads meet minimum touch target requirements even if the visual favicon is smaller.
- Dragging has an accessible alternate operation: Move head / Dock left / Dock right / Move up/down or equivalent.
- Do not rely on color alone for loading/security/private indicators.
- Browser UI supports font scaling and large display sizes.
- Predictive back and keyboard navigation work correctly.

## 21. Privacy / telemetry product policy

Default build should contain no advertising SDK and no behavioral analytics requirement.

If crash reporting or diagnostics are added, they must be optional/configurable, documented, and must not transmit page contents, URLs, form contents, browsing history or private-session metadata by default.

## 22. Out of scope for the first production release unless separately approved

- full Chrome-extension compatibility;
- cross-device sync backend;
- proprietary cloud account system;
- custom password vault;
- VPN;
- accessibility-service-based automation;
- circumventing Android overlay, background execution or security policies.

These can be separate projects/features rather than silently expanding the first release.
