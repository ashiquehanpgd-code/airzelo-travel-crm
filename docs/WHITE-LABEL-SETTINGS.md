# White-label company settings (v0.2.0)

Product identity: **AirZelo** (window title, installer, data folder, backup manifests, the "Generated with AirZelo" document credit, licence notices).
Agency identity: everything below, set per company workspace. Each agency's settings live in its own company file (`agency.db`) and its own `branding/` folder.

## What an agency can configure (Settings, or the first-run wizard)

| Area | Where | Notes |
|---|---|---|
| Company profile: display/trading name, legal name, tagline, address, country, state, phone, email, website, GSTIN, other registrations | Settings → Company & branding | Validated: email, phone, website, 15-character GSTIN, non-empty display name |
| Logo (PNG/JPEG, ≤ 1 MB, 16–4000 px) | Settings → Company & branding | Shown in the sidebar, on printed documents and in the ticket PDF header. Stored as `branding/logo-<hash>`: replacing a logo never deletes the old file, because issued invoices refer to it. Included in backups and restored with them. |
| Brand colour | Settings → Company & branding | App accent colour and ticket-PDF header |
| Invoice, credit-note, receipt and booking-reference prefixes | Settings → Documents & formats | Apply to **new** documents only. Counters continue, so numbers are never reused. |
| Invoice terms, invoice footer, receipt footer | Settings → Documents & formats | |
| Date format (4 options), currency display (symbol or code), default report period | Settings → Documents & formats | Display only |
| Bank / UPI payment details shown to customers | Settings → Payment details (and wizard step 3) | Start empty. Nothing from Quick Trip is built in. |
| Cash & bank accounts (add, edit, deactivate) | Money → Cash & bank (and wizard step 3) | Existing ledger model, unchanged |
| Tax profiles, currencies, expense categories, backups | Existing Settings tabs | Unchanged |

## First-run setup wizard

A new company workspace opens at `#/setup` until it is finished or skipped:
1. Company
2. Logo & colour
3. Bank & payments
4. Invoices & tax
5. Sample-invoice preview (synthetic data, never saved)
6. Finish

The owner login is created earlier, when the workspace is made in the agency chooser. "Skip for now" marks setup as done; the wizard can be rerun from Settings → Company & branding.

## History safety

- **Issued invoices and credit notes** store a snapshot of the company details at issue: names, address, GSTIN, registrations, logo file, colour, terms and footer. They print from that snapshot.
  - Fixed in this version: printed invoice headers used to read the *current* settings.
  - The database trigger that forbids changes to issued documents is unchanged.
- **Settings never change posted money.** The tests check that dashboard figures and account balances are identical before and after profile, prefix, colour and logo edits.
- **Every settings and logo change** is recorded in the audit trail.

## Tests (all on synthetic data)

`core/tests/test_branding.py` (12 tests) covers:
- profile validation and audit;
- PNG (RGB/RGBA) and JPEG logos embedded in ticket PDFs, including transparency;
- rejection of bad files and path tricks;
- issued-invoice snapshot after edits;
- prefixes applying only to new records;
- backup and restore of logos;
- **two agencies: one agency's settings and logo never reach the other**;
- HTTP routes requiring sign-in.

`build/e2e_gui.mjs` drives the wizard in the packaged app.

## Not implemented yet (stated plainly)

| Requested | Status |
|---|---|
| User roles and permissions | **Not yet.** Each workspace has a single owner login, which is the only user able to change settings. Multi-user roles are Stage 4. |
| Time zone setting | Not added. Dates use the computer's own clock and time zone. |
| Commission / markup defaults | Commission and markup stay per booking (existing model); there are no agency-wide defaults yet. |
| Custom payment-method list | The method list is fixed (cash, bank transfer, UPI, card, cheque, gateway, portal wallet, adjustment, other). |
| Custom receipt / ticket templates, PDF page layout options | Fixed layouts with agency branding; only footers and terms are editable. |
| Logo in the HTML document header on the very first issued invoices | Invoices issued before v0.2.0 have no logo in their snapshot, so they print without one. This is historically accurate. |
