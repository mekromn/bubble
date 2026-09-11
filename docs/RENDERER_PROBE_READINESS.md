# Bubble 7 — first-paint readiness investigation

## Evidence, not a performance result

Build 6 (`021d37bddf0480a467b94d7deb29815078ba3a55`, CI `34600006037`) compiled, signed, and published, but its Android 16 SwiftShader smoke test failed. The controller recovered from the first GeckoView reference timing out. The two raw SurfaceView cases and the queued AHardwareBuffer relay returned JavaScript reports without the native first-contentful-paint notification. The relay completed 114 buffer acquisitions/submissions/releases with zero native errors and drained its leases. This demonstrates queue activity, not correct physical visibility or Pixel frame rate.

The benchmark itself began changing scroll offsets on its first requestAnimationFrame, before waiting for initial contentful paint. Mozilla bug 2069900 documents that early trusted scroll events, including programmatic scrolls, can suppress first-contentful-paint generation. That makes early benchmark motion a plausible contributor; it does not establish the cause of every failure.

Primary report: https://bugzilla.mozilla.org/show_bug.cgi?id=2069900

The exact pinned GeckoView AAR (`154.0.20260824154132`) was inspected. Its `actors/ContentDelegateChild.sys.mjs` forwards `MozFirstContentfulPaint`, and its parent actor forwards `GeckoView:FirstContentfulPaint`. Do not confuse this event with the separate first-composite callback or with proof that a compositor child is visible on the panel.

## Code repair

Commit `f51d65ed9a1c7ad2c875b0b1c457844bee54d71b`, Probe Build 7, CI `34603620563`:

* Wait for the actual GeckoView SurfaceView/TextureView child to become ready before loading the document. GeckoView still owns its own GeckoDisplay; the harness does not acquire a second one.
* Initially keep the generated document stationary. Wait for its `first-contentful-paint` PerformancePaintTiming entry before starting warm-up, scrolling, animated drawing, or synthetic APZ input.
* Fail with `PAGE_FIRST_PAINT_TIMEOUT` after 15 seconds of page readiness waiting. Keep that waiting outside the 2.5-second warm-up and 10-second measurement.
* Keep the native `onFirstContentfulPaint` validation. Record first composite separately, not as a replacement.
* Record controller/renderer lifecycle phases and `lastObservedPhase` on watchdog failure.
* Preserve a native or lifecycle error as the primary status; add a paint-readiness failure without overwriting the more specific error.
* Check `PerformanceObserver.supportedEntryTypes` instead of treating a nonthrowing `observe()` as proof of long-task support.

Host tests exercise all three workloads with delayed initial paint, absent paint, and supported/unsupported long-task observation. They assert no motion before paint and preserve the timing calculations. Those synthetic tests passed locally and in the Build 7 host-test step. Android compilation and emulator runtime are separate subsequent gates; read the exact workflow result before recommending a build. No physical Pixel benchmark is supplied by CI.

## Next diagnostic decision

After the Build 7 smoke test, inspect both status and evidence. A passing readiness gate would support the early-motion explanation for that case, not establish a front-buffer win. A repeated timeout must be traced using `lastObservedPhase` and renderer lifecycle events; do not relabel it as a confirmed native deadlock. A failure must remain a failure rather than weakening the gate to obtain green CI.

All expert C1–C16 cases remain tracked in `RENDERER_PROBE_COVERAGE.md`. The 17-case stock-Gecko window/queued-relay matrix is a different matrix, not a substitute for proper EGL/Vulkan producer, direct-AHardwareBuffer, or engine-front-buffer implementations.
