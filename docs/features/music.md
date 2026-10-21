# Music

Status: v0.1 implemented (code in `app/src/main/java/com/zerosync/app/music/`).

## Layout (top → bottom)
- Folder dropdown (pill): phone folders with songs (e.g. English, Nepali, Hindi). Only the
  picked folder's songs are in the player, wheel and next/back. Default: first folder.
- Cover carousel: current song's cover big in the center; previous/next covers smaller and
  dimmed on the left/right (tap = previous/next). No cover → music-note placeholder.
- Title (large) + artist (small).
- Timestamp bar: current time · seek bar · total time. Drag to jump. Updates ~2×/s only
  while playing and the app is on screen.
- Previous · Play/Pause (big lilac circle) · Next.
- Two dials at the bottom, above the nav bar:
  - Left (raised): volume wheel. Each 30° tick = one system volume step + vibration.
  - Right (lower): song wheel. Each 30° tick = next/previous song shown + vibration;
    the song plays when the finger lifts. Wraps around the list.

## Playback
- No notification, no background service (user removed it 2026-10-18).
- Keeps playing with the screen off (power button) and while switching tabs.
- Keeps playing when leaving the app (home / app switch); stops when cleared from recents.
  No service, so HyperOS may still kill it in the background.
- Shuffle button (left of Previous): random order within the playing folder.
- Lock-screen player: if the Music tab is open and playing when the phone locks, pressing power
  shows ZeroSync's player over the lock screen (no unlock) with both wheels. Only the player
  is shown while locked (no nav bar / other tabs).
- Pauses for calls / other audio, and when headphones unplug.

## Technical notes
- Songs: MediaStore Audio (IS_MUSIC), sorted by title. Covers via built-in loadThumbnail.
- Folders = MediaStore buckets.
- `MusicViewModel` owns one Media3 ExoPlayer (wake lock while playing); volume via AudioManager.
- Permissions: READ_MEDIA_AUDIO (+ WAKE_LOCK shared with Gallery).

## Files
- `MusicRepository.kt`, `MusicViewModel.kt`, `MusicScreen.kt`, `RotaryWheel.kt`

## Not done yet / ideas
- Search / playlists / repeat mode
- Remember last song across reboots

## Done when
- Plays on Redmi Turbo 4 with screen off; wheels tick + vibrate; folder filter works.
