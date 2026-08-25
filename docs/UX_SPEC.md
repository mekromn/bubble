# Bubble — UX Specification

## 1. Experience goal

Bubble should feel native, fast and deliberate. Floating heads are a core tab state, not a gimmick bolted onto a browser.

## 2. Main browser screen

Phone layout should prioritize the page while keeping navigation one tap away.

Required visible affordances:

- omnibox/search field;
- back;
- forward when available;
- reload/stop;
- tab count/switcher;
- menu;
- a clearly discoverable **minimize tab to head** action.

The minimize action should never be buried several menus deep after first use. A toolbar control or configurable gesture is preferred.

## 3. Tab switcher

Tab switcher must show both normal tabs and head tabs.

Each card shows:

- favicon;
- title;
- domain/URL summary;
- private marker when applicable;
- floating-head marker when currently minimized;
- optional thumbnail;
- close action.

Filters:

- All
- Browser tabs
- Heads
- Private

Support search when tab count becomes large.

Tapping a head-tab card restores it to the browser. A secondary action may locate/show its floating head without restoring it.

## 4. Minimize animation

When a tab is minimized:

1. capture/update canonical tab metadata;
2. ensure overlay permission/service prerequisites;
3. create the head at a sensible initial location, ideally near the minimize action or the last chosen head location;
4. animate browser content toward the head without blocking completion on favicon/network work;
5. transition the logical presentation state to `HEAD`;
6. browser either activates another normal tab or shows the new-tab surface.

If overlay permission is absent, explain why it is required and open the Android permission screen only after explicit user action.

## 5. Head drag behavior

- Drag begins after normal touch slop; a tap should not accidentally move the head.
- Head follows the finger with minimal latency.
- Position writes are throttled during movement and committed at drag end.
- Head cannot become fully unreachable beyond safe screen bounds.
- System bars, display cutouts and gesture insets are respected.
- Free placement is default.
- Optional edge magnetism gives feedback but does not force snapping when disabled.
- Orientation change preserves relative placement.

## 6. Head tap behavior

On tap:

- if BrowserActivity is already visible, switch directly to the associated logical tab;
- if Bubble is backgrounded, launch/bring forward BrowserActivity as a direct result of the user gesture and restore that tab;
- remove the overlay head only after ownership is safely transferred to the browser presentation;
- if the renderer is hibernated, immediately show the browser chrome/title/loading state while recreating the WebView;
- if restoration fails, load the persisted canonical URL and show a non-destructive recovery message.

## 7. Head long-press menu

Compact menu actions:

- Restore
- Pin / Unpin
- Duplicate
- Share
- Copy URL
- Close

Optional later actions:

- Stack
- Move to edge
- Open floating card
- Mute

The menu must not require the browser Activity to become visible just to close or share a tab.

## 8. Drag-to-close

During a drag, reveal a clearly labeled close/trash target near the bottom safe area. The head must enter a strong visual “will close” state before release closes the tab.

Accidental closure protection:

- require the center of the head to be inside the target at release;
- offer Undo via notification/snackbar when practical;
- pinned tabs require confirmation or do not enter close target until unpinned, depending final UX testing.

## 9. Head stacking

Stacking is optional and intentional.

Recommended interaction:

- drag a head over another and hold for a short dwell;
- display “Stack” affordance;
- release to create a stack.

A stack shows top favicon plus count. Tap fans out or opens a compact chooser. Users can pull a head out of a stack.

Do not automatically stack heads just because they touch during normal dragging.

## 10. Hide heads temporarily

Provide a global “Hide heads” action from notification and in-app manager. This hides overlays without changing tabs from `HEAD` presentation state.

“Show heads” restores them at saved positions.

This is important when many heads cover a game, video, camera or presentation.

## 11. External links

If Bubble is chosen as the default browser, honor the user setting:

- Browser
- Head
- Ask

When “Head” is selected, the incoming URL may create a head without forcing a full browser screen when platform rules allow the user-initiated flow.

## 12. Link context menu

Long-pressing a link exposes “Open in floating tab” as a first-class action alongside normal new-tab behavior.

This is one of Bubble’s signature workflows and should be fast.

## 13. Notification

When heads exist, the overlay foreground service notification should be low-noise but useful.

Suggested actions:

- Show/Hide heads
- Open Bubble
- Close all heads (with confirmation if destructive)

The notification should state the number of floating tabs without leaking page titles or URLs onto the lock screen by default.

## 14. Visual design

- Modern Material 3 shell with strong contrast and restrained animation.
- Heads should look like refined browser/site icons, not cartoon chat bubbles.
- Favicon is primary; Bubble brand ring/background is secondary.
- Loading indicators are subtle and do not obscure the favicon.
- Dark/light system theming.
- Avoid giant rounded containers that waste page area.

## 15. Error states

### Overlay permission revoked
Keep logical head tabs in the manager, remove overlays, show a non-destructive banner explaining that floating display is disabled.

### Renderer crash
Replace the WebView and restore state/URL. Never crash the entire browser solely because a WebView renderer exited.

### Unsupported private profile
Private mode control is disabled with explanation; do not silently fall back to normal storage.

### Download failure
Show actionable retry/open-downloads state.

### No network
Show normal browser offline page while preserving navigation history.

## 16. Accessibility UX

Every draggable head exposes actions through accessibility services so drag is not the only way to reposition or close it. Head content description should be based on page title/domain, never only “Bubble”.
