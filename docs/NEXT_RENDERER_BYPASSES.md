# Next rendering bypasses after the working 136 baseline

User request, September 12, 2026: reduce rendering intermediaries without losing functionality. This is an implementation roadmap, NOT a list of additional changes shipped in 137. Build 137's production code is daf2ee7aaa49554e1f00c4f0376cddc1fa41d737; it adds startup hardware preferences and finishes native relay log elimination. No Chromium scrolling port or new engine render target is shipped.

## 1. Final-target engine ownership

Current floating route: Gecko renders into an ImageReader-owned native window; an ImageReader listener wakes our eventfd consumer; that consumer acquires/selects an image and submits its existing AHardwareBuffer to SurfaceControl. This is already free of application pixel copies.

Candidate: custom Gecko compositor owns a release-safe AHardwareBuffer-backed render-target pool and submits the rendered allocation directly, with its GPU completion fence. This removes the external ImageReader acquire/listener/worker handoff rather than removing a nonexistent pixel copy. Storage, ownership tracking, presentation transactions and synchronization remain. Preserve output backpressure ON; this is nevertheless a NEW complete pipeline, not the same relay preset. Do not assume a one-buffer pool is safe or fastest.

Concrete inspected Firefox-154 hooks: gfx/webrender_bindings/RenderCompositorEGL.cpp, BeginFrame(), EndFrame(const nsTArray<DeviceIntRect>& aDirtyRects), Resume()/Pause() and GetBufferSize(). EndFrame currently forwards dirty rectangles to GL SetDamage and calls SwapBuffers. It also exports a native fence for Android hardware-buffer resource lifetime. A port must distinguish the producer-completion fence for a displayed target from fences protecting sampled resources.

Preserve FBO binding assumptions, viewport/scissor, retained/dirty content, depth/stencil/MSAA when required, color/alpha, WebGL/video interop, screenshots, context loss, resize and fullscreen/floating ownership. Engine source must be matched to the packaged binary before implementing: the inspected 154 release Git ref is not yet asserted to map to the embedded HG revision.

## 2. Unnecessary per-frame Java cleanup entry

In that inspected engine's Android BeginFrame, Gecko calls GeckoSurfaceTexture::DestroyUnused through JNI and then calls gl()->MakeCurrent again because texture destruction can alter the current context. The corresponding Java destroyUnused(context) synchronizes on sUnusedTextures, looks up/removes a context list, and returns immediately when no list exists. Thus an empty cleanup check still crosses JNI and takes a Java monitor.

Candidate: publish a native per-context pending-cleanup signal when the Java lifetime path enqueues a texture. Visit the Java destruction path only when work actually exists; restore GL context when that path may have changed it. Preserve destruction on the required context/thread and handle signals racing with draining, context removal and shutdown. The Java code explicitly calls finalize to avoid FD exhaustion; deleting cleanup or delaying it indefinitely is not an optimization. No measured significance or speedup is claimed for this call-site finding.

## 3. Redundant floating-window choreography

The actual floating page currently has its own TYPE_APPLICATION_OVERLAY root. Explore sharing the existing Bubble window/input hierarchy and composing its page as a child layer, avoiding separate ViewRoot/WindowManager attachment and geometry changes. Preserve focus, IME, hit testing, accessibility ViewParent, resize, glass, crop and layer ordering. An idle root is not proof of an extra per-frame pixel pass.

## 4. Redundant work, not required rendering

Carry trustworthy producer damage to presentation, including the accumulated difference from the last displayed content after skipped images. Reuse compatible raster/resources and isolate chrome invalidation from page updates. APZ already scrolls asynchronously; do not add JavaScript smooth scrolling or claim enabling APZ is a new implementation. New text/layout, missing raster tiles, filters, blending and color management still require their real work.

## 5. Unnecessary image staging

Use GPU-shareable WebGL/video images where the actual engine backend and format support it. Build 137 requests existing Android WebGL AHardwareBuffer sharing and MediaCodec preference; this does not prove every path avoids readback or conversion. Preserve required transforms/color conversion, video synchronization and fallbacks for unsupported codecs. Hardware video acceleration is not a generic DOM-scrolling acceleration switch.

## 6. Redundant wakeups and queued frame age

Our current release callback always wakes the consumer. Narrow this only with a race-safe protocol for pending work and capacity recovery. An unconditional deletion can strand progress after maxImages is reached. Engine-side scheduling can avoid superseded rendering opportunities and sample fresher scroll state, but semantic input events must never be discarded. Do not insert another VSYNC wait or force a software timer just to increase callback counts.

## 7. Avoid unnecessary GPU composition while keeping hardware composition

SurfaceFlinger can delegate eligible layer composition to the display hardware. Keep exact geometry, honest opacity and compatible buffers without removing the requested glass/visual effects. The app cannot guarantee a physical overlay plane. Forcing all system composition onto the GPU can add an intermediate target; hardware acceleration includes the display compositor, not only the GPU.

## Non-negotiable boundaries

Keep acquire/release fences, buffer lifetime checks, sandbox/process isolation, full resolution/precision/AA and semantic browser behavior. Keep the selected relay as the working reference. No guarantee of unchanged functionality or latency improvement is made before each changed full pipeline is validated. Source/transport tests are not physical touch-to-photon tests.

## Primary sources

- Inspected Firefox-154 release: https://github.com/mozilla-firefox/firefox/tree/032a9fc1ac0cc3209f7c142744ba2e40847c8086
- Render compositor: gfx/webrender_bindings/RenderCompositorEGL.cpp (blob abd60ffb73e76a5f43b272b4b326b77d2110e3fd)
- Java cleanup: mobile/android/geckoview/src/main/java/org/mozilla/gecko/gfx/GeckoSurfaceTexture.java (blob 36a6411c5c226c025cbac694fe59b2be304b7912)
- APZ: https://firefox-source-docs.mozilla.org/gfx/AsyncPanZoom.html
- HWC: https://source.android.com/docs/core/graphics/implement-hwc
- Android hardware view layers: https://developer.android.com/develop/ui/views/graphics/hardware-accel
- Correct Gecko configuration documentation URL: https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/automation.html
