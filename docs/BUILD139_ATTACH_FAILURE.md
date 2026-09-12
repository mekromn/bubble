# Build139: confirmed constructor-time window-focus failure

## Working baseline

User-confirmed Build138 remains on `relay-latest-bp-fast138`, production source `5734387fb129f87eacc2038c970b1da835556ebf`. No baseline source, package identity or user profile was changed while investigating 139.

## Reproduction and actual exception

Initial 139 source `2560d7708ee0434703714276e22c9b343908c9f3` compiled and passed ARM64 tests/lint/signing/static hardware checks, but optimized Android16 integration run `34718084247` failed with `Timed out: floating`. This was a real runtime failure, not a passing release.

CI-only diagnostic run `34719008726` checked out exactly that source and added a delimited exception breadcrumb to the existing service catch. It reproduced the failure. Artifact `10305443737` (`bubble139-attachment-diagnosis-only`, ZIP SHA-256 `2069ca1908dcea2de2d6098d3b95504f0efdec075cc19f36529b1aa834d1f4be`) contains the original stack. The diagnostic workflow's green completion means evidence collection completed; its embedded browser test still FAILED and its binary was not published.

At 2026-09-12 21:12:28 UTC the captured exception was:

```text
java.lang.NullPointerException: Attempt to invoke virtual method
'boolean android.view.View.hasWindowFocus()' on a null object reference
  FloatingGeckoWindow$RawSessionBridge.hasWindowFocus
  android.view.View.onCreateDrawableState
  android.view.ViewGroup.onCreateDrawableState
  android.view.View.getDrawableState
  android.view.View.setBackgroundDrawable
  android.view.View.setBackground
  org.mozilla.geckoview.GeckoView.init
  org.mozilla.geckoview.GeckoView.<init>
  LiveGeckoView.<init>
  FloatingGeckoWindow$RawSessionBridge.<init>
  FloatingGeckoWindow.<init>
  FloatingWindow.build
  FloatingWindow.attach
  BubbleService.fulfillPending
```

The superclass constructor installs a stateful drawable. Its virtual focus query reaches the subclass before Kotlin initializes the subclass fields, including the raw native host. Directly calling `raw.hasWindowFocus()` is therefore unsafe during construction, even though the constructor parameter has a non-null Kotlin type.

## Fix and regression boundary

Source commit `a593528cc8510f151ccb4ea27acbae634ab043e1` introduces a nullable `focusHost: View?` assigned from the raw host after superclass construction. `hasWindowFocus()` returns false while that field is uninitialized, then delegates to the actual shared native host afterward. It does not manufacture permanent focus or change retained-session activity policy.

The embedded-host source guard now checks this constructor-safe delegate. The full integration test is unchanged: it must construct and display the real unified floating page, verify shared root/token, rendering, transforms, controls above page, real drag/resize/scroll, IME visibility, full typed text, tab lifetimes and cleanup.

Full corrected verification run: `34719095496`, testing the exact `a593528...` source. ARM64 artifact `10305723423` has APK SHA-256 `04dd6c7a121086aa6118a30ecb8f3a82bafd341bce6621dae843dd26f09d3074`; compile, unit tests, lint, signing and static policy checks passed. At the time this note was committed, the Android runtime outcome was still pending. Consult the final verification record rather than inferring a runtime pass from this note.

The ordinary APK does not contain the CI-only exception breadcrumb. No logging policy, native transport (PRIVATE/six acquired/drain-four/one consumer/backpressure ON), hardware preference, page script, engine binary, or image-quality setting changed in this fix.
