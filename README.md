# Bubble

Bubble is an Android browser whose defining interaction is simple: **any tab can be minimized into an independent, draggable heads-up icon that remains available over other apps and can be restored instantly.**

There is no application-enforced maximum number of logical tabs or heads. Bubble achieves that by separating durable tab/session state from expensive live WebView renderer state. A head does **not** imply that its page must remain resident in memory.

## Current repository status

This repository is in **specification / implementation-handoff** state. The existing Android scaffold on `r01-floating-head-core` is provisional and may be replaced by the production implementation. The specification is authoritative.

Start here:

- `docs/PRODUCT_SPEC.md` — complete product behavior and feature contract
- `docs/UX_SPEC.md` — browser, tab and floating-head interaction contract
- `docs/ARCHITECTURE.md` — production architecture and tab/session lifecycle
- `docs/PLATFORM_SECURITY.md` — Android platform constraints, privacy and security requirements
- `docs/TEST_RELEASE.md` — validation, CI, release and production-readiness requirements
- `docs/ROADMAP.md` — implementation phases and gates
- `CODEX.md` — implementation instructions for Codex

## Non-negotiable product principles

1. **No arbitrary tab/head cap.** Resource management must be dynamic and based on renderer residency, not a hard `MAX_TABS` value.
2. **Heads stay where the user puts them.** Free placement is the default. Edge snapping and stacking are optional behaviors, not forced behavior.
3. **A tab is not a WebView.** Durable logical session state survives renderer eviction.
4. **Minimize and restore must feel instant.** Heads are native lightweight overlay UI; WebView resurrection happens behind a clear loading state when necessary.
5. **No fake privacy.** Private mode is only exposed when Bubble can provide real storage/profile isolation on the installed WebView implementation.
6. **No unsafe browser shortcuts.** Never bypass TLS errors, never auto-grant web permissions, and never use unrestricted JavaScript bridges.
7. **Production quality is part of the feature.** Accessibility, process-death recovery, rotation, low-memory behavior, CI, testing, and crash recovery are acceptance criteria, not later cleanup.

Package name: `com.mekromn.bubble`
