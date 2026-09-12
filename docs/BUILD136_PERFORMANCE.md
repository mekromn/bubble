# Bubble 136: optimize selected relay_latest_bp first

User order, 2026-09-12: deliver an actually optimized Bubble build first, then add the Chromium scrolling port separately. This is the actual browser, not the benchmark app. The selected transport remains the reference; no new global benchmark suite is required of the user.

## Implemented

- Dedicated `performance` build type, same `com.mekromn.bubble.debug` application ID and existing development certificate. The suffix is historical identity, not a debuggable build setting. `debuggable=false`, `jniDebuggable=false`, shell profiling available, R8 optimization/resource shrinking enabled. Conservative keep rules preserve Gecko JNI and app names/reflection while allowing app code optimization.
- Native relay explicitly compiled with `-O3`, ThinLTO and frame pointers. No fast-math, reduced rendering precision, resolution scaling or disabled features.
- One reusable frame transaction per native generation; six preallocated callback bookkeeping slots replace per-submission transaction and Lease object creation. Android internals may still allocate. Only the consumer thread mutates its transaction. Pixel allocations are not recycled before the compositor release fence.
- Eventfd notifications coalesced with an atomic pending flag; clear-before-consume ordering prevents lost work. A bounded acquisition pass self-reschedules only when it reaches the drain limit. An observed empty queue waits for a new frame; capacity exhaustion retains release-driven recovery. No new VSYNC wait, periodic idle polling or UI-thread JNI frame loop.
- Opaque state is configured once; data space changes are forwarded when required, including resetting a previously known space to UNKNOWN. No invented partial damage rectangles.
- 67 diagnostic call sites guard argument construction with the compile-time disabled flag. Full diagnostics remain available in source, but disabled messages do not build strings or traverse session state first.
- Repeated identical screen-origin notifications to the same GeckoDisplay are suppressed. The cached identity is invalidated on display/pipeline changes. Real movement still updates input coordinates.

## Frozen behavior

PRIVATE ImageReader, GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY usage, maxImages 6, drain limit 4 sequential acquisitions, one native consumer, output backpressure ON. Neither `maxImages` nor callback slots describe the entire underlying allocation/producer queue. Fullscreen remains the existing GeckoView/SurfaceView route. No TextureView fallback, forced shared-buffer mode, extra pixel blit, image sharpening, quality reduction, Gecko preference changes or Voice/theme changes.

GeckoView `154.0.20260824154132` remains the prebuilt engine. Its `libxul.so` and `assets/omni.ja`, plus all 12 packaged chat-monitor extension files, were byte-hash compared against Build 135 and are identical in the first Build 136 artifact. The optimization claims above concern our application and native bridge, not a rebuilt or previously unoptimized Gecko engine.

## Validation boundaries

`tools/test-relay-native.sh` compiles the production C++ implementation against deterministic Android API stubs using AddressSanitizer and UndefinedBehaviorSanitizer. Cases include bounded acquisition, acquire/release fence forwarding, six outstanding leases, capacity recovery, known/unknown data space transitions, one transaction reused for 10,000 submissions, callback-thread release, 10,000 merged wakeups and 20,000 producer/consumer notification iterations. This is ownership/progress evidence, not Android GPU or Pixel speed evidence.

The Android integration test builds the SAME optimized/non-debuggable variant for x86_64, then checks local webpage pixels, input/typing, first-open floating tabs, repeated switches, resize, return to fullscreen and native retirement. API-36 software-emulated graphics use a smaller 720x1280 emulator display to avoid the previous launcher ANR; that setting changes only the CI emulator, never the APK's device resolution.

Initial run `34681522465`: source/native regressions, ARM64 compilation/unit tests/lint, certificate/manifest and native optimization-flag checks passed. Instrumentation packaging failed due to AndroidX's missing Error Prone annotation dependency, so runtime did not run. The test-only dependency was added explicitly in `071fe8289343476bea81b66889061465b5e29f87`; no application dependency or renderer behavior was changed by that fix. Read the final workflow/evidence result before asserting Android runtime success.

No Pixel frame-rate, CPU-percent speedup or touch-to-photon result is claimed by this document. Removing specific overhead and enabling real build optimization is implemented; its end-to-end device effect remains to be measured during actual use.

## Chromium scrolling follows, not mixed into this APK

The Build 135/136 engine embeds source repository `https://hg.mozilla.org/releases/mozilla-release` and revision `8b532c2140db30c193436254a61ce964e7d2a121` in `chrome/toolkit/content/global/buildconfig.html` inside omni.ja. Its build reports PGO/LTO. A custom engine port must preserve that optimized baseline and identify the matching source, not silently replace it with a different release tag.

The inspected Chromium MobileScroller implementation is pinned at `b797d144c33600051ed83fc2ca71e0647d7bf372` (`ui/events/mobile_scroller.cc` blob `822e552eb91218beb4e3a98681ec7ecd89ea72fa`, header blob `0fc47f497237cfb95434fb05cc3be449c426d7e1`). This is a component source pin, not a claim that it alone specifies current Chrome Android scrolling. Active curve selection, input velocity units/scaling, gesture processing, cancellation, nested scroll targeting and compositor sampling need auditing before integrating into APZ. Preserve attribution and source-level position/velocity parity tests. A JavaScript scroll shim or guessed preferences will not be called an exact port.

No Chromium scrolling port is present in Build 136.
