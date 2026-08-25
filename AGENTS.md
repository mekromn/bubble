# Bubble agent instructions

This repository is specification-first. Before writing or modifying implementation code, read these files completely:

1. `CODEX.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/UX_SPEC.md`
4. `docs/ARCHITECTURE.md`
5. `docs/PLATFORM_SECURITY.md`
6. `docs/TEST_RELEASE.md`
7. `docs/ROADMAP.md`

The authoritative implementation epic is GitHub issue #1. Implement phase issues #2 through #9 in order, using focused branches and validated PRs.

The old `r01-floating-head-core` branch is a provisional abandoned scaffold and is not the source of truth. Start production work from `main` unless a later issue/PR explicitly changes that instruction.

## Non-negotiable constraints

- A logical tab is not a WebView.
- Do not impose `MAX_TABS`, `MAX_HEADS`, or any equivalent arbitrary logical-session cap.
- Floating-head presentation and WebView renderer residency are independent state axes.
- Free head placement is the default; snapping and stacking are optional and must not be forced.
- Every head is keyed to a stable logical `TabId` and must restore that exact tab.
- Use one compliant overlay foreground service for visible heads, not one service per head.
- Do not keep a WebView alive merely because its tab is represented by a head.
- Bubble is a real browser and must support normal `http://` navigation; show insecure-page UI instead of globally blocking cleartext URLs.
- Never bypass TLS errors.
- Never auto-grant arbitrary WebView permission requests.
- Never expose unrestricted JavaScript interfaces to arbitrary internet content.
- Never fake private mode; use true WebView profile isolation when supported or disable the feature.
- Never commit signing keys, passwords, tokens, or secrets.
- Do not call a user-facing/overlay phase complete without runtime validation.

## Completion discipline

For every issue:

1. inspect the issue and authoritative docs;
2. create/use a focused branch;
3. implement the scoped behavior;
4. add focused tests;
5. run the strongest applicable build/lint/unit/instrumentation/runtime checks;
6. fix failures rather than weakening the contract;
7. commit in coherent slices;
8. open a PR whose body lists actual validation and uses the correct `Closes #N` keyword;
9. keep the issue open when a required acceptance criterion is not yet proven.

The project is not finished at prototype quality. v1.0 requires the production gates in `docs/TEST_RELEASE.md` and issue #9 to pass.
