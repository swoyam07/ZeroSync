# Music

Status: v0.1 implemented (code in `app/src/main/java/com/zerosync/app/music/`).

## Layout (top → bottom)
- No folder dropdown (removed 2026-10-21): folders change only via the song button (hold, drag
  down). Default: first folder.
- Big round cover with the time bar as a ring of wave bars around it; elapsed | total below.
- Title (large) + artist (small).
- Timestamp bar: current time · seek bar · total time. Drag to jump. Updates ~2×/s only
  while playing and the app is on screen.
- Previous · Play/Pause (big white circle) · Next.
- Two dials at the bottom, above the nav bar:
  - Left (raised): volume wheel. Each 30° tick = one system volume step + vibration.
  - Right (lower): song wheel. Each 30° tick = next/previous song shown + vibration;
    the song plays when the finger lifts. Wraps around the list.

## Round buttons (no hold)
- Both music buttons open their menu as soon as you start swiping (no long-press wait).
  A plain tap does nothing. Song button: up+circle = play, right+circle = pick, down = folder,
  left = time scrub. Volume button: right = volume, left = sleep timer.

## Playback
- No notification, no background service (user removed it 2026-10-18).
- Keeps playing with the screen off (power button) and while switching tabs.
- Keeps playing when leaving the app (home / app switch); stops when cleared from recents.
  No service, so HyperOS may still kill it in the background.
- Shuffle button (left of Previous): random order within the playing folder.
- Loop button (right of Next): repeats the current song forever.
- Reopening after the app was cleared: same song and time, paused (saved in private prefs on
  song change, play/pause, seek and when the screen stops).
- Sleep timer (volume button: hold, drag left, move up/down): Off / 30 min / 1 h / 2 h / 5 h.
  Pauses the music when it ends. One coroutine wait in the ViewModel (user-approved 2026-10-21), no service.
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
- Search / playlists


## Done when
- Plays on Redmi Turbo 4 with screen off; wheels tick + vibrate; folder filter works.
