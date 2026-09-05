# Clean rebuild contract — 2026-09-04

The failed source tree is replaced, not patched. Git history/implementation-v1 preserve the former implementation. This branch intentionally uses Kotlin with ordinary native Android Views, no Compose renderer boundary, no old observer graph, no Room/DataStore startup. A new versioned AtomicFile workspace store will replace the old tab database without deleting it. Gecko's profile stays app-private.

Gate 1: direct Activity-owned GeckoView, one lazy process runtime, no Application bootstrap. An Android 16 emulator must paint a two-colour localhost page, advance JavaScript for >12 seconds, and return actual nonblank compositor pixels.

Gate 2: durable UUID tabs, one shared workspace bubble, retained ChatGPT sessions, bounded renderer-recovery attempts, frame-paced native animation, async atomic snapshots, safe file selection and origin-scoped notifications. No arbitrary logical tab cap.

Gate 3: Google and ChatGPT runtime browsing, Pixel 9 Pro XL foreground/background/keyboard tests, frame timing on a 120Hz physical display. Emulator success is not a 120fps or zero-jank claim.

Never rotate the existing public debug signing identity; production signing remains separate. No production secrets, analytics SDKs, TLS bypass, universal JavaScript bridge, content logging, automatic sign-in, or unrestricted permission grants.

Status: isolated renderer gate submitted for CI. No runtime success yet claimed.
