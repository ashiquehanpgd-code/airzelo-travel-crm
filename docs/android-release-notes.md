**AirZelo for Android**: the travel agency books (bookings, customers, payments, invoices, reports, backups) on an Android
phone, fully offline. This is an early release: please keep a backup.

### What's new in 0.3.1

- A completely redesigned app: a new dashboard built from the desktop's (key figures, revenue & profit chart, profit
  breakdown, booking and payment status, pending payments, departures, follow-ups), redesigned bookings, customers,
  money, reports, invoices, backup and settings screens, quick actions, smooth scrolling, and a dark theme.
- Stronger app lock: fingerprint, face or your screen lock through Android's own prompt; the app locks again after 30
  seconds away and returns to the same screen. Without a screen lock it now shows how to set one instead of opening.
- Same books, same rules: nothing changes in your data or backups. 0.3.1 installs over 0.3.0 and keeps your books.

### Download and install

1. Download **`{{APK}}`** ({{SIZE}}) below.
2. Open it on the phone. Android asks to allow installs from your browser or Files app once: allow it, then install.
3. Needs **Android 11 or newer**.

Check the download (optional): its SHA-256 is `{{SHA256}}` (also in the `.sha256` file).

### Before you start

- **Set a screen lock** (PIN, pattern or password) on the phone. AirZelo opens only after your fingerprint, face or
  screen lock. Without a screen lock it opens with a warning, because anyone holding the phone could read the books.
- **Your data stays on this phone.** Nothing is sent to any server: the app has no internet permission. Android cloud
  backup is switched off for it, so **use the app's own backups**: More > Backup & restore > *Export a backup* saves an
  encrypted file (with a passphrase you choose) to Files, a computer or another phone.
- Desktop AirZelo backups (.zip) can be imported there too, and an Android export opens in the desktop app.

### Updating later

Install the newer APK over this one: your books are kept. (Updates must come from this same release page: they are
signed with the same key.)

### Not in this version

Live flight search, airline booking, online payment checks and cloud sync are not available (the app says so; nothing
is simulated). Debt register, bank reconciliation, tax-profile and currency editing are on the desktop app for now.

Built from commit `{{COMMIT}}`. All engine and app tests passed, including desktop parity, before this release was made.
