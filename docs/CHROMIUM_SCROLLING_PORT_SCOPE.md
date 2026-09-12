# Chromium scrolling port: source findings and scope

User decision, September 12, 2026: adopt `relay_latest_bp` for real Bubble now; investigate whether Chromium's exact scrolling can replace Firefox scrolling. This document is research, not a shipped scrolling change. The renderer integration deliberately leaves Gecko/APZ unchanged.

## What can be ported

An exact source-based port of a pinned Chromium fling/gesture algorithm into Gecko's APZ is feasible engineering work. Preserve its original algorithm and tests, adapt the surrounding types/clock units/input contract explicitly, retain applicable attribution/license notices, and compare deterministic position/velocity outputs against the pinned upstream implementation. Approximate friction tuning is not that port.

Complete Chrome scrolling is not a single decay curve. Input sampling and velocity estimation, gesture recognition, fling boosting/cancellation, axis locking, nested scroll targets/handoff, overscroll, snapping, pinch-zoom and compositor scheduling all affect observed behavior. Exact algorithm parity does not automatically establish identical end-to-end Chrome behavior.

## Existing shared ancestry

`mozilla/gecko-dev/gfx/layers/apz/src/AndroidFlingPhysics.cpp` explicitly states that its Android fling calculations are adapted from Chrome's Android scroller. Inspected blob: `be8c43853b5b14dce8472152bd63ea896771085d`. It implements spline distance/velocity, duration, deceleration and preference-backed friction/inflexion/stop threshold. This is source evidence of shared ancestry, not proof of which path/configuration our pinned GeckoView binary currently executes or exact parity with a current Chrome build.

Chromium's historical `ui/events/android/scroller.cc` path no longer resolves in the inspected main branch. The current `ui/events/mobile_scroller.cc` is the relevant mobile scroller file (inspected blob `822e552eb91218beb4e3a98681ec7ecd89ea72fa`). `ui/events/gestures/blink/web_gesture_curve_impl.cc` selects among mobile, fixed-velocity, feature-selected physics-based and other curves (inspected blob `e71f3c9d9394b5cce122df1f36e87a70ebddb8f0`). Therefore pin both source revision and actual curve-selection configuration, not just a familiar filename.

## Integration direction

1. Preserve the newly selected native relay as the rendering baseline. Do not add a second per-frame JavaScript scroll loop.
2. Identify the exact shipped GeckoView revision/configuration and Chromium Android reference build. Read their active input/fling dispatch paths.
3. Port any required Chromium physics/input components into a separately built GeckoView engine, with deterministic source-level parity tests for DPI, velocity, timestamps, direction changes, cancellation and repeated flings.
4. Adapt scroll targeting, nested containers, `touch-action`, pinch/overscroll, snapping and event acknowledgment without dropping semantic input events or moving asynchronous scrolling onto the web main thread.
5. Treat frame pacing and input-to-presentation behavior as separate engine/Android integration work. No toggle is called exact Chromium scrolling until the claimed behavior has actually been validated.

No decision to replace Gecko with WebView/Chromium, lose extensions, alter cookies or user profiles, or sacrifice fidelity is implied by this research.

## Primary references

- Mozilla AndroidFlingPhysics: https://github.com/mozilla/gecko-dev/blob/master/gfx/layers/apz/src/AndroidFlingPhysics.cpp
- Mozilla APZ architecture: https://firefox-source-docs.mozilla.org/gfx/AsyncPanZoom.html
- Chromium mobile scroller: https://github.com/chromium/chromium/blob/main/ui/events/mobile_scroller.cc
- Chromium curve selection: https://github.com/chromium/chromium/blob/main/ui/events/gestures/blink/web_gesture_curve_impl.cc
- Chromium compositor scrolling architecture: https://chromium.googlesource.com/chromium/src/+/HEAD/cc/input/
- Chrome team's July 23, 2026 account of input-routing, prediction, late-input handling and thread-hop changes: https://blog.google/chromium/smoother-scrolling-how-we-halved-scroll-jank-in-chrome-on-android/

The last reference reinforces why copying fling constants alone cannot promise the complete current Chrome scrolling experience. It is not a performance prediction for Bubble.
