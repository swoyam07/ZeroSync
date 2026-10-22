# Files

Status: v0.1 implemented (code in `app/src/main/java/com/zerosync/app/files/`). View-only.

## Access
- "All files access" (MANAGE_EXTERNAL_STORAGE), turned on by the user in Settings (approved
  2026-10-21). Used only to READ documents; never writes, never sends. Still no INTERNET.
- Files found through MediaStore (whole shared storage), newest first.

## List
- Pill tabs with counts: PDF · Word · PPT · Excel · Text (.pdf / .docx .doc / .pptx .ppt /
  .xlsx .xls / .txt .csv) · Images · Videos. Row: type tag, name, size · date. Old
  .doc/.ppt/.xls say "no preview".
- Images / Videos: grid of thumbnails, 3 per row, scrolls (lazy). Videos show a play mark.
- Delete (all tabs), two steps: hold an item → "Delete this file?" → red Delete. Removes the file
  from the phone (MediaStore delete, allowed by All files access).

## Viewers (full screen, nav hidden; back arrow + name top-left; system back works)
- PDF: built-in `PdfRenderer`, exact pages, scroll down; two-finger pinch zoom 1–4x.
- Word .docx: own zip+XML reader: headings, bold/italic, bullets, pictures, simple tables.
- PowerPoint .pptx: screen turns landscape automatically on open (no tilt sensor) and back
  on close. One slide per screen, swipe sideways; text boxes + pictures at their positions
  (white slide). Layout-only placeholders get a typical spot. No animations/charts/shapes.
- Excel .xlsx: sheet tabs, cell grid (scroll down + sideways), values only (≤5000 × 100).
- Text/CSV: monospace, ≤2M chars.
- Image: full screen (system thumbnail ≤2048 px), pinch zoom.
- Video: opens PAUSED with player controls; user taps play. Player released on close.

## Technical notes
- No libraries: java.util.zip + Android XmlPullParser (`OfficeParsers.kt`).
- Unzip capped at 200 MB; pictures decoded sampled to ≤1400 px; PDF pages drawn at 1.5x width.
- MainActivity handles rotation itself (configChanges), so turning for slides doesn't restart.
