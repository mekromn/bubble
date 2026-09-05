# Bubble clean-rebuild validation — 0.5.2

## Exact tested candidate

- Application source commit: `91353eaf4784be0a5b05c723d168381979291862` on `rebuild-v2`, draft PR #12.
- VersionName 0.5.2; versionCode 22; package `com.mekromn.bubble.debug`; ARM64 phone APK; minSdk 26; targetSdk 36.
- GitHub Actions run `33970576013` completed successfully. Debug assembly, release assembly, Android lint, signing verification, emulator compilation, and runtime instrumentation passed.
- ARM64 artifact `9970820451`; runtime-evidence artifact `9970915073`.
- APK size: 196,869,248 bytes.
- APK SHA-256: `1f71d05bef36f1cfcc6717694c645e1d5b01b3692f2e43e08deaa332e656f0b1`.
- Certificate SHA-256: `001a6f40ddcff14aec3aca71964fec58c29a32bdd0c649285bce60ae940c2b1f`.

The downloaded artifact digests, ZIP CRCs, exact source identity, APK checksum, official CI apksigner result, and independent local APK v2 RSA signature/whole protected-content digest were verified. The certificate was compared against the actual permanent-key 0.4.3 APK and matches exactly. No debug key rotation occurred. The development key is intentionally public and is not a private production signing identity.

## Observed functional tests

Four unit tests passed, zero failures/skips. Android 16 x86_64 instrumentation reported `OK (8 tests)` in 189.708 seconds. This is emulator runtime evidence, not a claim that the ARM64 build was installed on a physical Pixel.

1. Native touch and text entry into a local HTML input, IME/system back, and tab-tray navigation.
2. Contentful paint, advancing JavaScript for more than 12 seconds, continued Activity lifetime, and actual nonblank compositor pixels.
3. Starting the floating service twice produces exactly one accessible workspace bubble. Its tap restores the same browser session and removes the foreground-owned overlay.
4. Multiple independent native sessions, repeated tab switching, Activity recreation, foreground/background transitions, and synthetic active-generation JavaScript progress while backgrounded. The synthetic test is not a real authenticated ChatGPT generation.
5. Stable nonblank compositor output for Google's homepage and the signed-out ChatGPT homepage. Inspected screenshots show the actual dark ChatGPT home and composer, not a challenge page.
6. Continuous metadata updates every 70ms still reach the committed workspace file; there is no 500ms idle gap to satisfy the old trailing debounce.
7. A real webpage sees the dark color-scheme preference; a malformed Gecko saved-state string falls back to the retained canonical URL and renders successfully.
8. Rapid reversal of the native tab-tray animation neither accidentally hides it nor retains its temporary hardware layer. GeckoView is never used as that animation layer.

The public-site report records Google at `https://www.google.com/` and ChatGPT at `https://chatgpt.com/`, both with loading=false, FCP=true, and nonblankStable=true. No account credentials or real conversation contents were used in CI.

## Changes since the first clean-rebuild candidate

The old application source remains replaced: one lazy main-process GeckoRuntime, no custom Application browser bootstrap in Gecko subprocesses, one Activity-owned native GeckoView, durable UUID tabs with independent GeckoSessions, and one explicitly requested aggregate workspace bubble. ChatGPT sessions are kept active/high-priority while retained, without voluntary hibernation. This does not make them immune to Android process reclamation or website behavior.

Native tray transitions use a temporary hardware layer that is released on completion/cancellation; their animation does not rebuild or transform the webpage renderer. Font and icon measurements are cached. Rapid reversals continue from their current visual position.

Persistence is coalesced into the first scheduled checkpoint rather than postponed until page updates stop. Deferred restoration failures are contained at the asynchronous boundary. Unobserved native UI updates do not request extra frame callbacks.

The 0.5.1 runtime run passed seven tests and failed the dark-preference assertion. Inspection found that GeckoRuntimeSettings.updateSettings() omitted the non-Pref color-scheme field when copying builder settings. Version 0.5.2 applies the supported color-scheme setter directly to the attached runtime before initial navigation. The unchanged dark-preference/fallback test now passes.

## Performance: NOT PASSED

The final run's native frame report is: 60.0Hz software-rendered emulator, 46 sampled native frames, recent p95 424.72ms, 46/46 deadline misses, no lost callbacks. These are poor timings, not a performance success. The previous baseline was also slow. Different short emulator samples must not be presented as calibrated optimization gains or physical Pixel performance.

Bubble requests high refresh on supported displays and separates native animations from Gecko, but neither that code nor passing functional tests proves 120fps or zero jank. Physical-device frame pacing, webpage compositor performance, and broader UI polish remain open gates. The optional local frame meter measures native-window timing, not webpage FPS.

## Still unproven or incomplete

- Pixel 9 Pro XL installation, real 120Hz frame pacing, thermal/power behavior and long-running crash/renderer soak.
- Authenticated ChatGPT sign-in, real concurrent background generations, and completion-notification behavior. Origin-restricted lifecycle monitoring is implemented, but its real-site behavior is not yet certified.
- File uploads across actual document providers, microphone/camera permission flows, and downloads. Voice/media grants and downloads are not complete.
- Legacy Room tab import and legacy cookie-profile continuity. Old database files are left untouched, but old tabs are not imported into the new workspace. A preserved file is not the same as a completed migration.

This is a runtime-tested development candidate, not a production-complete browser or a zero-jank release. Future docs-only commits do not change the binary's exact source commit recorded above.
