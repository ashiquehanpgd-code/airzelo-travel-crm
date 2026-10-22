# Stage 2: Separate product foundation (report, 22 Oct 2026)

## Done and verified on this Mac (Apple Silicon, macOS 27)

| Item | Result |
|---|---|
| Separate project | `~/Downloads/Travel-CRM-Commercial`: own code, own app ID (`com.placeholder.travelagencycrm`), own data folders. The Quick Trip CRM was only read; its code is unchanged and its live app is still running. |
| Quick Trip identity removed | No Quick Trip name, bank account, IFSC, branch or UPI number in the code or the app bundle. New agencies start with blank payment details. Booking refs `BK-` (configurable), invoices `INV/`. Tests use synthetic data only. A test (`NoQuickTripData`) fails the build if Quick Trip identifiers reappear. |
| Company file per agency | `agencies/<uuid>/agency.db` + attachments + backups. Created from the app (no Terminal, no setup code). Tested: two agencies cannot see each other's records. |
| Safe upgrades | Files from a newer version are refused unchanged. A verified safety copy is taken before any schema upgrade. A failed upgrade restores the file exactly (tested with a deliberately broken migration). |
| Bundled runtime | CPython 3.12.15 (python-build-standalone 20261003), sha256-pinned, trimmed. No system Python needed. |
| Compatibility bug found and fixed | Migration v4's table rebuild failed on standard SQLite (it had only worked on Apple's build). Fixed in the product core. The tests pass on both SQLite builds. (The live Quick Trip CRM is already past v4 on Apple's SQLite, so it is unaffected.) |
| Bundled-runtime start-up bug found and fixed | `python -I` ignores `PYTHONUNBUFFERED` / `PYTHONDONTWRITEBYTECODE`. The shell now passes `-I -u -B`, so the ready signal arrives and nothing is written inside the app bundle. |
| Clean shutdown on every OS | Closing the core's stdin stops it cleanly (WAL checkpoint). This does not rely on Unix signals, so it works on Windows. |
| Desktop shell | Electron 44.7.0: agency chooser, create/open/switch agency, single instance, external links in the browser, navigation locked to the local core, desktop bridge refused to CRM pages. |
| Tests | 143 core tests on the bundled Python 3.12 **and** the Mac's Python 3.9: all pass, including the 7 ticket-reader tests. 17 WhatsApp template tests pass. |
| macOS packages (unsigned) | `dist/Travel Agency CRM-0.1.0-mac-arm64.dmg/.zip`, `…-mac-x64.dmg/.zip` (about 150 MB each; DMG checksums verified; `dist/SHA256SUMS.txt`). |
| Packaged-app smoke test (arm64) | Passed: bundled runtime → create agency → sign in → booking + receipt → correct sales/profit → backup → clean stop → database intact. |
| Packaged-app GUI end-to-end (arm64) | Passed: real window → New agency form → Open → CRM loads → sign in → no built-in payment details → bridge refused → quit → no engine left running, WAL empty. |

## Not done / not verified (stated plainly)

- **Windows: not built, not tested.** No Windows environment is available here. CI is ready (`.github/workflows/build.yml`: tests on the bundled Windows Python, NSIS installer, smoke test and GUI test). It has **never run**, so Windows compatibility is **not claimed**. It needs a GitHub repository, then a real Windows 10/11 PC check.
- **Intel Mac: built, not run.** This Mac has no Rosetta, and I did not install it (it is a system change). The CI `macos-13` (Intel) job runs the same tests.
- **Unsigned.** Gatekeeper will warn on other Macs and SmartScreen on Windows. Signing needs your Apple Developer and Windows signing accounts (Stage 5).
- **Not yet in the product:** users and roles, encryption of the database and backups, licensing, logo/branding settings, auto-update, the Windows ticket reader, and an admin password reset. These are Stages 3–4 as planned.
- The synthetic test ticket image (`core/tests/fixture_ticket.png`) still shows the old brand name in its picture. Replace it with a regenerated synthetic ticket in Stage 3.

## Next steps

1. **You:** create a private GitHub repository and give me access (or push this folder yourself) so the Windows and Intel CI jobs can run.
2. **Stage 3:** Windows ticket reader, branding settings (logo on PDFs), agency-level data export, any fixes from the first Windows CI run.
3. **Stage 4:** users/roles, encryption, licensing.
