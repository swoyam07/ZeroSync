---
paths:
  - "**/AndroidManifest.xml"
  - "**/data/**"
  - "**/xml/*rules*.xml"
---

# Security Rules

- No `INTERNET` permission. Ever.
- `android:allowBackup="false"` + data extraction rules excluding everything.
- All components `exported="false"` unless the dialer role requires it (document why).
- No ContentProviders exposing data. No share intents in or out.
- Private data lives in app-internal storage (Room). Encryption key in Android Keystore (if approved).
- Request only the permissions a feature needs; list each in `docs/decisions.md`.
