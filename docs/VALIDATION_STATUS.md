# Clean-rebuild validation status

## Observed baseline: cd5185c

GitHub Actions run 33935856375 completed successfully. Its downloaded artifact 9960262173 was inspected, not only the job's green status. Android 16 x86_64 instrumentation reported `OK (5 tests)` in 154.524 seconds. The tests covered actual nonblank compositor pixels and running JavaScript, native touch/text entry and system back, multiple retained sessions, Activity recreation, one acknowledged overlay and exact-session restore, and stable painted Google/ChatGPT pages. Screenshots show Google's homepage and the signed-out ChatGPT home with a composer, not a challenge interstitial. This does not establish logged-in ChatGPT generation.

The native frame report is NOT a performance pass: the 60Hz software-rendered emulator recorded native p95 474.01ms and 37/37 deadline misses over its small sample. Physical-device performance remains unproven. Do not convert a display mode request or an emulator functional pass into a 120fps/zero-jank claim.

## 0.5.1 candidate changes

- Native tab-tray transitions use a temporary hardware layer, released after completion/cancellation; GeckoView is never placed in this animation layer. Reversals continue from their current state. Font and icon dimensions are cached.
- Gecko requests dark web content using its supported color-scheme preference, not CSS inversion or arbitrary script injection. Site/account appearance settings may still take precedence.
- Checkpoints are coalesced within 500ms of the first change, not delayed until changes stop. Continuous metadata activity must not starve durable saves.
- Deferred saved-session restoration errors are contained at the asynchronous callback boundary; malformed/incompatible snapshots fall back to the tab's retained URL.
- No extra native frame callbacks are scheduled when no UI/overlay listeners are attached. Browser sessions themselves are not paused by this change.
- Added instrumentation regressions for checkpoint starvation, dark preference and malformed snapshot fallback, and rapid tray-animation reversal/layer cleanup.

Validation for this candidate is pending the new CI run. Baseline results above must not be relabeled as candidate results.

## Still unproven / incomplete

Pixel 9 Pro XL installation and real 120Hz frame pacing; authenticated ChatGPT login, sustained multi-chat background streaming and reply notification behavior; file upload across real providers; microphone/camera grants and downloads; native renderer/process crash soak; legacy Room tab import and legacy cookie-profile continuity. Old Room files remain on disk untouched; they are not imported into the new workspace.

Same package `com.mekromn.bubble.debug`, original public development certificate, versionCode 21. Never rotate the key to fix an installation problem. Private production signing remains separate. No behavioral analytics SDK or new outbound diagnostics is added.
