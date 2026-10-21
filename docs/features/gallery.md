# Gallery

Status: v0.2 implemented (code in `app/src/main/java/com/zerosync/app/gallery/`). Videos only.

## Modes (top pill switch, or swipe sideways on the Gallery screen: left = Folders, right = Shuffle)
- Shuffle: opens straight into ONE random video (no grid, no choices). Swipe up/down for more,
  always random order in cycles: every video plays once per cycle (no repeats), then a new
  random cycle starts (never the same video twice in a row). Pill switch + toggle overlay the video.
- Feed is true full screen: video fills the screen (cropped, no black bars), system bars hidden
  (swipe from edge to peek). Nav bar uses high-contrast colors (white icons, lilac border/active).
- Folders: phone folders that contain videos (auto-detected), shown 2 per row as big square
  covers with the name below (no counts) → pick folder → its videos (grid)
  → tap → vertical feed in folder order.

## Feed controls (right side, ~65% down, 28dp from the edge)
- Auto scroll (skip-next icon): on = next video when one ends; off = loop current video.
- Play order (shuffle icon / numbered-list icon): OFF by default = random; on = sequential
  (Shuffle: newest first; Folders: folder order). Applies to both feeds. Toggling keeps the
  current video first (it restarts) and reorders the rest.
- Round 48dp toggles: filled lilac = on, dark with white icon = off.

## Technical notes
- Source: Android `MediaStore` videos (no paths, no database). Folders = MediaStore buckets.
- Shuffle list reshuffles only when the set of videos on the phone changes.
- Thumbnails: built-in `ContentResolver.loadThumbnail`, LruCache ≤ 32 MB, max 4 parallel decodes.
- Feed: Compose `VerticalPager` + ONE Media3 ExoPlayer attached only to the settled page.
- Permissions: READ_MEDIA_VIDEO (+ partial access on Android 14+).
- Screen locked (power button) while playing: sound continues, auto-scroll keeps going.
  Leaving the app (home/switch): pauses. Pauses for calls and when headphones unplug.
- Reloads video list on resume.

## Files
- `MediaRepository.kt`: MediaStore queries
- `GalleryViewModel.kt`: state
- `GalleryScreen.kt`: Shuffle/Folders modes, folders, grid, permission
- `VideoFeed.kt`: vertical player + overlay
- `Thumbnail.kt`: thumbnail loader/cache

## Not done yet / ideas
- Remember mode/feed position after process death
- Custom (user-made) folders beyond phone folders

## Done when
- Builds and runs on Redmi Turbo 4; smooth scroll in release build with large library.
