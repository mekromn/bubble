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
8. `docs/IMPLEMENTATION_ISSUES.md`

GitHub issues #2 through #9 are mirrored in `docs/IMPLEMENTATION_ISSUES.md`. Live GitHub issue access is useful but is **not required to continue implementation**.

If provisional scaffold conflicts with the specification, the specification wins. You may rewrite or remove provisional scaffold code/build configuration.

## Required architectural rule

**A logical tab is not a WebView.**

Do not create one permanently resident WebView for every tab/head. Implement a durable tab/session model plus adaptive WebView renderer residency/hibernation.

Do not add `MAX_TABS`, `MAX_HEADS` or an equivalent arbitrary cap.

## Requested long-horizon execution

When asked to implement issues #2 through #9 in one PR, use one branch based on `main`, implement the phases in order, and make coherent phase-sized commits. Do **not** stop merely because the environment cannot reach GitHub, cannot create a PR, cannot download Gradle/SDK packages, or lacks an emulator/device.

External capability failures are validation/publication blockers, not automatic coding blockers.

For each phase:

1. inspect existing implementation, tests, authoritative docs and the mirrored issue contract;
2. implement the complete phase without knowingly carrying a design defect into the next one;
3. add tests and migrations with the implementation;
4. run every available local/static check;
5. if a required build/runtime/CI/GitHub check cannot run because infrastructure is unavailable, record the exact command and blocker in `docs/VALIDATION_STATUS.md`;
6. continue sequentially when no known implementation defect blocks the next phase;
7. commit each coherent phase/slice;
8. once external access is available, execute all deferred validation, fix resulting defects, and publish/update the requested single PR.

Never claim a blocked check passed. Never lower acceptance criteria to make a phase appear complete. The final v1.0 production claim remains gated on real build/runtime/CI evidence.

## Production requirements

- Kotlin Android app.
- Modern stable AndroidX libraries.
- Production namespace/application ID `com.mekromn.bubble`.
- Target API 36 for the 2026 production line; compile against a proven stable SDK.
- First-class Android 16 and Pixel 9 Pro XL behavior.
- Edge-to-edge and predictive back.
- `SYSTEM_ALERT_WINDOW` overlay heads using small independent overlay windows.
- Compliant `specialUse` foreground service only while visible heads require it.
- Room migrations, not destructive production resets.
- DataStore settings.
- Renderer death recovery.
- Bounded WebView state saving.
- True profile isolation for private mode or no private-mode claim.
- Android Autofill/password-manager integration; no home-grown password vault.
- No unsafe TLS bypass.
- No unrestricted JavaScript interface.
- No broad storage permission when scoped APIs suffice.
- No hidden telemetry dependency.

## Important correction to provisional scaffold

Bubble is a browser and must support normal `http://` navigation. Do not retain a global `usesCleartextTraffic="false"` configuration that prevents HTTP browsing. Show insecure-page UI instead.

Verify Gradle, AGP, Kotlin, SDK and build-tools versions against stable/currently supportable toolchains before calling the final build validated. If the coding environment cannot download or execute those tools, keep the versions internally consistent, configure CI to prove them later, and record the deferred verification rather than stopping all implementation.

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

Final production completion requires runtime proof for UI/overlay behavior. At minimum the deferred/final validation matrix includes:

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

If those cannot run in the current sandbox, implement the testability/hooks and continue, but list them as **BLOCKED / NOT YET PROVEN** in `docs/VALIDATION_STATUS.md`.

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

Do not stop at a demo-quality floating-head prototype and call the project finished; conversely, do not stop implementation solely because the current Codex sandbox lacks remote publication or Android runtime infrastructure.