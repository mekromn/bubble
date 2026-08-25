# Bubble — Feature Addendum

This document amends the production specification and is authoritative alongside `PRODUCT_SPEC.md`, `UX_SPEC.md`, and `ARCHITECTURE.md`.

## 1. Explicit Keep Live for floating heads

`Pin` and `Keep live` are different controls.

- **Pin** protects a logical tab from normal user-facing cleanup/closure flows and gives it preferential retention treatment.
- **Keep live** explicitly asks Bubble to retain a live renderer for that tab even while it is presented as a floating head.

`Keep live` is persisted per tab and is opt-in. A keep-live head should remain `WARM` while it is not the selected browser tab and while the Bubble process/renderer survives.

This is a best-effort Android guarantee, not an attempt to defeat OS memory management. Android may still kill a renderer or the application process. On renderer loss, Bubble must recover the tab and re-establish keep-live residency when legitimately running. On process death, durable tab state remains authoritative and Bubble restores the keep-live preference on the next legitimate application/service lifecycle.

Keep-live renderers are excluded from ordinary warm-LRU eviction. Under actual process death or unavoidable platform reclamation, Bubble recovers gracefully rather than pretending a renderer can be made immortal.

There remains no hard logical tab/head limit. Users can mark as many heads keep-live as they choose; Bubble may surface memory-pressure guidance but must not silently change the preference.

## 2. URL sharing and external browser entry

Bubble must support all of the following:

- registering as an Android HTTP/HTTPS browser (`ACTION_VIEW`);
- receiving ordinary `text/plain` Android shares containing a URL;
- extracting HTTP(S) URLs from common share payloads such as `title + URL`;
- an explicit share-sheet target **Open in Bubble head**;
- normal shared links entering the same durable logical-tab/session pipeline used by manually opened tabs;
- shared-to-head links entering the same durable head/session pipeline used by manually minimized tabs.

The explicit head target requests overlay permission only when needed. If permission is declined, offer to open the URL normally rather than losing it.

External/shared navigation must not create untracked temporary WebViews.

## 3. User-Agent switcher

Bubble defaults each normal tab to **Chrome-compatible Mobile** identity rather than the obvious embedded-WebView user agent.

Required per-tab modes:

1. **Chrome mobile** — default.
2. **Chrome desktop**.
3. **System WebView** — unmodified platform identity for compatibility/debugging.

The selected mode is durable per tab and survives hibernation/recovery/session save-restore.

For Chrome-compatible modes Bubble should, where supported by the installed WebView:

- remove obvious embedded-WebView markers from the legacy UA string;
- align the Chrome/Chromium major version with the installed WebView/Chromium engine rather than freezing a stale version number;
- use modern reduced-UA formatting;
- override User-Agent Client Hint metadata through AndroidX WebKit when supported;
- reload a resident page after a UA-mode change so the new identity applies to navigation.

Do **not** claim that Android WebView is cryptographically or behaviorally indistinguishable from standalone Google Chrome. JavaScript-visible engine behavior, available browser APIs, client hints, packaging and other fingerprint surfaces can still distinguish WebView. The product promise is maximum practical Chrome compatibility without deceptive security claims.

A future Custom UA mode may be added, but it must clearly warn that malformed or stale strings can break sites.

## 4. Named custom sessions / workspaces

Bubble supports reusable named session snapshots independent of automatic crash/process recovery.

A saved session captures all non-private logical tabs in the current workspace, including as applicable:

- URL and title;
- tab ordering;
- browser-vs-head presentation;
- normalized floating-head placement;
- pin state;
- Keep-live preference;
- per-tab User-Agent mode;
- per-tab zoom;
- group relationship through a snapshot-local group key.

Private tabs are never included in durable named-session snapshots.

Restoring a saved session creates **fresh TabIds** and remaps saved group identities. A saved snapshot is a reusable template, not a second reference to the same live logical tabs.

Required restore modes:

- **Replace** — close/remove the current non-private workspace and restore the saved workspace.
- **Merge** — preserve the current workspace and add saved items that are not already represented by the same URL + presentation class.
- **Add all** — preserve the current workspace and add every saved item, including duplicates.

Head placement remains normalized so sessions remain useful across rotation, display-size changes and different device/display dimensions.

Saved sessions must be listable, restorable, renameable, overwriteable/updatable, duplicable and deletable by the production release. Initial implementation may land save/list/restore/delete first, but rename/update/duplicate remain part of the v1 contract.

## 5. Validation additions

Production validation must include:

- Keep-live head remains renderer-resident across ordinary tab switching/minimize/restore cycles;
- Keep-live renderer death recovers without losing the logical head;
- ordinary non-keep-live heads still hibernate under resource policy;
- share a URL from at least Chrome, another browser/app, and Android text share into normal Bubble;
- share directly to **Open in Bubble head** with overlay permission granted and denied;
- Chrome-mobile UA legacy string and UA-CH behavior checked on representative fingerprint/echo pages without claiming perfect Chrome identity;
- UA mode persists across tab hibernation, process reconstruction and named-session restore;
- save a mixed browser/head workspace, rotate/change display size, then restore with usable head positions;
- Replace, Merge and Add-all session semantics verified;
- private tabs proven absent from saved-session database snapshots.
