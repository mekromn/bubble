# Bubble 139 — final unified-window validation

## Final artifact

- Compiled source: `67d409fe817593b398142dc3c4c7b4d9dadca1fe`.
- Branch: `relay-latest-bp-fast139`.
- Version: `0.7.11-relay-bp-unified`, versionCode 139.
- APK: `Bubble-139-relay-latest-bp-UNIFIED-ARM64.apk`.
- APK SHA-256: `96ca9bcc01e521e4c6d8001fa07e80a558f20706d4aea6f74836ae719fc4c54e`.
- APK size: 193,732,983 bytes.
- Package: `com.mekromn.bubble.debug`; manifest is non-debuggable and shell-profileable. The suffix retains update identity, not debug execution.
- Certificate SHA-256: `001a6f40ddcff14aec3aca71964fec58c29a32bdd0c649285bce60ae940c2b1f`, unchanged from user-confirmed 138.
- Release: `bubble-fast-139-34744003368-1`.
- ARM64 artifact: `10313334067`; verification artifact: `10313740530`.

Install as an update over 138, without uninstalling or clearing its profile. The user-confirmed 138 branch and APK are preserved. Earlier failed 139 candidates are not the artifact above.

## Implemented architecture

The separate page-only WindowManager window is removed. Floating webpage input and native chrome now share one interactive ViewRoot and window token. The existing noninteractive masked glass backdrop remains. This removes the second page-window attachment/layout/movement path; it does not mean the whole app has only one window or no compositor layers.

A public SurfaceView underlay anchors the relay's output SurfaceControl beneath chrome. Its own surface is never passed to Gecko, drawn into, or used to copy webpage pixels. SurfaceView still has platform bookkeeping. Gecko remains connected to the PRIVATE ImageReader producer, and the selected AHardwareBuffer is presented with its acquire fence. This is not a switch to Gecko's direct SurfaceView producer and is not removal of ImageReader.

The selected policy remains maxImages 6 (concurrently acquired images, not total allocation count), up to four sequential acquisitions per pass, one native consumer, output backpressure enabled. Demand-driven capacity recovery, transaction/lease reuse, explicit fences, exact-size buffers, and asynchronous retirement remain. No page pixel copy, TextureView, recurring Java frame pump, resolution reduction or new scrolling algorithm is added.

R8 optimization/resource shrinking, native -O3/ThinLTO, hardware-window flags and all 13 startup Gecko preferences remain. Our native relay contains no Android logging import or direct liblog dependency. Disabled app diagnostics remain guarded; the unchanged Gecko engine and Android may still log.

## Recovered interruption and final fix

The previous answer stopped before delivering 139. On resumption the newest completed run was still a failure, not an overnight successful build. Its screenshot showed that keyboard Back had also collapsed the floating chat.

The root key handler executed Back on canceled ACTION_UP events. It now ignores canceled releases, so platform/IME handling cannot be followed by another application navigation from that cancellation. The manual fallback uses actual IME visibility rather than imeBottom overlap height; a visible keyboard need not overlap a repositioned window. Default platform Back priority remains unchanged.

The runtime fixture was strengthened, not weakened: it sends a canceled Back sequence to the actual root, requires first real Back to hide IME without collapsing, requires a separate second Back to collapse, and reopens the retained page before continuing the existing tests.

## Final workflow: PASS

Run `34744003368`, job `103688414802`, passed source guards, native ASan/UBSan ownership/progress stress tests, optimized ARM64 compilation/unit tests/lint, signing/manifest/native-flag/binary audits, optimized x86_64 instrumentation compilation, and the actual Android 16 browser integration. Release publication occurred only after runtime success.

Runtime output: `OK (1 test)`. JUnit reported 59.176 seconds for the whole integration method; this is NOT input latency or rendering performance.

The test retained its assertions for actual fullscreen/floating page pixels, shared root/window token and accessibility parent, absence of the old page WindowState, transformed page pixels and alpha, a visible/clickable native control over webpage pixels, real header drag, eight click-driven idle/resume color changes, complete `relay` input before keyboard dismissal, Back semantics, first-open floating tab, six tab switches, real resize, touch-driven page scrolling, chooser/reattachment, fullscreen return, zero recorded native errors and all image/generation leases retired.

All 13 Gecko preferences read back as requested. `runtime-evidence/back-ime.txt`, `hardware-preferences.txt`, `idle-resume.txt`, shared-window dumps and screenshots preserve the observations. The post-Back screenshot establishes keyboard-hidden/chat-retained state; screenshots are not synchronized end-to-end input/frame measurements.

## Independent artifact checks

Downloaded ZIP hashes/CRCs, APK SHA-256, source pin and exported manifest were checked. The packaged hardware/logging audit was independently rerun and matches CI. The engine `libxul.so`, `omni.ja`, hardware-policy file and all 12 built-in chat-monitor files are byte-for-byte unchanged from 138. The source archive excludes signing keys and contains no font files. Native sanitizer tests were also independently rerun on the recovered production native implementation.

ARM64 ZIP SHA-256: `c7d662f5578bd52cf11bdc2be59f4b015d3d3999ef832ebb879b272a29cacfe6`.
Verification ZIP SHA-256: `44cd88f8a42c5a4fc6ad15f16b799b05388a296ec8e1958b3d4bfd9901313c23`.

## Failures preserved

Earlier 139 tests caught a constructor-time null focus delegate and a negative-Z child that still hid native controls. Those were corrected before this run; the SurfaceView underlay replaced the unproven negative-Z arrangement. Underlay source `8c3baa44c3eea2662dd81c87152e8b9f457a7f58`, run `34719970404` attempt 2, then failed `ime-hidden-not-collapsed`. Its APK SHA-256 was `0709aa2e3fce7ca45a2e7472f3540ad561a7027afd96415894dfe59c47ad9916`; it was not released. These failures were not relabeled as passes.

## Remaining boundaries

Runtime testing used an API-36 x86_64 emulator with software-emulated graphics and CI-only 720x1280 geometry. This validates the stated correctness/structural checks, not every website or all browser features. It does not certify Pixel GPU execution for every operation, a physical overlay plane, sustained 120 FPS, a speedup percentage, or touch-to-photon latency. User testing of final 139 on the Pixel is still pending.

ImageReader elimination, engine-owned final AHardwareBuffer render targets, engine cleanup changes and the exact Chromium scrolling port are NOT shipped in 139. The prebuilt Gecko engine and scrolling algorithms remain unchanged.

Primary Back contracts: Android KeyEvent.FLAG_CANCELED and WindowInsets.isVisible; relevant Android ViewRootImpl implementations forward cancellation after platform Back handling. See https://developer.android.com/reference/android/view/KeyEvent and https://developer.android.com/reference/android/view/WindowInsets .
