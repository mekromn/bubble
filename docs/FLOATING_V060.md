# Bubble 0.6.0 interaction redesign

## User contract

Tap the bubble to expand an animated conversation chooser over the current app. Select a conversation to open it in an interactive floating browser; fullscreen is an explicit control, not the tap default. The window can be moved by its header, resized from its handle, switched to other tabs, expanded fullscreen or minimized. Bubble dragging remains free-positioned.

Fullscreen chrome is one compact 56dp bottom bar rather than a branding header and double-row dock. The browser arrow navigates page history. System Back dismisses transient UI/IME first, then minimizes to the bubble. Home/Recents minimizes through the user-leave callback, provided overlay permission is already granted. Explicit file-picker, share and permission launches are not Home actions. Denied overlay permission never traps the user.

## Two distinct small-window features

1. Interactive floating chat uses a small TYPE_APPLICATION_OVERLAY window and a focusable GeckoView backed by TextureView. It supports webpage interaction and Android's keyboard. Native property transitions animate transforms after one final layout change, rather than relaying out the website every animation frame. Closing/collapsing the surface does not close the GeckoSession.
2. Native Android picture-in-picture uses the Activity API and is labeled view-only. It hides browser chrome and provides Previous tab, Next tab and Minimize to bubble RemoteActions. Native PiP does not expose ordinary webpage interaction. The two modes must never be described as the same mechanism.

Workspace centrally transfers display ownership between the Activity's SurfaceView and the floating TextureView. It releases the old view before attaching the exact same session to the new view. The floating service releases synchronously before the Activity resumes ownership. Normal session state, authentication storage and the existing signing identity are retained.

## Live tabs

Every open workspace session remains active and high-priority while resident, not only tabs matching ChatGPT. Only actual selected-view input focus is requested. A View subclass reconciles after visibility/focus/detach lifecycle callbacks, without a polling loop. A top-frame, exact-https://chatgpt.com document-start MAIN-world script also exposes visible/focused page state and suppresses window-level background signals. It does not suppress input-element blur, expose a native API, read conversations, inspect accounts or send messages. The isolated reply lifecycle monitor keeps its existing restricted native-message path.

These mechanisms do not make every Android process the top Activity. They do not override force-stop, system process reclamation, heat/power policy, networking failures or website/server changes. Do not advertise a blanket never-suspend guarantee.

## Persistence and security

The existing workspace-v2.json schema remains version 1; normalized floating placement and size are optional backward-compatible fields. All writes stay on the ordered atomic IO path. Public test signing key and com.mekromn.bubble.debug package are unchanged. A separate translucent, non-exported file-picker Activity can answer an explicit floating-page upload request without reopening the fullscreen browser. Results are tied to the original session and validated content URIs. TLS behavior and site permission defaults are not weakened.

## Validation at implementation submission

New automated cases cover tap-to-chooser without fullscreen, same-session floating compositor output, keyboard input in the floating window, explicit fullscreen transfer, Home/Back minimization, two background document timers remaining visible and running, and native PiP tab switching. Pure tests cover safe window geometry and the script's exact-origin/element-event boundaries. Prior rendering, input, persistence and dark-mode regression tests remain.

Status: implementation submitted for build and Android 16 emulator execution. No new runtime pass or physical Pixel 120fps claim is made by this document. Real test outcomes and screenshots must be inspected before delivery. Authenticated ChatGPT background generations, software-keyboard behavior on the user's phone, native GPU frame pacing and long-running memory/thermal behavior remain separate device checks.
