# Bubble 7: expert response and Gate 1 disposition

Basis: the user's three-page September 11 expert response, checked against source `3ec40c91a3fd60036e742a56479bd5af9b49adac`. The raw user PDF and device logs are not uploaded here. The migration workflow applies exact-anchor corrections, runs host tests, commits ordinary source files, then builds. A passing source test is not Android or Pixel runtime validation.

## Harness and interaction

Accepted: the three manually failed Build 7 G01 fullscreen references are not a working baseline. Existing transparent-parent/deferred-session changes remain candidates, not a proven physical repair. G01 visibility remains OPEN.

Correction: no Visible button or positive confirmation tap. Completion is automatic with `OK_NO_VISUAL_FAILURE_REPORTED`, an explicit assume-visible policy, and no claim of independently verified visibility. Black / broken records VISUAL_FAIL and its reporting timestamp, without classifying the reporting button itself as unrelated touch contamination. Stop stays CANCELLED, consistent with the user's existing instruction, rather than the PDF wording that both buttons mean visual failure. Other page touches still invalidate timing. Synthetic benchmark input is distinguished from human input. Existing paint/native-error checks stay active; cleanup errors do not overwrite failure/cancellation.

Old exports retain their original visibility policy and are not silently mixed with the new policy for inference.

## Implementation versus specification

Accepted: C1-C16 controlled synthetic producers and H01-H10 engine integrations remain OPEN. Existing Gecko queue variants are not those implementations. No new engine hook or front-buffer implementation is claimed.

Correction: add `relay_pool6_drain2` and `relay_pool4_drain4`. Alongside the existing (6,4) and (4,2) no-backpressure arms, this isolates acquisition capacity from drain limit. These are NOT producer buffer-count controls. The current host matrix becomes 22 cases/workload/block; UI counts derive from the actual plan.

## API and ownership

The earlier proposals contain the wrong GLsync-to-native-fence export, reader-level extraction name, nonexistent transaction-fence helper, public-NDK buffer-count assumption, and PRIVATE-as-AHB-format example. The existing relay already uses AImage_getHardwareBuffer, setBufferWithRelease with the acquire FD argument, and AImage_deleteAsync with the release FD. Guards preserve those calls and forbid the incorrect substitutes. No producer-side fence-export hook exists here; its eventual implementation still needs a real synchronization contract.

Reviewed public references:
- https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_native_fence_sync.txt
- https://developer.android.com/ndk/reference/group/native-activity
- https://developer.android.com/ndk/reference/group/media

Separate build defect: the prior v2 run failed lint on FrameMetrics.FRAME_TIMELINE_VSYNC_ID, a documented API-36 metric absent from the selected SDK getMetric IntDef. The correction scopes WrongConstant suppression to one helper; -1 still means unavailable. No global lint disable.

## Telemetry and statistics: source distinctions

Confirmed: the old Python comparator silently drops 11-column native sample arrays from presentation extraction. The actual report is JSON sampleColumns plus arrays, not CSV. A crash is not established by that branch of the inspected parser.

Correction: name-based decoding with explicit 7/11-column legacy fallback, reordered/extra named fields, malformed-row accounting, coverage and observed latency, partial/pending exclusion, and duplicate-pair detection. Reused buffer IDs remain allocation identities, not frame counts.

The newer JS report already labels root HWUI, page-host jank, and test-only WebGL GPU scopes; it already compares paired whole blocks with practical margins. TrialPlan already randomizes and counterbalances. Those facts limit the review's blanket claims, but do not establish adequate independent device repetitions, correct per-layer telemetry, or a validated confirmatory study. No overall winner is selected.

## Workloads and environment

OPEN: compositor-only CSS, nested/pinch gestures, rich markdown/layout churn, real-use fullscreen raster size, competing foreground apps, full Bubble chrome, resize/orientation stress, and production-browser reproduction. Six workload labels do not close these gaps.

## Validation boundary

Local host checks: five existing Python tests, twelve new JSON-schema tests, no-touch/source-contract guards, existing paired-block analysis tests, and workload parsing/guards passed. Two Java pool/control tests are added for CI. The migration workflow separately records actual ARM64 compilation, Java tests, lint, signature, source hash and artifacts. It does NOT execute the APK on Android or certify G01 on Pixel. Gate 1 stays open until actual runtime and visual verification; C/H paths remain distinct unfinished implementation work.
