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
- Missed calls: `MissedCallReceiver` gets SHOW_MISSED_CALLS_NOTIFICATION (so the system skips
  its own) and shows ZeroSync's notification: "Missed call · number", tap = dial pad prefilled,
  Call back button, swipe away = marked seen.

## Permissions
CALL_PHONE, POST_NOTIFICATIONS, USE_FULL_SCREEN_INTENT (+ role). HyperOS: allow "Show on lock
screen" and "Display pop-up windows while running in the background" for ZeroSync.

## Next steps
- Caller name from contacts (READ_CONTACTS), call log, ringtone choice, Bluetooth, proper Phone tab.

## Done when
Incoming and outgoing calls work on the Redmi Turbo 4, locked and unlocked.
