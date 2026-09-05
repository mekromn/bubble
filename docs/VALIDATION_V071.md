# Bubble 0.7.1 — GitHub build and runtime validation

## Exact candidate

The September 5 retry successfully committed and built the previously saved edge-access source. Tested commit: `57c655c89494649f8fe7c3e59b13096064a220ea`, tree `b3e5e29f0c98c95316d9352c8a87662b2c1b5d17`, branch `rebuild-v2`, draft PR #12. The committed tree matches the saved Bubble-0.7.1-Edge-Access-Source-UNBUILT.zip candidate exactly. No implementation changes were required to complete this retry.

VersionName 0.7.1; versionCode 41; package `com.mekromn.bubble.debug`; ARM64. The established public DEVELOPMENT signing key is unchanged. It is not suitable for production signing. Compatible permanent-key debug installations can update in place.

GitHub Actions run `33995262749`, job `101384587094`, finished successfully. ARM64 artifact: `9977882487`. Runtime evidence artifact: `9978067645`.

APK delivered as `Bubble-0.7.1-Black-Glass-Edge-ARM64.apk`: 196,970,683 bytes.
APK SHA-256: `2b9b10646096d268430749556eec002fb41bf2f20d290025d81235fbf7a37329`.
Certificate SHA-256: `001a6f40ddcff14aec3aca71964fec58c29a32bdd0c649285bce60ae940c2b1f`.
Runtime-evidence ZIP SHA-256: `6dbdde0ef1bdefa3df37d91483d6ee2f598a470878877679d48f89f35055a630`.

Downloaded artifact digests and ZIP CRCs were checked. CI apksigner passed. Local verification independently checked the APK v2 RSA signature, complete v2 content digest and pinned certificate. Binary manifest inspection confirmed version/package, minSdk 26, targetSdk 36 and hardwareAccelerated=true. Packaged foreground/monitor assets match the source bytes.

## Observed tests

- All 18 JVM unit tests passed, with zero failures and zero ignored tests.
- Foreground compatibility and ChatGPT reply-monitor Node fixtures passed.
- Debug and release assembly, x86_64 instrumentation compilation and permanent-key verification passed.
- Android lint reported 0 errors and 36 warnings. This is NOT warnings-free lint.
- Android 16 instrumentation reported `OK (16 tests)` in 571.418 seconds.

The new EdgeAccessRuntimeTest used actual touch long presses on fullscreen and floating minimize controls, and actual SystemUI notification taps. It verified that notification hiding removes both bubble and edge, does not close the GeckoSession, and does not accidentally restore UI when settings are changed while parked. Right-edge and invisible left-edge inward swipes opened the chooser without fullscreen promotion. Cancelled and vertical gestures did not open a chat. Normal minimize returned to edge mode; the Use bubble instead recovery action was present. The final source and observed v071-results.txt agree on these assertions.

The other tests retained actual rendering/input, floating/fullscreen session transfer, Home/Back behavior, native PiP tab selection, drag-to-X parking, notification routing, dark preference, local toolkit persistence, quick menus and find, Activity recreation and black-glass material checks. The all-web test recorded both retained synthetic tabs reporting true for inline startup foreground state, current foreground state and cross-origin-frame foreground state while their timers advanced after notification parking. This is specific observed behavior, not a universal undetectability guarantee.

Signed-out Google and ChatGPT produced stable nonblank rendered documents with loading=false and contentful paint. No authenticated user account or private conversation was used in CI. Edge-chooser and hidden-notification screenshots were downloaded for inspection.

## How to use the new controls

Long-press the fullscreen floating control or the floating window's Minimize button to hide directly to the persistent notification. Normal tap behavior is unchanged. Allow notifications so there is a recovery route.

Browser menu > Bubble / edge access, or floating chooser > Edge access, enables the optional edge mode. Choose left/right, position, width/height and whether the grey marker is visible. Swipe inward for the chooser; hold for the selected chat. The short strip consumes touches only in its configured area and excludes Android Back there; that local gesture conflict is a real tradeoff. Explicit notification-only hiding removes the strip too. Edge mode is opt-in; installing the build does not enable it automatically.

Grey smoked-black-glass visuals and the earlier all-web compatibility, long-press Back/quick-tab menus, local names, pins, notes, prompt library, recent-close history and reply alerts remain included.

## Open gates and scope

The emulator used 60Hz SwiftShader. Its optional native-window frame diagnostic measured only 38 frames, p95 505.08ms and 38 deadline misses: these are poor timings, not a performance pass. Functional test success does not demonstrate 120fps or minimal jank on the physical Pixel. Physical GPU/compositor pacing, sustained memory/thermal behavior and authenticated ChatGPT concurrent-generation/completion-alert behavior remain unverified.

JavaScript foreground compatibility does not forge trusted user activation, override Android sleep/force-stop/reclamation, or guarantee that a site cannot detect background differences. Native input focus remains exclusive to the selected visible view.

This retry builds the saved 0.7.1 candidate. Browser downloads, full live-provider attachment verification, draft recovery, additional conversation-navigation/reading/export tools and other unimplemented roadmap suggestions are not completed by this build. Existing attachment-picker code is not an end-to-end certification of every provider. No private data, production signing secret or conversation text was added to GitHub.

Documentation-only commits after the tested commit do not change the APK identity above.
