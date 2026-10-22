# Test Device

- Xiaomi Redmi Turbo 4, Android 16 (API 36), HyperOS 3
- 16 GB RAM, 256 GB storage, MediaTek Dimensity 8400 Ultra, 6550 mAh
- 6.67" AMOLED, 1220 × 2712, up to 120 Hz

## HyperOS quirks
- Aggressive app killing: enable Autostart + Battery "No restrictions" (needed for calls).
-

## TODO
-

## HyperOS: SMS hidden from ZeroSync (found 2026-10-22)
Even as default SMS app with READ_SMS granted, the SMS provider returned 0 rows until the
hidden Xiaomi app-op MIUIOP(10028) was allowed, and WRITE_SMS (package mode) was "ignore".
Fixed over USB:
  adb shell cmd appops set com.zerosync.app 10028 allow
  adb shell cmd appops set com.zerosync.app WRITE_SMS allow
Re-run these after a reinstall / clearing app data if Messages shows "No messages".
