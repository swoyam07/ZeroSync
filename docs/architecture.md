# Architecture

## Stack
- UI: Jetpack Compose (Material 3), single Activity
- State: ViewModel per feature + Compose state
- Media: Media3 ExoPlayer
- Data: MediaStore for gallery; database TBD (contacts)
- Camera: TBD (CameraX proposed)
- minSdk 29, targetSdk/compileSdk 36. Versions in `gradle/libs.versions.toml`.

## Package layout (`app/src/main/java/com/zerosync/app/`)
- `MainActivity.kt`: app shell, tab switching, nav bar
- `ui/theme/`: colors + theme
- `ui/`: shared controls (PillNavBar, PillSwitch, ZsIcons)
- `gallery/`: Gallery feature
- `music/`: Music feature (player in ViewModel, no service)
- (future) `contacts/`, `phone/`, `files/`, `camera/`

## Navigation
Floating pill bottom bar: Gallery (default) · Contact · Music · File · Camera.
No navigation library; tab held in `rememberSaveable`. Nav bar hides in full-screen views.

## Privacy
No INTERNET permission (removed even if a library adds it). Backups disabled. Only launcher activity exported.
