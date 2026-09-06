# Bubble continuation — September 6, 2026

## Verified starting point

The supplied 5,395-line Bubble 2 handoff was read in full. The earlier chat, verified 0.7.1 source/evidence, and saved file-transfer candidate were recovered from ChatGPT Library. Live rebuild-v2 is newer than the exported status: c16ad8d62b976ca5c73e0c8fd71bc544461a9cbe already has the 0.7.3 transfer implementation, but run 34014291842 failed Android runtime verification. Preserve that work rather than overwrite it with the older candidate.

## Root cause found in this continuation

TransferDelegates installs `object : GeckoSession.ContentDelegate by original`. Kotlin interface delegation does not forward inherited Java default methods. A local Java/Kotlin reproduction returned DEFAULT from the wrapper and REAL from the original override. Gecko ContentDelegate uses Java default methods, so the wrapper drops title, first-paint and other lifecycle callbacks. Put onExternalResponse directly in the original content delegate; remove the wrapper. Do not substitute weaker rendering assertions.

The transfer test server also has an uncaught Broken pipe on a worker thread which aborts instrumentation. Handle ordinary connection cancellation within that worker, retaining substantive byte, account and picker assertions.

## Required scope / precedence

P0: actual upload/download round trips in fullscreen and interactive floating mode; original Gecko response streams, exact bytes, app-private provider staging, profile isolation, cancellation and useful errors.

Preserve: stable debug package and signing identity, existing account storage and UUID tabs; one live session owner; all-web visible/focus compatibility but exact-origin ChatGPT native bridge; real input focus; one bubble, chooser, floating chat, notification parking, drag-to-X, Home/Back, anchored cancellable motion, native hardware rendering, local tools and profiles.

### ChatGPT renderer-residency policy — newer requirement overrides earlier always-live wording

Logical tabs remain durable and unlimited, but **idle ChatGPT renderer sessions must not all remain resident**. Only tabs doing work, tabs being actively viewed, loading tabs, non-ChatGPT tabs under the existing compatibility policy, and tabs explicitly marked **Force keep alive** should stay live.

- When an exact-origin ChatGPT monitor reports `started`, that tab is protected from suspension and stays active/high priority even in the background.
- When that response reports `finished`, Bubble first performs the durable checkpoint and reply-notification decision, then an idle background tab should hibernate immediately.
- A background ChatGPT tab that is already idle auto-hibernates after a short grace period so a just-submitted response can still announce `started`.
- Opening/selecting a suspended tab is the explicit resume action and recreates its GeckoSession from bounded saved state/canonical URL.
- **Manual Suspend now** must exist per tab, but refuse to kill an in-flight response, page load, or active file-picker transfer.
- **Force keep alive** must exist per tab, persist locally, and override automatic suspension. An explicit manual suspend remains stronger than Force keep alive.
- Auto-hibernation releases the renderer; it does not close/delete the logical tab or server-side ChatGPT conversation.

Remaining explicit requests: real translucent blurred neutral black glass; text-only quick tabs; inward swipe-release chooser and inward swipe-hold last tab; physically remove native view-only PiP while retaining interactive floating chat; pop-out ChatGPT sidebar whose selected links open new same-profile tabs; local explicit draft recovery, prompt insertion, scratchpads, navigation/search, pins/names/recently-closed/unread navigation, reading controls, explicit copy/export. No automatic prompt send or private-data uploads.

Build policy: consolidate before one explicit verification, preserve Release-only binary publishing and existing rollback builds. No repeat artifact cleanup or unrelated repository changes. A green compile is not a runtime pass, a refresh request is not measured 120 fps, and synthetic profile/DOM fixtures are not authenticated ChatGPT verification.

## Latest interaction requests added in this continuation

Do not lose these newer requirements while fixing the 0.7.3 runtime gate:

- The fullscreen compact bottom bar must expose one-tap **Refresh** and **Share** controls.
- The fullscreen **floating-window button must appear before the tab counter**; this is the requested swap from the prior tab-counter/floating order.
- Interactive floating chat must expose **Refresh** and **Share** together in native chrome without depending on ChatGPT page DOM.
- Interactive floating chat must have a broad **bottom swipe handle**: swipe down to minimize to the user's configured resting access mode (bubble by default, edge indicator when edge access is enabled).
- When edge access is enabled with its visible indicator, the expanded floating chat must also expose a matching **side indicator** that can be swiped outward to minimize back to the edge handle.
- A bottom-handle minimize in edge mode must visibly **slide toward the configured edge-handle position** before the handle takes ownership; in bubble mode the existing conceal must shrink back into the saved bubble position.
- Floating-chat ↔ fullscreen transitions must use a matched grow/shrink motion rather than a discontinuous pop. Prefer GPU transforms/system scale-up transitions and avoid resizing/reflowing Gecko on every animation frame.
- These swipe zones must be small/native, directional, cancellable and haptic at commitment; they must not install a fullscreen touch interceptor or interfere with ordinary webpage scrolling.

## Consolidated run 8 evidence and current transfer fix

Run `34039675642` compiled/signed ARM64 and emulator APKs, passed 27/27 unit tests and lint with zero errors, but Android 16 instrumentation finished with 23 tests / 7 failures, so the workflow correctly left only a draft verification release and did **not** publish an APK.

The evidence separates real defects from UI-automation races:

- The authenticated redirected HTTP download succeeded with exact `WORK` response bytes and one server hit, proving the original Gecko response path works without refetching.
- Generated `blob:` downloads did not reach `ContentDelegate.onExternalResponse`; this is a real missing path.
- The real multi-file picker was visibly open with both requested files and `2 selected`; Android 16 labeled the confirmation action `Select`, while the test searched only for `Open`.
- Floating upload evidence showed the webpage's `Attach files` accessibility node still present; the old hard-coded page coordinate no longer hit it after floating chrome geometry changed.
- Floating Refresh, Share and swipe-down controls were present in captured accessibility evidence even though an immediate one-shot lookup raced the overlay accessibility tree.
- The requested fullscreen order was also present in evidence; its immediate layout lookup similarly raced Activity layout.
- Notification tests were tapping the text node's coordinates rather than the actionable SystemUI notification ancestor.

The current fix keeps ordinary HTTP downloads untouched and adds a generated-file bridge with a stronger security boundary: an isolated top-frame WebExtension content script intercepts only `<a download href="blob:…">`, sends only the Blob URL + suggested filename/MIME metadata to native code, validates same-origin ownership, then `GeckoWebExecutor` fetches the Blob from the **same GeckoRuntime**. Blob bytes never cross WebExtension/native messaging and are streamed through the same `BrowserDownloads` response consumer. The Android tests now use semantic/accessibility activation for webpage/file controls, accept Android's `Select`/`Open` picker labels, wait for accessibility/layout publication, and click actionable SystemUI notification rows. Exact-byte, profile/account, same-session, no-refetch and cancellation assertions remain intact.

Current source includes those controls, adaptive ChatGPT renderer hibernation, manual residency controls, matched fullscreen/floating motion, generated Blob download support, and hardened Android/unit regression fixtures. None of the new runtime behavior is considered verified until the next consolidated Android 16 workflow passes.

## Real-device manual validation — September 6, 2026

User installed the published ARM64 manual-test build on the target Pixel 9 Pro XL / Android 16 device and reports that **attachments and downloads work**. Treat general attachment-upload and download behavior as **manually validated on the real target device**. The Android emulator's remaining file-transfer failures are therefore not allowed to block manual product acceptance for those working paths; keep the automated exact-byte/profile/session tests as regression diagnostics and continue hardening the runner separately.

Do not infer more narrowly unreported subcases than the user actually tested. In particular, retain generated-Blob and floating-picker automated coverage until each specific subcase is either green in CI or explicitly confirmed in a real-device test report.
