# Bubble 0.5 — native clean rebuild

`rebuild-v2` replaces the failed application source tree. The old implementation remains in Git history and on `implementation-v1`, but no old application source is compiled here.

## Architecture

- Kotlin with native Android Views. One Activity-owned GeckoView is independent from the animated/recycled tab tray and controls.
- One lazily created GeckoRuntime in the main application process. There is no custom Application initializer to run again in Gecko child processes.
- A new Workspace owns durable UUID tabs and GeckoSessions. Switching tabs/recreating an Activity does not recreate sessions. ChatGPT sessions are kept active and high-priority while retained, including when collapsed.
- One explicit foreground service owns one non-focusable workspace bubble. The Activity moves behind other apps only after an explicit user action and a successful overlay attachment acknowledgement. A tap opens the selected chat; long press opens the workspace tray. Dragging is free-positioned, frame-coalesced and persisted after release.
- Versioned AtomicFile workspace snapshots run on one ordered IO worker. Old Room workspace files are preserved, not silently deleted or imported. Corrupt new snapshots are preserved rather than overwritten by a fresh empty workspace.
- A built-in extension is restricted to the exact ChatGPT HTTPS origin. It sends coarse reply lifecycle events only, never conversation text or account data. Completion notifications are generic and deep-link to a durable tab ID. This DOM-based detector requires live-site validation and may prefer missing a signal over false notification.
- No TLS bypass, automatic permission grants, analytics SDK or remote debugging endpoint is added.

## Performance contract

Native animations use display-synchronized property animation; no 60fps timer drives animation and the browser surface is never scaled for a tab-tray transition. Bubble requests the highest supported display mode at the current resolution. Android retains authority over refresh rate and process lifetime. Neither this request nor an emulator benchmark proves 120fps or zero jank on a physical Pixel.

The optional local frame meter reports native-window deadlines and p95 frame duration, not Gecko compositor FPS. It has no network output and is stopped when the Activity stops.

## Validation

The pipeline builds ARM64 and x86_64 from the same source/engine version. Android 16 instrumentation checks actual compositor pixels, JavaScript progress, sustained Activity lifetime, multiple sessions, tab-tray interaction, Activity recreation and public-site painting. A painted ChatGPT challenge is not counted as proof of a usable authenticated ChatGPT session. Review the captured titles/screenshots and test report.

The initial rewrite does not claim full feature parity with every old browser option. Voice/media grants, downloads, named workspace export/import and production release signing need separate implementation/validation. Existing app-private Gecko profile storage is retained, but prior login restoration is not assumed proven.

## Build and signing

JDK 17, Gradle 9.5.0, AGP 9.3.0, Kotlin 2.4.10; compile SDK 37.1, target SDK 36, min 26. `gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Set `-PgeckoAbi=x86_64` for emulator builds.

Test package: `com.mekromn.bubble.debug`, versionCode 20. The original public TEST keystore is unchanged so compatible previous debug builds can update without uninstalling. Never use this public development key to sign a production/release package. The release target remains separately unsigned.

See `AGENTS.md` and `docs/REBUILD_V2.md`. Legacy spec files are product/history references, not instructions to restore the old runtime.
