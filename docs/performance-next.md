# Bubble floating renderer next A/Bs

These experiments remain isolated from `rebuild-v2` and from one another.

## AHardwareBuffer
- Start from the Build-115-derived direct SurfaceControl renderer only after its physical Pixel test.
- Native bridge owns AHardwareBuffer allocation/lifetime and synchronization fences.
- Use GPU-compatible usage bits; no CPU lock/readback in the hot path.
- Submit buffers to an ASurfaceControl via ASurfaceTransaction_setBuffer with the acquire fence.
- Preserve Gecko runtime/session/APZ/input/IME/accessibility policy and full rendering fidelity.
- Measure windowed scrolling cadence and SurfaceFlinger composition against Build 115 and direct SurfaceControl.

## ANativeWindow
- Separate A/B, not combined with AHardwareBuffer.
- Convert the direct Java Surface to ANativeWindow in JNI and keep native producer/lifecycle/frame-rate control there.
- No ANativeWindow_lock()/CPU pixel path for normal rendering; retain GPU producer semantics.
- Preserve exact Build-115 Gecko runtime/session policy and full fidelity.
- Measure windowed cadence independently.

Never merge either experiment into the main renderer until physical Pixel 9 Pro XL testing proves it better and at least as stable as Build 115.
