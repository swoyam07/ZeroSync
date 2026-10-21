# Decisions & Open Questions

## Approved decisions
| Date | Feature | Choice | Why |
|---|---|---|---|
| 2026-10-18 | App UI | Jetpack Compose (Material 3) | Modern Kotlin UI, easy pills/gestures |
| 2026-10-18 | Gallery video | Media3 ExoPlayer + media3-ui | Smooth swipe feed, offline |
| 2026-10-18 | Gallery storage | No database; scan MediaStore folders with videos | User choice, zero extra deps |
| 2026-10-18 | Gallery priority | Smooth scroll with low resources | User choice |
| 2026-10-18 | Thumbnails | Built-in loadThumbnail + LruCache (no Coil) | No extra library |
| 2026-10-21 | Camera | Jetpack CameraX 1.5.0 + built-in PdfDocument | Official, offline, simple; custom document frames |
| 2026-10-21 | Camera privacy | Private by default; per-file Public switch copies to shared storage, off deletes it | User choice |
| 2026-10-21 | Camera encryption | NOT added | Sandbox already blocks other apps; Android FBE already protects a locked phone over USB. Only helps vs root/debug, which user excludes |
| 2026-10-21 | Files access | MANAGE_EXTERNAL_STORAGE ("All files access"), read-only | User wants whole storage; folder picker can't reach Download |
| 2026-10-21 | Files viewers | PdfRenderer + own docx/pptx/xlsx reader, no libraries | Offline, small; simplified layout |
| 2026-10-18 | Music playback | ExoPlayer in ViewModel; NO service/notification (media3-session removed) | User doesn't want a notification; screen-off play is enough |

## Open questions
- Mobile.md line 20: "Get a pat" unfinished.
- Gallery & Contact marked "on hold". Build order?
- Phone: "default call pick up" means full-screen answer UI, or auto-answer?
- Contact long-press gesture: contact icon (not gallery)?
- Whole-gallery reads shared storage. OK as "read-in only"?

## Decided
- 2026-10-18 Gallery screen-off playback: approved by user. Sound keeps playing when the power
  button locks the screen; pauses when leaving the app. No service (a media service would need
  to be exported). Trade-off: HyperOS may still stop it after a long time.

- 2026-10-18 Music lock-screen player: activity shows over keyguard (setShowWhenLocked) only
  while Music tab is open and playing; only the player is visible while locked.

## Permissions in use
| Permission | Feature | Reason |
|---|---|---|
| READ_MEDIA_VIDEO | Gallery | Read videos (API 33+). Photos dropped from Gallery. |
| READ_MEDIA_VISUAL_USER_SELECTED | Gallery | Partial access (API 34+) |
| READ_EXTERNAL_STORAGE (max 32) | Gallery | Older Android |
| WAKE_LOCK | Gallery, Music | Keep video sound playing while screen is locked (held only while playing) |
| READ_MEDIA_AUDIO | Music | Read songs |
| INTERNET, ACCESS_NETWORK_STATE | (removed) | Stripped via tools:node="remove" |
