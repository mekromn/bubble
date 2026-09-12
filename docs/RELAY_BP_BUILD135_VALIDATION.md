# Bubble 135 relay_latest_bp — actual validation record

## Artifact

- Branch: `relay-latest-bp-build115`
- Final build source: `4084b8d307cbf90fc6b7a655d004438c717a33a2`
- Version: `0.7.7-relay-bp`, versionCode 135
- Package: `com.mekromn.bubble.debug`
- APK: `Bubble-135-relay-latest-bp-ARM64.apk`
- APK SHA-256: `b6201284ba5ac897872a9fd7635c8af6b7b1b4b551a2794e51d0e3da27954048`
- Signing certificate SHA-256: `001a6f40ddcff14aec3aca71964fec58c29a32bdd0c649285bce60ae940c2b1f`
- Release: `bubble-relay-bp-34676729193-1`

ARM64 app compilation, unit tests, lint, APK signature verification, and x86_64 instrumentation compilation passed. The APK rebuilt from the cleaned source has the exact same hash as the first build; test setup and workflow changes did not alter application code.

## Runtime results: NOT PASSED

Run `34676147063`, first attempt: failed `first-page` readiness. Its screenshot shows the unrelated first-run `Test Bubble alerts now?` modal covering the browser. Native floating rendering was not reached. The subsequent test setup seeds only `notification-health/test-offer-v3` and restores it afterward. Production onboarding is unchanged.

Run `34676729193`, corrected attempt: page title/first-paint readiness passed, but the screen-pixel assertion timed out at `fullscreen-red`. Its failure screenshot visibly contains the magenta local test page behind an Android dialog stating **Pixel Launcher isn't responding**. The dialog dims the page, invalidating the expected pixel values. This is an emulator-environment obstruction, not evidence that the floating relay succeeded or failed. The test never reached its floating stage.

Both failures, screenshots, runtime text, and logcat are preserved in the respective `bubble-relay-latest-bp-verification` artifacts and release verification archives. Do not convert these results into a green runtime claim. No color/pixel assertion was weakened to bypass the system dialog.

## Remaining verification boundary

The chosen policy is implemented in the actual browser: PRIVATE ImageReader, six acquired-image capacity, four sequential acquisitions per drain pass, one native consumer, and output SurfaceControl backpressure enabled. The earlier user's probe results exercise that transport policy, not this new full-browser lifecycle integration.

Actual floating pixels, input/typing, first-open floating tabs, repeated tab swaps, resize, return to fullscreen, and lease retirement remain unverified by this automated test because it did not reach them. Physical Pixel behavior is untested for this APK. No input-latency, 120-fps, HWC-plane, or front-buffer claim is made.

Fullscreen application code, pinned Gecko engine/scrolling, and unrelated Build-115-derived features remain unchanged. Chromium scrolling research is separately documented; no Chromium scrolling port is shipped in Build 135.
