# Bubble — Android Platform, Privacy and Security Requirements

## 1. Overlay permission

Floating heads require `SYSTEM_ALERT_WINDOW` and `TYPE_APPLICATION_OVERLAY`.

Request the special overlay permission only through `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` after explaining the feature. Check `Settings.canDrawOverlays()` before adding windows.

Official reference: https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW

## 2. Foreground service

A persistent overlay-head service is a `specialUse` foreground-service case unless future Android guidance provides a more specific type.

Manifest requirements include:

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_SPECIAL_USE`
- service `android:foregroundServiceType="specialUse"`
- `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` with a clear explanation.

Official reference: https://developer.android.com/develop/background-work/services/fgs/service-types

The service must call `startForeground()` promptly with a real notification and stop when no visible heads remain.

## 3. Android 15+ background FGS restriction

Apps targeting Android 15+ cannot rely on merely holding overlay permission to start an FGS from background; the overlay exemption requires a visible `TYPE_APPLICATION_OVERLAY` window. Bubble should avoid this trap by starting the overlay service during the foreground user action that minimizes the first tab.

Do not implement boot/background resurrection that assumes an unrestricted FGS start.

Reference: https://developer.android.com/about/versions/15/behavior-changes-15

## 4. Background Activity Launch behavior

Restoring a browser Activity from a head tap is a direct user interaction and Bubble also holds the overlay permission when heads are displayed. Still use current background-activity-launch APIs/policies and avoid indirect surprise launches.

When PendingIntents are used, apply the narrowest modern activity-start mode such as `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` where applicable.

Reference: https://developer.android.com/guide/components/activities/secure-bal

## 5. Android 16 UI requirements

When targeting API 36:

- edge-to-edge is mandatory;
- migrate to predictive back; legacy `onBackPressed`/back key assumptions are insufficient;
- large-screen orientation/resizability restrictions are no longer a viable layout strategy.

Reference: https://developer.android.com/about/versions/16/behavior-changes-16

## 6. WebView renderer death

Implement `WebViewClient.onRenderProcessGone`. A dead WebView cannot be reused; remove/destroy references and create a replacement. The app must not crash because one renderer exits.

If lowering renderer priority for hidden/warm WebViews, termination handling is mandatory.

References:

- https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,android.webkit.RenderProcessGoneDetail)
- https://developer.android.com/develop/ui/views/layout/webapps/managing-webview

## 7. WebView state size

Do not serialize an unlimited browsing session into Activity `savedInstanceState`; Android’s Binder transaction budget can be exceeded.

Use bounded state snapshots and `WebViewCompat.saveState` when `WebViewFeature.SAVE_STATE` is supported.

Reference: https://developer.android.com/reference/androidx/webkit/WebViewCompat

## 8. HTTP and TLS

Bubble is a browser, so HTTP URLs must remain visitable. Do not set an application-wide cleartext prohibition that makes normal `http://` browsing impossible.

UI must clearly identify insecure HTTP.

For TLS/certificate errors:

- never call `SslErrorHandler.proceed()` as a generic workaround;
- cancel the navigation and show a browser error page;
- do not add an “ignore all certificate errors” setting.

## 9. WebView file security

Set unsafe file access off unless a narrowly scoped internal feature explicitly needs a safe alternative.

At minimum:

- `allowFileAccess = false`
- `allowFileAccessFromFileURLs = false` where available
- `allowUniversalAccessFromFileURLs = false`
- do not expose app-private paths to page content
- use `WebViewAssetLoader` for any controlled internal web assets.

Reference: https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion

## 10. JavaScript interfaces

Do not use unrestricted `addJavascriptInterface` on arbitrary internet pages.

If a feature truly requires a bridge, prefer modern origin-restricted WebMessage APIs and document exact allowed origins and message schema. On WebKit versions that support it, consider WebViewBuilder restrictions that prevent accidental unrestricted JavaScript-interface use.

## 11. Mixed content

Use `MIXED_CONTENT_NEVER_ALLOW` by default. Compatibility exceptions must be per-site/explicit and should not become a global hidden downgrade.

## 12. Safe Browsing

Enable WebView Safe Browsing where supported. Do not suppress threat interstitials silently.

## 13. Web permissions

Never auto-grant every resource in `PermissionRequest`.

For each request:

1. identify requesting origin;
2. map only requested web resource to needed Android permission;
3. obtain runtime permission if required;
4. grant only the specific resource;
5. otherwise deny.

Treat geolocation separately through WebView geolocation callbacks/profile permissions.

## 14. File chooser security

`onShowFileChooser` returns URIs from other apps and WebView does not guarantee they are safe. Validate scheme/access and do not expose app-sensitive files.

Reference: https://developer.android.com/reference/android/webkit/WebChromeClient#onShowFileChooser(android.webkit.WebView,android.webkit.ValueCallback,android.webkit.WebChromeClient.FileChooserParams)

## 15. External intents

- Use explicit package resolution when possible.
- Never execute arbitrary command-like data from a page.
- Validate `intent:` fallbacks.
- Avoid launching external apps without a user gesture unless it is a normal browser navigation that the user initiated.
- Prevent intent loops back into Bubble.

## 16. Private browsing

AndroidX WebKit multi-profile support provides independent browsing data such as cookies and storage. Use it when feature-supported.

References:

- https://developer.android.com/reference/androidx/webkit/ProfileStore
- https://developer.android.com/reference/androidx/webkit/Profile

If unavailable, do not claim private browsing is isolated.

## 17. Backups

Define Android backup rules so private/session-sensitive data is not unintentionally copied to cloud/device transfer. Decide deliberately which normal bookmarks/settings/session data can be backed up.

## 18. Logs

Release builds must not log:

- full visited URLs by default;
- form contents;
- cookies;
- authorization headers;
- private-mode activity;
- file chooser paths;
- web permission secrets/tokens.

Sanitize crash logs.

## 19. Telemetry

No mandatory analytics SDK. If diagnostics are added, default to minimal collection and never collect browsing content/URLs without explicit policy and user control.

## 20. Play policy/release note

Google Play requires new apps and updates submitted starting August 31, 2026 to target Android 16 / API 36 or higher.

Reference: https://support.google.com/googleplay/android-developer/answer/11926878

The `specialUse` foreground-service explanation must accurately describe the user-visible floating browser heads for Play review.
