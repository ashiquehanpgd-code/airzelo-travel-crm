"""Who is acting, and what each role may do.

Every request runs with the signed-in user as the *actor*: services read it to stamp created_by / updated_by on
records and the user on every audit entry, so the database itself shows who did what.

Roles (one agency, many staff):
  owner          everything, including restore and managing admins; there is always exactly one active owner
  admin          everything except restoring backups and changing the owner
  booking_staff  bookings, customers, ticket scanner and PDFs, WhatsApp; may record customer receipts (create only)
  accountant     all money, invoices, debts, reconciliation and reports; bookings read-only; tax settings only
Permissions are checked on the server for every route (web.route(perm=...)); the screens only hide what the
server would refuse anyway.
"""
from contextlib import contextmanager
from contextvars import ContextVar

from .errors import Forbidden

ROLES = {
    "owner": "Owner",
    "admin": "Admin",
    "booking_staff": "Booking staff",
    "accountant": "Accountant",
}

# Permission names used by web.route(perm=...)
SIGNED_IN = "signed_in"          # any active user (own profile, meta, sign out)
BOOKINGS_VIEW = "bookings.view"  # bookings, customers, suppliers, tickets, documents (read)
BOOKINGS = "bookings"            # create / edit bookings, customers, suppliers, scans, ticket files, documents
RECEIPTS = "receipts"            # record a customer receipt (create only)
MONEY_VIEW = "money.view"        # transactions, cash & bank, invoices, debts, balances (read)
MONEY = "money"                  # every other money entry, reversals, adjustments, invoices, debts, reconciliation, accounts
REPORTS = "reports"              # dashboard figures, profit reports, statements, audit trail
SETTINGS = "settings"            # company profile, payment details, currencies, categories, logo
SETTINGS_TAX = "settings.tax"    # tax profiles only
USERS = "users"                  # staff accounts
BACKUP = "backup"                # make, list, verify and download backups
RESTORE = "restore"              # replace the data with a backup

ALL = {SIGNED_IN, BOOKINGS_VIEW, BOOKINGS, RECEIPTS, MONEY_VIEW, MONEY, REPORTS, SETTINGS, SETTINGS_TAX, USERS, BACKUP, RESTORE}

PERMISSIONS = {
    "owner": set(ALL),
    "admin": ALL - {RESTORE},
    "booking_staff": {SIGNED_IN, BOOKINGS_VIEW, BOOKINGS, RECEIPTS},
    "accountant": {SIGNED_IN, BOOKINGS_VIEW, RECEIPTS, MONEY_VIEW, MONEY, REPORTS, SETTINGS_TAX},
}

_actor = ContextVar("actor", default=None)


def can(user, perm):
    return bool(user) and perm in PERMISSIONS.get(user.get("role"), ())


def require(user, perm):
    if not can(user, perm):
        raise Forbidden("Your role (%s) cannot do this. Ask the owner or an admin."
                        % ROLES.get((user or {}).get("role"), "signed out"))


def current():
    """The acting user as a dict (id, username, display_name, role, ip), or None outside a request."""
    return _actor.get()


def uid():
    a = _actor.get()
    return a["id"] if a else None


@contextmanager
def acting_as(user):
    token = _actor.set(user)
    try:
        yield user
    finally:
        _actor.reset(token)
