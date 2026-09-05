# Bubble 0.6.1 — verified development candidate

## Exact build and artifact identity

Tested source commit: `fd7255e4c0baa5f79a08d04ed9a4b09df442c8b7` on `rebuild-v2`, draft PR #12. VersionName 0.6.1; versionCode 31; package `com.mekromn.bubble.debug`; ARM64 phone APK; minSdk 26; targetSdk 36.

GitHub Actions run `33981459029`, job `101347309505`, completed successfully on September 5, 2026. The run passed Node foreground/reply-monitor fixtures, 9 JVM unit tests (zero failures or skips), debug and release assembly, Android lint (0 errors, 26 warnings), permanent-key verification, x86_64 test compilation and all 11 Android 16 emulator instrumentation tests. Instrumentation reported `OK (11 tests)` in 310.421 seconds. Warnings are not represented as a warnings-free lint result.

ARM64 artifact: `9973898676`. Runtime-evidence artifact: `9974026283`. Runtime ZIP SHA-256: `87b21de0bcc972265147d04eefbcfb809db76fccec56057e6f5501d78e9eba39`.

APK: `Bubble-0.6.1-Hide-Restore-Motion-ARM64.apk`; 196,904,663 bytes.
APK SHA-256: `8a2223aa225b21903a4d72391fe91be48a234e02739934f3f6bacdfe8d60192f`.
Certificate SHA-256: `001a6f40ddcff14aec3aca71964fec58c29a32bdd0c649285bce60ae940c2b1f`.

Downloaded ZIP checksums/CRCs, APK checksum and embedded certificate identity were inspected. CI apksigner verified the APK. Binary-manifest inspection confirmed package/version, SDK levels and application hardwareAcceleration=true. The final APK is byte-identical to the preceding `2acacb0` application build because the last commit changes only the instrumentation harness. The permanent debug certificate has NOT changed. Compatible 0.6.0/debug installs can update without uninstalling. This intentionally public development key is not production signing.

## User-requested behavior implemented

Drag the bubble to the bottom circular × target to hide it in a persistent notification, not close the chats. The target appears only during dragging and is a small non-touchable overlay. Capture has radial hysteresis and an entry haptic. A cancelled drag does not hide. The restore notification is published before removing the windows; the original resting location and GeckoSessions survive. Hiding is refused when the notification route is blocked, so the user is not stranded. The notification provides Show bubble and Stop service and displays tab/generating/unread counts.

Opening is an anchored hardware circular reveal from the actual bubble; closing conceals to a circle, then moves only the small bubble to its resting position. The page is not stretched nonuniformly. Chooser and chat share stable resting bounds; keyboard accommodation is temporary. Structural list changes, control-sheet entry/exit, button feedback and unread acknowledgement use bounded motion rather than idle animation loops.

ChatGPT lifecycle monitoring handles observed completion, same-node regeneration, cancellation, errors, history and BFCache restoration. It emits only a random run ID and a coarse lifecycle event through the exact-origin bridge, not conversation text. The existing audible ChatGPT replies channel and user preferences are retained. Multiple replies group without an extra summary sound; tapping a reply restores its exact floating conversation. The floating chooser includes notification sound settings.

Fullscreen Gecko retains SurfaceView. The interactive/clipped floating Gecko uses TextureView. Actual supported display mode/rate and API 35 view frame-rate votes are applied to the floating view and drag target. No software-rendering fallback, permanent webpage bitmap cache or quality-reducing rendering switch was introduced.

## Observed Android runtime tests

- BrowserInputTest: real touch and typing into an HTML input; IME Back; native tab chooser Back; window frame diagnostics.
- BrowserSmokeTest: contentful paint, advancing JavaScript, sustained Activity lifetime and distinct captured compositor pixels.
- FloatingWorkspaceTest (three tests): tapping bubble opens chooser without promoting fullscreen; selecting a tab renders and accepts keyboard input in the floating window; explicit fullscreen and system Back preserve the same session; two background tabs remain visible to Gecko and their timers continue after Home; native Android PiP enters and Previous/Next controls select the original sessions. The floating view reports hardwareAcceleration=true. This reports the tested API/session behavior, not a screenshot-derived claim about transition smoothness.
- ParkedWorkspaceTest: actual drag cancellation restores placement; actual drag-to-× removes floating UI; an actual SystemUI notification tap restores original bubble placement and the same GeckoSession; a reply-notification tap opens the exact floating session and clears unread. The restored view returns actual nonblank Gecko compositor pixels. The notification layer is invoked with synthetic state here; this is not an authenticated ChatGPT generation.
- RebuildRegressionTest (three tests): continuous metadata updates still persist without waiting for idle; actual webpage dark-color preference plus malformed-session fallback; reversing native tray motion neither hides it incorrectly nor retains its temporary hardware layer.
- WorkspaceRuntimeTest (two tests): repeated tab switching and Activity recreation preserve independent sessions and actual rendered pixels; Google and signed-out ChatGPT produce stable nonblank documents.

The inspected public ChatGPT screenshot shows the actual dark homepage, composer and Log in control, not a challenge page. The report records both public sites with loading=false, FCP=true and nonblankStable=true. CI used only synthetic local pages and signed-out public sites, with no user credentials or private conversation contents.

Node fixtures separately verify history/no replay, completion, same-node regeneration, stop, missing positive end markers, error, old action buttons, conversation navigation, BFCache reconnect, deduplication and exact-origin/element-event isolation. Those fixtures do not certify current signed-in website selectors.

## Failures fixed during verification

The prior run exposed a real native tab-sheet Back/IME priority problem. The visible sheet now owns an overlay-priority Back callback, dismisses its own search keyboard first and releases priority when hidden. The unchanged Back behavior assertion passes.

The last remaining test failure was ActivityScenario filtering out a real RESUMED event after fullscreen restoration changed the Activity Intent. Logs showed RESTARTED/STARTED/RESUMED and explicitly reported an Intent mismatch. The corrected test uses the same component-only launch as restoration, then navigates its synthetic fixture after launch. All lifecycle, input, session and rendering assertions remain. This was a test-only correction; it did not change the APK.

## Performance gate: NOT PASSED

The final run's optional native frame diagnostic reports a 60.0Hz SwiftShader software-rendered emulator, 45 native frames, recent p95 434.66ms, 45/45 deadline misses and no lost callbacks. These are poor timings, not a performance success. They cannot establish physical GPU performance or a speedup. A functional green CI does not waive the performance gate.

The code requests high refresh and uses hardware-capable rendering paths, but sustained 120fps, minimal jank on the Pixel 9 Pro XL, native GPU/compositor frame pacing and long-duration thermal/memory behavior remain unmeasured. The local meter measures native-window duration, not webpage FPS. Do not advertise certified 120fps or zero jank.

## Remaining device/product gates

Authenticated ChatGPT concurrent generations and completion-alert behavior require live-device testing. DOM-based monitoring may miss future or unsupported page states; it is not server push after force-stop. Android can reclaim processes or restrict execution. Parking preserves resident sessions but cannot guarantee survival after force-stop or memory reclamation.

Provider-specific file uploads, microphone/camera permission flows, downloads, legacy Room import and old cookie-profile migration are not certified by these tests. Old workspace database files remain preserved. Release signing remains separate. This is a functionally runtime-tested development build, not a production-complete browser.

Documentation-only commits after the tested source commit do not change the APK identity above.
