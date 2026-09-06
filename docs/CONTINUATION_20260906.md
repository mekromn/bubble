# Bubble continuation — September 6, 2026

## Verified starting point

The supplied 5,395-line Bubble 2 handoff was read in full. The earlier chat, verified 0.7.1 source/evidence, and saved file-transfer candidate were recovered from ChatGPT Library. Live rebuild-v2 is newer than the exported status: c16ad8d62b976ca5c73e0c8fd71bc544461a9cbe already has the 0.7.3 transfer implementation, but run 34014291842 failed Android runtime verification. Preserve that work rather than overwrite it with the older candidate.

## Root cause found in this continuation

TransferDelegates installs `object : GeckoSession.ContentDelegate by original`. Kotlin interface delegation does not forward inherited Java default methods. A local Java/Kotlin reproduction returned DEFAULT from the wrapper and REAL from the original override. Gecko ContentDelegate uses Java default methods, so the wrapper drops title, first-paint and other lifecycle callbacks. Put onExternalResponse directly in the original content delegate; remove the wrapper. Do not substitute weaker rendering assertions.

The transfer test server also has an uncaught Broken pipe on a worker thread which aborts instrumentation. Handle ordinary connection cancellation within that worker, retaining substantive byte, account and picker assertions.

## Required scope / precedence

P0: actual upload/download round trips in fullscreen and interactive floating mode; original Gecko response streams, exact bytes, app-private provider staging, profile isolation, cancellation and useful errors.

Preserve: stable debug package and signing identity, existing account storage and UUID tabs; one live session owner; all-web visible/focus compatibility but exact-origin ChatGPT native bridge; real input focus; one bubble, chooser, floating chat, notification parking, drag-to-X, Home/Back, anchored cancellable motion, native hardware rendering, local tools and profiles.

Remaining explicit requests: real translucent blurred neutral black glass; swap tab-counter/floating controls; text-only quick tabs; inward swipe-release chooser and inward swipe-hold last tab; physically remove native view-only PiP while retaining interactive floating chat; pop-out ChatGPT sidebar whose selected links open new same-profile tabs; local explicit draft recovery, prompt insertion, scratchpads, navigation/search, pins/names/recently-closed/unread navigation, reading controls, explicit copy/export. No automatic prompt send or private-data uploads.

Build policy: consolidate before one explicit verification, preserve Release-only binary publishing and existing rollback builds. No repeat artifact cleanup or unrelated repository changes. A green compile is not a runtime pass, a refresh request is not measured 120 fps, and synthetic profile/DOM fixtures are not authenticated ChatGPT verification.
