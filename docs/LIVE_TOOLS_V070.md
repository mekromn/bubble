# Bubble 0.7.0: live web tabs and local ChatGPT workspace tools

## Foreground compatibility, not an undetectability promise

All retained GeckoSessions remain setActive(true) and PRIORITY_HIGH. Only the visible selected view owns native keyboard/accessibility focus. A document-start MAIN-world script now covers ordinary http/https pages and permitted matching frames, including cross-origin frames. It reports document.hidden=false, document.visibilityState=visible and document.hasFocus()=true, handles legacy visibility aliases, and suppresses window/document background signals. Element blur/focus and page cleanup events remain real. Native userActivation, trusted events, permissions, hardware idle state and Android process lifecycle are not forged.

The browser addon is registered before the first native web navigation, with an explicit fail-open warning if initialization fails. Protected browser domains and privileged documents can restrict injection. An injected compatibility layer is observable; it is not an engine-wide guarantee that every timing, focus, rendering or scheduling difference is invisible. Android may still sleep, reclaim, force-stop or restrict networking. Keeping every tab resident uses memory and battery. The same exact-origin isolated ChatGPT lifecycle bridge remains separate from the all-web MAIN-world script and still transfers no conversation text.

## Shortcuts and useful tools

Long-press the Back arrow for Forward, Stop loading, Refresh and Find. This is available even with no back history, and in both fullscreen and floating chat. Long-press the tab count for a bounded, searchable quick-tab popup. Filters show All, Unread, Working or Pinned tabs. Selection uses durable TabId and does not promote a floating chat to fullscreen. Long-press a row for local name, pin, note, notification mute, duplicate, copy address and close actions. Pinned or generating tabs require confirmation before closing.

Local names do not rename server conversations. Pins are listed first without changing session identity. Notes are explicitly authored local text, not automatic webpage scraping. Muting a tab suppresses its reply notification but retains unread state. Next unread selects the next available unread tab. Duplicating opens a separate browser tab at the current URL; it neither branches a server conversation nor replays composer text/generation. Closing preserves the last 20 tabs for reopening; there is no arbitrary open-tab cap. A reopened tab restores its logical ID and available Gecko session state without claiming interrupted generation can resume.

The prompt library starts with 12 editable templates: handoff, code review, planning, debugging, comparison, explanation, assumption checking, research, summary, test design, writing and decision log. Create/edit/delete and copy are explicit user actions. Nothing is auto-sent to ChatGPT. Names, pins, notes, per-tab mute, prompts and recent-close history are optional backward-compatible fields in the existing version-1 atomic workspace store. Old files load without reset. Explicitly deleting every prompt does not silently reinstall defaults.

Find uses Gecko's native finder, next/previous and highlight-all. It searches the currently loaded document, not every conversation in a ChatGPT account. Its listeners and highlights are cleared when closed or when the targeted session/navigation changes. Keyboard shortcuts in fullscreen: Ctrl+T new, Ctrl+Shift+T reopen, Ctrl+Tab / Ctrl+Shift+Tab cycle, Ctrl+F find, Ctrl+L address, Ctrl+W close with the same safeguards.

No additional fullscreen toolbar row was added. Floating chat gets a Back control in its existing header; chooser footer holds Chat tools and Reply sound. Menus use bounded native popups and existing supported frame-rate votes rather than another full browser surface.

## Acceptance tests

New instrumentation checks webpage-visible signals from inline startup scripts and cross-origin iframe scripts before/after Home and notification parking; real Back long-press with no history, quick-tab selection, floating long-press menus and native page search; and backward-compatible local-tool persistence. Pure tests cover tab filters, cycling, names and stable template IDs. Existing drag/hide/restore, notification routing, input, compositor, dark-mode, session-recreation and PiP assertions remain.

Implementation submission is not a runtime or physical 120fps pass. Record results only after retrieving actual CI reports. Authenticated ChatGPT response detection and physical Pixel frame pacing remain separate device validation.
