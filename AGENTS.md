# Bubble agent instructions

This repository is specification-first. Before writing or modifying implementation code, read these files completely:

1. `CODEX.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/AI_CHAT_SPEC.md`
4. `docs/FEATURE_ADDENDUM.md`
5. `docs/UX_SPEC.md`
6. `docs/ARCHITECTURE.md`
7. `docs/PLATFORM_SECURITY.md`
8. `docs/TEST_RELEASE.md`
9. `docs/ROADMAP.md`
10. `docs/IMPLEMENTATION_ISSUES.md`

The authoritative implementation epic is GitHub issue #1. Issues #2 through #9 are mirrored in `docs/IMPLEMENTATION_ISSUES.md` so implementation does not depend on live GitHub access.

**AI-first direction:** `docs/AI_CHAT_SPEC.md` is authoritative for supported AI-chat pages and supersedes conflicting generic-browser behavior in older specs/issues. ChatGPT is the first supported AI service and must be completed and validated before additional providers are treated as first-class targets.

The old `r01-floating-head-core` branch is a provisional abandoned scaffold and is not the source of truth. Start production work from `main` unless a later issue/PR explicitly changes that instruction.

## Non-negotiable constraints

- **Bubble is now an AI-chat workspace browser first, with ChatGPT as the first-class v1 AI target.** Generic browsing remains infrastructure/fallback, not the product priority.
- A logical tab is not a WebView.
- Do not impose `MAX_TABS`, `MAX_HEADS`, or any equivalent arbitrary logical-session cap.
- Floating-head presentation and WebView renderer residency are independent state axes.
- **Normal ChatGPT workspace behavior is one aggregate workspace bubble for all ChatGPT chats, not one overlay head per ChatGPT tab.** Individual breakout heads may be a future explicit action only.
- **All ChatGPT tabs in an active live workspace default to `keepRendererAlive=true` and are exempt from Bubble's normal voluntary LRU hibernation/eviction policy.** Evict unrelated generic browser tabs and nonessential caches first. Android/WebView forced reclamation must still be handled honestly with immediate recovery.
- Do not call `WebView.onPause()` or `WebView.pauseTimers()` merely because a live ChatGPT workspace is collapsed/backgrounded.
- Never claim Bubble can make Android processes literally unkillable or override provider/server timeouts. Use honest states such as Live, Keeping live, and Recovering.
- ChatGPT-specific page monitoring/injection must be restricted to validated trusted `https://chatgpt.com` origins and use minimal origin-restricted messaging. Never expose an unrestricted JavaScript bridge to arbitrary pages.
- Do not globally spoof Page Visibility or install generic anti-throttling hacks. A ChatGPT-only compatibility shim is allowed only if device testing proves visibility state alone pauses generation; it must remain origin-gated, minimal, reversible, and must not bypass access controls, rate limits, abuse prevention, or provider safety mechanisms.
- **Reply-completion notifications are native Bubble notifications driven by local ChatGPT adapter state, not an assumption that WebView provides general ChatGPT web-push completion notifications.** Use a dedicated audible `ChatGPT replies` Android notification channel, exact-`TabId` notification routing, deduplication, grouping, and privacy-preserving lock-screen defaults.
- The default ChatGPT notification must not expose generated answer text, prompt text, URLs, account identifiers, or other browsing contents on the lock screen. Conversation-title display must be user-controllable.
- Free head placement is the default for generic/breakout heads; snapping and stacking are optional and must not be forced.
- Every individual head is keyed to a stable logical `TabId`; the aggregate ChatGPT workspace bubble is keyed to a stable workspace identity and must still route exact-chat actions by `TabId`.
- Use one compliant overlay foreground service for visible heads/workspace bubbles, not one service per head or chat.
- `Pin` and `Keep live` are separate semantics for generic tabs. ChatGPT live-workspace membership automatically requests keep-live residency unless the user explicitly disables live mode.
- Bubble is a real browser and must support normal `http://` navigation; show insecure-page UI instead of globally blocking cleartext URLs.
- Never bypass TLS errors.
- Never auto-grant arbitrary WebView permission requests.
- Never expose unrestricted JavaScript interfaces to arbitrary internet content.
- Never fake private mode; use true WebView profile isolation when supported or disable the feature.
- Chrome-compatible Mobile is the default per-tab UA mode. Keep Chrome Mobile / Chrome Desktop / System WebView switching durable per tab, and never claim WebView is perfectly indistinguishable from standalone Chrome.
- Support Android HTTP/HTTPS `VIEW`, normal URL sharing, and an explicit **Open in Bubble head** share target through the same durable session pipeline.
- Support reusable named saved sessions/workspaces. Private tabs must never be included in durable saved-session snapshots. Restore must create fresh `TabId`s.
- High-refresh rendering defaults to **120+**: request the highest supported rate at or above 120 Hz, falling back to the display's highest rate. Auto/60/90/120+/Highest must remain capability-aware, and Android 16 should propagate the requested rate through the browser view hierarchy without claiming to override system thermal/battery/display policy.
- **Chrome Mobile is the minimum user-experience quality bar for the phone browser shell.** Bubble must feel like a current production Android browser, not a developer scaffold. Phone ergonomics, information hierarchy, touch targets, density, responsive layouts, bottom-sheet/menu behavior, tab overview, typography, iconography, system-bar integration, dark/light themes, and polish must be at least comparable to a modern Chrome Mobile release while preserving Bubble's own identity.
- The primary browsing surface must not expose an engineering control dump. Advanced UA, refresh, session, memory, debugging, and expert controls belong in coherent menus/settings/sheets unless they are genuinely primary browsing actions.
- **Motion is part of the interaction contract.** Minimize-to-bubble/head, bubble/head appearance/removal, restore, AI workspace chooser, tab overview open/close, tab switching, menus/sheets, delete-target feedback, loading state, and other major state transitions require short, responsive, coherent animation. Avoid both abrupt teleporting UI and gratuitously slow animation.
- **Duplicate or visually overlapping automatic heads are release blockers.** Head creation must be idempotent by `TabId`; automatic/default/restored placement must avoid unintended collisions. User-chosen free placement remains allowed.
- **Floating-head/workspace-bubble crashes are release blockers.** Overlay permission races, WindowManager failures, foreground-service restrictions, process/renderer death, repeated taps, configuration changes, notification routing, and service lifecycle transitions must fail safely without losing the logical chat/tab.
- No user-facing build may be described as polished, showcase-ready, beta-ready, or production-ready while the phone UI is materially below the Chrome-class baseline, ChatGPT live-workspace generation stops because of Bubble's own lifecycle policy, completion notifications are unreliable, exact-chat routing is wrong, head duplication remains reproducible, or known interaction crashes remain unresolved.
- Never commit signing keys, passwords, tokens, or secrets.
- Do not call a user-facing/overlay/AI-workspace phase complete without runtime validation.
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

The project is not finished at prototype quality. v1.0 requires the production gates in `docs/TEST_RELEASE.md`, the ChatGPT acceptance tests in `docs/AI_CHAT_SPEC.md`, and applicable issue gates to pass.