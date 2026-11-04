# Camera

Status: v0.1 implemented (code in `app/src/main/java/com/zerosync/app/camera/`).

## Modes: Passport · ID · PDF
- Picked in a frosted dark panel under the frame (round icon + small caps label, selected =
  white circle). Tap, or swipe left/right inside the panel. No top pill; Saved has a back arrow.
- Shutter centred at the bottom; Saved = folder button at the right edge (back = camera).
  PDF in progress: Cancel / Submit N in a row above the shutter.
- Live back camera with a white frame in the real document shape; outside is darkened.
  Only the inside of the frame is saved.
  - Passport: 35 × 45 mm (portrait) → JPEG.
  - ID: 85.6 × 54 mm (bank-card size, landscape) → JPEG.
  - PDF: A4 portrait (210 × 297 mm). After each photo: "Add another page?"
    Yes = take the next page; No / Submit = "Name this PDF" box (suggested "Scan <date time>",
    editable, saved as <name>.pdf; same name gets " (2)") → one PDF from all pages. While pending:
    Cancel (left of shutter) and Submit N (right).
- Saved: icon column at the bottom-right (like Files; rounded squares, no counts):
  Passport · ID · PDF. Each shows only its own captures (newest first), Public switch.
  Category = file name prefix.
- Delete is two-step, like the File section: hold a row → "Delete this file?" → red Delete.

## QR scanner (2026-10-25) — `QrCheck.kt`
- 4th mode "QR" in the mode panel (square frame, no shutter). ZXing core 3.5.3 decodes frames
  offline (QR + barcodes: EAN/UPC, Code 128/39, Data Matrix, Aztec, PDF417).
- Result card: type, readable fields, warnings, full text. Only Copy / Cancel — NOTHING is ever
  opened, joined, called, sent or saved. Scanning pauses while the card is open.
- Types: website, dangerous link (javascript:/intent:/data:/file:/content:), payment (EMV
  "000201…": merchant, city, amount, network), app link, Wi-Fi (password hidden), phone, SMS,
  email, contact card, location, calendar event, ISBN, product code, text.
- Link checks (text only, offline): http, @ trick, raw IP, punycode xn--, shorteners, risky
  endings, bank/wallet name outside the real domain, very long / messy. Real domain shown bold.
  Can't check online blacklists or who owns a site (no internet by design).

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

- Zoom (2026-11-04): pinch on the preview = real camera zoom (1x–8x, capped by the lens), in all
  modes; the saved photo / PDF page is zoomed too. Chip at the top always shows the level ("1.0x", "2.4x"); tap = 1x.
  Shutter: tap = photo; hold and drag up = zoom in, drag down = zoom out (no photo).
- Mode dial (2026-11-04, user chose "dial wheel"): Passport · ID · PDF on a curved wheel, chosen one big +
  white in the middle, neighbours small + gray. Loops (PDF → Passport). Swipe = one step; tap a side name = turn to it.
  Uses the approved 200 ms mode-strip slide.
