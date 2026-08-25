# Bubble agent instructions

This repository is specification-first. Before writing or modifying implementation code, read these files completely:

1. `CODEX.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/FEATURE_ADDENDUM.md`
4. `docs/UX_SPEC.md`
5. `docs/ARCHITECTURE.md`
6. `docs/PLATFORM_SECURITY.md`
7. `docs/TEST_RELEASE.md`
8. `docs/ROADMAP.md`
9. `docs/IMPLEMENTATION_ISSUES.md`

The authoritative implementation epic is GitHub issue #1. Issues #2 through #9 are mirrored in `docs/IMPLEMENTATION_ISSUES.md` so implementation does not depend on live GitHub access.

The old `r01-floating-head-core` branch is a provisional abandoned scaffold and is not the source of truth. Start production work from `main` unless a later issue/PR explicitly changes that instruction.

## Non-negotiable constraints

- A logical tab is not a WebView.
- Do not impose `MAX_TABS`, `MAX_HEADS`, or any equivalent arbitrary logical-session cap.
- Floating-head presentation and WebView renderer residency are independent state axes.
- Free head placement is the default; snapping and stacking are optional and must not be forced.
- Every head is keyed to a stable logical `TabId` and must restore that exact tab.
- Use one compliant overlay foreground service for visible heads, not one service per head.
- Do not keep a WebView alive merely because its tab is represented by a head; only the explicit per-tab **Keep live** preference requests persistent warm residency, and Android process/renderer reclamation must still be handled honestly.
- `Pin` and `Keep live` are separate semantics.
- Bubble is a real browser and must support normal `http://` navigation; show insecure-page UI instead of globally blocking cleartext URLs.
- Never bypass TLS errors.
- Never auto-grant arbitrary WebView permission requests.
- Never expose unrestricted JavaScript interfaces to arbitrary internet content.
- Never fake private mode; use true WebView profile isolation when supported or disable the feature.
- Chrome-compatible Mobile is the default per-tab UA mode. Keep Chrome Mobile / Chrome Desktop / System WebView switching durable per tab, and never claim WebView is perfectly indistinguishable from standalone Chrome.
- Support Android HTTP/HTTPS `VIEW`, normal URL sharing, and an explicit **Open in Bubble head** share target through the same durable session pipeline.
- Support reusable named saved sessions/workspaces. Private tabs must never be included in durable saved-session snapshots. Restore must create fresh `TabId`s.
- High-refresh rendering defaults to **120+**: request the highest supported rate at or above 120 Hz, falling back to the display's highest rate. Auto/60/90/120+/Highest must remain capability-aware, and Android 16 should propagate the requested rate through the browser view hierarchy without claiming to override system thermal/battery/display policy.
- Never commit signing keys, passwords, tokens, or secrets.
- Do not call a user-facing/overlay phase complete without runtime validation.
- Production application ID/namespace is `com.mekromn.bubble` unless the specification is deliberately amended.

## Execution mode for issues #2 through #9

When the user asks to implement issues #2 through #9 in one PR, use **one implementation branch from `main`**, keep phase boundaries as coherent commits, and continue through all phases sequentially. A separate remote PR per phase is not required for that request.

A phase's *implementation gate* and the project's *publication/validation gate* are different:

- Complete the code, tests, migrations, docs, and internal review for the current phase before moving forward.
- If GitHub access, network access, MCP PR tooling, Gradle distribution access, Android SDK packages, emulator/device access, or other external infrastructure is unavailable, **do not stop implementation merely because of that external limitation**.
- Instead, record the exact blocked validation in `docs/VALIDATION_STATUS.md`, perform the strongest available static/local checks, and continue to the next phase if there is no known code defect that logically blocks it.
- Never fabricate a successful build, test, runtime check, push, CI run, or PR.
- Do not describe the product as production-complete until the missing remote/runtime validation has actually been performed.
- Once GitHub/CI/device access is available, run the deferred gates, fix failures, then publish/update the single PR.

## Completion discipline

For every phase:

1. inspect the mirrored phase contract and authoritative docs;
2. continue on the requested implementation branch;
3. implement the scoped behavior;
4. add focused tests;
5. run the strongest checks available in the environment;
6. fix known failures rather than weakening the contract;
7. commit in coherent phase-sized slices;
8. update `docs/VALIDATION_STATUS.md` with completed and blocked verification;
9. continue to the next phase unless there is a known implementation defect that makes the next phase unsafe.

When remote GitHub is available, open/update the requested PR with exact validation and appropriate issue-closing keywords. When it is unavailable, leave the branch/commits ready for publication rather than halting the implementation.

The project is not finished at prototype quality. v1.0 requires the production gates in `docs/TEST_RELEASE.md` and issue #9 to pass.
