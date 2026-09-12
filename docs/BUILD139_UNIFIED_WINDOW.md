# Bubble 139 — one interactive floating window

## User-confirmed baseline and exact scope

The user confirmed Build138 works on the Pixel on 2026-09-12. Preserve its production commit `5734387fb129f87eacc2038c970b1da835556ebf` and branch. Build139 removes the standalone page WindowManager window, not the native ImageReader relay or Gecko engine. No Chromium scrolling port is claimed.

Same `com.mekromn.bubble.debug` update identity, existing signing certificate, non-debuggable performance variant, R8, native -O3/ThinLTO, 13 startup hardware preferences and disabled own diagnostic/native log work. No profile reset, telemetry, lower resolution/precision/AA, disabled feature or TextureView fallback.

## Removed intermediary

138: interactive chrome window + separate page-only overlay ViewRoot/window + masked noninteractive glass backdrop.
139: chrome AND native page input share one ViewRoot/window. A platform SurfaceView underlay anchors the existing AHardwareBuffer output control inside that window. The noninteractive glass backdrop remains: the requested blur belongs to chrome, not webpage pixels.

`FloatingGeckoWindow.show` inserts its actual FrameLayout/input host into the existing page slot. It no longer constructs WindowManager.LayoutParams or calls WindowManager addView/updateViewLayout/removeViewImmediate. `FloatingWindow.place` updates only one interactive window; there is no second page-window move/resize command. This is a structural reduction, NOT a measured FPS or latency claim. The SurfaceView anchor still has platform surface bookkeeping; it is not advertised as zero-overhead or as removal of every layer.

The GeckoView-shaped RawSessionBridge remains detached and delegates actual shared-window focus. Its constructor-time delegate is nullable because the GeckoView superclass can query focus before Kotlin fields are initialized; see BUILD139_ATTACH_FAILURE.md. The real FrameLayout parent remains the accessibility ViewParent. Retained sessions, text-input/key/drag delegates, APZ input forwarding and one-display-owner lifetime remain.

## Why the initial negative-Z attempt was rejected

Corrected-constructor source `a593528cc8510f151ccb4ea27acbae634ab043e1`, run `34719095496`, reached shared-root/token checks, real floating pixels and transform/alpha checks. It then FAILED `native-control-over-page`: the green native control was hidden beneath the page. Verification artifact `10305863689`, ZIP SHA-256 `ff832627c658e03dffc38f06a2ae98b1ce6ea18b49d2a6905e16514d44643086`, preserves the screenshot and failure. That APK was not released.

AOSP's current ViewRootImpl buildReparentTransaction parents children under a bounds container. A negative child layer number is not a promise that its entire ancestor subtree sits below the window's buffer. A source regex checking `setLayer(-1)` therefore did not establish the required visual order; the actual pixel/control test caught this distinction.

Source `8c3baa44c3eea2662dd81c87152e8b9f457a7f58` changes the host to SurfaceView with `setZOrderOnTop(false)` and uses its documented child-parent SurfaceControl. Android owns the underlay's relative Z and render-timeline geometry. Our output control is above the anchor's unused buffer layer but the whole anchor hierarchy is beneath chrome. Native controls drawn later in the same view hierarchy remain eligible to cover it.

IMPORTANT: this is NOT a switch from the chosen relay to Gecko's ordinary SurfaceView producer. `holder.surface` is never passed to Gecko and receives no page frames, canvas drawings or GPU copies. Gecko still receives the PRIVATE ImageReader producer through the unchanged native bridge; only that relay's child control receives the fenced output buffers. The anchor is not mutated through SurfaceControl transactions: its control is used only as a parent, in accordance with the public API.

## Page/chrome composition and movement

A DrawableWrapper excludes the page rectangle from background drawing with clipOutRect. SurfaceView provides the actual native-underlay hole and relative ordering. No app bitmap, TextureView, saveLayer or copy of webpage pixels is added. SurfaceView's own platform surface/clear bookkeeping remains.

SurfaceView now supplies the position, scale, crop ancestry and render-timeline placement. The old manual absolute surface-offset/matrix calculation was removed to avoid double-transforming its children. An OnPreDraw listener sends only changed ancestor opacity, reveal coverage and exact child crop using applyTransactionOnDraw. Screen origin is refreshed for Gecko after actual movement/layout.

The native acquisition/submission loop remains independent of chrome drawing: no per-page-frame Java/JNI pump, polling timer or extra frame gate is added. Short existing UI animations still invalidate changed metadata. Circular reveals keep the native page covered until the native-chrome mask finishes. Existing fullscreen snapshot/morph code is untouched.

The anchor's SurfaceHolder callbacks bound creation/destruction. Its empty redraw handshake is completed without claiming that the native first frame has arrived; that frame remains independently fenced. Resizes recreate the producer at exact new dimensions, never stretch an undersized page buffer. Generation checks prevent stale asynchronous creation from publishing into another tab/host. Setup/UI retries remain bounded.

A setup-only black SurfaceControl background is removed atomically with the first fenced buffer. It is neither a black bitmap nor a recurring paint pass. Host tests verify the transition happens once per generation.

## Frozen native transport

PRIVATE ImageReader, six concurrently acquired images, four sequential acquisitions per drain pass, one native consumer, output backpressure ON. Native acquire/release fences, demand-driven capacity handshake, transaction/lease reuse, asynchronous generation retirement and failure recovery remain. The underlay correction changes no native C++ code, engine binary, page script, hardware setting, signing configuration, version or runtime assertion.

## Verification gate and limitations

Source guards, real native C++ ASan/UBSan tests, ARM64 performance unit tests/build/lint/signing/manifest/ELF audits, then the same optimized x86_64 Android16 integration are required. Publication remains gated on the runtime result. Underlay-correction verification run: `34719970404`, exact app source `8c3baa44c3eea2662dd81c87152e8b9f457a7f58`. Consult the final verification record for its outcome; this architecture document alone does not assert a pass.

Runtime retains all 138 assertions and the new 139 checks: shared root AND token; absent standalone page WindowState; visible and clickable native control over page; transforms/alpha and exact settled size; actual header drag/resize; IME visible, complete typed text and Back hiding only IME; real touch scrolling; chooser/reattach; cold tab and repeated tab swaps; fullscreen return; all leases/generations reclaimed; all 13 engine preferences read back.

These establish emulator correctness/structure, not physical Pixel speed, touch-to-photon latency, hardware-plane eligibility or all-website certification. Reduced software-emulator geometry is CI-only. No runtime assertion was weakened to admit an invisible control or a nonworking keyboard.

## Primary API/source references

- https://developer.android.com/reference/android/view/SurfaceView#getSurfaceControl()
- https://developer.android.com/reference/android/view/SurfaceView#setZOrderOnTop(boolean)
- https://developer.android.com/reference/android/view/AttachedSurfaceControl#applyTransactionOnDraw(android.view.SurfaceControl.Transaction)
- https://android.googlesource.com/platform/frameworks/base/+/1424d3ce2511632725092920194e0c7075ffc168/core/java/android/view/ViewRootImpl.java
- https://developer.android.com/ndk/reference/group/native-activity#asurfacetransaction_setcolor

Engine-owned AHardwareBuffer targets and exact Chromium/APZ source-port work remain separate. Unsafe immediate front-buffer reuse or unverified latency claims from earlier research are not represented as implemented behavior.
