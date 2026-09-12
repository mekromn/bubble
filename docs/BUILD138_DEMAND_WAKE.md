# Bubble 138 — remove release-only consumer wakeups

## Baseline and bounded change

The user confirmed that Build 137 works on the Pixel on 2026-09-12. Preserve its compiled source `837d575fc615d9d44ff572d98323dfebb61528de` and branch. Build 138 continues renderer optimization; it does not yet replace Gecko's compositor, remove ImageReader, or port Chromium scrolling.

Only the native relay's capacity-notification protocol and app version change in production. Hardware preferences, Gecko binaries, built-in page scripts, fullscreen renderer, UI/window/input logic, signing identity, and fidelity remain unchanged. The selected PRIVATE ImageReader / maxImages 6 / sequential drain limit 4 / one consumer / output backpressure ON policy remains intact. The non-debuggable R8 / native -O3 / ThinLTO build and compiled-out native logging remain intact.

## Removed work

Before: each `releaseFrame` callback woke the consumer, even if the last acquisition pass established that the reader queue was empty. A return is not a new incoming image. Such a wake could cause a poll/read and another unsuccessful acquisition.

After: a normal return does not wake the consumer unless it had observed MAX_IMAGES or an acquisition error and registered a retry waiter. The new-image callback, bounded backlog continuation, refresh-rate changes and shutdown retain their existing notifications. This is event-driven, not a periodic polling or additional VSYNC gate. Frames remain admitted and selected under the same policy; backpressure is not disabled to reduce latency.

## Race safety

The release epoch and capacity-waiter bit share one atomic word. The consumer snapshots that word BEFORE its acquire call. After MAX_IMAGES it compare-and-swaps the snapshot to mark waiting. Either an intervening return changed the epoch (consumer schedules a retry), or a later callback sees and clears the waiter (callback schedules a retry). Clearing a waiter at the beginning of an already active pass prevents old requests from causing unnecessary notifications.

The callback updates this gate only AFTER `AImage_deleteAsync` returns image capacity with the original compositor release fence. A local validation rejection also advances the gate after returning its selected image; it cannot depend on a nonexistent later presentation callback. A stopped worker has an independent unconditional wake and existing retirement logic.

This protocol removes scheduling work. It does not remove GPU fences, own/recycle image storage, fabricate a presentation timestamp, or turn the acquired-image ceiling into an assumed physical buffer count.

## Evidence and limits

The same unmodified operation-count harness is compiled against real Build-137 and Build-138 relay source, with deterministic Android API stubs. Under 10,000 repetitions of one image followed by an empty queue and its release:

| Observation | 137 | 138 |
| --- | ---: | ---: |
| Submitted images | 10,000 | 10,000 |
| Released images | 10,000 | 10,000 |
| Release-only consumer passes | 10,000 | 0 |
| Total acquisition calls | 30,000 | 20,000 |
| Frame transactions created | 1 | 1 |
| Unexpected errors | 0 | 0 |

These are deterministic operation counts, NOT Android CPU utilization, refresh rate, end-to-end latency or touch-to-photon measurements. When acquisition capacity is exhausted, some release-driven wakes remain necessary by design. No fixed device speedup is inferred.

The sanitizer suite runs actual production code against stubs and includes a callback between MAX being observed and its status being handled, six overlapping callbacks, saturated notifications without spinning, local-rejection capacity recovery, 20,000 gate arm/return races, 20,000 eventfd races, 10,000 overlapping acquired images and 10,000 idle releases. Three API failures are deliberately injected (capacity-bound rejection, full-drain rejection, and a transient acquisition error); each must produce exactly one error. Release-driven recovery after an acquisition error is preserved without adding error polling. Ordinary sequences require zero errors. A rejected final image from a full bounded pass still schedules the next pass so a remaining backlog cannot depend on a nonexistent release callback. Fences, data-space transitions and transaction/lease reuse remain checked.

The Android test uses the actual optimized x86_64 browser, not Renderer Lab. It retains the 137 screen-pixel, typing, cold-tab, tab-swap, resize, fullscreen-return, cleanup and 13-preference readback assertions, and adds eight visible page-click/color changes, seven after idle pauses. This tests resumption, not touch latency. The emulator's reduced resolution is CI-only; no APK resolution change is made. Consult final run evidence for actual pass/fail.

## Reproduction

- `bash tools/test-relay-native.sh`
- `bash tools/compare-relay-idle.sh` (requires the pinned 137 Git history)
- `node tools/test-foreground.cjs`
- `node tools/test-fast-build.cjs`
- `node tools/test-hardware-policy.cjs`
- `gradle :app:testPerformanceUnitTest :app:assemblePerformance :app:lintPerformance`

GitHub's 138 workflow publishes the release only after the optimized Android runtime test succeeds. Compilation-only artifacts are retained separately and are not called runtime-validated.
