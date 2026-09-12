# Bubble 137: hardware preferences and native log elimination

## Baseline and scope

The user reported Build 136 works on the Pixel on 2026-09-12. Its exact source is `933c8fd043f8bc42af2337207f431e19d8ac133b`. This is user confirmation, not a retroactive pass for the failed instrumentation runner. Preserve that branch/artifact. Build 137 changes settings and logging, not engine code, scrolling algorithms, profiles, Voice behavior, transport selection or fidelity.

The APK retains the same application ID and existing development signing certificate for update compatibility. The performance variant is non-debuggable, R8-optimized and uses native `-O3`/ThinLTO. It is not a newly secured retail signing identity. No Chromium scrolling port is included.

## Hardware policy

`assets/gecko-hardware.yaml` is the complete list of 13 boolean preferences, not a generated wildcard over names containing "GPU". Hardware composition/WebRender, accelerated Canvas, WebGL and hardware MediaCodec video are explicitly enabled/preferred; Android WebGL AHardwareBuffer sharing is enabled. All 13 names were verified in the actual shipped engine binary. A present name is not proof of feature availability or physical GPU execution.

The bundled policy is atomically installed into app-private no-backup storage on an existing IO executor, before Workspace creates the Gecko runtime. The explicit `configFilePath` works in a non-debuggable build. No user-editable shared-storage override, environment injection, profile rewrite, remote debugger, periodic logging or per-frame preference polling is added. A preparation failure visibly reports use of the existing Gecko defaults rather than breaking browser startup. These startup preferences are not incorrectly set only after the renderer has initialized.

The options request acceleration, not impossible device capabilities. Software paths for unsupported operations/codecs and hard driver/platform failure handling remain. We do not assert that GPU-force preferences preserve behavior on every driver. The Android test must read the effective Gecko values as a separate check; pixel/input tests and actual device feedback remain separate from preference readback.

No blanket native-compositor, ANGLE, DirectComposition, Windows media, software-VSYNC, unsafe driver-threading, front-buffer, sandbox or process-isolation override is applied. In the inspected Firefox 154 source, a positive `layout.frame_rate` explicitly selects software VSYNC: setting it to 120 would not force hardware VSYNC. All existing Android Surface/window maximum-refresh requests remain unchanged.

Android hardware-accelerated application rendering was already enabled. `RenderPolicy.vote` now also ensures the hardware flag for each supplied window before any refresh-rate early return. There are no forced software View layers. We do not add hardware texture layers to every View: those are offscreen caches, not a general speed switch.

## Logging: exact completion boundary

The native bridge's first-submission counter/log and error/retirement formatting are compiled out by a preprocessing macro, including argument evaluation. A separate diagnostic compile option remains OFF. The production bridge no longer directly links `liblog` or imports `__android_log_*`; the packaged ELF audit enforces both. Ownership/error counts required by the existing lifetime tests are retained; they are not disk/network logs.

The remaining unconditional Java logs in the floating host are replaced by guarded diagnostics; the logger's installation is guarded too. Gecko's `debugLogging(false)`, `consoleOutput(false)` and `remoteDebuggingEnabled(false)` are explicit. Mozilla documents that debugLogging does not control every Gecko log source. Thus the correct claim is no compiled native-relay logging plus disabled application diagnostic work, not zero logging instructions throughout Android or the unchanged third-party engine.

## Verification

Host tests run actual production relay code against deterministic Android API stubs with ASan/UBSan. They check exact fence forwarding, six outstanding leases, capacity recovery, transaction/slot reuse, overlapping callbacks and wakeup races. A new assertion verifies a disabled native log's argument has no side effects.

The packaged APK audit checks the native log boundary, exact policy, all 13 preference names in libxul, and unchanged byte hashes for libxul, omni.ja and all 12 built-in chat-monitor files against Build 136. Manifest/debug/signing/native-optimization gates also run. None is a speed measurement.

The same optimized x86_64 variant is built for Android 16 correctness tests: actual page pixels, touch/typing, cold floating tab, repeated switches, resize, fullscreen return, lease cleanup, hardware-window flags, and effective Gecko preference readback. The test-runner Kotlin shared ABI is retained to address Build 136's pre-test `kotlin.jvm.internal.Lambda` error. Reduced emulator resolution affects only CI, not the APK or Pixel quality. Consult run evidence for actual pass/fail, not this test list.

## Additional intermediaries: staged roadmap, NOT shipped changes

1. **ImageReader consumer/worker handoff:** a custom Gecko render-compositor can render into an owned AHardwareBuffer-backed target and submit it directly to SurfaceControl. A release-safe reusable pool replaces reader acquisition/listener/eventfd/worker handling; image storage, synchronization and system presentation still exist. Buffer-pool sizes need correctness and frame-age evidence. Backpressure-on behavior can remain, but this is a new complete pipeline, not an identical relay preset.
2. **Separate page window choreography:** explore parenting the page/input host within the existing Bubble window to remove a separate ViewRoot/WindowManager surface lifecycle and coordinate geometry. Preserve correct focus, IME, touch regions, accessibility, glass, clipping, scaling and z-order. An extra root that is idle is not automatically an extra per-frame copy.
3. **Redundant release-driven acquisition passes:** narrow worker wakeups to outstanding work/capacity recovery only with an explicit race-safe protocol. Dropping a wake without proving progress can stall the current renderer. This is a targeted future change, not a reason to remove release fences or callbacks.
4. **Unnecessary scene/raster/resource work:** retain APZ asynchronous scrolling, cache compatible content and propagate true accumulated damage. Skipped frames require union/history-aware damage relative to last displayed content. Required new layout/paint, text shaping, filters, color conversion and alpha composition are not disposable overhead.
5. **WebGL/video CPU staging:** keep hardware images GPU-shareable to WebRender where backend/format/capabilities support it. The new AHB sharing preference requests an existing engine route, but actual uses must be observed. A video frame that needs filtering/color/composition may still require GPU processing. This is not a universal bypass for ordinary chat DOM rendering.
6. **Producer/consumer scheduling distance:** reduce needless queued frame age and redundant task hops without adding another VSYNC wait or turning display synchronization off. Requires correlating source work with presentation, not rAF counts. Chromium input/scroll sampling research belongs to the separately pinned engine port.

Do not remove SurfaceFlinger/HWC mediation in an ordinary app, remove buffer fences, recycle a still-read buffer, disable browser sandboxes/process isolation, drop semantic input events, lower resolution/AA, or disable requested visual features. Genuine front-buffering is separate experimental research with tearing/scanout risks, not a guaranteed functionality-preserving optimization.

## Primary implementation references

- Gecko configuration: https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/docs/automation.html
- Gecko runtime initialization: https://github.com/mozilla-firefox/firefox/blob/032a9fc1ac0cc3209f7c142744ba2e40847c8086/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/GeckoRuntime.java
- WebRender feature gating: https://github.com/mozilla-firefox/firefox/blob/032a9fc1ac0cc3209f7c142744ba2e40847c8086/gfx/config/gfxConfigManager.cpp
- Android codec/WebGL and VSYNC setup: https://github.com/mozilla-firefox/firefox/blob/032a9fc1ac0cc3209f7c142744ba2e40847c8086/gfx/thebes/gfxPlatform.cpp
- Android hardware acceleration: https://developer.android.com/develop/ui/views/graphics/hardware-accel
- APZ architecture: https://firefox-source-docs.mozilla.org/gfx/AsyncPanZoom.html

The Firefox release source above is an inspected version reference; it is not asserted to be the exact Git mapping of the embedded HG revision. The shipped engine is separately byte-pinned to Build 136.
