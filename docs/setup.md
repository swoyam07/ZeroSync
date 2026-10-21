# Setup (VS Code, no Android Studio)

## 1. Android SDK (command line)
- Download "Command line tools only" from developer.android.com/studio (bottom of page),
  or Arch AUR: `android-sdk-cmdline-tools-latest`.
- Put it at `~/Android/Sdk/cmdline-tools/latest/`.
- Add to `~/.bashrc`:
  ```
  export ANDROID_HOME=$HOME/Android/Sdk
  export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
  ```
- Install: `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"`
- Accept licenses: `sdkmanager --licenses`

## 2. Gradle wrapper (one time, in project root)
`gradle wrapper --gradle-version 9.8.1` → then always use `./gradlew`.

## 3. Phone
Developer options → USB debugging + "USB debugging (Security settings)" (HyperOS, needs Mi account)
and "Install via USB". Check: `adb devices`.

## 4. Build & run
- `./gradlew installDebug` then open ZeroSync on the phone.
- Logs: `adb logcat | grep -i zerosync`
- Performance testing: `./gradlew installRelease`

## VS Code extensions
TBD (Kotlin language support + Gradle). Compose previews don't work in VS Code; test on the phone.

## If the first build fails on versions
Update `gradle/libs.versions.toml` (AGP ↔ Gradle ↔ Kotlin must be compatible).
