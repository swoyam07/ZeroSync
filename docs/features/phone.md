# Phone

Status: v0.1 (step 1+2) implemented (code in `app/src/main/java/com/zerosync/app/phone/`).

## Goal
Make and receive normal SIM calls using ZeroSync's own UI instead of the system Phone app.

## Done
- Default Phone app role (`RoleManager.ROLE_DIALER`), asked from the Contact tab (temporary home
  of the Phone screen: setup card + dial pad + Call). User approved 2026-10-22.
- `CallService` (InCallService): system binds it only while a call exists. Ringtone/vibration
  stay with the system.
- Incoming: full-screen-intent notification → `CallActivity` (shows over lock screen, turns
  screen on). Swipe white handle UP = answer, DOWN = decline.
- In call: number, timer, Mute, Speaker, Keypad (DTMF), Hold, red End.
- `DialerActivity` handles DIAL / tel: links from other apps (required for the role).
- Missed + declined calls: `CallService` counts every incoming call that never connected, per
  number (private prefs). One notification per number, "number (3)" / "Missed 3 calls",
  grouped so several stack. Tap = unlock if locked → ZeroSync dial pad with the number filled
  in (count cleared). Swipe away = cleared. `MissedCallReceiver` only exists so the system
  skips its own missed-call notification.
- Phone screen: Keypad · Recents · Contacts tabs. Keypad = digits only, green Call button,
  hold 0 = "+". Recents = Android's own call history (CallLog, read-only, nothing copied;
  user chose option 1 on 2026-10-22): type icon, name (phone contacts' cached name) or number,
  "(N)" for back-to-back repeats, relative time; tap = call back. Last 500 calls.
- Network logo (`Carrier.kt`, images in `res/drawable/carrier_*.png`): first 3
  digits (after removing +977 / 00977): NTC 984/985/986/974/975/976, Ncell 980/981/982/970; anything else
  (e.g. 01 landlines) shows no logo. Shown on the incoming-call screen and in Recents.
- Contacts (private, `ContactsStore.kt` + `ContactsUi.kt`): name (required), 1+ numbers each
  Friends (default) / Family / Work, description, optional photo (system photo picker, ≤512 px).
  Stored only in app-private files, AES-256-GCM with a hardware-backed Android Keystore key
  (contacts.bin + <id>.photo). Never in the phone's shared contacts, no backup.
  Numbers match in any form (+977, spaces). Shown as name on: incoming screen (+ label, photo),
  in-call screen, Recents, missed-call notification. Recents: hold a line = save / open contact.
- Unlocked incoming call (`CallOverlay.kt`, user chose 2026-10-22): ZeroSync's own floating card
  at the top (photo with NTC/Ncell color ring, name/number + logo, red Decline, green Answer;
  tap = full screen). Needs "Display over other apps"; the notification then goes on a quiet
  channel (no HyperOS pop-up, no full-screen intent). Without the permission: system pop-up.
  Locked / screen off: unchanged.
- Spam (`Spam.kt`): unlimited numbers in private prefs (local form). "Spam" button left of + in
  Contacts opens the list (add / ✕ remove); Recents hold → "Mark as spam". `SpamScreeningService`
  (CallScreeningService, default-dialer role) rejects spam before it rings: no ringtone, no UI,
  no notification; still logged → Recents shows it with a red "Spam" tag. CallService rejects
  spam too as a backup and never counts it as missed.
- SIMs (`SimInfo.kt`, `SimCall.kt`): dial pad shows the calling SIM ("SIM 2 · Ncell" / "Ask each
  time"); call screen shows the SIM in use. With TWO active SIMs: Keypad shows Call 1 / Call 2,
  Contacts rows show "1" / "2" buttons. Each SIM dials the contact's number on the SAME network
  if they have one (NTC→NTC, Ncell→Ncell, usually cheaper), else their first number. Green =
  same network (recommended); gray = different network, still allowed. Call forced through the
  chosen SIM via EXTRA_PHONE_ACCOUNT_HANDLE. One SIM: single call button as before.
- Favorites (`Favorites.kt`, `FavCallButton.kt`): 4 slots ↑ → ↓ ← (private prefs). ★ in the
  Contacts header opens them: pick a contact number or type one; ✕ clears. Keypad call button:
  tap = call typed number; drag (no hold) = 4-way menu (pads show the favorite's initial / last 2
  digits, "+" if empty), drag onto one and release = call it from the same-network SIM. Empty
  slot opens the Favorites screen. Release in the middle = cancel.
- Quick codes (`ServiceCodes.kt`): NTC (blue) / Ncell (purple) buttons at the top of the Keypad.
  NTC: Balance *400#, Pack *1415#, Data balance *1414*55#, Credit *1477#. Ncell: Balance *901#,
  Pack *123#, Credit *9988#. Tap = dialed from that network's SIM (system shows the reply).

## Permissions
CALL_PHONE, READ_CALL_LOG, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW, USE_FULL_SCREEN_INTENT (+ role). HyperOS: allow "Show on lock
screen" and "Display pop-up windows while running in the background" for ZeroSync.

## Next steps
- Caller name from contacts (READ_CONTACTS), call log, ringtone choice, Bluetooth, proper Phone tab.

## Done when
Incoming and outgoing calls work on the Redmi Turbo 4, locked and unlocked.
