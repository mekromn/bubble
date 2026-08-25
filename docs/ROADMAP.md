# Bubble — Implementation Roadmap

The end goal is a production browser, but implementation should land in verifiable slices.

## Phase 0 — Specification and project foundation

- authoritative docs committed
- Gradle/Android versions verified against real current stable tooling
- project builds cleanly from fresh checkout
- CI produces debug APK
- package/versioning conventions finalized

Gate: clean CI from a fresh repository checkout.

## Phase 1 — Browser/session core

- BrowserActivity shell
- WebView engine factory/adapter
- omnibox/navigation
- tab UUID model
- Room persistence
- active/warm/saved lifecycle
- renderer-gone recovery
- process/session restore
- favicon/title/progress

Gate: multiple tabs survive Activity recreation/process restart with no heads yet.

## Phase 2 — Floating heads

- overlay permission flow
- specialUse foreground service
- independent overlay windows
- free drag
- normalized placement persistence
- tap restore
- long-press actions
- drag-to-close
- hide/show heads
- correct service teardown on last head

Gate: repeated minimize/drag/restore cycles work across other apps and rotation.

## Phase 3 — Unlimited logical tab memory architecture

- adaptive renderer pool
- bounded WebView state snapshots
- disk hibernation/fallback metadata
- LRU/memory pressure policy
- pinned-tab preference
- large-session performance tests

Gate: 100+ logical tabs can exist without an artificial limit or proportional live-WebView count.

## Phase 4 — Browser completeness

- history
- bookmarks
- recently closed
- tab search/groups
- link context menu
- popup/new-window handling
- file upload
- web permissions
- downloads including authenticated HTTP(S)
- full-screen media
- find in page
- desktop mode/zoom
- external schemes
- share/open-as-head workflows

Gate: common modern websites and browser workflows function without silent failures.

## Phase 5 — Private browsing and privacy controls

- feature-detect WebView multi-profile
- normal/private isolation
- private UI/heads
- private cleanup
- clear browsing data
- history/privacy settings
- backup rules

Gate: automated/manual proof that private profile does not share normal cookies/storage/history.

## Phase 6 — Many-head UX and polish

- optional stacking
- fan-out/chooser
- edge magnetism settings
- animations/haptics
- accessibility move actions
- head manager filters/search
- visual polish
- adaptive tablet layouts

Gate: usability remains good with many heads and accessibility services.

## Phase 7 — Production hardening

- full API/device matrix
- macro/performance profiling
- leak detection
- crash recovery soak testing
- security review
- accessibility review
- Play FGS/data-safety review if publishing
- release signing pipeline
- release notes/known issues

Gate: all `docs/TEST_RELEASE.md` production blockers cleared.

## Phase 8 — v1.0

Tag only after production gates pass. Do not rename a partially implemented milestone as “1.0” merely because core heads work.

## Post-1.0 candidates

- expanded floating browser cards
- content blocking / tracker protection
- sync via user-selected backend
- richer tab-group workspaces
- alternate rendering engine experiment
- import/export bookmarks/session
- PWA/app-mode enhancements
