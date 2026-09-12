# Bubble 138 — final validation

## Delivered artifact

- Production source: `5734387fb129f87eacc2038c970b1da835556ebf`.
- Branch: `relay-latest-bp-fast138`.
- Version: `0.7.10-relay-bp-demand`, versionCode 138.
- APK: `Bubble-138-relay-latest-bp-DEMAND-ARM64.apk`.
- SHA-256: `46e81a3e1a5c4a92c5cbc64dc6038e67ee72b0738d857e85d46abb77b6f8e6d8`.
- Package: `com.mekromn.bubble.debug`; non-debuggable and shell-profileable.
- Certificate SHA-256: `001a6f40ddcff14aec3aca71964fec58c29a32bdd0c649285bce60ae940c2b1f`, unchanged from 137.
- Release: `bubble-fast-138-34715908837-1`.
- ARM64 artifact: `10304940176`; final verification artifact: `10303824853`.

The user confirmed 137 works on the Pixel. Its branch and APK are preserved; 138 is an update using the same identity, not a profile reset or a new signing identity.

## Implemented change

Only the native relay capacity-notification protocol and application version change in production. Normal returned buffers no longer wake an otherwise idle acquisition worker. A single atomic release-epoch/waiter handshake preserves progress when a release races an acquisition-capacity limit. Release-driven recovery after an acquisition error and continuation after a rejected full-drain image are retained. New-image, backlog, rate-change and shutdown notifications remain.

The selected PRIVATE ImageReader, maxImages 6, sequential drain limit 4, one consumer and output backpressure ON remain. The acquired-image limit is not a claimed physical buffer-pool size. Acquire/release fences and image ownership are unchanged. There is no new polling timer, VSYNC wait, CPU pixel copy or Java per-frame pump.

This is a targeted optimization of relay notification work. ImageReader is still present; engine-owned final render targets and the Chromium scrolling port are not implemented in this APK.

## Final workflow: PASS

Run `34715908837`, job `103612989402`, passed every stage: source guards, sanitizer/native stress regressions, controlled operation-count comparison, ARM64 compilation/unit tests/lint, manifest/certificate/optimization checks, packaged hardware/logging audit, optimized x86_64 instrumentation compilation, and actual Android 16 browser integration. The release was published only after the Android test passed.

The runtime output is `OK (1 test)`. JUnit reports 43.668 seconds: this is suite duration, NOT rendering latency or performance.

Runtime assertions exercised actual fullscreen/floating webpage pixels; eight page-click/color changes, seven following idle pauses; full `relay` text input after acknowledged field focus; a first-open floating tab; six tab swaps; resize; fullscreen return; selected native policy; zero recorded native errors; and image/generation retirement. All 13 hardware-preference readbacks matched the bundled policy. Screenshots and `idle-resume.txt` are preserved.

This was an API-36 x86_64 emulator using software-emulated graphics at 720x1280. That geometry is CI-only, not an APK or Pixel resolution change. It does not prove physical GPU execution on the Pixel, every website's compatibility, or a Pixel FPS/CPU/touch-to-photon improvement.

## Controlled evidence: less work, not a device-speed score

The identical host harness executes real 137 and 138 relay code against deterministic Android API stubs. For 10,000 repetitions of one incoming image, an empty queue, and its release:

| Observation | 137 | 138 |
| --- | ---: | ---: |
| Submitted images | 10,000 | 10,000 |
| Released images | 10,000 | 10,000 |
| Release-only consumer passes | 10,000 | 0 |
| Total acquisition calls | 30,000 | 20,000 |
| Frame transactions created | 1 | 1 |
| Unexpected errors | 0 | 0 |

These operation counts are not a complete browser benchmark or latency measurement. Necessary release-driven retries still occur under capacity pressure. The local independent rerun matches CI exactly.

ASan/UBSan tests include 20,000 capacity-arm/return races, 20,000 eventfd race iterations, six concurrent callbacks, 10,000 overlapping acquired images, idle returns, stale MAX_IMAGES handling, exact fences, color-space transitions and transaction/lease reuse. Three API failures are intentionally injected and each must produce exactly one error; ordinary sequences require zero errors.

## Preserved binary and behavior boundaries

R8 optimization/resource shrinking, native -O3/ThinLTO, hardware-window settings and all 13 startup preferences remain. Native-relay logging is compiled out, including argument evaluation. The packaged bridge has no Android logging imports or direct liblog dependency. Application diagnostics remain guarded; unchanged Gecko and Android may still generate their own logs.

The independently inspected APK matches 137 byte-for-byte for libxul.so, omni.ja, the hardware-policy file and all 12 built-in chat-monitor files. Production Java/Kotlin, input/window/fullscreen code and assets were also held unchanged by a CI source-diff guard. The packaged audit independently reran successfully. The source archive excludes signing keys.

## First attempt retained as a failure

Run `34715202920` passed source/native/build gates and all eight new idle-to-active pixel checks, then failed the typing assertion. Its screenshot contains `elay`, not `relay`. The fixture had injected text immediately after tapping the input without waiting for focus acknowledgment. The corrected fixture waits for both its DOM focus marker and Android host focus, then still requires the full `relay` string. Production input handling was not changed.

The final native delta also adds explicit regressions and recovery for a transient acquisition error and a rejected selected image at a full drain boundary. These are separate from the test-focus change. No failed assertion was removed to obtain the final pass.

The first, unpublished APK had SHA-256 `44742c19d680bec833b61c4348ea17a30c92fa8239a1a18b6377d529df1be149` and source `e0d92c35e14df702f9be943e44dc8be83ca41a19`. Do not confuse it with the final artifact above. Its failure evidence is retained separately.
