# Phone

## Goal
Make and receive normal SIM calls using ZeroSync's own UI instead of the system Phone app.

## Requirements
- Custom calling and incoming-call UI (app's design)
- Full-screen incoming call with answer / end
- Show caller name if saved in ZeroSync contacts
- Bluetooth compatible
- Changeable ringtone

## Technical notes
- Requires default dialer role (`RoleManager.ROLE_DIALER`) + `InCallService`
- Full-screen intent notification for incoming calls
- Test on the real phone only (emulator can't do SIM calls)

## Permissions
TBD

## Open questions
- Auto-answer or not?

## Done when
TBD
