# Bubble 0.6.1 — live ChatGPT floating workspace

Tap one draggable bubble to choose among independent conversation tabs, then chat in an interactive floating browser. Fullscreen is explicit. Home and system Back minimize to the bubble after transient UI, with overlay permission granted. Native Android picture-in-picture is separately available as a view-only mode with tab controls.

Drag the bubble to the bottom × target to hide it in a persistent notification without closing tabs. Tap Show bubble to restore its previous resting position. The target does not intercept the screen; hiding requires a usable notification restore route. Stop service is separate from hiding.

## Motion and rendering

Anchored reveal/conceal replaces stretching a webpage into a circle. Chooser/chat resting position is stable; keyboard resizing is temporary. Row insertion/removal, controls, press feedback and new unread state receive short animations without perpetual idle effects. Fullscreen uses Gecko SurfaceView; only the clipped/animated floating browser uses TextureView. High refresh is requested using supported display modes and view votes. Sustained 120fps/zero jank is NOT yet verified on a physical device.

## Session ownership and privacy

One lazy main-process GeckoRuntime owns independent GeckoSessions with durable UUID tabs. Each session has one display owner at a time, either the fullscreen Activity or a bounded floating WindowContext. There is no custom Application browser bootstrap in Gecko child processes. Open workspace sessions remain active/high-priority while retained, but Android can still kill/restrict processes.

Workspace snapshots use ordered off-main-thread AtomicFile writes. The exact-origin ChatGPT extension sends only lifecycle events and random run IDs to native completion notifications, never prompt/answer text. Reply taps open the corresponding floating chat. Completion detection is DOM-based and requires authenticated live-site testing; it is not server push after force-stop. No TLS bypass or universal JavaScript bridge is added.

## Verified build

Source `fd7255e4c0baa5f79a08d04ed9a4b09df442c8b7`; GitHub Actions run `33981459029`; all 11 Android 16 emulator tests, 9 JVM tests and foreground/reply-script fixtures passed. Debug/release assembly and lint passed (0 errors, 26 warnings). Actual drag, notification tap, restoration, floating input and nonblank compositor output were exercised.

APK version 0.6.1 / code 31 / package `com.mekromn.bubble.debug` retains the existing permanent public DEBUG key. Compatible earlier permanent-key debug versions update without uninstalling. This key must not be used for production release signing. APK SHA-256: `8a2223aa225b21903a4d72391fe91be48a234e02739934f3f6bacdfe8d60192f`.

Read `docs/VALIDATION_STATUS.md` for exact artifacts, observed results, poor software-emulator frame diagnostics and open physical-device/performance/product gates. Green functional CI does not certify 120fps or authenticated ChatGPT notifications.

## Build

JDK 17; Gradle 9.5.0; AGP 9.3.0; Kotlin 2.4.10; compile SDK 37.1; target SDK 36; min SDK 26.

`gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`

Set `-PgeckoAbi=x86_64` for emulator builds; default is arm64-v8a. The failed legacy implementation stays recoverable in Git history/on `implementation-v1`, not compiled here. Old Room files are preserved but not imported. Full browser feature parity, voice/downloads and production release signing remain separate work.

Implementation contracts: `AGENTS.md`, `docs/FLOATING_V060.md`, `docs/MOTION_AND_ALERTS_V061.md`.
