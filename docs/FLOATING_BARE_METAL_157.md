# Bubble 157 — floating bare-metal pass

## Why this pass exists

Physical Pixel testing showed two independent problems after the opaque floating-page split:

1. the separately composed page can occasionally expose a visible seam above the bottom toolbar; and
2. steady-state floating-page scrolling still does not match the same GeckoSession when it is owned by the fullscreen Activity.

Build 157 does not reduce resolution, sampling quality, color fidelity, bit depth, Gecko/WebRender settings, or page content. The goal is to remove Android window/scheduler disadvantages while preserving the direct Gecko SurfaceView path.

## Seam hardening

The page remains an independent `PixelFormat.OPAQUE` `TYPE_APPLICATION_OVERLAY`, but it now:

- uses `SOFT_INPUT_ADJUST_NOTHING`; the containing chrome window is the single owner of keyboard/inset geometry instead of allowing WindowManager to resize the page independently;
- disables WindowManager position animations with `setCanPlayMoveAnimation(false)`; Bubble already performs its own geometry/transition choreography, so a second platform position animation can only make page and toolbar diverge;
- listens to the page-slot layout event as well as pre-draw, while still skipping identical geometry updates; and
- keeps a two-physical-pixel native-chrome underpaint below the opaque page perimeter. The page covers this normally. It exists only so a cross-window transaction ordering/rounding difference cannot expose the application below as a slit.

`RenderPolicy` also disables WindowManager move animation for all Bubble application-overlay windows, keeping page and chrome on the same immediate-position policy.

## Floating scheduling / ADPF

Fullscreen and floating already share Bubble's maximum-refresh policy, touch boost, unbuffered page input, GeckoRuntime, GeckoSession, and direct SurfaceView. A remaining difference is Android process/window state: the resumed fullscreen Activity is eligible for the system's top-app scheduling treatment, while a foreground-service application overlay is not equivalent to a resumed top Activity.

Build 157 uses the public Android 16 NDK Performance Hint API rather than hidden scheduler knobs:

- locate Mozilla/Android threads on the graphics critical path (UI caller plus RenderThread / compositor / WebRender / renderer / APZ names up to the device's graphics-pipeline-thread limit);
- create a graphics-pipeline performance-hint session;
- associate the session directly with the real Gecko SurfaceView `ANativeWindow`;
- enable automatic CPU/GPU timing only when the device reports support;
- retain the maximum-rate contract on the same native producer surface; and
- issue one workload-increase hint at touch-down to pre-announce scroll/fling work. There is no per-MOVE or per-frame Java/JNI hint loop.

If the Pixel does not expose the required Android 16 ADPF features, the feature simply remains inactive and browsing continues on the direct SurfaceView path.

## Measurement

The user-triggered Local frame measurements report now includes the last floating ADPF feature/result snapshot, observed floating refresh rate and process importance. This remains dormant when the user is not measuring.

`tools/probe-floating-composition.sh` now records process/cgroup/cpuset/scheduler excerpts along with SurfaceFlinger layers, timestats and optional composition/HWC trace. This is intended to answer, on the real Pixel, which difference remains after the 157 pass rather than guessing from compilation.

## Hard invariants

- No TextureView.
- No steady-state page bitmap/readback.
- No page resolution or fidelity reduction.
- No fast-math.
- No per-frame Java/JNI performance-hint callback.
- One GeckoRuntime and exactly one display owner per GeckoSession.
- Relay remains a reliability fallback only.
- Physical Pixel testing, not CI, decides whether scrolling actually improves.
