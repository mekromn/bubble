# Bubble 140 — gesture-scoped page input delivery

Starting point: user-confirmed Build139, production source 67d409fe817593b398142dc3c4c7b4d9dadca1fe; repository head 1422f7eb36a76c5d6fb95a75a1c2e0f35dbcb6fd adds its validation record. The user confirmed 139 works on September 13, 2026.

## Implemented

Both attached page input hosts now call Android's public `View.requestUnbufferedDispatch(MotionEvent)` when receiving the initial DOWN of a touchscreen or stylus gesture with an associated GeckoSession. Fullscreen continues through `super.onTouchEvent(event)`; floating continues through its existing `PanZoomController.onTouchEvent(event)`. Neither route queues, copies, synthesizes, changes timestamps/coordinates/pressure/history, or consumes the event in the helper.

The overload is intentionally per-gesture. No permanent input-source registration, hidden API, device setting, root permission or frame callback is introduced. MOVE, POINTER_DOWN, POINTER_UP, UP and CANCEL do not make new requests. Mouse, touchpad, wheel, keyboard and gamepad handling are unchanged. Android's ViewRoot terminates the unbuffered gesture on terminal touch events. The shared floating window's native controls are not separately opted in.

This removes the *request to wait for View-frame batching* ahead of Gecko for those gestures. It does not bypass Android InputDispatcher, IME, security checks, APZ event handling, engine queues, GPU fences, SurfaceFlinger or scanout. Android's input processing can still have latency. Delivering moves earlier can increase callback/CPU work and change resampling opportunities. A device latency/smoothness/thermal gain is a hypothesis, not a demonstrated result.

Build139's two packaged DEX files contain no `requestUnbufferedDispatch` method name. The inspected Firefox154 GeckoView.onTouchEvent also requests focus and forwards input to APZ without that call. The 140 call is an application input-policy change, not proof of a missing Gecko feature, and not a Chromium source/physics port.

## Frozen boundaries

The selected PRIVATE ImageReader / maximum six acquired images / sequential drain four / one consumer / output backpressure ON pipeline is unchanged. All C++ renderer sources, hardware config, page scripts, floating chrome geometry/Back behavior, app manifest, signing identity and Gecko binaries remain unchanged. R8, resource shrinking, native -O3/ThinLTO, non-debuggable/profileable packaging and compiled-out own-relay logging remain. No new logging or counters are added to production.

Keep versioned package `com.mekromn.bubble.debug` and the existing certificate so 140 updates 139 without clearing the user's profile. The historical suffix does not enable debugging.

## Verification required before publication

- Pure JVM policy tests: valid touchscreen/stylus starts, missing session, nonstart gesture phases and unrelated source exclusions.
- Source guards: both real hosts route the same event to their original Gecko handler; no new event queue, timer, clone or log.
- Packaged DEX inspection confirms the unbuffered call and both host handlers survive optimization. Native/engine/assets checks retain existing gates.
- The entire 139 optimized Android16 integration test remains. Additional checks in BOTH fullscreen and floating use real UiAutomation MotionEvents into a test-only local document: finger drag/end coordinates, ordered timestamps, explicit CANCEL without click, two active pointers, fresh gesture after cancellation, stylus type/pressure and actual response pixels. Existing touch-driven APZ scrolling, native control overlay, drag/resize, IME/Back, idle-resume, cold tabs, chooser, fullscreen return and cleanup checks remain.

The new `touch-action:none` fixture checks DOM pointer fidelity. It is not advertised as a pinch-zoom or fling-physics parity test. The supplied two-pointer stream also does not establish physical touchscreen sensing accuracy. The verification does not read hidden ViewRoot flags or claim timestamp-to-photon measurements. Android emulator correctness is separate from physical Pixel speed.

## Primary source basis

- Android View requestUnbufferedDispatch(MotionEvent): https://developer.android.com/reference/android/view/View#requestUnbufferedDispatch(android.view.MotionEvent)
- AOSP ViewRootImpl processPointerEvent, onBatchedInputEventPending, terminal-event handling: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/view/ViewRootImpl.java
- Inspected GeckoView source: https://github.com/mozilla-firefox/firefox/blob/032a9fc1ac0cc3209f7c142744ba2e40847c8086/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/GeckoView.java
- Chromium also has an Android unbuffered dispatch integration in some revisions; this one API call is NOT the requested exact Chromium scrolling port. That source port and engine-owned final AHardwareBuffer targets remain separate outstanding work.
