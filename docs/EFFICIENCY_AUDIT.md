# Bubble Efficiency Audit

This is the permanent performance/efficiency ledger for Bubble. The goal is not to make the browser
"lighter" by lowering quality. The goal is to stop paying for work that produces no useful pixel,
input response, state transition, browser capability or reliability benefit.

## Hard invariants

- Never lower Gecko/WebRender page resolution, image quality, color fidelity, bit depth or sampling quality for an efficiency win.
- Never replace the direct Gecko SurfaceView path with a lower-quality renderer to save power.
- Never reduce attachment/archive fidelity or silently alter selected bytes.
- Never trade successful capture/upload/storage reliability for a benchmark number.
- Prefer eliminating redundant work over making useful work cheaper by degrading it.
- Physical Pixel testing decides performance claims. Compilation/source guards are not runtime proof.
- Instrumentation must be dormant or absent during ordinary performance builds unless explicitly enabled by the user.

Status legend: **DONE** = implemented and source-guardable, **VERIFY** = architecture exists but needs physical measurement,
**NEXT** = high-priority remaining work, **LATER** = useful but lower priority or more invasive.

## 50-item ledger

1. **VERIFY** — Audit global opaque mode across every Bubble-owned panel/dialog/popup; Build 149 covers persistent chrome, blur backdrops, controls-sheet dimming and resting bubble.
2. **DONE** — Transparency OFF destroys Bubble blur-only windows/listener instead of setting radius to zero.
3. **NEXT** — Lazy-create blur windows only when a blurred region first becomes visible while transparency is ON.
4. **NEXT** — Retire blur resources when no currently visible Bubble region needs them, without breaking same-type overlay Z ordering.
5. **NEXT** — Use opaque window/surface formats wherever the visible result is fully opaque.
6. **LATER** — Safely change window composition format by mode where Android permits it without relayout regressions.
7. **VERIFY** — Audit invisible native overdraw. Chat already clips its background around the embedded page.
8. **DONE** — Floating Gecko lives only in the middle page container between native top/bottom chrome; it is not intentionally rendered beneath those bars.
9. **DONE** — Embedded page/background separation uses geometry/clip, not a captured page texture or steady-state bitmap layer.
10. **DONE** — Direct Gecko SurfaceView is the preferred floating renderer; relay is fallback/benchmark infrastructure.
11. **VERIFY** — Confirm relay Java/native resources remain entirely cold until fallback/benchmark selection.
12. **VERIFY** — Confirm every relay fallback allocation is destroyed immediately when its host is destroyed/replaced.
13. **LATER** — Remove relay from the eventual production line only after long physical-device confidence proves direct is sufficient.
14. **NEXT** — Centralize frame-rate ownership so windows/views/surfaces do not issue redundant votes.
15. **LATER** — Allow truly static native-only UI to stop unnecessarily influencing high-refresh arbitration, without touching page smoothness.
16. **LATER** — Evaluate interaction-driven refresh boosting instead of permanent native-chrome max-rate requests; preserve instant response.
17. **LATER** — Evaluate content-motion-aware refresh policy only if Gecko exposes a reliable signal; no frame-quality downgrade.
18. **DONE** — Efficiency work does not reduce page fidelity/resolution to save refresh/render cost.
19. **DONE** — Workspace listener notifications are already coalesced to at most one callback fan-out per Choreographer frame.
20. **NEXT** — Separate model changes that affect visible pixels from persistence/notification-only changes.
21. **NEXT** — Replace broad Workspace listeners with scoped subscriptions/revisions for selected tab, tab list, unread/generating state and chrome.
22. **NEXT** — Stop reconstructing the whole floating mode tree when only labels/status change.
23. **NEXT** — Cache/reuse stable Bubble/chooser/chat view trees when lifecycle correctness is proven.
24. **NEXT** — Reuse stable drawables/ripples/gradients instead of recreating them on repeated mode builds.
25. **NEXT** — Cache density-derived dimensions used in drag/layout hot paths.
26. **NEXT** — Remove avoidable short-lived arrays/Rects/Points/data objects from hot drawing/gesture paths.
27. **NEXT** — Reduce WindowBox/geometry allocations during high-rate drag/resize without changing geometry precision.
28. **VERIFY** — WindowMotion already coordinates major floating geometry transitions; audit remaining independent ViewPropertyAnimators for overlap.
29. **VERIFY** — No decorative idle animation loops should exist; audit all repeating animators/timers.
30. **NEXT** — Ensure loading/reload motion has zero lifetime outside the exact visible loading interval.
31. **LATER** — Measure whether transient hardware layers on tiny chrome animations help or hurt before changing them.
32. **DONE** — Pixel capture is transition-only; direct steady-state browsing does not use page bitmap capture.
33. **NEXT** — Audit transition snapshot allocation/reuse and peak memory.
34. **VERIFY** — Prove transition snapshots are released immediately after handoff completion/cancellation.
35. **NEXT** — Audit fullscreen/floating target calculation for avoidable intermediate relayouts.
36. **DONE** — Drag/resize WindowManager updates are coalesced with postOnAnimation to one latest target per display frame.
37. **DONE** — Unbuffered touch dispatch is scoped to real page gestures rather than blanket app input.
38. **VERIFY** — Direct SurfaceView removes Bubble's steady-state relay SurfaceControl transaction chain; audit transition-only transactions for batching.
39. **DONE** — FloatingWindow skips identical non-flag geometry updates before WindowManager.updateViewLayout.
40. **NEXT** — Move provider metadata queries for selected attachments fully off the main thread.
41. **DONE** — ZIP creation streams provider input directly to output with bounded buffers and no whole-file materialization.
42. **LATER** — Offer an explicit smart STORE recommendation for already-compressed formats; never silently change the user's chosen compression mode.
43. **NEXT** — Resolve independent attachment metadata concurrently where provider behavior permits it safely.
44. **DONE (150)** — Direct-Attach staging reuses one bounded transfer buffer for the whole multi-file request instead of allocating one per file.
45. **LATER** — Investigate whether the pinned/future Gecko FilePrompt can safely consume provider-backed descriptors and eliminate direct-attach staging copies.
46. **LATER** — Distinguish session residency from invisible presentation work while retaining all browser/session fidelity.
47. **LATER** — Stop invisible Gecko presentation only if Gecko exposes a safe mechanism that preserves the retained live-session contract.
48. **DONE (policy)** — Never substitute a lower-quality visible page/frame as an efficiency fallback.
49. **NEXT** — Continue replacing polling/repeated discovery with event-driven cached state (blur availability already converted).
50. **NEXT** — Build a user-triggered, normally dormant efficiency ledger measuring CPU, frame callbacks, WM relayouts, SurfaceControl work, allocations/GC, layer/window count, blur area, thermal/battery and Gecko presentation behavior.

## Build 150 — Phase A: redundant-work removal

The first audit build intentionally starts with changes that should be output-identical:

- Cache maximum same-resolution display refresh discovery rather than walking supported display modes on repeated votes.
- Deduplicate identical `View.setRequestedFrameRate()` calls while preserving the exact same maximum-rate contract.
- DirectGeckoWindow no longer re-walks/re-votes the Gecko view tree on every geometry-animation frame; the installed SurfaceHolder lifecycle callback owns Surface re-voting.
- Foreground-service notification state scans all tabs once instead of repeatedly calling multiple `count` operations and rebuilding the same summary twice.
- Notification-channel creation is cached for the process instead of repeatedly issuing the same system-service mutation.
- Direct attachment staging allocates one 64 KiB transfer buffer per request, not per file.
- ZIP progress callbacks are capped at 20 Hz plus forced file-completion updates, preventing thousands of main-thread progress posts on large archives while leaving ZIP bytes/compression settings unchanged.

## Next phase

Phase B should attack the larger structural costs: scoped Workspace revisions/listeners, selected-tab lookup/indexing,
chooser-row rebuild avoidance, floating view-tree reuse, cached safe-area/density geometry, and a dormant measurement ledger.
Those changes are more invasive, so they should land behind dedicated source/runtime checks rather than being mixed blindly into Phase A.
