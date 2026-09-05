# Bubble clean rebuild

The user's September 4, 2026 request explicitly replaces the failed application implementation. On `rebuild-v2`, this file and `docs/REBUILD_V2.md` supersede the old implementation stack requirements. Old specification documents remain as historical/product references, not instructions to restore the old source code.

Keep the existing debug signing identity and package; never rotate them as part of a fix. No production signing secrets. No browser bootstrap in Application.onCreate or Gecko subprocesses. Main-thread Gecko operations, off-main-thread disk IO, one process-owned runtime, durable UUID tabs, one Activity-owned GeckoView, one small workspace overlay, no arbitrary logical tab cap. Do not voluntarily suspend ChatGPT live sessions. Never bypass TLS or grant arbitrary site permissions. No network analytics. Do not claim runtime or 120 fps success based on build/lint alone.
