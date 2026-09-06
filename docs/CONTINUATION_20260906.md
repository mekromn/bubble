# Bubble continuation — September 6, 2026

## Verified starting point

The supplied 5,395-line Bubble 2 handoff was read in full. The earlier chat, verified 0.7.1 source/evidence, and saved file-transfer candidate were recovered from ChatGPT Library. Live rebuild-v2 is newer than the exported status: c16ad8d62b976ca5c73e0c8fd71bc544461a9cbe already has the 0.7.3 transfer implementation, but run 34014291842 failed Android runtime verification. Preserve that work rather than overwrite it with the older candidate.

## Root cause found in this continuation

TransferDelegates installs `object : GeckoSession.ContentDelegate by original`. Kotlin interface delegation does not forward inherited Java default methods. A local Java/Kotlin reproduction returned DEFAULT from the wrapper and REAL from the original override. Gecko ContentDelegate uses Java default methods, so the wrapper drops title, first-paint and other lifecycle callbacks. Put onExternalResponse directly in the original content delegate; remove the wrapper. Do not substitute weaker rendering assertions.

The transfer test server also has an uncaught Broken pipe on a worker thread which aborts instrumentation. Handle ordinary connection cancellation within that worker, retaining substantive byte, account and picker assertions.

## Required scope / precedence

P0: actual upload/download round trips in fullscreen and interactive floating mode; original Gecko response streams, exact bytes, app-private provider staging, profile isolation, cancellation and useful errors.

Preserve: stable debug package and signing identity, existing account storage and UUID tabs; one live session owner; all-web visible/focus compatibility but exact-origin ChatGPT native bridge; real input focus; one bubble, chooser, floating chat, notification parking, drag-to-X, Home/Back, anchored cancellable motion, native hardware rendering, local tools and profiles.

Remaining explicit requests: real translucent blurred neutral black glass; text-only quick tabs; inward swipe-release chooser and inward swipe-hold last tab; physically remove native view-only PiP while retaining interactive floating chat; pop-out ChatGPT sidebar whose selected links open new same-profile tabs; local explicit draft recovery, prompt insertion, scratchpads, navigation/search, pins/names/recently-closed/unread navigation, reading controls, explicit copy/export. No automatic prompt send or private-data uploads.

Build policy: consolidate before one explicit verification, preserve Release-only binary publishing and existing rollback builds. No repeat artifact cleanup or unrelated repository changes. A green compile is not a runtime pass, a refresh request is not measured 120 fps, and synthetic profile/DOM fixtures are not authenticated ChatGPT verification.

## Latest interaction requests added in this continuation

Do not lose these newer requirements while fixing the 0.7.3 runtime gate:

- The fullscreen compact bottom bar must expose one-tap **Refresh** and **Share** controls.
- The fullscreen **floating-window button must appear before the tab counter**; this is the requested swap from the prior tab-counter/floating order.
- Interactive floating chat must expose **Refresh** and **Share** together in native chrome without depending on ChatGPT page DOM.
- Interactive floating chat must have a broad **bottom swipe handle**: swipe down to minimize to the user's configured resting access mode (bubble by default, edge indicator when edge access is enabled).
- When edge access is enabled with its visible indicator, the expanded floating chat must also expose a matching **side indicator** that can be swiped outward to minimize back to the edge handle.
- These swipe zones must be small/native, directional, cancellable and haptic at commitment; they must not install a fullscreen touch interceptor or interfere with ordinary webpage scrolling.

Current source commits add those controls and an Android runtime regression fixture. They are not considered verified until the consolidated Android runtime workflow passes.
