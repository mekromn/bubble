# Bubble 158 — single floating ViewRoot

Build 158 is a controlled architecture experiment based on the validated Bubble 157 source.

## Steady-state change

Bubble 157 split interactive floating CHAT into two application-overlay windows: the chrome card and an independently positioned Gecko page window. Bubble 158 removes the second page window completely.

The selected direct path is now:

`one TYPE_APPLICATION_OVERLAY Window/ViewRoot -> Bubble top chrome + GeckoView/native SurfaceView + Bubble bottom chrome`

Gecko still owns and renders directly into Mozilla's original SurfaceView. The page is not converted into a TextureView, ImageReader stream, bitmap, or Bubble-owned buffer.

## Work physically removed

- second `WindowManager.addView()` for the page
- page `WindowManager.updateViewLayout()` calls
- cross-window pre-draw geometry sampling
- cross-window layout-change synchronization
- page-window coordinate transforms / floor / ceil rounding
- separate page Back dispatcher
- separate page IME policy
- separate page WindowManager move-animation policy
- cross-window two-pixel seam compensation

Floating drag/resize now has one WindowManager geometry transaction for the complete browser card. Gecko's child SurfaceView and native chrome remain under one ViewRoot traversal and SurfaceControl hierarchy.

## Deliberately unchanged

- GeckoView / GeckoSession / GeckoRuntime
- direct SurfaceView renderer selection
- Gecko hardware configuration and fidelity
- gesture-scoped unbuffered input
- Bubble 157 efficiency changes
- Android 16 ADPF Surface-bound experiment
- transparency / bounded blur policy
- fullscreen renderer
- file, tabs, profiles, persistence and networking behavior

This is a Pixel runtime performance candidate. Compilation and CI do not prove scrolling parity with fullscreen; the physical device comparison is authoritative.
