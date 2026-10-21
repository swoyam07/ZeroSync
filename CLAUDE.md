# ZeroSync

Offline-first, private, pure-black AMOLED Android app. Kotlin + Jetpack Compose.
The developer is new to Kotlin/Android: explain briefly, keep code simple.

## Environment (no Android Studio)
- Editor: VS Code only. Build with the Gradle wrapper from the terminal.
- Android SDK via command-line tools (`sdkmanager`), device via `adb`. See `docs/setup.md`.
- JDK 21, Kotlin 2.4.x, Gradle 9.x. AGP decides compatible versions.
- Test device: Redmi Turbo 4, Android 16 (API 36), HyperOS 3. See `docs/device.md`.

## Commands
- Build debug: `./gradlew assembleDebug`
- Install on phone: `./gradlew installDebug`
- Unit tests: `./gradlew test`
- Lint: `./gradlew lint`
- Performance claims: only after testing a release build (`./gradlew assembleRelease`).

## Hard rules
- NEVER add the `INTERNET` permission. App must work fully offline.
- No data in/out to other apps: no exported components (except those Android requires
  for the default-dialer role), no ContentProviders, no share intents, backups disabled.
  Exception: Camera's per-file Public switch copies a file to shared storage (user-approved).
- Ask before adding ANY dependency, SDK, or Gradle plugin: use the `package-decision` skill.
- Features with real trade-offs: ask priority first (see `package-decision` skill).
- No animations by default. Approved exception: 120 ms pop-in of the music hold menu. No background work, services, or polling unless approved.
- Prefer Android/Jetpack built-ins over third-party packages.
- Adaptive layouts only; never hardcode screen pixel sizes.
- Handle backgrounding, process death, config changes, low memory.

## Where things live (read only what the task needs)
- Feature specs: `docs/features/<feature>.md` (phone, contacts, gallery, music, files, camera)
- Architecture & navigation: `docs/architecture.md`
- Open questions / decisions log: `docs/decisions.md`
- Theme/UI rules auto-load from `.claude/rules/` when editing UI files.

## Workflow
- One feature per session. Read that feature's spec before coding.
- Plan first for anything touching more than ~3 files.
- If a spec is empty or unclear, ask instead of guessing.
