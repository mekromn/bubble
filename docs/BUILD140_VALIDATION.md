# Bubble 140 — final page-input validation

## Artifact and baseline

- Compiled source: `eb3b4ea449e6fd6e12df6da140d1821c8263cf1c`.
- Branch: `relay-latest-bp-fast140`.
- Version: `0.7.12-relay-bp-input`, versionCode 140.
- APK: `Bubble-140-relay-latest-bp-INPUT-ARM64.apk`.
- APK SHA-256: `5eaad16ee90efea7dc4dc38652ac3d5ba6a03cf38f3c66e7dbfd522b6309d009`.
- APK size: 193,732,975 bytes.
- Package: `com.mekromn.bubble.debug`; non-debuggable, shell-profileable, hardware-accelerated. The historical suffix is update identity, not debug execution.
- Signing certificate SHA-256: `001a6f40ddcff14aec3aca71964fec58c29a32bdd0c649285bce60ae940c2b1f`, unchanged from working 139.
- Release: `bubble-fast-140-34752603401-1`.
- ARM64 artifact: `10316741877`; verification artifact: `10316712349`.

The user confirmed 139 works on September 13, 2026. Its source, branch and APK remain preserved. 140 installs as an update without uninstalling or clearing the existing profile.

## Implemented scope

Both real webpage input hosts now request `View.requestUnbufferedDispatch(MotionEvent)` on the initial touchscreen/stylus ACTION_DOWN with a session and attached View. Fullscreen forwards the same event to GeckoView.onTouchEvent; floating forwards the same event to its existing PanZoomController. The helper does not copy, retain, synthesize, retimestamp, split or consume events. No production input counter, log, queue, timer or per-frame callback is added.

The request is per gesture, not a persistent registration of input sources. Other gesture phases make no new request. Mouse, touchpad, keyboard, wheel and gamepad handling are unchanged; native chrome is not separately opted in. Android still owns dispatch, gesture termination and its internal buffering decisions.

This is an input-delivery policy candidate, not a new fling algorithm or proof of lower end-to-end latency. Removing intentional View-frame batching may allow earlier engine input but can increase callbacks/CPU work and alter resampling. Android's API documentation cautions that unbuffered dispatch can worsen latency or scrolling jitter for unsuitable workloads. No unconditional fastest-path claim is made.

## Final workflow: PASS

Run `34752603401`, job `103711542215`, passed source/unchanged-renderer guards, native ASan/UBSan ownership and progress stress tests, optimized ARM64 compilation, all 57 JVM tests (zero failures and zero skipped), lint, signing/manifest/native optimization checks, packaged hardware and bytecode audits, optimized x86_64 instrumentation compilation, and the complete extended Android 16 browser integration. The release was published only after runtime success.

Runtime output: `OK (1 test)`. JUnit reports 79.415 seconds for the entire integration method, NOT input latency or performance.

The additional real Android MotionEvent tests ran in BOTH attached page hosts. Each checked a finger drag and endpoint, explicit cancellation without click, two active pointers, a fresh tap after cancellation/multitouch, stylus type/pressure, event order and nondecreasing DOM timestamps, and an actual visible page-color response. Recorded final DOM counters:

| Observation | Fullscreen | Floating |
| --- | ---: | ---: |
| Pointer downs | 6 | 6 |
| Pointer ups | 5 | 5 |
| Pointer cancellations | 1 | 1 |
| Active pointers remaining | 0 | 0 |
| Peak active pointers | 2 | 2 |
| Detected order/timestamp errors | 0 | 0 |
| Pointer moves observed | 24 | 22 |
| Maximum reported pressure, scaled by 1000 | 700 | 700 |

Move counts are observations, not throughput scores or proof that Android never coalesces events. The two-pointer fixture uses touch-action:none to test DOM input fidelity; it is NOT a pinch-zoom or Chromium fling-parity test. Input injection is not physical touchscreen sensing. The suite does not inspect private ViewRoot flags or claim input-to-photon timing.

All pre-existing 139 assertions remained: real fullscreen/floating pixels, shared window/root and accessibility parent, native control visibly above the webpage and clickable, transforms, header drag, resizing, touch-driven APZ scrolling, eight idle/resume page changes, typing, canceled Back handling, first Back hides keyboard without collapse, separate second Back collapses, retained page reopening, cold floating tab, repeated tab switches, chooser return, fullscreen return, zero recorded native errors and retired buffers/generations. All 13 hardware preferences read back as requested.

## Independent delivered-binary checks

Downloaded ZIP CRCs and SHA-256 digests, APK hash, compiled-source pin and exported manifest were checked locally. The packaged hardware/logging audit was independently rerun successfully. Optimized smali was inspected: the helper calls the public MotionEvent overload and both actual hosts forward the original event to their original Gecko handlers. Build 139's two DEX files contain no requestUnbufferedDispatch reference; 140 contains the new helper call.

All 14 ARM64 native libraries, including our libbubble-ahb.so and Gecko libxul.so, are byte-for-byte unchanged from 139. So are omni.ja, gecko-hardware.yaml and all 12 built-in chat-monitor files. Generated assets/dexopt/baseline.prof and baseline.profm change with DEX; they are not claimed identical. R8/resource shrinking, -O3/ThinLTO, unchanged native logging elimination and guarded app diagnostics remain. The unchanged Gecko engine/Android can still generate their own logs.

The source archive excludes signing keys and contains no font files. Evidence includes original runtime output, both page-input reports/screenshots, 13 preference readbacks, Back/IME checks, shared-window dumps, build reports and independent binary comparisons.

ARM64 ZIP SHA-256: `66e02a7abdfd4d13eb32c84b80ae91a52d5b5cf576f66705903e538831f8bfbd`.
Verification ZIP SHA-256: `055fa4ed091e83d671327f7aac574b5202af34f804aa87307c2c4a938d89a66a`.

## Remaining boundaries

This run used an Android 16 x86_64 emulator with software-emulated graphics and CI-only 720x1280 geometry. Pixel responsiveness, sustained FPS, CPU/thermal changes and motion quality are unmeasured. Passing gesture and lifecycle assertions does not certify every website or device.

The selected PRIVATE ImageReader / maxImages 6 / sequential drain 4 / one consumer / output backpressure ON renderer and unified window are unchanged. ImageReader elimination, engine-owned final render targets and the exact Chromium scrolling source port are NOT shipped in 140. No Gecko native binary or scrolling preference was changed.

Primary input contract: https://developer.android.com/reference/android/view/View#requestUnbufferedDispatch(android.view.MotionEvent). Android's stylus documentation also describes this request independently of its front-buffer example: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features . Bubble does not add front-buffer rendering in this build.
