# Camera

Status: v0.1 implemented (code in `app/src/main/java/com/zerosync/app/camera/`).

## Modes (top pill): Passport · ID · PDF · Saved
- Live back camera with a white frame in the real document shape; outside is darkened.
  Only the inside of the frame is saved.
  - Passport: 35 × 45 mm (portrait) → JPEG.
  - ID: 85.6 × 54 mm (bank-card size, landscape) → JPEG.
  - PDF: A4 portrait (210 × 297 mm). After each photo: "Add another page?"
    Yes = take the next page; No = build one PDF from all pages. While pages are pending:
    Cancel (left of shutter) and Submit N (right).
- Saved: category tabs Passport · ID · PDF (with counts); each lists only its own captures
  (newest first) with a Public switch and Delete. Category = file name prefix.

## Privacy (user decision 2026-10-21)
- Everything is saved PRIVATE in app storage (`filesDir/camera`). Other apps can't see it.
- Public switch ON = copy to shared storage (`Pictures/ZeroSync` for photos,
  `Documents/ZeroSync` for PDFs) so WhatsApp etc. can pick it.
- Switch OFF = that shared copy is deleted; the private file stays.
- No share intents, no exported components.

## Technical notes
- CameraX (camera-camera2, camera-lifecycle, camera-view 1.5.0). Preview + ImageCapture both 4:3;
  preview FILL_CENTER, frame mapped from screen to photo with the same scale, then cropped.
- PDF: built-in `android.graphics.pdf.PdfDocument`, A4 at 595 × 842 pt, one photo per page.
  Pages wait as temp JPEGs in cache (≤1240 px) to keep RAM low.
- Camera runs only while the Camera tab is open.
- Permission: CAMERA (asked on first open).

## Not done yet / ideas
- Passport 2 × 2 inch option; flash; front camera; auto edge detection.
