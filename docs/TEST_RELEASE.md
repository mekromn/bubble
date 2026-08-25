# Bubble — Test, CI and Release Specification

## 1. Definition of done

A feature is not complete because it compiles. Production completion requires automated tests where practical, manual runtime verification for overlay/WebView behavior, and no known critical security or data-loss regressions.

## 2. CI required on every PR

- clean Gradle build
- unit tests
- Android lint
- formatting/static analysis
- debug APK artifact
- release compile check without publishing/signing secret exposure
- Room schema export/migration validation once Room exists

CI versions must be pinned deliberately. Do not use imaginary SDK/build-tool versions merely to satisfy a config file.

## 3. Unit test suites

### Tab state machine

Cover:

- create
- activate
- minimize
- restore
- close
- duplicate
- pin
- warm → saved → hibernated transitions
- recovery from renderer-gone
- illegal transition rejection
- idempotent duplicate intents

### Placement math

- normalized coordinates
- rotation
- status/navigation insets
- cutouts
- display-size changes
- clamping off-screen coordinates

### Navigation policy

- URL vs search text
- HTTP/HTTPS
- intent/mailto/tel/geo/market
- unknown schemes
- malicious/invalid intent URIs

### Persistence

- Room migrations
- ordering
- crash/restart reconstruction
- closed-tab restoration
- private data never entering normal tables

## 4. Instrumentation/UI tests

- normal browsing
- minimize active tab
- independent drag of at least several heads
- tap restore exact tab
- close head
- overlay permission grant/deny/revoke
- hide/show heads
- rapid minimize/restore loop
- new-window creates tracked tab
- file chooser cancel/select
- camera/mic permission deny/allow flows
- download initiation
- full-screen video enter/exit
- predictive back
- edge-to-edge/insets
- TalkBack labels/actions where automatable

## 5. Process and memory tests

Manually/instrumentedly test:

- Activity recreation
- orientation while browsing
- orientation while heads exist
- process death with several normal tabs and heads
- renderer crash via controlled test page / platform test mechanism
- low-memory trimming
- many logical tabs with only a small renderer pool
- restoring a hibernated head
- service killed/recreated by system where applicable
- force-stop semantics
- reboot followed by normal user launch

## 6. Device/API matrix

Minimum useful matrix:

- API 26
- API 29
- API 31/32
- API 34
- API 35
- API 36

First-class real-device target: Pixel 9 Pro XL on Android 16.

Add current stable API 37 testing after final SDK/device availability is verified, without making preview-only behavior a blocker for API 36 production.

## 7. Web compatibility test pages

Maintain local/test-server pages for:

- history pushState/back-forward
- target=_blank/window.open
- file input single/multiple
- camera/microphone request
- geolocation
- downloads with content-disposition
- authenticated download requiring cookie
- blob download
- full-screen video
- mixed-content attempt
- TLS error test domain in controlled environment
- renderer-hang/crash recovery

## 8. Performance gates

Measure rather than guess.

Track:

- cold start to browser chrome
- first WebView usable
- warm tab switch latency
- hibernated head restore latency
- head drag frame pacing
- memory with 1, 5, 20, 100 logical tabs
- memory with adaptive warm pool
- DB/session load time with large tab/history sets

Overlay dragging should remain smooth even when page renderer is busy.

## 9. Accessibility gate

Before production release:

- TalkBack can identify and activate heads;
- non-drag reposition/close actions exist;
- minimum touch targets;
- large font/display scale does not break browser chrome;
- contrast audit;
- keyboard navigation sanity check on large-screen/desktop mode.

## 10. Security gate

Review before release:

- no TLS proceed workaround;
- no unrestricted JS bridge;
- unsafe WebView file access disabled;
- external intent validation;
- file chooser URI validation;
- private-profile feature gating/cleanup;
- no sensitive release logging;
- exported components minimized;
- pending intent mutability flags correct;
- notification and FGS declarations match actual behavior.

## 11. Release signing

Never commit production signing keys or passwords.

GitHub Actions may build unsigned/release-check artifacts. Production signing should use protected secrets or an explicit local/release pipeline with least privilege.

## 12. Versioning

Use semantic application versions with milestone tags. Keep feature work in branches/PRs and do not mix unrelated behavior into one giant unreviewable commit.

Suggested progression:

- `0.1.x` core browsing/session/head correctness
- `0.2.x` browser completeness
- `0.3.x` advanced head management/private mode
- `1.0.0` production gate passed

## 13. Release artifacts

For candidate releases produce:

- signed APK where appropriate;
- AAB for Play distribution if pursued;
- mapping files if minification enabled;
- release notes;
- exact source commit/tag;
- test/known-issues summary.

## 14. Production blocker classes

Do not release with:

- reproducible tab/session loss;
- heads opening wrong tabs;
- overlay heads becoming unreachable;
- app crash on renderer termination;
- unsafe TLS bypass;
- private mode sharing normal cookies/history;
- persistent overlay without visible notification/service compliance;
- critical accessibility dead ends;
- CI build not reproducible from repository source.
