# Bubble 156 — opaque floating page experiment

## Why this build exists

Physical testing showed that Bubble 151's newer code scrolls better than Bubble 150, but the same page still scrolls materially worse in the interactive floating window than fullscreen. Transparency/blur ON versus OFF made almost no visible performance difference on the Pixel test device, so Build 156 targets the remaining structural difference instead of spending more time on glass effects.

Fullscreen is a normal Activity window containing GeckoView's original SurfaceView. Before Build 156, floating also used GeckoView's original SurfaceView, but that SurfaceView lived inside Bubble's large `TYPE_APPLICATION_OVERLAY` chrome window whose pixel format is translucent. The page itself was already direct and relay-free, but the whole floating card still belonged to that translucent overlay scene.

## Build 156 architecture

The floating CHAT page now gets a second, exact-size `TYPE_APPLICATION_OVERLAY` window whose pixel format is `PixelFormat.OPAQUE`.

- The independent page window contains Mozilla's original GeckoView/SurfaceView. No TextureView is introduced.
- The exact same GeckoSession transfers between fullscreen and floating.
- The page window occupies only the middle rectangle between Bubble's 52dp top chrome and 48dp bottom chrome.
- Bubble's existing translucent chrome window remains responsible for header/footer controls, drag/resize, glass and transitions.
- The chrome background still clips out the page rectangle. The opaque page window is above that transparent hole, so the underlying app and transparent Bubble middle are fully occluded in the page rectangle.
- The page window uses the same maximum-refresh `RenderPolicy` and hardware-acceleration flags as the previous direct path.
- Moving/resizing geometry is synchronized only from chrome pre-draw/animation events. Identical geometry is skipped; there is no timer or polling loop.
- Short reveal transitions use Window compositor alpha; Gecko's SurfaceView stays attached and warm.
- If Android rejects or later loses the independent page overlay, Bubble falls back to the previous same-ViewRoot direct SurfaceView path rather than falling back immediately to the ImageReader relay.

This is deliberately an experiment. An opaque overlay does not guarantee Hardware Composer/device composition; the Pixel's SurfaceFlinger/HWC decides that at runtime. It also does not prove 120 fps just because Bubble requests the highest refresh rate.

## Physical compositor probe

`tools/probe-floating-composition.sh` records the device evidence that source inspection cannot provide.

Recommended comparison:

1. Install Build 156 and open one long, repeatable page.
2. Run `tools/probe-floating-composition.sh start` from a host with adb access.
3. Scroll fullscreen for about ten seconds and run `tools/probe-floating-composition.sh snapshot fullscreen`.
4. Open the same tab in floating CHAT mode, use the same window size for repeated runs, scroll for about ten seconds and run `tools/probe-floating-composition.sh snapshot floating`.
5. Optionally run `tools/probe-floating-composition.sh trace 10` once in each mode. The Perfetto trace requests SurfaceFlinger layer composition and HWC metadata.
6. Run `tools/probe-floating-composition.sh stop`.

The SurfaceFlinger timestats dump provides per-layer `averageFPS` and `presentToPresent` histograms where supported. The script retains full layer and SurfaceFlinger dumps because layer naming and composition dump formats vary by vendor. Build 156 gives its independent window the stable title `Bubble opaque floating page` to make the relevant hierarchy easier to locate.

## What would validate the hypothesis

The strongest success signal is the floating Gecko SurfaceView moving materially closer to fullscreen in subjective scrolling and in SurfaceFlinger present-to-present timing. A trace showing the page layer staying in device/HWC composition more consistently, or avoiding client/GPU composition that the previous floating path triggered, would explain the improvement.

If performance is unchanged, the likely bottleneck is not transparent chrome composition. The next targets are overlay-window scheduling/focus/input arbitration, actual Gecko SurfaceView buffer cadence, and Pixel-specific SurfaceFlinger refresh arbitration.

## Invariants

This build does not lower page resolution, Gecko/WebRender quality, image quality, color fidelity, bit depth, source frames, attachment fidelity or refresh-rate requests. It does not add a page bitmap cache, idle animation, periodic poller or relay stage. Compile/CI success is not treated as physical runtime proof.
