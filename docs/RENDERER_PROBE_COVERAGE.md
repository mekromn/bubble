# Bubble 7 — expert matrix coverage, not predicted results

The three supplied September 11 documents cover (1) shared-buffer compatibility, (2) the C1–C16 test matrix, and (3) proposed fastest rendering paths. All three belong to the investigation. Their expected Pass/Fail outcomes and absolute latency numbers are hypotheses, not device measurements.

## Two different matrices

The existing probe's **17 trials per workload/round** are one stock-GeckoView reference plus eight window/transport variants in Activity and overlay windows. They are **not** an implementation of the expert's **16 consumer/producer/configuration combinations**. These numbers must never be conflated.

Keep actual webpage/Gecko benchmarks separate from synthetic EGL/Vulkan producer experiments. A native test pattern proving a front-buffer transport works does not prove that Gecko/WebRender uses that transport. Each native producer case needs both Activity and overlay hosting, matched buffers/workload, explicit capability/result reporting, and isolated-process recovery.

## C1–C16 inventory

| Expert ID | Proposed experiment | Current coverage |
|---|---|---|
| C1 | PRIVATE ImageReader, queued EGL producer | Queued ANativeWindow/AHardwareBuffer Gecko relay exists. Exact Gecko backend/producer negotiation is not measured; controlled synthetic EGL case still required. |
| C2 | PRIVATE ImageReader, queued Vulkan producer | Not implemented. |
| C3 | FRONT_BUFFER allocation usage with ordinary queued EGL | Not implemented as a rendering trial; allocation support alone is not this test. |
| C4 | Shared/auto-refresh ImageReader with unmodified EGL producer | Historical failing experiment, not a controlled current probe case. Do not expose production Bubble to it. |
| C5 | Shared PRIVATE ImageReader with mutable-render-buffer EGL single buffering | Not implemented. Must negotiate and query actual EGL config/surface state. |
| C6 | Same producer with RGBA ImageReader | Not implemented. |
| C7 | ImageReader with Vulkan shared-presentable swapchain | Not implemented. Query extensions and actual surface present modes first. |
| C8 | Direct AHardwareBuffer imported as EGLImage/FBO, front-buffer usage | Not implemented. Requires actual drawing and presentation, not just allocation. |
| C9 | Direct AHardwareBuffer imported into Vulkan | Not implemented. |
| C10 | Direct compositor-bound native window, negotiated EGL single buffering | Not implemented. |
| C11 | Direct compositor window, forced shared mode but ordinary EGL producer | Not implemented; dangerous mismatch reproduction belongs in an isolated synthetic producer process. |
| C12 | Direct compositor window with Vulkan shared present modes | Not implemented. |
| C13 | ImageReader producer with requested buffer count 1 | Not implemented. Record API availability/rejection and negotiated constraints; do not silently substitute maxImages. |
| C14 | ImageReader producer with requested buffer count 2 | Not implemented. maxImages/drain-limit variants are not an exact buffer-count test. |
| C15 | Direct manual two-AHardwareBuffer pool | Not implemented. Requires release-fenced reuse of each allocation. |
| C16 | Shared EGL ImageReader without auto-refresh | Not implemented. |

Additional required controls: direct queued compositor-bound EGL/Vulkan windows; FRONT_BUFFER usage absent/present; shared-demand versus shared-continuous refresh where exposed; explicit opaque/size/format choices; capability queries separated from allocation, import, producer, consumer, and presentation success.

## Evidence recorded so far

Probe build 6 source: `021d37bddf0480a467b94d7deb29815078ba3a55`.
CI run: `34600006037`, emulator API 36, x86_64, SwiftShader. ARM64 compilation/unit tests/lint/signing/publication passed. Emulator smoke **failed**; physical Pixel benchmarking has not occurred.

The saved four-trial smoke export records:

* GeckoView fullscreen reference: controller watchdog timeout. This label does not establish a native deadlock.
* Raw SurfaceView Activity and overlay cases: completed JavaScript reports, but no recorded Gecko first-contentful-paint callback; failed readiness gate.
* Queued AHardwareBuffer overlay relay: 114 acquisitions, 114 submissions, 114 releases, zero native acquisition errors, zero outstanding leases, teardown DRAINED. It also failed the first-contentful-paint gate. These counters are not 114 proven unique on-screen webpage frames.

The emulator's separate capability context reports front-buffer auto-refresh support and successful front/overlay allocation, but no mutable-render-buffer extension/config. This is a concrete reason not to treat allocation success as producer opt-in.

Source evidence and full smoke exports are retained through the read-only `probe-evidence.yml` workflow. Fix readiness/timing instrumentation without weakening the existing no-black-frame, lifecycle, release-fence, and no-fabricated-performance requirements.

## Completion criteria

A case is implemented only when its actual producer/consumer path exists and its requested state, observed state, errors, fences, lifetime, and output are recorded. Unsupported is a measured capability outcome; NOT_IMPLEMENTED is missing code. Never count one as the other.

Gecko engine modifications remain explicitly authorized. Genuine Gecko front buffering requires engine-side render-target/presentation integration plus the same browser workload and quality checks; adding a label or mutating an unaware producer's ANativeWindow does not implement it. No fidelity reduction, silent TextureView fallback, or forced-HWC/zero-latency claim.
