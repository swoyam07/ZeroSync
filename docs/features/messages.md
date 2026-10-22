# Messages (SMS)

Status: v0.1 implemented (code in `app/src/main/java/com/zerosync/app/sms/`). Text only.

## Decisions (user, 2026-10-22)
- Option A: ZeroSync is the default SMS app (RoleManager.ROLE_SMS). Text only, no MMS.
- Entry: Messages icon at the top-right of the Phone screen, with a white unread-count badge.
- Notifications show WHO only (saved name, else number) + "N new messages" — never the text,
  so it is safe on the lock screen.

## Storage
- Messages live in the phone's own SMS database (Telephony provider), not the SIM and not
  ZeroSync's files. As default SMS app, ZeroSync writes received + sent texts there.
- Other apps with SMS permission can read that database (same as the call log).

## Screens (`MessagesActivity`)
- Conversation list: name/number (bold if unread), last text, network logo, unread badge, "+".
- Conversation: bubbles (received gray left, sent white right, time, "Not sent" in red),
  rounded input + green send button. Opening marks it read and clears its notification.
- New message: "To" number + text.

## Safety (user rule, 2026-10-22)
- Messages are shown as plain text only. Links are never clickable (no auto-link); hold a
  message to select and copy it, then paste it in a browser yourself.
- Nothing in a message is opened, downloaded or run. MMS (pictures, files) is ignored entirely.
  If MMS images are ever added: show the picture only, never open/run any other attachment.
- Notifications carry no text and have system "Open link / Copy code" suggestions turned off.

## Technical notes
- Receive: `SmsDeliverReceiver` (SMS_DELIVER, BROADCAST_SMS) joins multi-part texts, saves to
  inbox, notifies (not for spam numbers). MMS receiver required by Android but ignores MMS.
- Send: SmsManager (default SMS SIM, else first active SIM), long texts split; saved as outbox
  → sent / failed via `SmsSentReceiver`. `HeadlessSmsSendService` = "reply with message".
- Exported parts are only those Android requires for the SMS role, each system-protected.

## Not done yet
- MMS (pictures, group), delete/search conversations, message button on contacts.
