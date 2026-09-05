# Bubble 0.6.1 — hide, restore, meaningful motion and reply alerts

This follows the user's September 5 feedback on the working floating prototype. The debug certificate, package, browser profile, UUID tabs and compatible workspace store are retained.

## Hide is not close

Dragging a bubble reveals one small non-touchable bottom target. Target capture uses radial hysteresis and a single entry haptic. Releasing there shrinks the bubble away and hides all floating UI after publishing a restore notification. The original resting placement and all GeckoSessions survive. Cancelled gestures do not hide. With notifications denied/blocked, hiding is refused so there is still a usable restore route.

The same user-started foreground service continues while parked, showing counts and Show bubble / Stop service controls. A direct Activity PendingIntent handles notification restoration and cold-process workspace loading without an Android notification trampoline. It does not reopen fullscreen unless overlays are unavailable or a fullscreen workspace is already visible. There is no boot restart or force-stop bypass.

## Motion grammar

Circle-to-panel uses an anchored hardware circular reveal, not independent X/Y stretching of text. Panel-to-circle reverses the reveal, then moves only the small bubble back to its resting location. Chooser-to-chat is a short content crossfade in a stable-sized container. Unread arrival is a one-shot badge acknowledgement, not an idle pulse. Property animations follow Android's frame clock and animation scale settings. Motion cancellation invalidates obsolete callbacks.

Fullscreen retains Gecko SurfaceView; the floating surface retains TextureView because it needs clipped hardware transitions. The drag target and interactive overlay request a real supported high-refresh display mode and API 35 view frame-rate votes. No forced software rendering, busy loop, permanent webpage bitmap cache or quality-reducing rendering fallback is introduced. A 120Hz request is not a 120fps measurement.

## ChatGPT alerts

An exact-origin isolated content script emits only started/finished/aborted plus a random run ID. It does not read message text. Positive end markers and observed generation activity are required; stopping, errors and navigation do not report success. Regenerating inside the same DOM node and BFCache restoration are handled. The existing native acknowledgement-before-notify and visible-tab suppression remain in force.

Replies retain the existing audible Android channel and user sound settings. Multiple unread replies group without an extra summary sound. Taps restore the exact tab into floating chat when appropriate. Sound controls are available in the floating chooser. DOM lifecycle detection is heuristic and requires authenticated live-site validation; it is NOT push delivery after force-stop or a guarantee that every future ChatGPT UI is compatible.

## Verification

New pure tests cover target hysteresis and malformed coordinates. Node tests cover origin isolation, old history, completion, regeneration, cancellation, page errors, navigation, repeat suppression and BFCache. Existing Android input, rendering, session, PiP and persistence gates remain. New Android tests must exercise actual drag, hiding, notification restoration and same-session alerts. Record observed results separately; do not claim completion or physical Pixel 120fps from build success.
