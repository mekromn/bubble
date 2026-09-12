# Bubble 137 — final validation and artifact identity

## Delivered candidate

- Compiled browser source: `837d575fc615d9d44ff572d98323dfebb61528de`.
- Branch: `relay-latest-bp-fast137`.
- Version: `0.7.9-relay-bp-hardware`, versionCode 137.
- APK: `Bubble-137-relay-latest-bp-HARDWARE-ARM64.apk`.
- APK SHA-256: `2b524affaa89072ceaacab3f5db5bb94e14e50312108687bf437d6b10d1dcffb`.
- Application ID: `com.mekromn.bubble.debug`; manifest is non-debuggable and shell-profileable.
- Certificate SHA-256: `001a6f40ddcff14aec3aca71964fec58c29a32bdd0c649285bce60ae940c2b1f` (same update identity as 136).
- Release: `bubble-fast-137-34712413214-1`.
- ARM64 artifact: `10303970776`; verification artifact: `10303564223`.

## Final workflow: PASS

Run `34712413214`, job `103603440702`, passed all stages: source guards, native ownership/progress stress regressions, ARM64 compilation/unit tests/lint, certificate/manifest/native optimization checks, packaged hardware/logging audit, optimized x86_64 instrumentation compilation, and the actual Android 16 browser integration test.

The runtime output is `OK (1 test)` with 35.891 seconds reported by JUnit. This is test duration, not a rendering-speed or latency result.

The test exercised actual local webpage pixels in fullscreen and floating mode, a page button click, input/typing, a first-open floating tab, six tab swaps, resize, return to fullscreen, and native generation/image-lease retirement. It checked the selected native policy and zero recorded native errors. Screenshots preserve magenta/cyan pages in the real browser window, not a renderer-probe application.

It also checked hardware-window flags and read every requested preference back from Gecko. All 13 matched:

| Preference | Effective readback |
| --- | --- |
| layers.acceleration.disabled | false |
| layers.acceleration.force-enabled | true |
| gfx.webrender.all | true |
| gfx.webrender.software | false |
| gfx.canvas.accelerated | true |
| gfx.canvas.accelerated.force-enabled | true |
| webgl.disabled | false |
| webgl.force-enabled | true |
| webgl.out-of-process.enable-ahardwarebuffer | true |
| media.hardware-video-decoding.enabled | true |
| media.hardware-video-decoding.force-enabled | true |
| media.android-media-codec.enabled | true |
| media.android-media-codec.preferred | true |

The emulator uses x86_64 and software-emulated graphics at 720x1280. That resolution is CI-only. Preference readback and hardware-window flags are NOT proof of physical Pixel GPU execution for every operation. Pixel speedup, touch-to-photon delay, hardware-plane assignment and all website/codec compatibility remain unmeasured.

## Binary checks

Our compiled native bridge has zero Android logging references and no direct liblog dependency. The macro removes disabled argument evaluation, not only the final print. Application diagnostics are guarded. The unchanged prebuilt Gecko engine and Android may still log.

The APK retains native -O3/ThinLTO and R8 optimization. libxul.so, omni.ja and all 12 built-in chat-monitor files match working Build 136 by byte hash. The hardware policy is the exact bundled 13-preference list. The independent container re-run of the packaged audit matches CI.

## Failures preserved rather than relabeled

Initial build run `34711470118` passed ARM64 gates but failed test compilation on nullable preference readback. Runtime recheck `34712039288` corrected that test-only issue, then discovered a REAL startup crash in the new hardware-config path: R8 had moved SnakeYAML TypeDescription to the default package, causing its static initializer's Package.getName() to dereference null.

The rejected initial APK SHA-256 was `81ea64fadde27810f04a22252d1807432e5dd4462b6f37694304b93faf4b1f7c` (source `daf2ee7aaa49554e1f00c4f0376cddc1fa41d737`). Do not install or identify that APK as the final delivery.

The final source preserves SnakeYAML's package/class/member/constructor reflection contract. R8 still optimizes the application and the native relay. The new mapping retains the real parser names, and the final Android run validates config loading and effective preferences successfully. No failed assertion was removed to obtain this pass.

## Preserved boundaries

User-confirmed working Build 136 remains unchanged on its branch. The selected floating relay is still PRIVATE ImageReader, six acquired-image capacity, drain limit four sequential acquisitions, one consumer, output backpressure enabled. Fullscreen transport, engine binaries, scrolling algorithms, profiles and built-in page scripts remain unchanged. No Chromium scrolling port or direct-engine AHardwareBuffer target is claimed or included in 137.
