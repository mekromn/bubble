# Bubble

Bubble is an Android browser whose defining interaction is simple: **any tab can be minimized into an independent, draggable heads-up icon that remains available over other apps and can be restored instantly.**

There is no application-enforced maximum number of logical tabs or heads. Bubble achieves that by separating durable tab/session state from expensive live WebView renderer state. A head does **not** imply that its page must remain resident in memory.

## Production implementation

Production work is on `implementation-v1`, with phase-sized commits corresponding to issues #2 through #9. The specification on `main` is authoritative if implementation and docs ever conflict.

### Verified foundation toolchain

- Android Gradle Plugin 9.3.0
- Gradle 9.5.0
- JDK 17
- Kotlin 2.4.10
- compileSdk / targetSdk 36
- minSdk 26
- Compose BOM 2026.06.00 (stable Compose 1.11 generation; Compose 1.12 requires compileSdk 37)
- AndroidX WebKit 1.16.0
- package / namespace `com.mekromn.bubble`

### Build

Install Android SDK Platform 36, Build Tools 36.0.0, JDK 17, and Gradle 9.5.0, then run:

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

GitHub Actions performs the same checks plus a release compile check and publishes the debug APK artifact.

## Specification

- `docs/PRODUCT_SPEC.md`
- `docs/UX_SPEC.md`
- `docs/ARCHITECTURE.md`
- `docs/PLATFORM_SECURITY.md`
- `docs/TEST_RELEASE.md`
- `docs/ROADMAP.md`
- `docs/IMPLEMENTATION_ISSUES.md`
- `docs/VALIDATION_STATUS.md`

## Non-negotiable product principles

1. No arbitrary tab/head cap.
2. Heads stay where the user puts them; free placement is default.
3. A logical tab is not a WebView.
4. Heads are lightweight native overlay UI; renderer residency is independently managed.
5. Private mode is exposed only with real profile isolation.
6. Bubble never bypasses TLS errors or auto-grants arbitrary web permissions.
7. Process-death recovery, memory behavior, accessibility, security, CI, and runtime validation are release requirements.
