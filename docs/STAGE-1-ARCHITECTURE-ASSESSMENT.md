# Commercial Travel Agency CRM: Stage 1 architecture assessment

Date: 22 Oct 2026. Source inspected (read-only): `~/Downloads/Quick-Trip-CRM 7` (schema v4, 135 passing tests) and its desktop wrapper `~/Desktop/Quick Trip CRM 5.app`.
The Quick Trip production CRM was **not** modified. This folder is a separate project.
Product name and app identity are placeholders until you choose them.

---

## 1. What the existing CRM is

| Layer | Implementation | Size |
|---|---|---|
| Backend | Python 3 **standard library only**: `http.server` JSON API, `sqlite3`, PBKDF2 passwords, zip backups with SHA-256 manifests, its own PDF writer | about 7,000 lines in `server/` |
| Database | One SQLite file (WAL mode); schema migrations v1–v4 with rollback; immutability triggers on posted money; audit log | `server/db.py` |
| Frontend | Single-page vanilla JS + one CSS file; no framework, no CDN, no web fonts, inline SVG icons | about 4,800 lines |
| Desktop | Electron wrapper that runs `python3 run.py` **from the Mac's own Python**, finds it through the login shell, uses `lsof` and the `sqlite3` command-line tool | macOS only, unsigned |
| Ticket scanner | Swift helper using Apple PDFKit + Vision OCR, plus a Python parser | macOS only (helper), portable (parser) |
| Tests | 135 unittest tests covering finance, refunds, debts, security, backup, migrations | about 2,700 lines |

**Business logic worth keeping, all in Python and covered by tests:**
- the ledger and profit maths (`finance.py`);
- bookings, payments, allocations, refunds, edits and reversals (`services.py`);
- debts and loans, including repayment reversal (`debts.py`);
- reports (`reports.py`) and ticket PDFs (`ticket.py`, `pdfkit.py`);
- the ticket text parser (`ticketscan.py`);
- WhatsApp message templates (`whatsapp.js`).

### Why the current app cannot simply be sold or copied to Windows

1. **It needs a Python the customer installed.** It relies on the Mac's Command Line Tools Python 3.9, found through the login shell. Customers cannot be expected to have that, and Windows has none.
2. **macOS-only pieces:**
   - `/usr/bin/open` for Preview and Finder;
   - `lsof` and the `sqlite3` command-line tool in the wrapper;
   - the Swift/Vision ticket reader;
   - `~/Downloads` paths.
3. **Single business, single user.** The `owner` table holds exactly one login, with no roles. Data lives in one file next to the code.
4. **Quick Trip identity is hard-coded:**
   - Quick Trip's real bank account, IFSC, branch and UPI number are default settings in `db.py`;
   - "Quick Trip" appears in UI text, PDFs, backup manifests and the plane logo.
   All of it must be removed or made configurable. **None of it may ship.**
5. **Not packaged for sale:** no installer, no code signing or notarisation, no auto-update, no licence.
6. **Privacy gaps for a commercial product:**
   - passport numbers are stored in plain text;
   - backups are not encrypted;
   - the database is not encrypted at rest. Today that relies on FileVault.

---

## 2. Reuse assessment

| Module | Reuse | Work needed |
|---|---|---|
| `finance.py`, `services.py`, `debts.py`, `reports.py` | **Yes, as is** | Add `agency_id` / user context; replace Quick Trip strings |
| `db.py` migrations | Yes, the pattern | New schema baseline v1 for the product (no Quick Trip data or history) |
| `auth.py` | Partly | Multi-user, roles, permissions, password reset by an admin |
| `backup.py` | Yes | Encryption, product manifest, restore UI for non-technical users |
| `ticket.py`, `pdfkit.py` | Yes | Agency branding (logo image, colours, address) instead of the fixed plane mark |
| `ticketscan.py` parser | Yes | Pluggable text source per OS |
| Swift reader | macOS only | Windows needs its own reader (see 3.4) |
| Frontend JS/CSS | Yes | Product branding, user management screens, licence screens, Windows-friendly copy ("this Mac" → "this computer") |
| Electron wrapper | Rewrite | Proper product shell: bundled runtime, installers, updates, single-instance lock, data folder per OS |
| Tests (135) | **Yes, the most valuable asset** | They become the regression suite that guards financial accuracy across platforms |

**Legal note:** the code is your project's own work plus the Python standard library (PSF licence, redistributable). There are no third-party libraries, fonts or paid components. Electron is MIT-licensed. Before release, every added dependency gets a licence entry in `THIRD-PARTY-NOTICES`. Please confirm you hold the rights to the Quick Trip CRM code you want reused (for example, it was not written under someone else's contract).

---

## 3. Recommended architecture

### 3.1 Desktop shell: Electron + bundled Python core (recommended)

| Option | Verdict |
|---|---|
| **Electron + bundled Python runtime running the existing core** | **Recommended.** Reuses the tested financial code unchanged, so it has the lowest risk to accounting accuracy. Electron is already in use and has mature installers (`electron-builder`), auto-update (`electron-updater`), macOS notarisation and Windows signing support. The Python core is standard-library only, so the bundled runtime is clean and small (about 25–40 MB per platform). |
| Tauri + Python sidecar | Same Python bundling problem, plus a Rust toolchain (not installed here). Smaller shell, but more moving parts. Nothing gained for this codebase. |
| Rewrite the backend in TypeScript (Electron + SQLite) | One runtime, but it re-implements about 7,000 lines of financial logic. That is a high risk of subtle accounting bugs. Worth considering only later, using the 135 tests as the specification. |
| Copy the Mac app to Windows | Not possible (see section 1). |

**Runtime packaging:**
- Ship a relocatable CPython from `python-build-standalone` (MPL-2.0 build scripts; the CPython itself is under the PSF licence; verify before release) for macOS arm64, macOS x64 and Windows x64.
- Electron starts the core on `127.0.0.1` with a random free port and a per-launch secret token. The existing host and CSRF checks remain.
- Data goes in the OS's standard per-user folder:
  - macOS: `~/Library/Application Support/<Product>/`
  - Windows: `%APPDATA%\<Product>\`
  Data is never stored inside the app folder, so upgrades never touch it.

### 3.2 Agencies, users and data isolation

- **Desktop edition: one encrypted database file per agency (a "company file").** One installation can hold several agencies. Each has its own:
  - database file, attachments and backups;
  - settings and branding;
  - users and roles.
  Isolation comes from **separate files**, not from filtering rows, so one agency's data cannot appear in another's screens or reports through a coding mistake.
- **Users and roles** per agency: Owner, Accountant, Agent and Viewer.
  - Each API route declares the permission it needs.
  - Financial edits, reversals, deletes, backups and restores are restricted to Owner and Accountant.
  - Every financial change is audit-logged with the user who made it.
- **Online / multi-device edition (optional, later):** a **hosted server** running the same core on PostgreSQL, with an `agency_id` on every row plus database row-level security. It would not synchronise local databases with each other: two-way sync of a double-entry ledger brings conflict and duplicate-payment risks, which is unsafe for accounting.
  - Local-only data stays on the desktop.
  - An agency that chooses the online edition moves its company file up once, through a guided import.
  - The UI always shows which mode a company file is in.

### 3.3 Security and privacy

- **Database encryption at rest:**
  - SQLCipher (BSD-style community edition, built into the bundled runtime); or
  - an application-level AES-GCM layer for sensitive columns, using the `cryptography` package (Apache-2.0/BSD).
  - The key is kept in the OS keychain (macOS Keychain / Windows Credential Manager through Electron `safeStorage`).
- **Passport numbers** are encrypted per field and masked in the UI and PDFs (already never printed).
- **Backups** are encrypted zips with the existing SHA-256 manifest and verified restore, plus export to CSV/Excel at any time.
- **Safeguards carried over:**
  - immutable posted money with reversals;
  - idempotency keys (no duplicate transactions);
  - integrity checks before and after every migration;
  - verified backup before each upgrade.

### 3.4 Ticket reading on Windows

- **macOS:** the existing Swift helper (PDFKit + Vision). It must be signed and notarised.
- **Windows:** a small helper using **Windows' built-in APIs**: `Windows.Data.Pdf` to render pages and `Windows.Media.Ocr` for OCR. Both ship with Windows 10/11, so there is no third-party licence. Text-based PDFs can also be read in Python (a pure-Python text extractor written for this product), which avoids GPL/AGPL libraries such as MuPDF.
- **Same parser on both:** the parser and its tests are shared. Windows reading accuracy must be measured separately on synthetic tickets.

### 3.5 Licensing and activation

- **Signed licence files** (Ed25519):
  - The licence server holds the private key. The app contains only the **public key**, so there are no licence secrets in the app.
  - A licence states: plan (trial / monthly / annual), agency, number of devices, expiry, and the last version covered by updates.
  - Activation binds the licence to a device ID. Deactivation frees the seat.
  - The app re-checks online periodically, with an **offline grace period** (for example 14 days) for internet outages. Offline licences can be issued by hand for agencies without internet.
- **On expiry:** the app becomes **read-only**. Customers can still open, view, print, back up and **export all their data**. Nothing is deleted or encrypted against them, and there are no hidden lockouts.
- **Licence server and payments:** use a licensing provider (for example Keygen) and a merchant-of-record payment service (for example Paddle or Lemon Squeezy, which handle VAT/GST on software sales), or a small self-hosted licence service. This is a business decision (section 6). Gate the app on licence *state* only, never on the payment provider being reachable.

### 3.6 Updates, migrations and recovery

- **Signed updates** through `electron-updater` from your release server or GitHub Releases. Before a new version opens a company file, it:
  1. takes a verified backup;
  2. runs the migrations inside a transaction with integrity and foreign-key checks;
  3. if anything fails, restores the backup and opens the old data read-only with a clear message.
- **Customer recovery:** Settings → Backup & restore (no Terminal), a "restore from backup" option on the start screen, and a support bundle export (logs only, no customer data).

---

## 4. Build, test and release pipeline

| Target | Build where | Test where |
|---|---|---|
| macOS Apple Silicon (arm64) | This Mac | This Mac |
| macOS Intel (x64) | This Mac (cross-build) or a CI `macos-13` runner | CI Intel runner or a real Intel Mac |
| Windows 10/11 x64 | **CI `windows-latest` runner (GitHub Actions)**: this Mac cannot build or test Windows | CI runner + a real Windows 10/11 PC for the installer/UI check |

- **Installers:** a signed and notarised **DMG** for macOS (universal or per-architecture) and a signed **NSIS `.exe`** for Windows. Electron-builder is MIT-licensed.
- **CI runs on every change:** all Python tests on macOS **and Windows**, packaging, an installer smoke test (install → start → create agency → back up → restore → uninstall), and the migration tests on copies of synthetic databases.
- **Demo and test data are synthetic only.** No Quick Trip data, names, PNRs, bank details or tickets.

**Current limits on this Mac** (checked today):
- Node 22, git and Swift are available.
- **No** GitHub CLI or GitHub repository, **no** Windows environment, **no** code-signing certificates, **no** full Xcode.
- macOS packages can be built here but only **unsigned**.
- Windows builds and tests need GitHub Actions (or a Windows PC).

---

## 5. Staged plan and effort

| Stage | Contents | Rough effort |
|---|---|---|
| 2. Foundation | New repo; product skeleton (Electron + bundled Python); per-OS data folders; agency (company file) create/open; backup/restore/upgrade foundation; CI for macOS + Windows; unsigned test installers | 2–3 working sessions |
| 3. Core modules | Port the core with agency context; branding instead of Quick Trip; all 135 tests passing on macOS **and Windows**; ticket attachments; Windows ticket reader | 3–5 sessions |
| 4. Commercial | Users/roles, encryption, licensing client + server integration, branding UI, optional online edition design | 4–6 sessions (the online edition is a separate project) |
| 5. Release | Signing/notarisation, auto-update, real Windows/Intel testing, installer docs, EULA/privacy policy | 2–3 sessions + your accounts |

**Claude usage:** this is many times the size of the Debts feature, which used about 3% of a week. With **about 19% left this week** (reset 24 Oct, about 20:30 IST), Stage 2 can realistically be started; Stages 3–5 will span several weekly cycles. Each stage ends in a tested, saved state so nothing is left half-done.

---

## 6. What you need to provide or decide

1. **Approve the architecture** (Electron + bundled Python core, company file per agency, hosted server, not sync, for online).
2. **Product name, company/publisher name and app ID** (for example `com.yourcompany.productname`). Needed before any installer is built.
3. **GitHub account and a private repository** (GitHub Actions minutes for macOS/Windows runners), or a Windows 10/11 PC for testing.
4. **Signing:**
   - Apple Developer Program (USD 99/year) for macOS signing and notarisation;
   - a Windows code-signing option (for example Azure Trusted Signing or an OV/EV certificate).
   Without them, installers trigger security warnings.
5. **Licensing and payments provider** choice (Stage 4), and a business entity for selling the product.
6. **Legal:** confirm ownership of the existing code; EULA, privacy policy (India's DPDP Act 2023 if you sell in India), GST on software sales. These need professional advice. I can draft templates, but cannot provide legal advice.

---

## 7. Key risks

| Risk | Mitigation |
|---|---|
| Accounting errors after porting | Keep the tested core unchanged; run all tests on both OSes in CI; compare synthetic company files before and after every change |
| Windows-specific bugs (paths, file locks, antivirus, SQLite WAL on network drives) | CI on Windows, real-PC test pass, refuse network-drive company files |
| Data loss on upgrade | Verified backup + transactional migration + automatic roll-back |
| Licence lockout harming customers | Read-only mode with full export; grace period; no remote kill switch on data |
| Sync conflicts in a ledger | No peer sync; an online edition uses a single server of record |
| Quick Trip data leaking into the product | New baseline schema; synthetic demo data; a CI check that fails if Quick Trip identifiers appear in the product |
