# Bubble selected renderer: relay_latest_bp

The user selected this route on September 12, 2026, after physical probe testing.
This is a browser integration, not another benchmark or a claim of an objective latency winner.

## Frozen scope

Based on `anw-ahb-build115` / `3d12f56a87172693673c60b60d20ae5760eeb96c`, itself
Build-115-derived. Do not merge the later regressed Voice experiments. Preserve the
same `com.mekromn.bubble.debug` package, public development certificate, browser profile,
UUID sessions, fullscreen GeckoView/SurfaceView path, and unrelated features.
Only floating page transport is changed. Scrolling, Gecko version, themes and Voice
scripts/notification code are not changed by this integration.

## Exact policy

Gecko -> ImageReader-owned ANativeWindow -> AImage / same AHardwareBuffer ->
release-fenced ASurfaceControl output. PRIVATE reader, GPU_SAMPLED_IMAGE |
COMPOSER_OVERLAY consumer usage, maxImages 6, up to 4 sequential acquire-next calls
per pass, ONE event-driven consumer, output SurfaceControl backpressure ENABLED.
Superseded acquisitions are returned with their own acquire fence. Submitted images
are retained until the release callback returns them through AImage_deleteAsync.
No forced shared/auto-refresh mode, FRONT_BUFFER requirement, CPU lock/readback,
bitmap conversion, TextureView fallback, or new engine prefs.

## Browser lifetime integration

Native creation runs off-main. UI publication is generation-tagged; an obsolete
creation after resize/tab switch/hide cannot rebind the next tab. Each new generation
gets its own output layer and reader; frames from an old tab cannot enter the new
layer. All GeckoDisplay acquire/publish/destroy/release calls remain main-thread.
Java has no per-frame JNI pump. Native destroy marks the generation stopping and
returns; consumer teardown and reader deletion are asynchronous. Reader lifetime
extends to the last outstanding compositor image lease. One sleeping cleanup
executor handles retirement. At most eight live/retiring native generations are
allowed: a permanently broken release chain fails visibly instead of leaking
unbounded allocations on every switch. No unsafe timeout-and-delete fallback.

Detailed persistent forensic logging is retained but disabled. Native lifecycle and
errors still appear in logcat; there is no per-frame timestamp collector or benchmark UI.

## Verification

Source guards preserve the selected policy, fences, off-main processing and old
fullscreen/native focus behavior. The focused Android integration test checks actual
screen pixels (not only callbacks), fullscreen -> floating, touch/typing, a tab first
opened while floating, repeated selected-session changes, target resize and return
to fullscreen. It verifies native leases/workers are reclaimed. Its local fixture
contains no accounts, network telemetry or user messages. Emulator performance is
NOT Pixel performance and compilation is NOT runtime success.

## Separate Chromium scrolling research

Mozilla's `gfx/layers/apz/src/AndroidFlingPhysics.cpp` explicitly identifies its math
as adapted from Chromium Android's historical `ui/events/android/scroller.cc`.
Inspected Mozilla blob: be8c43853b5b14dce8472152bd63ea896771085d.
Chromium's current `ui/events/gestures/blink/web_gesture_curve_impl.cc` selects
among different curves and references `ui/events/mobile_scroller.h`; inspected blob
 e71f3c9d9394b5cce122df1f36e87a70ebddb8f0.

This does NOT prove the pinned Gecko AAR behaves identically to current Chrome.
An exact source port needs a pinned Chromium revision, its actual Android branch,
units, device scaling and feature flags, plus test vectors for position and velocity.
Drag velocity estimation/resampling, axis locking, scroll target/handoff, nested
scrolling, overscroll, snap and pinch need separate APZ integration. A JS smooth-scroll
shim or guessed friction values must not be described as exact Chromium scrolling.
The renderer build deliberately leaves all of that unchanged.

Primary inspected sources:
https://github.com/mozilla/gecko-dev/blob/master/gfx/layers/apz/src/AndroidFlingPhysics.cpp
https://github.com/chromium/chromium/blob/main/ui/events/gestures/blink/web_gesture_curve_impl.cc
