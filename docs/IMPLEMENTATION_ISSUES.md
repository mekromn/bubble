# Bubble implementation issues — offline mirror

This file mirrors the implementation contract for GitHub issues #2 through #9. It exists so Codex can continue working when live GitHub access is unavailable. GitHub remains the publication/tracking surface; these phase contracts are sufficient for implementation.

## Issue #2 — Phase 0: verified Android project foundation and CI

### Scope
- Clean production Android project from `main`; provisional r01 scaffold is disposable.
- Kotlin + modern AndroidX; Material 3/Compose shell.
- Package/namespace: `com.mekromn.bubble`.
- `minSdk 26`, `targetSdk 36` unless evidence justifies a deliberate change.
- Java 17.
- Edge-to-edge and predictive-back-ready navigation.
- Dependency/version management suitable for maintained production code.
- CI for build, unit tests, lint/static analysis and debug APK artifact.
- Release/version conventions and Room schema export location.
- Do not globally block cleartext HTTP browsing.
- No production signing secrets in repo.

### Gate
Fresh checkout builds; CI produces installable debug APK; API 36 app launches to minimal shell; edge-to-edge and predictive-back foundation are correct; docs/build instructions are accurate. If environment cannot run these checks, implement all infrastructure and record them as deferred in `docs/VALIDATION_STATUS.md` rather than halting later phases.

## Issue #3 — Phase 1: durable browser session core and renderer recovery

### Scope
- Production WebView adapter/factory and BrowserActivity.
- Omnibox URL/search handling; back/forward/reload/stop; title/favicon/progress.
- UUID logical `TabId` model.
- Room database + migrations for tabs/session metadata.
- DataStore settings foundation.
- `TabSessionManager` owns authoritative transitions.
- Presentation state and renderer residency are separate axes.
- Active/warm renderer handling.
- Bounded WebView save/restore with AndroidX WebKit feature detection.
- `onRenderProcessGone` recovery.
- Activity recreation and process-death reconstruction.
- Safe WebView baseline.

### Gate
Multiple tabs create/switch/close/reorder without arbitrary cap; recreation and process restart preserve durable session without duplicates; renderer termination does not crash Bubble; WebView creation is centralized/testable; saved state is bounded; core transitions/recovery have tests.

## Issue #4 — Phase 2: independent draggable floating tab heads

### Scope
- Overlay permission explanation/request flow.
- One compliant `specialUse` foreground service for visible heads.
- Lightweight `TYPE_APPLICATION_OVERLAY` window per head.
- Favicon/fallback identity and applicable loading/private indicators.
- Free drag default with touch-slop tap/drag distinction.
- Resolution-independent normalized position persistence + inset/cutout clamping.
- Tap head restores exact `TabId`.
- Long press: Restore, Pin/Unpin, Duplicate, Share, Copy URL, Close.
- Drag-to-close target.
- Hide/show all heads without closing tabs.
- Correct service shutdown and overlay permission revocation handling.
- Restore works whether BrowserActivity is foreground/background.
- Do not force edge snapping or stacking.
- No WebView kept alive solely because tab is a head.

### Gate
Several simultaneous heads can be independently dragged; exact-tab restore is reliable; positions survive foreground/background and remap after display changes; underlying apps remain touchable outside head windows; permission denial/revocation does not lose tabs or crash; service lifecycle is compliant.

## Issue #5 — Phase 3: unlimited logical tabs with adaptive renderer hibernation

### Scope
- Adaptive `RendererPool` separate from logical tab count.
- Residency states: ACTIVE, WARM, SAVED, HIBERNATED, RECOVERING.
- LRU eviction based on memory class, pressure and last-used time.
- `onTrimMemory` integration.
- Bounded AndroidX WebKit state save when supported with safe fallback.
- App-private state blob per-tab/total budget.
- Durable canonical URL/title fallback.
- Pinned-tab preference without claiming process-death immunity.
- Restore/recovery for saved/hibernated tabs and heads.
- Large-session startup without WebView-per-tab.
- Incompatible saved-state recovery.
- No `MAX_TABS`, `MAX_HEADS` or hidden equivalent.

### Gate
Controlled 100+ logical tab test does not create proportional live WebViews; old tabs hibernate by policy; restore reconstructs correct logical session or canonical URL fallback; memory pressure preserves identity/order; no obvious renderer leaks; policy/recovery tests and performance measurements exist when executable.

## Issue #6 — Phase 4: production browser workflows and site compatibility

### Scope
- History, recently closed, bookmarks/favorites.
- Tab search/order/groups.
- Link context menu including `Open in floating tab`.
- `target=_blank` / `window.open()` become tracked logical tabs.
- File upload via `onShowFileChooser` + Activity Result APIs with URI validation.
- Camera/microphone/geolocation permission flows with origin display and least privilege.
- HTTP(S) and authenticated downloads; content disposition/naming; downloads UI; safe open/share.
- Explicit blob/data download strategy.
- Full-screen media; find in page; desktop mode; zoom.
- Share/copy URL.
- Safe external schemes (`mailto:`, `tel:`, `geo:`, `market:`, validated `intent:`).
- Incoming HTTP/HTTPS preference: Browser / Head / Ask.
- Share to Bubble / explicit open-as-head workflow.
- Android Autofill/password-manager compatibility.
- Print support where available.
- No TLS bypass, unrestricted JS bridge, unsafe file access, blanket permission grants or blind external intents.

### Gate
Common modern sites can navigate/authenticate/open windows/upload/request permissions/play fullscreen/download in supported cases without silent failure; new windows are tracked tabs; external link preference works; authenticated downloads preserve required context without leaking secrets; durable browsing data does not corrupt tab state; appropriate tests/manual compatibility evidence exist.

## Issue #7 — Phase 5: true private browsing and privacy controls

### Scope
- Feature-detect AndroidX WebKit `MULTI_PROFILE`.
- Separate normal/private WebView profile data.
- Private tabs/heads with unmistakable indicator.
- Private navigation never enters normal history/recently-closed/session tables.
- Destroy all private WebViews and delete/clear private profile data when last private tab closes, respecting profile lifecycle.
- Do not durably resurrect private heads after process death if privacy semantics would be violated.
- Clear browsing data UI for supported categories.
- Browsing-history controls.
- Explicit Android backup rules.
- Release logs exclude full URLs, cookies, auth headers, form contents and private activity.
- No mandatory behavioral analytics/ads.
- If true isolation unavailable, private mode is disabled/hidden with capability explanation; never fake it.

### Gate
Normal/private profiles do not share cookies/storage/history; closing final private tab clears private data correctly; private activity is absent from normal history/session/recently-closed; private heads work without durable privacy leaks; backup and privacy behavior have tests/runtime evidence where executable.

## Issue #8 — Phase 6: many-head UX, stacking, accessibility and adaptive layouts

### Scope
- Refined head visuals/animation/haptics without oversized cartoon styling.
- Optional intentional stacking via dwell/explicit action; never surprise auto-stack.
- Stack fan-out/chooser and removal.
- Optional edge magnetism/snap while free placement remains default.
- Settings for head size, opacity, snap, magnetism, stacking, haptics and tap behavior where implemented.
- In-app many-head manager with search/filter, Heads-only view, hide/show all and safe batch actions.
- Robust normalized placement across orientation, display size, insets and large screens.
- TalkBack labels/actions for every head.
- Accessible non-drag alternatives for moving/docking/closing.
- Minimum touch targets and non-color-only indicators.
- Font/display scaling and keyboard navigation.
- Adaptive phone/tablet/large-screen layout under Android 16 resizability.
- Final Material 3 shell polish.

### Gate
Many heads remain manageable without count cap; stacking is intentional and reversible; free placement is default; TalkBack can identify/restore/move/close; large font/display scale preserves controls; rotation/resizing never strands heads; drag remains performant; runtime visual evidence recorded when available.

## Issue #9 — Phase 7: production hardening, release validation and v1.0 candidate

### Scope
- Full CI/lint/unit/instrumentation pass.
- Device/API matrix with Pixel 9 Pro XL / Android 16 first-class when available.
- Process death, renderer death, low memory, rotation and overlay permission soak tests.
- Large-session performance and leak profiling.
- Head-drag frame pacing.
- Accessibility review.
- Full security review against repository specs.
- Exported components, PendingIntent flags, FGS declarations/notification behavior and backup rules.
- Verify no sensitive release logging.
- Verify normal/private isolation.
- Reproducible clean repository build.
- Protected release-signing procedure without committed keys.
- Release notes, known issues, exact source tag; APK/AAB where supported.
- Review current Play API-target and `specialUse` FGS requirements if Play distribution is pursued.

### Production blockers
Do not release with any known reproducible tab/session loss, wrong-tab head restore, unreachable head placement, renderer-exit app crash, TLS bypass, normal/private leakage, noncompliant persistent overlay service, critical accessibility dead end, or unreproducible repository build.

### Gate
All production blockers are cleared; CI green on release candidate; runtime evidence exists for overlay/session/memory flows; clean checkout is reproducibly buildable; version/tag/release notes identify exact source. This final gate cannot be marked passed merely because the coding sandbox lacked the required infrastructure.
