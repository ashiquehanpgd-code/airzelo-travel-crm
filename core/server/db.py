"""SQLite connection, schema migrations and default seed data."""
import sqlite3
from contextlib import contextmanager
from datetime import datetime

SCHEMA_V1 = r"""
CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE owner(
  id INTEGER PRIMARY KEY CHECK (id = 1),
  username TEXT NOT NULL,
  pw_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE sessions(
  token_hash TEXT PRIMARY KEY,
  created_at TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE TABLE currencies(
  code TEXT PRIMARY KEY CHECK (length(code) = 3 AND code = upper(code)),
  name TEXT NOT NULL,
  symbol TEXT NOT NULL DEFAULT '',
  active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE counters(name TEXT PRIMARY KEY, value INTEGER NOT NULL);

CREATE TABLE customers(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL CHECK (length(trim(name)) > 0),
  phone TEXT, alt_phone TEXT, email TEXT, address TEXT, city TEXT, state TEXT,
  gstin TEXT, passport_no TEXT, notes TEXT,
  follow_up_date TEXT, follow_up_note TEXT,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX idx_customers_phone ON customers(phone);
CREATE INDEX idx_customers_name ON customers(name COLLATE NOCASE);

CREATE TABLE suppliers(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE COLLATE NOCASE CHECK (length(trim(name)) > 0),
  kind TEXT NOT NULL DEFAULT 'b2b_portal'
    CHECK (kind IN ('b2b_portal','airline','consolidator','hotel','visa','other')),
  contact_person TEXT, phone TEXT, email TEXT, gstin TEXT, notes TEXT,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);

CREATE TABLE accounts(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE COLLATE NOCASE CHECK (length(trim(name)) > 0),
  kind TEXT NOT NULL CHECK (kind IN ('cash','bank','wallet','gateway')),
  currency TEXT NOT NULL REFERENCES currencies(code),
  opening_balance INTEGER NOT NULL DEFAULT 0,
  opening_date TEXT,
  details TEXT,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);

CREATE TABLE tax_profiles(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  rate TEXT NOT NULL DEFAULT '0',
  basis TEXT NOT NULL
    CHECK (basis IN ('none','service_charge','markup','service_and_markup','selling_price','custom')),
  split TEXT NOT NULL DEFAULT 'none' CHECK (split IN ('none','cgst_sgst','igst')),
  sac_code TEXT, notes TEXT,
  active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE expense_categories(name TEXT PRIMARY KEY, active INTEGER NOT NULL DEFAULT 1);

CREATE TABLE bookings(
  id INTEGER PRIMARY KEY,
  ref TEXT NOT NULL UNIQUE,
  pnr TEXT,
  customer_id INTEGER NOT NULL REFERENCES customers(id),
  supplier_id INTEGER REFERENCES suppliers(id),
  service_type TEXT NOT NULL DEFAULT 'flight'
    CHECK (service_type IN ('flight','visa','hotel','package','umrah','other')),
  booking_date TEXT NOT NULL,
  travel_date TEXT,
  return_date TEXT,
  trip_type TEXT NOT NULL DEFAULT 'one_way' CHECK (trip_type IN ('one_way','round_trip','multi_city')),
  status TEXT NOT NULL
    CHECK (status IN ('enquiry','on_hold','confirmed','ticketed','cancelled','refunded')),
  committed INTEGER NOT NULL DEFAULT 0 CHECK (committed IN (0,1)),
  cabin TEXT, baggage TEXT, notes TEXT,
  currency TEXT NOT NULL REFERENCES currencies(code),
  exchange_rate TEXT NOT NULL DEFAULT '1',
  rate_date TEXT,
  purchase_cost INTEGER NOT NULL DEFAULT 0 CHECK (purchase_cost >= 0),
  selling_price INTEGER NOT NULL DEFAULT 0 CHECK (selling_price >= 0),
  commission INTEGER NOT NULL DEFAULT 0 CHECK (commission >= 0),
  service_charge INTEGER NOT NULL DEFAULT 0 CHECK (service_charge >= 0),
  other_income INTEGER NOT NULL DEFAULT 0 CHECK (other_income >= 0),
  tax_profile_id INTEGER REFERENCES tax_profiles(id),
  tax_override INTEGER NOT NULL DEFAULT 0,
  taxable_value INTEGER NOT NULL DEFAULT 0 CHECK (taxable_value >= 0),
  tax_rate TEXT NOT NULL DEFAULT '0',
  tax_amount INTEGER NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
  tax_split TEXT NOT NULL DEFAULT 'none',
  place_of_supply TEXT,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX idx_bookings_pnr ON bookings(pnr COLLATE NOCASE);
CREATE INDEX idx_bookings_customer ON bookings(customer_id);
CREATE INDEX idx_bookings_supplier ON bookings(supplier_id);
CREATE INDEX idx_bookings_date ON bookings(booking_date);
CREATE INDEX idx_bookings_travel ON bookings(travel_date);

CREATE TABLE booking_passengers(
  id INTEGER PRIMARY KEY,
  booking_id INTEGER NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL DEFAULT 0,
  title TEXT,
  name TEXT NOT NULL CHECK (length(trim(name)) > 0),
  pax_type TEXT NOT NULL DEFAULT 'ADT' CHECK (pax_type IN ('ADT','CHD','INF')),
  ticket_number TEXT, passport_no TEXT, notes TEXT
);
CREATE UNIQUE INDEX ux_ticket_number ON booking_passengers(ticket_number)
  WHERE ticket_number IS NOT NULL AND ticket_number <> '';
CREATE INDEX idx_pax_name ON booking_passengers(name COLLATE NOCASE);
CREATE INDEX idx_pax_booking ON booking_passengers(booking_id);

CREATE TABLE booking_segments(
  id INTEGER PRIMARY KEY,
  booking_id INTEGER NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL DEFAULT 0,
  direction TEXT NOT NULL DEFAULT 'outbound' CHECK (direction IN ('outbound','return','other')),
  airline TEXT, flight_no TEXT, origin TEXT, destination TEXT,
  dep_at TEXT, arr_at TEXT, cabin TEXT, baggage TEXT, airline_pnr TEXT
);
CREATE INDEX idx_seg_booking ON booking_segments(booking_id);
CREATE INDEX idx_seg_dep ON booking_segments(dep_at);

CREATE TABLE booking_adjustments(
  id INTEGER PRIMARY KEY,
  adj_no TEXT NOT NULL UNIQUE,
  booking_id INTEGER NOT NULL REFERENCES bookings(id),
  adj_date TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('cancellation','refund','reissue','date_change','other')),
  sign INTEGER NOT NULL DEFAULT 1 CHECK (sign IN (1,-1)),
  exchange_rate TEXT NOT NULL,
  customer_credit INTEGER NOT NULL DEFAULT 0 CHECK (customer_credit >= 0),
  customer_credit_tax INTEGER NOT NULL DEFAULT 0
    CHECK (customer_credit_tax >= 0 AND customer_credit_tax <= customer_credit),
  supplier_credit INTEGER NOT NULL DEFAULT 0 CHECK (supplier_credit >= 0),
  commission_reversal INTEGER NOT NULL DEFAULT 0 CHECK (commission_reversal >= 0),
  extra_customer_charge INTEGER NOT NULL DEFAULT 0 CHECK (extra_customer_charge >= 0),
  extra_supplier_charge INTEGER NOT NULL DEFAULT 0 CHECK (extra_supplier_charge >= 0),
  notes TEXT,
  reversal_of INTEGER REFERENCES booking_adjustments(id),
  reversed_by INTEGER REFERENCES booking_adjustments(id),
  created_at TEXT NOT NULL
);
CREATE INDEX idx_adj_booking ON booking_adjustments(booking_id);

CREATE TABLE transactions(
  id INTEGER PRIMARY KEY,
  txn_no TEXT NOT NULL UNIQUE,
  uid TEXT NOT NULL UNIQUE,
  idem_key TEXT UNIQUE,
  txn_date TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('customer_receipt','supplier_payment','customer_refund',
                                     'supplier_receipt','expense','other_income','transfer')),
  sign INTEGER NOT NULL DEFAULT 1 CHECK (sign IN (1,-1)),
  amount INTEGER NOT NULL CHECK (amount > 0),
  currency TEXT NOT NULL REFERENCES currencies(code),
  exchange_rate TEXT NOT NULL,
  rate_date TEXT,
  base_amount INTEGER NOT NULL CHECK (base_amount >= 0),
  account_id INTEGER NOT NULL REFERENCES accounts(id),
  to_account_id INTEGER REFERENCES accounts(id),
  to_amount INTEGER,
  method TEXT NOT NULL,
  reference TEXT,
  category TEXT NOT NULL,
  customer_id INTEGER REFERENCES customers(id),
  supplier_id INTEGER REFERENCES suppliers(id),
  booking_id INTEGER REFERENCES bookings(id),
  is_direct INTEGER NOT NULL DEFAULT 0 CHECK (is_direct IN (0,1)),
  payee TEXT,
  notes TEXT,
  reversal_of INTEGER REFERENCES transactions(id),
  reversed_by INTEGER REFERENCES transactions(id),
  reconciled INTEGER NOT NULL DEFAULT 0 CHECK (reconciled IN (0,1)),
  reconciled_on TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX idx_txn_date ON transactions(txn_date);
CREATE INDEX idx_txn_kind ON transactions(kind);
CREATE INDEX idx_txn_customer ON transactions(customer_id);
CREATE INDEX idx_txn_supplier ON transactions(supplier_id);
CREATE INDEX idx_txn_booking ON transactions(booking_id);
CREATE INDEX idx_txn_account ON transactions(account_id);

CREATE TABLE allocations(
  id INTEGER PRIMARY KEY,
  txn_id INTEGER NOT NULL REFERENCES transactions(id),
  booking_id INTEGER NOT NULL REFERENCES bookings(id),
  amount INTEGER NOT NULL CHECK (amount > 0),
  created_at TEXT NOT NULL,
  UNIQUE(txn_id, booking_id)
);
CREATE INDEX idx_alloc_booking ON allocations(booking_id);

CREATE TABLE invoices(
  id INTEGER PRIMARY KEY,
  number TEXT NOT NULL UNIQUE,
  doc_type TEXT NOT NULL CHECK (doc_type IN ('invoice','credit_note')),
  booking_id INTEGER NOT NULL REFERENCES bookings(id),
  customer_id INTEGER NOT NULL REFERENCES customers(id),
  adjustment_id INTEGER REFERENCES booking_adjustments(id),
  related_invoice_id INTEGER REFERENCES invoices(id),
  issue_date TEXT NOT NULL,
  currency TEXT NOT NULL,
  lines_json TEXT NOT NULL,
  tax_json TEXT NOT NULL DEFAULT '[]',
  subtotal INTEGER NOT NULL,
  tax_total INTEGER NOT NULL,
  total INTEGER NOT NULL,
  notes TEXT,
  status TEXT NOT NULL DEFAULT 'issued' CHECK (status IN ('issued','cancelled')),
  cancel_reason TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_inv_booking ON invoices(booking_id);

CREATE TABLE documents(
  id INTEGER PRIMARY KEY,
  booking_id INTEGER REFERENCES bookings(id),
  transaction_id INTEGER REFERENCES transactions(id),
  original_name TEXT NOT NULL,
  stored_name TEXT NOT NULL UNIQUE,
  mime TEXT NOT NULL,
  size INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  label TEXT,
  deleted INTEGER NOT NULL DEFAULT 0,
  uploaded_at TEXT NOT NULL
);

CREATE TABLE booking_history(
  id INTEGER PRIMARY KEY,
  booking_id INTEGER NOT NULL REFERENCES bookings(id),
  at TEXT NOT NULL,
  action TEXT NOT NULL,
  detail TEXT
);
CREATE INDEX idx_hist_booking ON booking_history(booking_id);

CREATE TABLE audit_log(
  id INTEGER PRIMARY KEY,
  at TEXT NOT NULL,
  entity TEXT NOT NULL,
  entity_id TEXT,
  action TEXT NOT NULL,
  before_json TEXT,
  after_json TEXT
);
CREATE INDEX idx_audit_entity ON audit_log(entity, entity_id);

CREATE TABLE reconciliations(
  id INTEGER PRIMARY KEY,
  account_id INTEGER NOT NULL REFERENCES accounts(id),
  statement_date TEXT NOT NULL,
  statement_balance INTEGER NOT NULL,
  cleared_balance INTEGER NOT NULL,
  difference INTEGER NOT NULL,
  notes TEXT,
  created_at TEXT NOT NULL
);

-- Posted money records are immutable: corrections are made with reversals.
CREATE TRIGGER trg_txn_no_delete BEFORE DELETE ON transactions
BEGIN SELECT RAISE(ABORT, 'Posted transactions cannot be deleted. Reverse them instead.'); END;

CREATE TRIGGER trg_txn_immutable BEFORE UPDATE OF txn_date, kind, sign, amount, currency,
  exchange_rate, base_amount, account_id, to_account_id, to_amount, customer_id, supplier_id,
  booking_id, is_direct, reversal_of ON transactions
BEGIN SELECT RAISE(ABORT, 'Posted transaction amounts cannot be changed. Reverse and re-enter instead.'); END;

CREATE TRIGGER trg_adj_no_delete BEFORE DELETE ON booking_adjustments
BEGIN SELECT RAISE(ABORT, 'Posted adjustments cannot be deleted. Reverse them instead.'); END;

CREATE TRIGGER trg_adj_immutable BEFORE UPDATE OF booking_id, adj_date, kind, sign, exchange_rate,
  customer_credit, customer_credit_tax, supplier_credit, commission_reversal,
  extra_customer_charge, extra_supplier_charge, reversal_of ON booking_adjustments
BEGIN SELECT RAISE(ABORT, 'Posted adjustments cannot be changed. Reverse them instead.'); END;

CREATE TRIGGER trg_inv_no_delete BEFORE DELETE ON invoices
BEGIN SELECT RAISE(ABORT, 'Invoices cannot be deleted. Cancel them instead.'); END;

CREATE TRIGGER trg_inv_immutable BEFORE UPDATE OF number, doc_type, booking_id, customer_id,
  issue_date, currency, lines_json, tax_json, subtotal, tax_total, total ON invoices
BEGIN SELECT RAISE(ABORT, 'Issued invoices cannot be changed. Cancel and reissue instead.'); END;

CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON audit_log
BEGIN SELECT RAISE(ABORT, 'Audit log entries cannot be deleted.'); END;
CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON audit_log
BEGIN SELECT RAISE(ABORT, 'Audit log entries cannot be changed.'); END;
"""

# v2: richer cash / bank accounts. Additive only: existing rows and older code keep working.
# `kind` (cash/bank/wallet/gateway) still drives reports; `account_type` is the finer label.
# Only the last 4 digits of an account/card number are ever stored.
SCHEMA_V2 = r"""
ALTER TABLE accounts ADD COLUMN account_type TEXT;
ALTER TABLE accounts ADD COLUMN bank_name TEXT;
ALTER TABLE accounts ADD COLUMN account_last4 TEXT;
UPDATE accounts SET account_type = CASE kind WHEN 'cash' THEN 'cash' WHEN 'wallet' THEN 'wallet'
  WHEN 'gateway' THEN 'gateway' ELSE 'bank' END;
"""

# v3: a submission key on adjustments so a double-clicked refund / cancellation is posted once.
SCHEMA_V3 = r"""
ALTER TABLE booking_adjustments ADD COLUMN idem_key TEXT;
CREATE UNIQUE INDEX ux_adj_idem ON booking_adjustments(idem_key) WHERE idem_key IS NOT NULL;
"""

# v4: debt register, linked to the ledger. Loan money moves through two new transaction kinds:
# loan_received (financing money in) and loan_repayment (principal paid back). Both change account
# balances but never sales, profit or the business cash-flow figure. Interest paid is an ordinary
# expense. Existing rows are not touched: the kind list is widened in place and one nullable column
# (transactions.debt_id) is added.
SCHEMA_V4 = r"""
CREATE TABLE debts (
  id INTEGER PRIMARY KEY,
  debt_no TEXT NOT NULL UNIQUE,
  creditor TEXT NOT NULL,
  creditor_type TEXT NOT NULL DEFAULT 'other'
    CHECK (creditor_type IN ('bank','nbfc','person','supplier','credit_card','other')),
  creditor_phone TEXT,
  reference TEXT,
  start_date TEXT NOT NULL,
  due_date TEXT,
  currency TEXT NOT NULL,
  opening_amount INTEGER NOT NULL DEFAULT 0 CHECK (opening_amount >= 0),
  interest_rate TEXT,
  interest_terms TEXT,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed')),
  notes TEXT,
  idem_key TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX ux_debt_idem ON debts(idem_key) WHERE idem_key IS NOT NULL;

-- interest charged by the lender but not yet paid (no money moves, so it is not a transaction)
CREATE TABLE debt_entries (
  id INTEGER PRIMARY KEY,
  entry_no TEXT NOT NULL UNIQUE,
  debt_id INTEGER NOT NULL REFERENCES debts(id),
  entry_date TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('interest_charge')),
  sign INTEGER NOT NULL DEFAULT 1 CHECK (sign IN (1,-1)),
  amount INTEGER NOT NULL CHECK (amount > 0),
  reference TEXT,
  notes TEXT,
  reversal_of INTEGER REFERENCES debt_entries(id),
  reversed_by INTEGER REFERENCES debt_entries(id),
  idem_key TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX ix_debt_entries_debt ON debt_entries(debt_id, entry_date);
CREATE UNIQUE INDEX ux_debt_entry_idem ON debt_entries(idem_key) WHERE idem_key IS NOT NULL;

CREATE TRIGGER trg_debt_entry_no_delete BEFORE DELETE ON debt_entries
BEGIN SELECT RAISE(ABORT, 'Debt entries cannot be deleted. Reverse them instead.'); END;
CREATE TRIGGER trg_debt_entry_immutable BEFORE UPDATE OF debt_id, entry_date, kind, sign, amount, reversal_of ON debt_entries
BEGIN SELECT RAISE(ABORT, 'Debt entries cannot be changed. Reverse them instead.'); END;

ALTER TABLE transactions ADD COLUMN debt_id INTEGER;

CREATE TRIGGER trg_txn_debt_valid BEFORE INSERT ON transactions
WHEN NEW.debt_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM debts WHERE id = NEW.debt_id)
BEGIN SELECT RAISE(ABORT, 'The linked debt does not exist.'); END;
CREATE TRIGGER trg_txn_loan_needs_debt BEFORE INSERT ON transactions
WHEN NEW.kind IN ('loan_received', 'loan_repayment') AND NEW.debt_id IS NULL
BEGIN SELECT RAISE(ABORT, 'A loan transaction must be linked to a debt.'); END;
CREATE TRIGGER trg_txn_debt_immutable BEFORE UPDATE OF debt_id ON transactions
BEGIN SELECT RAISE(ABORT, 'The debt linked to a posted transaction cannot be changed. Reverse it instead.'); END;
CREATE TRIGGER trg_debt_no_delete_linked BEFORE DELETE ON debts
WHEN EXISTS (SELECT 1 FROM transactions WHERE debt_id = OLD.id) OR EXISTS (SELECT 1 FROM debt_entries WHERE debt_id = OLD.id)
BEGIN SELECT RAISE(ABORT, 'This debt has transactions, so it cannot be deleted.'); END;

INSERT OR IGNORE INTO expense_categories(name) VALUES ('Loan interest');
"""

TXN_KIND_CHECK_V1 = ("'customer_receipt','supplier_payment','customer_refund',\n"
                     "                                     'supplier_receipt','expense','other_income','transfer')")
TXN_KIND_CHECK_V4 = ("'customer_receipt','supplier_payment','customer_refund',\n"
                     "                                     'supplier_receipt','expense','other_income','transfer',\n"
                     "                                     'loan_received','loan_repayment')")


def _statements(script):
    """Split an SQL script into complete statements (trigger bodies contain ';')."""
    out, buf = [], ""
    for line in script.splitlines(True):
        buf += line
        if sqlite3.complete_statement(buf):
            if buf.strip():
                out.append(buf.strip())
            buf = ""
    if buf.strip():
        raise RuntimeError("Incomplete SQL statement in migration")
    return out


def _rebuild_transactions(conn, old, new, drop_debt_id=False):
    """Change the CHECK list on transactions.kind (and optionally drop debt_id) with SQLite's documented table
    rebuild: copy every row, with its id, into a new table, swap it in and recreate the indexes and triggers.
    Runs inside the caller's transaction with foreign keys off; the caller checks integrity before committing."""
    table_sql = conn.execute("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'transactions'").fetchone()[0]
    head = next((h for h in ('CREATE TABLE transactions(', 'CREATE TABLE "transactions"(') if table_sql.startswith(h)), None)
    if table_sql.count(old) != 1 or not head:
        raise RuntimeError("Unexpected transactions table definition; upgrade stopped without changes.")
    new_sql = "CREATE TABLE transactions_rebuild(" + table_sql[len(head):].replace(old, new)
    cols = [r[1] for r in conn.execute("PRAGMA table_info(transactions)")]
    if drop_debt_id:
        if not new_sql.endswith(", debt_id INTEGER)"):
            raise RuntimeError("Unexpected transactions table definition; rollback stopped without changes.")
        new_sql = new_sql[:-len(", debt_id INTEGER)")] + ")"
        cols.remove("debt_id")
    extras = [r[0] for r in conn.execute("SELECT sql FROM sqlite_master WHERE tbl_name = 'transactions' "
                                         "AND type IN ('index', 'trigger') AND sql IS NOT NULL ORDER BY type, name")]
    # triggers on other tables that mention `transactions` would fail the rename check on standard SQLite builds
    # (the table is briefly absent): set them aside and recreate them afterwards
    others = [(r[0], r[1]) for r in conn.execute("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND tbl_name <> 'transactions' "
                                                 "AND sql LIKE '%transactions%'")]
    for name, _ in others:
        conn.execute('DROP TRIGGER "%s"' % name.replace('"', '""'))
    before = conn.execute("SELECT COUNT(*), COALESCE(SUM(id), 0), COALESCE(SUM(amount * sign), 0) FROM transactions").fetchone()
    conn.execute(new_sql)
    col_list = ", ".join(cols)
    conn.execute("INSERT INTO transactions_rebuild(%s) SELECT %s FROM transactions" % (col_list, col_list))
    conn.execute("DROP TABLE transactions")
    conn.execute("ALTER TABLE transactions_rebuild RENAME TO transactions")
    for sql in extras:
        if drop_debt_id and "debt_id" in sql:
            continue
        conn.execute(sql)
    for _, sql in others:
        conn.execute(sql)
    after = conn.execute("SELECT COUNT(*), COALESCE(SUM(id), 0), COALESCE(SUM(amount * sign), 0) FROM transactions").fetchone()
    if tuple(before) != tuple(after):
        raise RuntimeError("Transaction rows changed during the upgrade; nothing was saved.")


def _up_v4(conn):
    for stmt in _statements(SCHEMA_V4):
        conn.execute(stmt)
    _rebuild_transactions(conn, TXN_KIND_CHECK_V1, TXN_KIND_CHECK_V4)


def _down_v4(conn):
    n = conn.execute("SELECT COUNT(*) FROM transactions WHERE kind IN ('loan_received', 'loan_repayment')").fetchone()[0]
    if n:
        raise RuntimeError("%d loan transaction(s) are recorded. Rolling back would leave them without a type, so it is refused. "
                           "Restore a backup from before the upgrade instead." % n)
    for stmt in _statements(r"""
DROP TRIGGER IF EXISTS trg_debt_no_delete_linked;
DROP TRIGGER IF EXISTS trg_txn_debt_immutable;
DROP TRIGGER IF EXISTS trg_txn_loan_needs_debt;
DROP TRIGGER IF EXISTS trg_txn_debt_valid;
"""):
        conn.execute(stmt)
    _rebuild_transactions(conn, TXN_KIND_CHECK_V4, TXN_KIND_CHECK_V1, drop_debt_id=True)
    for stmt in _statements(r"""
DROP TRIGGER IF EXISTS trg_debt_entry_immutable;
DROP TRIGGER IF EXISTS trg_debt_entry_no_delete;
DROP TABLE IF EXISTS debt_entries;
DROP TABLE IF EXISTS debts;
DELETE FROM counters WHERE name IN ('debt', 'debt_entry', 'txn-LNR', 'txn-LNP');
"""):
        conn.execute(stmt)

MIGRATIONS = [
    (1, "initial schema", SCHEMA_V1),
    (2, "account type, bank name and last 4 digits", SCHEMA_V2),
    (3, "adjustment submission key", SCHEMA_V3),
    (4, "debt register linked to the ledger", _up_v4),
]

# Undo scripts for `python3 run.py --rollback-to N` (a verified backup is taken first).
DOWN_MIGRATIONS = {
    # removes the debt register (refused while loan transactions exist; the rollback takes a verified backup first)
    4: _down_v4,
    3: r"""
DROP INDEX IF EXISTS ux_adj_idem;
ALTER TABLE booking_adjustments DROP COLUMN idem_key;
""",
    2: r"""
ALTER TABLE accounts DROP COLUMN account_last4;
ALTER TABLE accounts DROP COLUMN bank_name;
ALTER TABLE accounts DROP COLUMN account_type;
""",
}

DEFAULT_SETTINGS = {
    "base_currency": "INR",
    "business_name": "",
    "business_tagline": "",
    "business_address": "",
    "business_phone": "",
    "business_email": "",
    "business_gstin": "",
    "business_state": "",
    "booking_prefix": "BK-",
    "invoice_prefix": "INV/",
    "credit_note_prefix": "CN/",
    "invoice_terms": "Fares are subject to airline rules. Please verify passenger names and travel documents.",
    "invoice_footer": "Thank you for travelling with us.",
    "auto_backup_hours": "24",
    "backup_keep": "30",
    "session_hours": "8",
    # payment details shown on reminders and the ticket + payment PDF (Settings -> Payment details)
    "pay_account_name": "",
    "pay_account_number": "",
    "pay_ifsc": "",
    "pay_branch": "",
    "pay_bank_name": "",
    "pay_upi_number": "",
    "pay_upi_apps": "Google Pay, PhonePe, BHIM UPI",
}

DEFAULT_CURRENCIES = [
    ("INR", "Indian Rupee", "₹"),
    ("SAR", "Saudi Riyal", "SAR"),
    ("AED", "UAE Dirham", "AED"),
    ("USD", "US Dollar", "$"),
    ("QAR", "Qatari Riyal", "QAR"),
    ("OMR", "Omani Rial", "OMR"),
    ("KWD", "Kuwaiti Dinar", "KWD"),
    ("BHD", "Bahraini Dinar", "BHD"),
]

DEFAULT_EXPENSE_CATEGORIES = [
    "Office expenses", "Rent", "Salaries & wages", "Internet & software", "Telephone",
    "Marketing & advertising", "Bank charges", "Payment gateway charges", "Travel & conveyance",
    "Professional fees", "Utilities", "Printing & stationery", "Repairs & maintenance",
    "Direct booking cost", "Other operating expenses",
]

# Templates only - the accountant must review and activate the right one per transaction.
DEFAULT_TAX_PROFILES = [
    ("No tax", "0", "none", "none", "", "No tax charged on this booking."),
    ("GST 18% on service charge (CGST+SGST) - review with CA", "18", "service_charge", "cgst_sgst", "998555",
     "Template. Confirm taxable value, rate, SAC and place of supply with your CA before use."),
    ("IGST 18% on service charge - review with CA", "18", "service_charge", "igst", "998555",
     "Template. Confirm taxable value, rate, SAC and place of supply with your CA before use."),
    ("Custom taxable value", "0", "custom", "none", "",
     "Enter the taxable value and rate manually on the booking."),
]


def now_iso():
    return datetime.now().replace(microsecond=0).isoformat()


def connect(db_path):
    conn = sqlite3.connect(str(db_path), timeout=20, isolation_level=None, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 20000")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = FULL")
    return conn


@contextmanager
def tx(conn):
    """Atomic write transaction. Everything inside commits together or not at all."""
    if conn.in_transaction:
        # nested use - join the outer transaction
        yield conn
        return
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield conn
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise


def current_version(conn):
    conn.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, "
                 "name TEXT NOT NULL, applied_at TEXT NOT NULL)")
    row = conn.execute("SELECT MAX(version) v FROM schema_migrations").fetchone()
    return row["v"] or 0


def migrate(conn):
    version = current_version(conn)
    for num, name, sql in MIGRATIONS:
        if num <= version:
            continue
        if callable(sql):
            _run_step(conn, sql, "INSERT INTO schema_migrations(version, name, applied_at) VALUES (?, ?, ?)", (num, name, now_iso()))
            continue
        script = "BEGIN;\n" + sql + "\nINSERT INTO schema_migrations(version, name, applied_at) VALUES (%d, '%s', '%s');\nCOMMIT;" % (
            num, name.replace("'", "''"), now_iso())
        try:
            conn.executescript(script)
        except Exception:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
            raise
    seed(conn)
    return current_version(conn)


def rollback(conn, target):
    """Undo migrations above `target`, newest first, each in its own transaction."""
    version = current_version(conn)
    for num in range(version, target, -1):
        if num not in DOWN_MIGRATIONS:
            raise RuntimeError("Migration %d cannot be rolled back automatically. Restore a backup instead." % num)
        if callable(DOWN_MIGRATIONS[num]):
            _run_step(conn, DOWN_MIGRATIONS[num], "DELETE FROM schema_migrations WHERE version = ?", (num,))
            continue
        script = "BEGIN;\n" + DOWN_MIGRATIONS[num] + "\nDELETE FROM schema_migrations WHERE version = %d;\nCOMMIT;" % num
        try:
            conn.executescript(script)
        except Exception:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
            raise
    return current_version(conn)


def _run_step(conn, fn, record_sql, record_args):
    """Run a Python migration step and record it, all in one transaction, checking the database before it commits.
    Foreign keys are switched off for the step (required for a table rebuild) and checked explicitly instead."""
    conn.execute("PRAGMA foreign_keys = OFF")
    conn.execute("BEGIN IMMEDIATE")
    try:
        fn(conn)
        conn.execute(record_sql, record_args)
        ok = conn.execute("PRAGMA integrity_check").fetchone()[0]
        if ok != "ok":
            raise RuntimeError("Integrity check failed during upgrade: %s" % ok)
        if conn.execute("PRAGMA foreign_key_check").fetchone():
            raise RuntimeError("Foreign key check failed during upgrade")
        conn.execute("COMMIT")
    except BaseException:
        if conn.in_transaction:
            conn.execute("ROLLBACK")
        raise
    finally:
        conn.execute("PRAGMA foreign_keys = ON")


def seed(conn):
    with tx(conn):
        for k, v in DEFAULT_SETTINGS.items():
            conn.execute("INSERT OR IGNORE INTO settings(key, value) VALUES (?, ?)", (k, v))
        for code, name, sym in DEFAULT_CURRENCIES:
            conn.execute("INSERT OR IGNORE INTO currencies(code, name, symbol) VALUES (?, ?, ?)", (code, name, sym))
        if conn.execute("SELECT COUNT(*) FROM expense_categories").fetchone()[0] == 0:
            for c in DEFAULT_EXPENSE_CATEGORIES:
                conn.execute("INSERT INTO expense_categories(name) VALUES (?)", (c,))
        if conn.execute("SELECT COUNT(*) FROM tax_profiles").fetchone()[0] == 0:
            for row in DEFAULT_TAX_PROFILES:
                conn.execute("INSERT INTO tax_profiles(name, rate, basis, split, sac_code, notes) VALUES (?,?,?,?,?,?)", row)
        if conn.execute("SELECT COUNT(*) FROM accounts").fetchone()[0] == 0:
            ts = now_iso()
            conn.execute("INSERT INTO accounts(name, kind, account_type, currency, opening_balance, opening_date, details, created_at, updated_at) "
                         "VALUES ('Cash in hand', 'cash', 'cash', 'INR', 0, NULL, 'Set the opening balance in Accounts.', ?, ?)", (ts, ts))


def get_setting(conn, key, default=None):
    row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
    return row["value"] if row else default


def all_settings(conn):
    return {r["key"]: r["value"] for r in conn.execute("SELECT key, value FROM settings")}


def next_counter(conn, name):
    """Must be called inside a write transaction."""
    row = conn.execute("SELECT value FROM counters WHERE name = ?", (name,)).fetchone()
    if row is None:
        conn.execute("INSERT INTO counters(name, value) VALUES (?, 1)", (name,))
        return 1
    value = row["value"] + 1
    conn.execute("UPDATE counters SET value = ? WHERE name = ?", (value, name))
    return value


def rows(cursor):
    return [dict(r) for r in cursor]


def one(cursor):
    r = cursor.fetchone()
    return dict(r) if r else None
