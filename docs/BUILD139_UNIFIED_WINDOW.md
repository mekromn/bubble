# Bubble 139 — one interactive floating window

## User-confirmed baseline and exact scope

The user confirmed Build138 works on the Pixel on 2026-09-12. Preserve its production commit `5734387fb129f87eacc2038c970b1da835556ebf` and branch. Build139 removes the standalone page WindowManager window, not the native ImageReader relay or Gecko engine. No Chromium scrolling port is claimed.

Same `com.mekromn.bubble.debug` update identity, existing signing certificate, non-debuggable performance variant, R8, native -O3/ThinLTO, 13 startup hardware preferences and disabled own diagnostic/native log work. No profile reset, telemetry, lower resolution/precision/AA, disabled feature or TextureView fallback.

## Removed intermediary

138: interactive chrome window + separate page-only overlay ViewRoot/window + the masked noninteractive glass backdrop.
139: interactive chrome AND native page input share one ViewRoot/window. The existing AHardwareBuffer output layer is a child of that window. The masked noninteractive glass backdrop remains because the requested blur belongs to chrome, not webpage pixels.

`FloatingGeckoWindow.show` now inserts its real FrameLayout/input View into the existing page slot. It no longer constructs WindowManager.LayoutParams or calls addView/updateViewLayout/removeViewImmediate. `FloatingWindow.place` performs one interactive-window layout operation: there is no second page-window movement/resize command. This is structural work elimination, NOT a measured FPS or latency claim.

The GeckoView-shaped RawSessionBridge remains detached. Its native input view reports the real shared window focus. The real FrameLayout parent remains the accessibility ViewParent. Resident GeckoSessions, text-input/key/drag delegates, APZ input forwarding and the one-display-owner lifetime remain.

## Page/chrome composition and movement

The output layer uses negative Z relative to the translucent chrome root. A DrawableWrapper excludes the page rectangle from BACKGROUND drawing with clipOutRect; native controls above the page remain visible/clickable. No bitmap, texture-cache layer, saveLayer or copy of webpage pixels is added.

An OnPreDraw listener sends only changed UI placement, visibility/alpha and crop metadata with AttachedSurfaceControl.applyTransactionOnDraw. It uses the actual input View's surface-coordinate offset, including surface insets, and its ancestor scale/translation/alpha. The window parent's motion carries the layer without a second WindowManager call. Screen origin is refreshed for Gecko after actual layout/movement.

The native acquisition/submission loop is independent of the chrome draw: no per-page-frame Java/JNI pump, timeout polling, or extra frame-timeline gate is added. Existing short card/property animations have UI update callbacks so the separate layer and glass cutout receive the same transforms. Circular reveals keep the new page covered until their mask finishes; this prevents a rectangular child surface escaping the circular native-chrome reveal. Existing fullscreen snapshot/morph code is untouched.

Resizes still recreate a generation at exact new dimensions rather than stretching an undersized page buffer. Pending creation cannot publish to a different tab/host. Position is first synchronized after nativeCreate's setup transaction, avoiding a race with its initial zero-origin write. Geometry synchronization failures have bounded attachment/UI retries, not an idle loop.

A setup-only black SurfaceControl background prevents an unfilled native page region becoming a permanent transparent hole. The first fenced buffer transaction removes it atomically. It is not a black bitmap or recurring paint pass. Host tests verify the clear operation occurs once per generation, not on each submission.

## Frozen native transport

PRIVATE ImageReader, six concurrently acquired images, four sequential acquisitions per drain pass, one native consumer, output backpressure ON. Native acquire/release fences, demand-driven capacity handshake, transaction/lease reuse, asynchronous generation retirement and failure recovery remain. Background initialization/first-buffer transition is the only additional native state; no pixel processing is introduced.

## Required verification (consult final run for outcome)

Run source guards, actual native C++ under ASan/UBSan, ARM64 performance unit tests/build/lint/signing/manifest/ELF audits, then the same optimized x86_64 Android16 browser integration. Publication is gated on that runtime result.

In addition to all 138 assertions (pixels, eight idle-resume changes, full text input, cold tab, six swaps, resize, fullscreen return, cleanup, 13 preferences), 139 checks:
- Input and chrome share the same root AND window token; the standalone page WindowState is absent.
- Native control placed over webpage pixels is visible and receives a real click.
- Page transforms/alpha follow the card, then return to exact settled size.
- Actual header drag preserves pixels and input; actual resize handle changes dimensions.
- IME becomes visible, full text arrives, Back hides it without collapsing the browser.
- Real touch scrolling changes a scrollable document, not a JavaScript scroll timer.
- Chooser removes the page; returning reattaches the resident tab.

These are correctness/structural tests, not physical Pixel speed, touch-to-photon timing, hardware-plane eligibility, or all-website certification. Reduced software-emulator geometry affects only CI. The engine binaries, hardware config and all 12 built-in page scripts are held unchanged.

## Primary API references

- https://developer.android.com/reference/android/view/AttachedSurfaceControl#applyTransactionOnDraw(android.view.SurfaceControl.Transaction)
- https://developer.android.com/reference/android/view/SurfaceControl.Transaction#setLayer(android.view.SurfaceControl,int)
- https://developer.android.com/reference/android/view/View#getLocationInSurface(int[])
- https://developer.android.com/ndk/reference/group/native-activity#asurfacetransaction_setcolor

Engine-owned AHardwareBuffer render targets and exact Chromium/APZ source-port work remain separate. This build does not represent the old PDFs' unsafe immediate front-buffer reuse or unverified latency promises as implemented behavior.
