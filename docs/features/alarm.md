# Time: Alarm + Calendar

Status: Alarm v0.1 implemented (`app/src/main/java/com/zerosync/app/alarm/`). No Timer (user removed it 2026-10-23).

## Entry
- Clock icon at the bottom of the File section's icon column → `TimeActivity`.
  Not in the main nav (user choice: used rarely).
- Tabs: Calendar · Alarm (default). Tap the pill or swipe sideways.

## Alarm (user decisions 2026-10-23)
- Rings exactly at the set time (no early notice). Swipe bar: left = Snooze 5 min, right = Stop.
- Locked / screen off: full-screen `AlarmActivity` (over lock screen, turns screen on).
  Unlocked + "Display over other apps": small pop-up card with the same swipe bar.
- Repeat: 7 day checkboxes (S M T W T F S); none = once (turns itself off after ringing).
- Sound: phone's default alarm tone, or any song from Music. Plays at ALARM volume (rings in
  silent mode), loops, vibrates; auto-stops after 10 min.
- Type to stop (optional per alarm, 2026-10-24): set a text when saving. When it rings, the swipe
  can only Snooze 5 min; swiping right ("Type to stop") opens a box on the ring screen (keyboard
  works over the lock screen, no unlock needed). Typing the text exactly (case / outer spaces
  ignored) stops it. The unlocked pop-up opens the full screen to type. No Stop button in the
  notification; after 10 min unanswered it snoozes instead of stopping.
- List: time, repeat + sound, on/off switch; tap = edit; hold = delete (confirm); + = new.
- Permission cards (notifications, full-screen alerts, display over apps) shown ONLY in the
  Alarm tab, never elsewhere.

## Technical
- AlarmManager.setAlarmClock (exact, wakes phone, shows the system alarm icon); nothing runs
  between alarms. `AlarmService` = foreground service (systemExempted) only while ringing.
- `AlarmBootReceiver` re-sets alarms after restart / app update / time or timezone change.
- Permissions: USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE(+_SYSTEM_EXEMPTED).
- HyperOS: keep Autostart on + Battery saver "No restrictions" for reliable alarms.

## Calendar (user decisions 2026-10-23) — `calendar/`
- English (AD) month grid: ‹ month ›, today ringed, dot on event days; tapping a day sets the
  date for +. Below: "Upcoming" = every event's next occurrence, nearest first (date badge,
  "Today" in white). One-time events whose day has passed (or Done/stopped) are auto-deleted;
  passed yearly events move down to next year. Tap = edit; hold = delete (confirm).
- Event: title, date (date picker), time (new events default 8:00 AM, no All-day switch), note, Every year, reminder
  None / 1 day / 3 days / 1 week before.
- Reminder: from N days before at 8:00 AM, a FIXED notification until the event day ends.
  Swiping it away re-posts it (Android 14+ lets users swipe "ongoing" ones). Lock screen shows
  only "Event" + date/time (title hidden). Needs the phone's lock-screen notifications ON. Done = finished: one-time → reminder + ring cancelled;
  yearly → this year only, next year scheduled. Deleting the event removes everything.
- At event time: rings like an alarm (AlarmService, same screens / swipe bar, snooze 5 min);
  on the lock screen the title is hidden ("Unlock to see the event"). Stop = finished.
- Storage: encrypted (hardware Keystore key shared with contacts), app-private.
- Re-scheduled (and reminders re-posted) by the same boot receiver.
