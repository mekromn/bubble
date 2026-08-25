# Bubble architecture

## Core invariant

A **tab is not a WebView**.

`TabRecord` is the durable logical browser object. A WebView is only a renderer temporarily attached to a tab. This is what lets Bubble support an unbounded logical tab/head count without trying to keep an unbounded number of renderer processes and WebViews resident in RAM.

## Tab states

r01 implements the first useful subset of the state machine:

- **ACTIVE** — the logical tab owns the currently attached WebView.
- **INACTIVE** — the tab remains in the browser but its WebView state is captured in memory and its renderer is released.
- **HEAD** — the tab is represented by a system overlay head. The logical tab, URL, title, favicon path and head coordinates remain durable.

Future milestones extend this to explicit suspended and disk-hibernated states.

## Components

### `TabRepository`
Durable tab metadata in app-private `SharedPreferences` JSON. There is deliberately no `MAX_TABS` constant.

### `BrowserStateCache`
Process-local WebView `Bundle` snapshots. This preserves navigation/scroll/form state while the process remains alive without holding many WebViews.

### `BrowserActivity`
Owns the visible browser UI and exactly one live WebView at a time in r01. Switching tabs captures the outgoing WebView state and releases its renderer.

### `FloatingHeadService`
Foreground service that owns `TYPE_APPLICATION_OVERLAY` heads. Each minimized tab gets an independent `WindowManager` view and layout params.

### `HeadOverlayController`
Creates, drags, persists and restores heads. No count cap is enforced.

### `FaviconStore`
Persists favicons by logical tab ID so heads remain identifiable after the browser UI is gone.

## Memory contract

Logical tab count must never be constrained merely because WebViews are expensive. Renderer residency is a cache/policy decision; the tab database is the source of truth.

## Security baseline

- cleartext HTTP traffic disabled at the application level
- WebView file access disabled
- mixed-content loading disabled
- Safe Browsing enabled on supported WebView versions
- overlay service is not exported
- only user-created tabs become overlay heads
