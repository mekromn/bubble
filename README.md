# Bubble

Bubble is an **AI-chat workspace browser for Android**, with ChatGPT as the first-class initial target.

The defining workflow is simple: open several real ChatGPT conversations, keep them live concurrently, collapse the whole ChatGPT workspace into **one floating bubble**, use other Android apps, and receive native Android notifications when individual replies finish. Tapping the bubble or notification restores the exact logical chat.

Bubble still contains a real browser/session engine underneath, but generic browsing is now infrastructure and fallback behavior rather than the product's primary identity.

## ChatGPT-first direction

For the first AI milestone Bubble must provide:

- multiple simultaneous ChatGPT web conversations;
- maximum-live renderer policy for every ChatGPT workspace tab;
- no voluntary Bubble hibernation of active ChatGPT workspace renderers;
- one aggregate ChatGPT workspace bubble instead of one automatic overlay head per ChatGPT tab;
- exact-chat routing through stable `TabId`s;
- generating/unread/recovery state on the workspace bubble and conversation chooser;
- native Android `ChatGPT replies` notifications with sound when a reply completes;
- privacy-preserving notification contents by default;
- immediate renderer recovery when Android/WebView forces reclamation;
- provider-neutral adapter boundaries so Claude, Gemini and other AI chats can be added later without contaminating the browser core.

Android can still reclaim processes/renderers under real memory pressure, so Bubble must maximize liveness and recover immediately rather than make a false “unkillable” promise.

## Current repository status

The repository is specification-first and has an active implementation line. The old `r01-floating-head-core` scaffold is provisional/abandoned and is not authoritative.

Start here:

- `docs/AI_CHAT_SPEC.md` — **authoritative ChatGPT/live-workspace product and architecture contract**
- `docs/PRODUCT_SPEC.md` — underlying browser behavior and generic feature contract
- `docs/UX_SPEC.md` — browser, tab and overlay interaction contract
- `docs/ARCHITECTURE.md` — production browser/session lifecycle architecture
- `docs/PLATFORM_SECURITY.md` — Android platform constraints, privacy and security requirements
- `docs/TEST_RELEASE.md` — validation, CI, release and production-readiness requirements
- `docs/ROADMAP.md` — implementation phases and gates
- `AGENTS.md` — authoritative instructions and non-negotiable constraints for coding agents
- `CODEX.md` — implementation instructions for Codex

GitHub issue #11 is the focused ChatGPT live-workspace implementation contract.

## Non-negotiable product principles

1. **AI chat first.** ChatGPT is the first supported AI provider and must be runtime-solid before additional providers become first-class targets.
2. **One ChatGPT workspace bubble.** Normal ChatGPT collapse does not create one overlay head per chat.
3. **All ChatGPT workspace tabs live by default.** Bubble does not voluntarily evict/hibernate them through its normal LRU renderer policy; unrelated generic tabs are sacrificed first.
4. **A logical chat/tab is not a WebView.** Durable `TabId` state survives forced renderer termination and recovery.
5. **Native completion notifications.** Bubble locally tracks trusted ChatGPT generation state and emits exact-chat Android notifications rather than assuming generic web push is available.
6. **Origin-restricted integration only.** ChatGPT-specific monitoring/native messaging is enabled only for validated trusted `https://chatgpt.com` pages. No unrestricted JavaScript bridge.
7. **No false background guarantees.** Android/WebView forced reclamation, server timeouts and network loss are real; Bubble maximizes liveness and surfaces recovery honestly.
8. **No fake privacy or unsafe browser shortcuts.** Never bypass TLS errors, silently grant web permissions, or weaken browser boundaries for convenience.
9. **Production quality is part of the feature.** Exact-chat routing, notification deduplication, overlay stability, process/renderer recovery, accessibility and on-device concurrent-chat tests are release gates.

Package name: `com.mekromn.bubble`