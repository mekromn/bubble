# Bubble 0.7.2: persistent per-tab account profiles

## Scope and behavior

User request: use the same website in simultaneous tabs with different signed-in accounts. A named profile maps to a persistent GeckoSessionSettings contextId. This is an engine storage container, not JavaScript cookie swapping, private mode, a separate Android process or a VPN.

The existing literal contextId `normal` is retained as the Default profile, so upgrading does not intentionally move existing tabs into a fresh cookie store. New profile IDs are stable UUID-based identifiers independent of editable labels. Browser menu > Profiles / accounts, or Chat tools > Profiles / accounts, supports creating and renaming profiles, opening a new ChatGPT tab in a chosen profile, and opening the current address in another profile. Existing tabs stay open; the latter operation creates a NEW tab with URL only and does not copy cookies, form state, Gecko SessionState, history or in-flight generation.

Ordinary new tabs inherit the selected tab's profile. target=_blank/login popups inherit the OPENER'S profile rather than the globally selected tab. Duplicates inherit the original profile. Recently closed tabs, session recovery, Activity recreation and floating/fullscreen display handoffs retain the immutable tab profile ID. Different profiles can remain resident together; tabs in the same profile intentionally share sign-ins and same-origin storage. UI labels show which profile is in use without adding another permanent toolbar row.

## Persistence and safeguards

The version-1 atomic workspace file gets optional profiles metadata and per-tab profileId, including recently closed entries. Legacy entries missing the field resolve to `normal`. Invalid explicit IDs fail closed using the existing preserve-and-do-not-overwrite corrupted-file behavior; they never silently use Default's account. Valid IDs missing registry metadata are recovered under a local label while preserving their exact context ID. Registry and tab references are written in one atomic snapshot.

Profile names are local, limited to 60 characters, control-character stripped and checked for case-insensitive duplicates. Renaming never changes the storage ID. No destructive profile clearing, account logout or cookie deletion is performed as part of this update. The user's authorization to clear storage refers to obsolete GitHub Actions artifacts, not on-device browsing data. The same debug package/signing identity is retained.

## Verification contract

Pure policy tests cover legacy context preservation, UUID IDs, names and fail-closed restoration. Android instrumentation checks synthetic identities on the EXACT SAME origin: JavaScript cookies, an HttpOnly cookie received by the server, localStorage, IndexedDB and CacheStorage. It checks empty new profiles, intentional same-profile sharing, separate profile data, actual touched popup inheritance, duplication/reopening, Activity recreation and URL-only open-in-profile. A separate test verifies old-file loading, profile metadata roundtrip and missing-metadata recovery.

At source submission these new Android tests have NOT yet run. Existing all-web/ChatGPT lifecycle Node tests and local pure Kotlin policy checks passed; they are not proof of complete browser account isolation. Do not claim authenticated ChatGPT or Google account tests without device evidence, or claim protection from device fingerprint correlation, IP correlation, OS eviction or untrusted code in the same Android app. Cookies/site data partitioning is the feature, not universal anonymity.

## Separate backlog

This profile patch is based on the verified 0.7.1 app. The earlier actual backdrop-blur redesign, complete download/attachment work, text-only quick tabs, swapped toolbar icons, new edge swipe-hold behavior, native view-only PiP removal and ChatGPT-sidebar popup have not been silently counted as part of profiles. Keep these requested changes on the backlog and do not claim this profile-only build completes them.
