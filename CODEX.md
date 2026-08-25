# Codex Implementation Contract — Bubble

## Mission

Implement Bubble as a production-grade Android browser according to the repository specifications.

The defining feature is independent draggable floating browser heads: any logical tab can be minimized to a native Android overlay head and later restored. There must be **no application-defined maximum number of logical tabs/heads**.

## Source of truth

Read these files completely before editing code:

1. `README.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/UX_SPEC.md`
4. `docs/ARCHITECTURE.md`
5. `docs/PLATFORM_SECURITY.md`
6. `docs/TEST_RELEASE.md`
7. `docs/ROADMAP.md`

If the provisional scaffold conflicts with the specification, the specification wins. You may rewrite or remove provisional scaffold code/build configuration.

## Required architectural rule

**A logical tab is not a WebView.**

Do not create one permanently resident WebView for every tab/head. Implement a durable tab/session model plus adaptive WebView renderer residency/hibernation.

Do not add `MAX_TABS`, `MAX_HEADS` or an equivalent arbitrary cap.

## Implementation strategy

Work in small version-controlled phases matching `docs/ROADMAP.md`.

For each phase:

1. inspect existing implementation and tests;
2. create/continue a focused feature branch;
3. implement the smallest complete slice;
4. add tests;
5. run lint/unit/build plus relevant instrumentation/manual validation;
6. fix failures rather than weakening requirements;
7. commit atomically;
8. open/update a PR with exact validation performed and known gaps;
9. do not move to the next phase while the current phase gate is knowingly broken.

## Production requirements

- Kotlin Android app.
- Modern AndroidX libraries.
- Target API 36 for the 2026 production line; compile against a proven current stable SDK.
- First-class Android 16 and Pixel 9 Pro XL behavior.
- Edge-to-edge and predictive back.
- `SYSTEM_ALERT_WINDOW` overlay heads using small independent overlay windows.
- Compliant `specialUse` foreground service only while visible heads require it.
- Room migrations, not destructive production resets.
- DataStore settings.
- Renderer death recovery.
- Bounded WebView state saving.
- true profile isolation for private mode or no private-mode claim.
- Android Autofill/password-manager integration; no home-grown password vault.
- no unsafe TLS bypass.
- no unrestricted JavaScript interface.
- no broad storage permission when scoped APIs suffice.
- no hidden telemetry dependency.

## Important correction to provisional scaffold

Bubble is a browser and must support normal `http://` navigation. Do not retain a global `usesCleartextTraffic="false"` configuration that prevents HTTP browsing. Show insecure-page UI instead.

Also verify all Gradle, AGP, Kotlin, SDK and build-tools versions against actually available stable toolchains before committing them. The existing scaffold values are placeholders, not requirements.

## Head service rule

Do not run one service per head and do not keep WebViews inside the overlay service merely because a tab is a head.

One overlay foreground service manages lightweight native head windows. Renderer/session ownership remains in the browser/session architecture and is memory-policy controlled.

## UX non-negotiables

- free dragging is default;
- heads remain where placed;
- each head restores its exact logical tab;
- favicon/fallback identity;
- heads are individually closable;
- optional snapping/stacking must not be forced;
- user can temporarily hide all heads without closing tabs;
- many heads remain manageable from the in-app tab/head manager.

## Validation non-negotiables

Do not call a feature complete without runtime proof for UI/overlay behavior.

At minimum validate:

- fresh install;
- overlay permission denied/granted/revoked;
- several simultaneous heads;
- free dragging;
- exact-tab restore;
- rotation/configuration change;
- app background/foreground;
- process death/session restore;
- renderer termination recovery;
- low-memory tab hibernation;
- API 36 behavior;
- current Pixel device when available.

## Security non-negotiables

Treat arbitrary web content as hostile input.

Do not:

- proceed through TLS errors;
- grant every WebView permission request;
- expose `file://` app data;
- trust arbitrary file chooser URIs;
- blindly execute `intent:` or unknown schemes;
- log cookies/auth headers/private URLs;
- fake private mode when WebView profile isolation is unsupported.

## Completion condition

The project is complete only when the production gates in `docs/TEST_RELEASE.md` pass and a reproducible release candidate can be built from repository source.

Do not stop at a demo-quality floating-head prototype and call the project finished.
