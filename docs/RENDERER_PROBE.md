# Bubble 7: controlled renderer probe

User direction (September 11, 2026): benchmark fullscreen and floating implementations against each other **before picking a repair**, and modify the engine when the evidence calls for it. Genuine front-buffer rendering remains a target; a queued relay is a diagnostic reference, not a substitute silently declared complete.

## Isolation and use

`renderer-probe/` is an independent Android 16+ application, `com.mekromn.bubble.probe`, signed with Bubble's existing public development identity. It does not replace Bubble, open its profiles, load authenticated pages, modify its notifications/themes, or change the production renderer. The pinned default engine is the same GeckoView AAR coordinate as the Build-115-derived experimental branch: `154.0.20260824154132`. The probe intentionally uses fresh stock test profiles, not Bubble's full extension/session/chrome workload. Findings must subsequently be reproduced in production Bubble before merging a winner.

Install the separate **Bubble Renderer Probe** APK. Grant its floating-window permission. Close other floating apps and screen recording, leave the display/brightness/power settings unchanged, and start **Quick A/B**. The page should visibly move. Tap **Visible during warm-up**; tapping during measurement marks the trial as interacted with. **Black / broken** records a visual failure, and **Stop** cancels the suite. Then run the 17-case matrix. The extended matrix covers three workloads and three rounds (153 trials). Each trial has 2.5 seconds of warm-up and 10 seconds of measurement; the per-trial process startup and cleanup are not part of that measurement.

Export creates `Downloads/Bubble Benchmarks/BubbleProbe-*.zip`. Reports are first written atomically in app-private storage; a failed later case does not delete earlier results. A `.pending.json` checkpoint with no completed matching report denotes an interrupted trial. Do not clear app data before exporting.

The controller and renderer run in separate processes. The controller watches each trial and may terminate **only its own verified `:trial` process**, never the production Bubble app. An OS kill of the controller can stop automatic sequencing; partial files remain exportable. Native driver/system-wide faults cannot be guaranteed harmless by process isolation.

## Matrix (same physical viewport in both windows)

The Activity occupies the normal fullscreen application window, but the page's physical pixel dimensions match its floating counterpart exactly. This separates windowing effects from rendering fewer pixels in floating mode. Reports include both native dimensions and CSS viewport/DPR. It is **not** a comparison of a full-screen-sized page against a smaller floating page; actual-use-size testing is a subsequent, separately labelled experiment.

| Variant | Change under test |
|---|---|
| `geckoview_max` | Fullscreen GeckoView/SurfaceView reference, max-refresh requests |
| `raw_auto` | Raw GeckoDisplay/SurfaceView, system refresh policy |
| `raw_max` | Same raw path with maximum same-resolution refresh votes |
| `texture_max` | Explicit TextureView diagnostic comparator, never a production fallback |
| `raw_extra_window` | Raw max path plus an extra floating chrome window; no webpage blur |
| `relay_fifo_bp` | Combined ANativeWindow/AHardwareBuffer relay, FIFO acquire, backpressure, maxImages 6 |
| `relay_latest_bp` | Same relay, bounded latest-frame drain, backpressure, maxImages 6 |
| `relay_latest_no_bp` | Same bounded drain, backpressure disabled |
| `relay_small_pool` | Bounded drain 2, maxImages 4, backpressure disabled |

Each of the eight non-reference variants is run in both Activity and overlay modes. Pairs stay adjacent; AB/BA order reverses across rounds and variant order is shuffled with a saved seed. The extra-window variant intentionally has no extra window in its Activity arm. The repeated reference at each workload/round helps expose drift. Three rounds are a start, not strong statistical proof.

Workloads use the same generated local chat-like document: fixed node count, text, code blocks, physical dimensions, and time-based motion. They comprise synthetic APZ touch/fling input, JavaScript-controlled scrolling, and scrolling plus Canvas repaint. Synthetic APZ input is delivered only to this generated document, not to a logged-in browser tab. It is not a hardware touch-to-photon measurement. The workload's CSP disallows networking; the unmodified Gecko runtime itself still has normal network permission.

## Measurements and limits

* Page rAF interval arrays, callback Hz, p50/p95/p99, visibility transitions, viewport, long-task observations where supported.
* Main-thread Choreographer intervals, separately labelled; they are not webpage frame counts. Instrumentation has nonzero cost and is kept consistent across comparable cases.
* Relay acquisitions, submissions, discarded queued images, exact acquisition errors, outstanding leases, actual AHardwareBuffer dimensions/format/usage/ID, and measured submission/latch/completion/presentation-fence timestamps.
* Android-reported display rates/modes, thermal state, power-save/charging state, battery temperature, runner PSS/native heap at boundaries. Runner PSS is not total Gecko subprocess memory or GPU memory.
* Device EGL extension/configuration and FRONT_BUFFER + COMPOSER_OVERLAY allocation capability checks. These are from a separate tiny probe EGL context, **not evidence that Gecko negotiated single buffering**.

Missing metrics stay unknown. Deduplicate presentation-fence signal timestamps: several transactions can be associated with one presentation. Even these fence events do not independently prove unique webpage content, hardware-plane allocation, or panel scanout timing. HWC and touch-to-photon remain explicitly unmeasured. A first-contentful-paint callback does not prove that the final floating layer was visible; that is why visual confirmation is separate.

Native timestamps use CLOCK_MONOTONIC, Android elapsed timestamps use BOOTTIME, and page timestamps use its performance time origin. Do not subtract timestamps across these clock domains without calibration. The probe does not correlate a native frame with a specific JS frame ID; the visible frame marker is for inspection only.

## Freeze-safe diagnostic relay

The relay never forces shared-buffer/auto-refresh mode on Gecko's producer. It retains the combined path:

`Gecko -> ImageReader-owned ANativeWindow -> AImage -> same AHardwareBuffer -> ASurfaceControl`.

Only the dedicated event-driven native consumer acquires/submits images. Reader callbacks and release callbacks merely wake it through a nonblocking eventfd. Acquisition uses a bounded number of `AImageReader_acquireNextImageAsync` calls, not the open-ended latest-image drain. Status is checked before the image pointer, so max-images/errors cannot masquerade as ordinary no-buffer availability.

Acquire fences go directly to `ASurfaceTransaction_setBufferWithRelease`. Release fences go to `AImage_deleteAsync`. Discarded images also retain their acquire fence when returned. Teardown is off-main and waits for leases; if it cannot drain safely, the reader is retained until the isolated process exits rather than destroyed underneath callbacks. That condition is recorded as a failed case. Main-thread Gecko lifecycle calls remain main-thread because Mozilla requires that; the separate controller watchdog covers a blocking engine call.

This is queued zero-copy **application-side transport**. It is not genuine Gecko front-buffer rendering, not a fixed triple-buffer count, and not a guarantee of hardware overlay composition. `maxImages` is an acquisition limit, not proof of exact BufferQueue allocation depth.

## Analyze exports

```sh
python3 tools/compare-renderer-probe.py BubbleProbe-YYYYMMDD-HHMMSS.zip --out comparison
```

The tool writes a per-trial CSV and JSON with matched fullscreen/floating pairs. It refuses to treat crashes, no first paint, hidden pages, human interaction during measurement, missing APZ input, severe thermal states, or changed power state as clean timing results. Viewport/DPR and engine identities must match. It deliberately does not automatically declare a winner. Fast black output is not success.

## Engine experiments are permitted, not implemented by a label

Build a fork as a Maven artifact with its proper transitive dependency metadata, then use:

```sh
gradle -p renderer-probe -PgeckoVersion=YOUR_EXACT_VERSION -PgeckoMaven=YOUR_MAVEN_REPOSITORY assembleDebug
```

The exact resolved AAR SHA-256 is embedded in the probe's generated assets and exported with results. The APK also records the coordinate, repository, and source commit. Compare changes to producer EGL configuration, mutable render-buffer negotiation, WebRender render-target ownership, frame pacing, and buffer presentation only after adding the actual engine-side implementation and evidence. Extension support and successful allocation alone do not pass that gate. A Vulkan/shared-presentable-image experiment likewise requires real producer implementation, not ANativeWindow flag mutation.

Engine changes should preserve webpage fidelity, resolution, features, session behavior, and explicit synchronization. No reduced-quality substitute, silent TextureView fallback, or unsupported 120-fps claim.

## Primary API references

* https://developer.android.com/ndk/reference/group/media — ImageReader acquisition, AImage lifetime and fence ownership.
* https://developer.android.com/ndk/reference/group/native-activity — SurfaceControl submission, backpressure, release/completion callbacks and presentation fences.
* https://developer.android.com/ndk/reference/group/sync — native fence introspection.
* https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoDisplay.html — surface ownership and main-thread lifecycle.
* https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoView.html — backend selection.
* https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_mutable_render_buffer.txt — actual producer-side single-buffer protocol.

## Validation status

Source guards, host Java/JavaScript/Python tests, Android/NDK compilation, emulator smoke tests, and physical Pixel results are separate gates. Never convert one into a claim about the next. No physical Pixel benchmark data is bundled with the source. Any synthetic unit-test numbers are test fixtures only.
