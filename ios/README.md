# AirZelo for iPhone (offline)

A native SwiftUI iPhone app that runs the agency's books entirely on the phone. No cloud hosting, no
server, no internet connection is needed for any core work. The Mac and Windows apps are unchanged.

## Layout

| Path | What it is |
|---|---|
| `AirZeloKit/` | Swift package with the offline engine. No third-party dependencies and no network code. |
| `AirZeloKit/Sources/AirZeloKit/Database` | SQLite access and the **desktop schema v4**, generated from `core/server/db.py` |
| `.../Core`, `.../Services`, `.../Finance` | Line-by-line Swift port of `money.py`, `validate.py`, `services.py`, `finance.py` (Ledger, dashboard) and `reports.py` |
| `.../Security` | Passport-number encryption (AES-GCM, Keychain key) and iOS Data Protection |
| `.../Backup` | Encrypted backups, verified restore, desktop backup import/export, corruption recovery |
| `.../PDF` | Ticket (with payment page), invoice and credit note PDFs, drawn on the device |
| `.../Offline` | Live features that are refused as unavailable (see below) |
| `AirZeloKit/Sources/AirZeloCheckSuite` | 25 checks, runnable with or without Xcode |
| `AirZelo/` | The SwiftUI app plus `project.yml` (XcodeGen), unit tests and a UI smoke test |
| `tools/export_schema.py` | Regenerates `Schema.generated.swift` from the desktop migrations (`--check` in CI) |
| `tools/parity.py` | Runs seeded operations through the **desktop** core, so the iPhone results can be compared with them |

## What works offline

Everything that only uses the agency's own records:

- **Customers:** create, edit, deactivate/delete, follow-ups, statements (CSV).
- **Suppliers:** create, edit, statements.
- **Bookings:** create and edit, with passengers (ticket and passport numbers) and flight segments. Also status changes, PNR / name / ticket / phone search and duplicate-PNR warnings.
- **Money:** receipts, supplier payments, refunds, expenses (operating or direct to a booking), other income and transfers, in any currency. Allocations, reversals and corrections are supported; posted money is never edited.
- **Refunds and cancellations:** customer credit, supplier refund and commission reversal, with the profit adjusted.
- **Invoices and credit notes:** financial-year numbering and CGST/SGST/IGST. Issued documents are locked and cancelled, never deleted.
- **Reports and dashboard:** all 13 desktop reports (CSV export), the dashboard, and account reconciliation data.
- **Documents:** ticket, itinerary, ticket with payment details, and invoice / credit note PDFs, all made on the phone.

**Not available, and not simulated:** live flight search, new airline bookings, online payment verification and live PNR status. The app says why and what to do instead: book on the portal, then record the booking. (`OnlineFeature` in AirZeloKit.)

## Accounting parity with the desktop

The iPhone uses the same database schema and the same rules, so a figure is the same on both:

- `tools/parity.py` drives the desktop core through hundreds of seeded random operations. These cover INR and SAR bookings, GST, receipts, allocations, refunds, adjustments, reversals, corrections, invoices, credit notes, transfers, reconciliation, and deliberately invalid input.
- The Swift checks must then pass four tests:
  1. read the desktop's database and reproduce every figure, report, statement and dashboard value;
  2. replay the same operations and give the same results, refusals (same messages) and database rows;
  3. import the desktop's backup `.zip`;
  4. produce an export that the **desktop app restores** with every figure identical (`parity.py --check-export`).
- The desktop's money parsing and rounding results are compared value by value.

## Security

- **App lock:** Face ID / Touch ID, falling back to the device passcode (LocalAuthentication). The app locks when it leaves the screen, and a privacy cover hides it in the app switcher.
- **Files:** the database uses iOS Data Protection `completeUnlessOpen`; backups and exported PDFs use `complete`.
- **Passport numbers:** encrypted again inside the database with AES-256-GCM. The key is held in the Keychain (`WhenUnlocked`, never synced to iCloud). Passport numbers are never written to the audit log in plain text.
- **Bank account numbers:** only the last 4 digits are stored, as on the desktop.

## Backup and restore

- **Device backups** (`backup-<kind>-<time>.azbackup`): encrypted with a key derived from the device key. Automatic backups run when the app goes to the background (setting `auto_backup_hours`), and old automatic backups are pruned.
- **Exports:** encrypted with a passphrase (PBKDF2-SHA256, 600,000 rounds), to keep in Files or on a computer, or to open on another iPhone.
- **Container format:** AES-256-GCM in 1 MB chunks. Every chunk is authenticated with the header, its index and an end marker, so an edited, truncated or reordered file is rejected.
- **Contents:** inside the encryption is exactly a desktop backup (`manifest.json` with SHA-256 values, `agency.db`, `uploads/`, `branding/`). A decrypted export restores in the desktop app, and desktop backups import on the iPhone.
- **Restore:** checks the whole file (decryption, SHA-256 of every entry, `integrity_check`, foreign keys, data version) and saves a safety backup of the current books. It then swaps the files, re-encrypts passport numbers with this phone's key, and checks again. On any failure the previous books are put back.
- **Damaged books:** if the database fails its check on opening, the app offers to restore the newest backup that verifies, and keeps the damaged files aside.

## Build and test

Without Xcode (Command Line Tools are enough):

```bash
python3 ios/tools/parity.py --out /tmp/parity --seed 7 --ops 600
```

```bash
cd ios/AirZeloKit && swift build -c release && .build/release/AirZeloChecks --parity /tmp/parity --scale 20000
```

```bash
python3 ios/tools/parity.py --check-export /tmp/parity
```

With Xcode:

```bash
brew install xcodegen && xcodegen generate --spec ios/AirZelo/project.yml && open ios/AirZelo/AirZelo.xcodeproj
```

The `ios` GitHub Actions workflow does all of this on every push. It runs the engine checks (3 seeds, 20,000-booking scale test, desktop restore of the iPhone export), then builds the app and runs the unit, engine and UI tests on an iPhone simulator, plus an unsigned Release build for a device.

## Known limits (this version)

- **Debts & loans:** the debt register (new loans, interest entries) is managed in the desktop app. Imported loan transactions show and count correctly, and their balance guard is enforced.
- **Attachments:** imported documents and logos are kept and backed up, but adding new attachments on the phone is not built yet.
- **WhatsApp:** the desktop's WhatsApp message templates are not ported.
- **Memory:** all figures are computed in memory, like the desktop. About 165 MB at 20,000 bookings and 390 MB at 60,000 on a Mac. Very large books on older iPhones need an incremental ledger first.
- **No sync:** there is no sync between devices yet. Use one device as the live book, and move the books with export/import.
- **Not verified on a real iPhone:** CI runs the simulator only. Real-device Face ID, Data Protection while locked, and performance still need checking, which needs signing with an Apple Developer account.
