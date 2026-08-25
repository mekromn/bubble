# Bubble — Production Architecture

## 1. Core invariant

**A tab is not a WebView.**

The durable model is the source of truth. WebView is a replaceable renderer/session surface attached to a logical tab when resource policy allows.

## 2. Recommended stack

- Kotlin
- Coroutines + Flow
- Jetpack Compose / Material 3 for browser chrome and management UI
- a lifecycle-controlled native View/WebView host embedded into Compose without recreating the WebView on recomposition
- AndroidX WebKit stable
- Room for durable browser/session metadata
- DataStore for settings
- Activity Result APIs for permission/file flows
- `WindowManager.TYPE_APPLICATION_OVERLAY` for heads
- one foreground overlay service while one or more visible heads exist
- WorkManager only for deferrable maintenance/cleanup, not for interactive heads

Dependency injection may use Hilt if it materially improves testability. Avoid architecture ceremony that does not improve correctness.

## 3. Module/package boundaries

A single app module is acceptable initially if package boundaries remain clean. Suggested packages:

- `browser/engine` — WebView creation/configuration/session adapter
- `browser/session` — logical tab model, lifecycle state machine, renderer pool
- `browser/navigation` — URL normalization, search, external schemes, popup handling
- `browser/downloads`
- `browser/permissions`
- `browser/private`
- `data/db`
- `data/settings`
- `data/state`
- `heads/service`
- `heads/overlay`
- `heads/model`
- `ui/browser`
- `ui/tabs`
- `ui/settings`
- `ui/common`

## 4. Logical tab model

Representative fields:

```text
TabId: UUID
profileId: normal/private profile identity
createdAt
lastActivatedAt
lastCommittedUrl
title
faviconKey
presentationState: BROWSER | HEAD
residencyState: ACTIVE | WARM | SAVED | HIBERNATED | RECOVERING
pinned
private
userAgentMode
zoom
headPlacementId?
groupId?
restoreStateKey?
crashRecoveryCount
```

Do not persist transient WebView references or Android View objects.

## 5. Session controller

Create one authoritative `TabSessionManager` with serialized state transitions on the main thread where WebView operations are involved.

Responsibilities:

- create/close/duplicate tabs;
- switch active tab;
- minimize/restore;
- manage presentation state;
- request/release renderer sessions;
- persist metadata;
- feed tab/head UI state as Flows;
- respond to memory pressure;
- recover renderer crashes;
- ensure exactly one selected browser tab per browser window.

Avoid scattered direct Room writes from UI and overlay code.

## 6. Browser engine abstraction

Define an internal interface such as `BrowserEngineSession` around WebView operations:

- load URL
- reload/stop
- back/forward
- title/favicon/progress events
- navigation events
- save/restore bounded state
- find in page
- desktop UA mode
- permission callbacks
- file chooser
- full-screen media
- popup/new-window callback
- renderer-gone callback
- destroy

The first engine is Android System WebView. Keeping an adapter boundary prevents the entire app from being coupled to raw WebView APIs and leaves room for a future GeckoView engine without redesigning heads/tabs/data.

## 7. WebView creation

Centralize all WebView creation in a factory. The UI must not construct WebViews ad hoc.

Factory responsibilities:

- choose correct profile before navigation;
- apply common safe settings;
- attach WebViewClient/WebChromeClient/DownloadListener;
- attach renderer termination handling;
- attach navigation listeners when feature-supported;
- attach Safe Browsing policy;
- set renderer priority policy only if corresponding termination handling is active;
- configure Autofill and accessibility;
- never use unrestricted `addJavascriptInterface`.

Consider AndroidX WebKit async WebView startup to reduce first-tab latency.

## 8. Renderer pool

`RendererPool` owns live engine sessions.

It should have:

- one active renderer;
- adaptive number of warm renderers;
- LRU eviction;
- memory-pressure hooks;
- pinned/media-aware priority hints;
- no fixed relationship between logical tab count and renderer count.

Initial default may be a small warm set determined by memory class, but it must be policy-driven and adjustable after measurement.

## 9. State save/restore

Use a tiered strategy:

### Tier A — live WebView
Fastest, exact live page state.

### Tier B — bounded WebView saved state
On supported implementations, use `WebViewCompat.saveState(webView, bundle, maxSizeBytes, includeForwardState)` after checking `WebViewFeature.SAVE_STATE`.

Store state outside Activity saved-instance state when representing many tabs. Limit per-tab and total snapshot budgets.

### Tier C — durable browser metadata
Always persist canonical URL, title, timestamps and tab ordering. This is the guaranteed process-death fallback.

Do not assume WebView state serialization is indefinitely stable across WebView version changes. Detect restore failure and fall back to URL.

## 10. Head overlay architecture

### Service

`FloatingHeadService` exists only while visible overlay heads exist (or while performing a tightly bounded transition). It owns overlay controllers, not browser WebViews.

Use `foregroundServiceType="specialUse"` and the required service property explaining the floating-browser-head use case. Maintain a foreground notification while required.

Start the service from a user-visible foreground flow when the first tab is minimized. Do not rely on background starts that violate Android 15+ restrictions.

### Overlay windows

Each independently draggable head should use a small `TYPE_APPLICATION_OVERLAY` window so touches outside the head continue to reach the underlying app. Do not use a giant full-screen transparent touch window; Android blocks unsafe pass-through touches and it would degrade interaction with other apps.

Window flags should keep normal head mode non-focusable and non-modal. Expanded interactive overlays, if added later, require a separate carefully tested focus/IME strategy.

### Head controller

One controller per visible head owns:

- overlay View/ComposeView;
- WindowManager layout params;
- drag gesture state;
- normalized placement;
- visual state;
- click/long-click callbacks.

The service retains controllers in a map keyed by `TabId`.

## 11. Head persistence

Store placement separately from tab metadata:

```text
HeadPlacement
- tabId
- normalizedX 0..1
- normalizedY 0..1
- lastDisplayId
- preferredEdge nullable
- stackId nullable
- updatedAt
```

At render time, transform normalized placement into current safe bounds and clamp for insets/cutouts.

## 12. Database

Use Room migrations from day one.

Never use destructive migration in production browser data unless explicitly gated for development.

Key tables:

- tabs
- tab_groups
- closed_tabs
- head_placements
- history
- bookmarks
- downloads
- site_permission_decisions

Indexes should cover `lastActivatedAt`, tab order, history URL/time and bookmark URL.

## 13. Settings

Use DataStore with typed keys/model for:

- search engine
- theme
- restore session
- external-link mode
- memory mode
- head size
- snap behavior
- opacity
- stacking
- haptics
- privacy/history controls

## 14. Private profile architecture

Use AndroidX WebKit multi-profile APIs only when `WebViewFeature.MULTI_PROFILE` is supported.

Normal and private WebViews must never share a profile.

Private state must not be inserted into normal history/closed-tab persistence. Keep only the minimum ephemeral metadata needed to render active private UI/heads. Clear/delete private profile data after all private WebViews are destroyed and the last private tab closes, respecting API lifecycle restrictions.

## 15. Popup/new-window architecture

`WebChromeClient.onCreateWindow` must delegate to `TabSessionManager.createTab()` and provide the resulting engine session/WebView to the transport rather than creating an untracked WebView.

This preserves tab identity, head support and cleanup.

## 16. Navigation architecture

`NavigationPolicy` classifies navigations:

- http/https → WebView
- search text → configured search URL
- mailto/tel/sms/geo/market → validated external intent
- intent: → parse safely, validate package/fallback and require appropriate user gesture/confirmation
- file/content/javascript → block unless explicitly generated/owned by Bubble for a safe internal purpose
- unknown schemes → do not launch blindly; present safe external-open option when resolvable

## 17. Download architecture

Keep downloads outside the WebView renderer lifecycle. A minimized or hibernated tab’s initiated download must continue independently through the chosen Android download mechanism.

Capture cookies/referer/user agent needed for authenticated HTTP(S) downloads.

## 18. Activity navigation

Single `BrowserActivity` is preferred for v1. Use Android predictive-back APIs rather than legacy `onBackPressed` assumptions when targeting API 36.

Back priority:

1. dismiss transient UI/menu;
2. close tab switcher/settings destination as appropriate;
3. page `goBack()` if current tab has WebView history;
4. otherwise follow browser-level navigation/exit policy.

## 19. Threading

All WebView interactions occur on the WebView/UI thread.

Room and disk operations use appropriate Dispatchers.

State transitions that affect one tab must be serialized to prevent minimize/restore/close races.

## 20. Race conditions to test explicitly

- user taps head while renderer is being evicted;
- user closes a tab while favicon callback arrives;
- overlay permission revoked while dragging;
- Activity is recreated while a head restore intent is in flight;
- two external links arrive rapidly;
- popup opens during source-tab minimization;
- renderer dies during saveState;
- process is killed after DB update but before overlay controller update;
- orientation changes during head drag;
- user closes last head while foreground service is transitioning.

## 21. Source-of-truth rule

Room/durable session model is authoritative for tab existence. Overlay windows and WebViews are projections/caches. If projections disagree after recovery, rebuild them from durable session state rather than inventing new tab IDs.
