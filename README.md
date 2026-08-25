# Bubble

Bubble is an Android browser built around **independent floating tab heads**.

A normal browser tab can be minimized into a draggable system-overlay head, moved anywhere on screen, and tapped to restore that same logical tab. Bubble intentionally has **no arbitrary tab/head count limit**. Instead, the architecture separates logical tabs from live WebView renderers so inactive tabs can be suspended or hibernated as the project evolves.

## r01 — Floating head core

The first milestone provides:

- real WebView browsing
- multiple logical tabs
- minimize any tab into its own Android overlay head
- independently draggable heads
- tap a head to restore its tab
- favicon-backed heads with a fallback site initial
- persisted tab/head metadata and head positions
- memory-conscious WebView state capture for inactive tabs
- foreground-service ownership of overlays
- GitHub Actions debug APK builds

Development branch: `r01-floating-head-core`

See `docs/ARCHITECTURE.md` and `docs/R01.md` for the design contract and milestone details.
