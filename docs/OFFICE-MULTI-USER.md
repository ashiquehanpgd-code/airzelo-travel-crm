# Shared office: staff accounts, roles and the cloud workspace

Status: built on branch `feature/office-multi-user` (schema v5). Not deployed.

## Architecture

* **One server of record per agency.** Every computer talks to one AirZelo core over HTTPS; the core owns the
  SQLite company file and serialises every write (`BEGIN IMMEDIATE`, WAL, 20 s busy timeout). Nothing is
  synchronised between computers, and the database file is never shared through Drive, Dropbox or a network folder.
* **Local mode is unchanged.** The desktop app still creates and opens local company files on the computer, offline.
  Cloud workspaces are a separate list in the same app (https only). The two are never merged.
* **Several agencies later:** one company file (folder + database) per agency, as today. A hosted service would run
  one core process per agency (or route by hostname, e.g. `sunrise.airzelo.in`) with a small control database that maps
  logins to agencies. No table is shared between agencies, so a query can never return another agency's rows.

## Roles (server-enforced on every route)

| | Owner | Admin | Booking staff | Accountant |
|---|---|---|---|---|
| Bookings, customers, suppliers, scanner, ticket PDFs, WhatsApp | ✓ | ✓ | ✓ | view |
| Customer receipts | ✓ | ✓ | create only | ✓ |
| Supplier payments, expenses, transfers, refunds, adjustments, debts, reconciliation, accounts | ✓ | ✓ | – | ✓ |
| Reverse / void money entries, invoices, credit notes | ✓ | ✓ | – | ✓ |
| Dashboard, reports, statements, audit trail | ✓ | ✓ | – | ✓ |
| Company settings, currencies, categories, logo | ✓ | ✓ | – | – |
| Tax profiles | ✓ | ✓ | – | ✓ |
| Staff accounts | ✓ | booking staff and accountants only | – | – |
| Backups (make, verify, download) | ✓ | ✓ | – | – |
| Restore a backup | ✓ | – | – | – |

* `web.route(..., perm=...)` is required for every non-public route; a route without one fails to load, and
  `test_users_roles.py` checks every route.
* There is exactly one owner (`ux_users_one_owner`). Only the owner manages admins; nobody can change their own role.
* Switching a user off, changing their role or resetting their password ends their sessions everywhere.
* A temporary password (new user, reset) must be changed before anything else.
* Failed sign-ins are limited per username (5) and per address (20), so one person cannot lock out the office.

## Who did what

* `created_by` on transactions, adjustments, invoices, debts and debt entries; `created_by` / `updated_by` on
  bookings, customers, suppliers and accounts.
* Every audit entry stores the user id, username and address. The audit trail shows a **Who** column; a money entry
  shows "Recorded … by <name>".

## Two people working at once

* **Same submission twice** (double-click, two computers, retry after a network drop): the form's `idem_key` makes the
  second one return the first result. Tested with four parallel posts.
* **Numbers** (receipt, invoice, booking): allocated inside the write transaction, unique under parallel saves.
* **Editing the same record:** bookings, customers, suppliers and accounts carry `row_version`. The screen sends the
  version it loaded; if a colleague saved first, the save is refused with "changed by <name> at <time>, reload".
* Posted money entries are never edited in place (reverse and repost), so they cannot conflict.
* The server's listen queue is 128 (Python's default of 5 reset connections when 10 saves arrived together).

## When the internet connection is lost (cloud workspace)

* The page shows a red banner and every save is refused on the computer before it is sent; GET requests keep failing
  until the server answers again, then the banner clears by itself.
* Money entries are **never** queued offline: two offline computers could otherwise post the same receipt.
* Open forms keep their submission key, so saving again after reconnecting cannot create a duplicate.
* If the desktop app cannot reach the workspace at all it returns to the chooser and says so. A local company file
  still works offline, but it is separate data.

## Upgrade and rollback

* v5 turns the existing owner into user 1 (role owner), signs everyone out once, and adds the columns above.
* `run.py --rollback-to 4` removes staff accounts and the new columns; the owner keeps their current username and
  password (the old `owner` table is kept in step). A refused rollback (for example loans recorded, v4) changes nothing.
