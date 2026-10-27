"""Business logic for AirZelo. All writes go through here inside atomic
transactions, with an audit trail of every change to financial data."""
import base64
import hashlib
import json
import os
import re
import uuid
from datetime import date

from . import db, validate as v
from .db import tx, now_iso, rows, one, get_setting, next_counter
from .errors import ValidationError, NotFound, Conflict
from .finance import Ledger, txn_row, KIND_LABELS
from .money import parse_amount, parse_rate, parse_percent, convert, percent_of, split_half

BOOKING_STATUSES = ("enquiry", "on_hold", "confirmed", "ticketed", "cancelled", "refunded")
COMMIT_STATUSES = ("confirmed", "ticketed")
SERVICE_TYPES = ("flight", "visa", "hotel", "package", "umrah", "other")
METHODS = ("cash", "bank_transfer", "upi", "card", "cheque", "payment_gateway", "portal_wallet", "adjustment", "other")
TXN_KINDS = tuple(KIND_LABELS.keys())
PARTY_KINDS = {"customer_receipt": "customer", "customer_refund": "customer",
               "supplier_payment": "supplier", "supplier_receipt": "supplier"}
TXN_PREFIX = {"customer_receipt": "RCT", "supplier_payment": "PAY", "customer_refund": "CRF",
              "supplier_receipt": "SRC", "expense": "EXP", "other_income": "INC", "transfer": "TRF",
              "loan_received": "LNR", "loan_repayment": "LNP"}
LOAN_KINDS = ("loan_received", "loan_repayment")
DEFAULT_CATEGORY = {"customer_receipt": "Customer receipt", "supplier_payment": "Supplier payment",
                    "customer_refund": "Customer refund", "supplier_receipt": "Supplier refund",
                    "transfer": "Transfer", "loan_received": "Loan received", "loan_repayment": "Loan repayment"}
PRICING_FIELDS = ("purchase_cost", "selling_price", "commission", "service_charge", "other_income",
                  "currency", "exchange_rate", "tax_amount", "taxable_value", "tax_rate", "tax_profile_id")
ALLOWED_UPLOADS = {
    ".pdf": "application/pdf", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
    ".webp": "image/webp", ".gif": "image/gif", ".heic": "image/heic", ".txt": "text/plain",
    ".csv": "text/csv", ".doc": "application/msword",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xls": "application/vnd.ms-excel",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
}
MAX_UPLOAD = 15 * 1024 * 1024


def today():
    return date.today().isoformat()


# ------------------------------------------------------------------ audit
def audit(conn, entity, entity_id, action, before=None, after=None):
    conn.execute(
        "INSERT INTO audit_log(at, entity, entity_id, action, before_json, after_json) VALUES (?,?,?,?,?,?)",
        (now_iso(), entity, str(entity_id) if entity_id is not None else None, action,
         json.dumps(before, default=str, sort_keys=True) if before is not None else None,
         json.dumps(after, default=str, sort_keys=True) if after is not None else None))


def history(conn, booking_id, action, detail=""):
    conn.execute("INSERT INTO booking_history(booking_id, at, action, detail) VALUES (?,?,?,?)",
                 (booking_id, now_iso(), action, detail))


def list_audit(conn, entity=None, entity_id=None, limit=300):
    q = "SELECT * FROM audit_log"
    args = []
    cond = []
    if entity:
        cond.append("entity = ?")
        args.append(entity)
    if entity_id:
        cond.append("entity_id = ?")
        args.append(str(entity_id))
    if cond:
        q += " WHERE " + " AND ".join(cond)
    q += " ORDER BY id DESC LIMIT ?"
    args.append(min(int(limit or 300), 2000))
    return rows(conn.execute(q, args))


# --------------------------------------------------------------- settings
EDITABLE_SETTINGS = ("business_name", "business_tagline", "business_address", "business_phone", "business_email",
                     "business_gstin", "business_state", "invoice_prefix", "credit_note_prefix", "invoice_terms",
                     "invoice_footer", "auto_backup_hours", "backup_keep", "session_hours",
                     "pay_account_name", "pay_account_number", "pay_ifsc", "pay_branch", "pay_bank_name",
                     "pay_upi_number", "pay_upi_apps",
                     # company profile, white-label branding and document preferences
                     "legal_name", "business_country", "business_website", "business_registration", "receipt_footer",
                     "brand_color", "booking_prefix", "receipt_prefix", "date_format", "currency_display",
                     "report_default_period", "setup_completed",
                     # fare search (optional feature)
                     "fare_search_enabled", "fare_stale_minutes")
# cosmetic / identity settings: changing them never touches posted money or issued documents
CHOICE_SETTINGS = {"date_format": ("DD/MM/YYYY", "DD MMM YYYY", "MM/DD/YYYY", "YYYY-MM-DD"),
                   "currency_display": ("symbol", "code"),
                   "report_default_period": ("month", "fy", "year", "week", "today"),
                   "setup_completed": ("0", "1"),
                   "fare_search_enabled": ("0", "1")}
PREFIX_SETTINGS = ("invoice_prefix", "credit_note_prefix", "booking_prefix", "receipt_prefix")
FORMAT_RULES = {
    "business_email": (r"^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$", "Enter a valid email address"),
    "business_phone": (r"^[+0-9][0-9 ()\-]{5,24}$", "Enter a valid phone number (digits, spaces, + ( ) -)"),
    "business_website": (r"^(https?://)?[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+(/\S*)?$", "Enter a website such as www.example.com"),
    "brand_color": (r"^#[0-9A-Fa-f]{6}$", "Brand colour must be a hex colour such as #1B3FCC"),
    "business_gstin": (r"^[0-9]{2}[A-Z0-9]{10}[0-9A-Z]{3}$", "GSTIN must be 15 characters, e.g. 32ABCDE1234F1Z5"),
}
PAYMENT_FORMATS = {"pay_account_number": (r"^\d{9,18}$", "Account number must be 9 to 18 digits"),
                   "pay_ifsc": (r"^[A-Z]{4}0[A-Z0-9]{6}$", "IFSC code must look like ABCD0123456 (11 characters)"),
                   "pay_upi_number": (r"^\d{10}$", "UPI payment number must be a 10-digit mobile number")}


def get_settings(conn):
    return db.all_settings(conn)


def update_settings(conn, data):
    with tx(conn):
        before = db.all_settings(conn)
        changes = {}
        for k in EDITABLE_SETTINGS:
            if k in data:
                val = "" if data[k] is None else str(data[k]).strip()
                if len(val) > 2000:
                    raise ValidationError("%s is too long" % k, k)
                if k in ("auto_backup_hours", "backup_keep", "session_hours", "fare_stale_minutes"):
                    try:
                        n = int(val)
                    except ValueError:
                        raise ValidationError("%s must be a whole number" % k, k)
                    lim = {"auto_backup_hours": (0, 720), "backup_keep": (3, 1000), "session_hours": (1, 72),
                           "fare_stale_minutes": (1, 240)}[k]
                    if not lim[0] <= n <= lim[1]:
                        raise ValidationError("%s must be between %d and %d" % (k, lim[0], lim[1]), k)
                    val = str(n)
                if k in ("pay_ifsc", "pay_account_name", "business_gstin"):
                    val = val.upper()
                if k == "business_name" and not val:
                    raise ValidationError("The display name cannot be empty", k)
                if k in CHOICE_SETTINGS and val not in CHOICE_SETTINGS[k]:
                    raise ValidationError("%s must be one of: %s" % (k.replace("_", " ").capitalize(), ", ".join(CHOICE_SETTINGS[k])), k)
                if k in PREFIX_SETTINGS and not re.match(r"^[A-Za-z0-9][A-Za-z0-9/_\-]{0,11}$", val):
                    raise ValidationError("Prefix must be 1-12 letters, digits, / _ or -, e.g. INV/", k)
                if k in FORMAT_RULES and val and not re.match(FORMAT_RULES[k][0], val):
                    raise ValidationError(FORMAT_RULES[k][1], k)
                if k in ("pay_account_number", "pay_upi_number"):
                    val = val.replace(" ", "")
                if k in PAYMENT_FORMATS and val and not re.match(PAYMENT_FORMATS[k][0], val):
                    raise ValidationError(PAYMENT_FORMATS[k][1], k)
                if before.get(k) != val:
                    conn.execute("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", (k, val))
                    changes[k] = val
        if changes:
            audit(conn, "settings", None, "update", {k: before.get(k) for k in changes}, changes)
    return db.all_settings(conn)


# ------------------------------------------------------------- currencies
def list_currencies(conn):
    return rows(conn.execute("SELECT * FROM currencies ORDER BY CASE code WHEN 'INR' THEN 0 WHEN 'SAR' THEN 1 ELSE 2 END, code"))


def save_currency(conn, data):
    code = v.norm_code(data.get("code"), 3)
    if not code or len(code) != 3 or not code.isalpha():
        raise ValidationError("Currency code must be 3 letters (e.g. SAR)", "code")
    name = v.s(data, "name", 60, True)
    symbol = v.s(data, "symbol", 8) or code
    active = 0 if data.get("active") in (False, 0, "0") else 1
    with tx(conn):
        before = one(conn.execute("SELECT * FROM currencies WHERE code = ?", (code,)))
        if before and code == get_setting(conn, "base_currency") and not active:
            raise ValidationError("The reporting currency cannot be deactivated")
        conn.execute("INSERT INTO currencies(code, name, symbol, active) VALUES (?,?,?,?) "
                     "ON CONFLICT(code) DO UPDATE SET name=excluded.name, symbol=excluded.symbol, active=excluded.active",
                     (code, name, symbol, active))
        audit(conn, "currency", code, "update" if before else "create", before,
              {"code": code, "name": name, "symbol": symbol, "active": active})
    return one(conn.execute("SELECT * FROM currencies WHERE code = ?", (code,)))


def _currency_and_rate(conn, data, cur_key="currency", rate_key="exchange_rate"):
    base = get_setting(conn, "base_currency", "INR")
    cur = v.norm_code(data.get(cur_key), 3) or base
    row = conn.execute("SELECT * FROM currencies WHERE code = ?", (cur,)).fetchone()
    if not row:
        raise ValidationError("Unknown currency %s - add it in Settings first" % cur, cur_key)
    if cur == base:
        return cur, "1"
    return cur, parse_rate(data.get(rate_key), "Exchange rate to %s" % base)


# ------------------------------------------------------------- customers
CUSTOMER_FIELDS = ("name", "phone", "alt_phone", "email", "address", "city", "state", "gstin", "passport_no",
                   "notes", "follow_up_date", "follow_up_note")


def _customer_payload(data):
    return {
        "name": v.s(data, "name", 150, True, "Customer name"),
        "phone": v.phone(data, "phone"),
        "alt_phone": v.phone(data, "alt_phone"),
        "email": v.email(data, "email"),
        "address": v.s(data, "address", 500),
        "city": v.s(data, "city", 80),
        "state": v.s(data, "state", 80),
        "gstin": v.norm_code(v.s(data, "gstin", 15), 15),
        "passport_no": v.norm_code(v.s(data, "passport_no", 20), 20),
        "notes": v.s(data, "notes", 4000),
        "follow_up_date": v.d(data, "follow_up_date"),
        "follow_up_note": v.s(data, "follow_up_note", 500),
    }


def create_customer(conn, data, _in_tx=False):
    p = _customer_payload(data)
    if p["phone"] and not v.b(data, "force_duplicate"):
        dup = conn.execute("SELECT id, name FROM customers WHERE phone = ? AND active = 1", (p["phone"],)).fetchone()
        if dup:
            raise Conflict("A customer with this phone number already exists: %s" % dup["name"], "duplicate_customer",
                           {"id": dup["id"], "name": dup["name"]})
    ts = now_iso()
    with tx(conn):
        cur = conn.execute("INSERT INTO customers(%s, created_at, updated_at) VALUES (%s, ?, ?)" % (
            ", ".join(CUSTOMER_FIELDS), ", ".join("?" * len(CUSTOMER_FIELDS))),
            [p[f] for f in CUSTOMER_FIELDS] + [ts, ts])
        cid = cur.lastrowid
        audit(conn, "customer", cid, "create", None, p)
    return cid


def update_customer(conn, cid, data):
    with tx(conn):
        before = one(conn.execute("SELECT * FROM customers WHERE id = ?", (cid,)))
        if not before:
            raise NotFound("Customer not found")
        merged = dict(before)
        merged.update({k: data[k] for k in data if k in CUSTOMER_FIELDS})
        p = _customer_payload(merged)
        active = before["active"]
        if "active" in data:
            active = 0 if data["active"] in (False, 0, "0") else 1
        conn.execute("UPDATE customers SET %s, active = ?, updated_at = ? WHERE id = ?" % ", ".join("%s = ?" % f for f in CUSTOMER_FIELDS),
                     [p[f] for f in CUSTOMER_FIELDS] + [active, now_iso(), cid])
        audit(conn, "customer", cid, "update", before, dict(p, active=active))
    return get_customer(conn, cid)


def delete_customer(conn, cid):
    with tx(conn):
        c = one(conn.execute("SELECT * FROM customers WHERE id = ?", (cid,)))
        if not c:
            raise NotFound("Customer not found")
        used = conn.execute("SELECT (SELECT COUNT(*) FROM bookings WHERE customer_id = ?) + "
                            "(SELECT COUNT(*) FROM transactions WHERE customer_id = ?)", (cid, cid)).fetchone()[0]
        if used:
            conn.execute("UPDATE customers SET active = 0, updated_at = ? WHERE id = ?", (now_iso(), cid))
            audit(conn, "customer", cid, "deactivate", c, {"active": 0})
            return {"deactivated": True}
        conn.execute("DELETE FROM customers WHERE id = ?", (cid,))
        audit(conn, "customer", cid, "delete", c, None)
        return {"deleted": True}


def list_customers(conn, q=None, include_inactive=False):
    L = Ledger(conn)
    bal = L.customer_balances()
    out = []
    ql = (q or "").strip().lower()
    for c in sorted(L.customers.values(), key=lambda c: c["name"].lower()):
        if not include_inactive and not c["active"]:
            continue
        if ql and not (ql in c["name"].lower() or ql in (c["phone"] or "") or ql in (c["email"] or "").lower()):
            continue
        r = dict(c)
        r.update({k: bal[c["id"]][k] for k in ("billed", "received", "outstanding", "unallocated", "bookings", "credits", "refunded")})
        out.append(r)
    return out


def get_customer(conn, cid):
    L = Ledger(conn)
    c = L.customers.get(int(cid))
    if not c:
        raise NotFound("Customer not found")
    bal = L.customer_balances()[c["id"]]
    bookings = [L.booking_row(b) for b in L.bookings.values() if b["customer_id"] == c["id"]]
    bookings.sort(key=lambda r: (r["booking_date"], r["id"]), reverse=True)
    txns = [txn_row(L, t) for t in L.transactions if t["customer_id"] == c["id"]]
    txns.sort(key=lambda r: (r["txn_date"], r["id"]), reverse=True)
    tickets = []
    for b in L.bookings.values():
        if b["customer_id"] == c["id"]:
            for p in L.passengers.get(b["id"], []):
                tickets.append({"booking_id": b["id"], "ref": b["ref"], "pnr": b["pnr"], "name": p["name"],
                                "ticket_number": p["ticket_number"], "status": b["status"]})
    refunds = [r for r in txns if r["kind"] == "customer_refund"]
    adjustments = []
    for a in L.all_adjustments:
        b = L.bookings[a["booking_id"]]
        if b["customer_id"] == c["id"]:
            adjustments.append(dict(a, ref=b["ref"]))
    return {"customer": c, "balance": bal, "bookings": bookings, "transactions": txns, "tickets": tickets,
            "refunds": refunds, "adjustments": adjustments}


def _resolve_customer(conn, data):
    cid = v.i(data, "customer_id")
    if cid:
        c = conn.execute("SELECT id, active FROM customers WHERE id = ?", (cid,)).fetchone()
        if not c:
            raise ValidationError("Selected customer does not exist", "customer_id")
        return cid
    nc = data.get("new_customer")
    if isinstance(nc, dict) and (nc.get("name") or "").strip():
        if nc.get("phone"):
            dup = conn.execute("SELECT id FROM customers WHERE phone = ? AND active = 1", (str(nc["phone"]).strip(),)).fetchone()
            if dup:
                return dup["id"]
        return create_customer(conn, nc)
    raise ValidationError("Select a customer or enter a new customer name", "customer_id")


# ------------------------------------------------------------- suppliers
SUPPLIER_FIELDS = ("name", "kind", "contact_person", "phone", "email", "gstin", "notes")
SUPPLIER_KINDS = ("b2b_portal", "airline", "consolidator", "hotel", "visa", "other")


def _supplier_payload(data):
    return {
        "name": v.s(data, "name", 150, True, "Supplier name"),
        "kind": v.choice(data, "kind", SUPPLIER_KINDS, "b2b_portal", "Supplier type"),
        "contact_person": v.s(data, "contact_person", 120),
        "phone": v.phone(data, "phone"),
        "email": v.email(data, "email"),
        "gstin": v.norm_code(v.s(data, "gstin", 15), 15),
        "notes": v.s(data, "notes", 4000),
    }


def create_supplier(conn, data):
    p = _supplier_payload(data)
    ts = now_iso()
    with tx(conn):
        if conn.execute("SELECT 1 FROM suppliers WHERE name = ? COLLATE NOCASE", (p["name"],)).fetchone():
            raise Conflict("A supplier named '%s' already exists" % p["name"], "duplicate_supplier")
        cur = conn.execute("INSERT INTO suppliers(%s, created_at, updated_at) VALUES (%s, ?, ?)" % (
            ", ".join(SUPPLIER_FIELDS), ", ".join("?" * len(SUPPLIER_FIELDS))), [p[f] for f in SUPPLIER_FIELDS] + [ts, ts])
        audit(conn, "supplier", cur.lastrowid, "create", None, p)
    return cur.lastrowid


def update_supplier(conn, sid, data):
    with tx(conn):
        before = one(conn.execute("SELECT * FROM suppliers WHERE id = ?", (sid,)))
        if not before:
            raise NotFound("Supplier not found")
        merged = dict(before)
        merged.update({k: data[k] for k in data if k in SUPPLIER_FIELDS})
        p = _supplier_payload(merged)
        dup = conn.execute("SELECT id FROM suppliers WHERE name = ? COLLATE NOCASE AND id <> ?", (p["name"], sid)).fetchone()
        if dup:
            raise Conflict("A supplier named '%s' already exists" % p["name"], "duplicate_supplier")
        active = before["active"]
        if "active" in data:
            active = 0 if data["active"] in (False, 0, "0") else 1
        conn.execute("UPDATE suppliers SET %s, active = ?, updated_at = ? WHERE id = ?" % ", ".join("%s = ?" % f for f in SUPPLIER_FIELDS),
                     [p[f] for f in SUPPLIER_FIELDS] + [active, now_iso(), sid])
        audit(conn, "supplier", sid, "update", before, dict(p, active=active))
    return get_supplier(conn, sid)


def delete_supplier(conn, sid):
    with tx(conn):
        s = one(conn.execute("SELECT * FROM suppliers WHERE id = ?", (sid,)))
        if not s:
            raise NotFound("Supplier not found")
        used = conn.execute("SELECT (SELECT COUNT(*) FROM bookings WHERE supplier_id = ?) + "
                            "(SELECT COUNT(*) FROM transactions WHERE supplier_id = ?) + "
                            "(SELECT COUNT(*) FROM fare_search_suppliers WHERE supplier_id = ?)", (sid, sid, sid)).fetchone()[0]
        if used:
            conn.execute("UPDATE suppliers SET active = 0, updated_at = ? WHERE id = ?", (now_iso(), sid))
            audit(conn, "supplier", sid, "deactivate", s, {"active": 0})
            return {"deactivated": True}
        conn.execute("DELETE FROM suppliers WHERE id = ?", (sid,))
        audit(conn, "supplier", sid, "delete", s, None)
        return {"deleted": True}


def list_suppliers(conn, include_inactive=False):
    L = Ledger(conn)
    bal = L.supplier_balances()
    out = []
    for s in sorted(L.suppliers.values(), key=lambda s: s["name"].lower()):
        if not include_inactive and not s["active"]:
            continue
        r = dict(s)
        r.update({k: bal[s["id"]][k] for k in ("payable", "credits", "paid", "refunds", "outstanding", "unallocated", "bookings")})
        out.append(r)
    return out


def get_supplier(conn, sid):
    L = Ledger(conn)
    s = L.suppliers.get(int(sid))
    if not s:
        raise NotFound("Supplier not found")
    bookings = [L.booking_row(b) for b in L.bookings.values() if b["supplier_id"] == s["id"]]
    bookings.sort(key=lambda r: (r["booking_date"], r["id"]), reverse=True)
    txns = [txn_row(L, t) for t in L.transactions if t["supplier_id"] == s["id"]]
    txns.sort(key=lambda r: (r["txn_date"], r["id"]), reverse=True)
    return {"supplier": s, "balance": L.supplier_balances()[s["id"]], "bookings": bookings, "transactions": txns}


# --------------------------------------------------------------- accounts
def list_accounts(conn):
    L = Ledger(conn)
    bal = L.account_balances()
    out = []
    for a in sorted(L.accounts.values(), key=lambda a: (not a["active"], a["kind"], a["name"].lower())):
        r = dict(a)
        r.update({k: bal[a["id"]][k] for k in ("inflow", "outflow", "balance", "reconciled_balance")})
        r["txn_count"] = sum(1 for t in L.transactions if t["account_id"] == a["id"] or t.get("to_account_id") == a["id"])
        out.append(r)
    return out


# account_type -> the reporting kind stored in accounts.kind
ACCOUNT_TYPES = {"cash": "cash", "bank": "bank", "current": "bank", "savings": "bank", "upi": "wallet",
                 "credit_card": "bank", "wallet": "wallet", "gateway": "gateway"}


def _last4(raw):
    """Keep only the last 4 digits of an account or card number; the full number is never stored."""
    if raw is None or str(raw).strip() == "":
        return None
    digits = "".join(ch for ch in str(raw) if ch.isdigit())
    if len(digits) < 4:
        raise ValidationError("Enter at least the last 4 digits of the account number", "account_last4")
    return digits[-4:]


def save_account(conn, data, aid=None):
    name = v.s(data, "name", 100, True, "Account name")
    acc_type = data.get("account_type") or data.get("kind") or "bank"
    if acc_type not in ACCOUNT_TYPES:
        raise ValidationError("Account type must be one of: %s" % ", ".join(ACCOUNT_TYPES), "account_type")
    kind = ACCOUNT_TYPES[acc_type]
    cur = v.norm_code(data.get("currency"), 3) or get_setting(conn, "base_currency", "INR")
    if not conn.execute("SELECT 1 FROM currencies WHERE code = ?", (cur,)).fetchone():
        raise ValidationError("Unknown currency", "currency")
    opening = parse_amount(data.get("opening_balance"), "Opening balance", allow_negative=True)
    opening_date = v.d(data, "opening_date")
    details = v.s(data, "details", 500)
    bank_name = v.s(data, "bank_name", 100)
    last4 = _last4(data.get("account_last4"))
    active = 0 if data.get("active") in (False, 0, "0") else 1
    ts = now_iso()
    with tx(conn):
        dup = conn.execute("SELECT id FROM accounts WHERE name = ? COLLATE NOCASE AND id <> ?", (name, aid or 0)).fetchone()
        if dup:
            raise Conflict("An account with this name already exists", "duplicate_account")
        if aid:
            before = one(conn.execute("SELECT * FROM accounts WHERE id = ?", (aid,)))
            if not before:
                raise NotFound("Account not found")
            has_txn = conn.execute("SELECT 1 FROM transactions WHERE account_id = ? OR to_account_id = ? LIMIT 1", (aid, aid)).fetchone()
            if has_txn and cur != before["currency"]:
                raise ValidationError("Currency cannot be changed after transactions are recorded", "currency")
            if before["opening_balance"] != opening and has_txn and not v.b(data, "confirm_opening_change"):
                raise Conflict("Changing the opening balance of an account with transactions changes every running balance. Confirm to proceed.",
                               "confirm_opening_change")
            conn.execute("UPDATE accounts SET name=?, kind=?, account_type=?, bank_name=?, account_last4=?, currency=?, opening_balance=?, "
                         "opening_date=?, details=?, active=?, updated_at=? WHERE id=?",
                         (name, kind, acc_type, bank_name, last4, cur, opening, opening_date, details, active, ts, aid))
            audit(conn, "account", aid, "update", before, {"name": name, "kind": kind, "account_type": acc_type, "bank_name": bank_name,
                                                          "currency": cur, "opening_balance": opening, "opening_date": opening_date, "active": active})
        else:
            c = conn.execute("INSERT INTO accounts(name, kind, account_type, bank_name, account_last4, currency, opening_balance, opening_date, "
                             "details, active, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                             (name, kind, acc_type, bank_name, last4, cur, opening, opening_date, details, active, ts, ts))
            aid = c.lastrowid
            audit(conn, "account", aid, "create", None, {"name": name, "kind": kind, "account_type": acc_type, "bank_name": bank_name,
                                                        "currency": cur, "opening_balance": opening})
    return aid


def set_account_active(conn, aid, active):
    with tx(conn):
        before = one(conn.execute("SELECT id, name, active FROM accounts WHERE id = ?", (aid,)))
        if not before:
            raise NotFound("Account not found")
        active = 1 if active else 0
        if not active and conn.execute("SELECT COUNT(*) FROM accounts WHERE active = 1 AND id <> ?", (aid,)).fetchone()[0] == 0:
            raise ValidationError("Keep at least one active account")
        conn.execute("UPDATE accounts SET active = ?, updated_at = ? WHERE id = ?", (active, now_iso(), aid))
        audit(conn, "account", aid, "activate" if active else "deactivate", {"active": before["active"]}, {"active": active})
    return aid


# ----------------------------------------------------------- tax profiles
def list_tax_profiles(conn):
    return rows(conn.execute("SELECT * FROM tax_profiles ORDER BY active DESC, id"))


def save_tax_profile(conn, data, pid=None):
    p = {
        "name": v.s(data, "name", 120, True, "Tax profile name"),
        "rate": parse_percent(data.get("rate"), "Rate"),
        "basis": v.choice(data, "basis", ("none", "service_charge", "markup", "service_and_markup", "selling_price", "custom"), "none", "Taxable basis"),
        "split": v.choice(data, "split", ("none", "cgst_sgst", "igst"), "none", "Tax split"),
        "sac_code": v.s(data, "sac_code", 12),
        "notes": v.s(data, "notes", 1000),
        "active": 0 if data.get("active") in (False, 0, "0") else 1,
    }
    with tx(conn):
        dup = conn.execute("SELECT id FROM tax_profiles WHERE name = ? AND id <> ?", (p["name"], pid or 0)).fetchone()
        if dup:
            raise Conflict("A tax profile with this name already exists")
        if pid:
            before = one(conn.execute("SELECT * FROM tax_profiles WHERE id = ?", (pid,)))
            if not before:
                raise NotFound("Tax profile not found")
            conn.execute("UPDATE tax_profiles SET name=?, rate=?, basis=?, split=?, sac_code=?, notes=?, active=? WHERE id=?",
                         (p["name"], p["rate"], p["basis"], p["split"], p["sac_code"], p["notes"], p["active"], pid))
            audit(conn, "tax_profile", pid, "update", before, p)
        else:
            c = conn.execute("INSERT INTO tax_profiles(name, rate, basis, split, sac_code, notes, active) VALUES (?,?,?,?,?,?,?)",
                             (p["name"], p["rate"], p["basis"], p["split"], p["sac_code"], p["notes"], p["active"]))
            pid = c.lastrowid
            audit(conn, "tax_profile", pid, "create", None, p)
    return pid


def compute_tax(conn, data, amounts):
    pid = v.i(data, "tax_profile_id")
    empty = {"tax_profile_id": None, "tax_override": 0, "taxable_value": 0, "tax_rate": "0", "tax_amount": 0,
             "tax_split": "none"}
    if not pid:
        if v.b(data, "tax_override"):
            taxable = parse_amount(data.get("taxable_value"), "Taxable value")
            rate = parse_percent(data.get("tax_rate"), "Tax rate")
            tax = parse_amount(data.get("tax_amount"), "Tax amount") if str(data.get("tax_amount") or "").strip() else percent_of(taxable, rate)
            return {"tax_profile_id": None, "tax_override": 1, "taxable_value": taxable, "tax_rate": rate,
                    "tax_amount": tax, "tax_split": v.choice(data, "tax_split", ("none", "cgst_sgst", "igst"), "none")}
        return empty
    prof = conn.execute("SELECT * FROM tax_profiles WHERE id = ?", (pid,)).fetchone()
    if not prof:
        raise ValidationError("Selected tax profile does not exist", "tax_profile_id")
    markup = max(amounts["selling_price"] - amounts["purchase_cost"], 0)
    if v.b(data, "tax_override"):
        taxable = parse_amount(data.get("taxable_value"), "Taxable value")
        rate = parse_percent(data.get("tax_rate") if str(data.get("tax_rate") or "").strip() else prof["rate"], "Tax rate")
        tax = parse_amount(data.get("tax_amount"), "Tax amount") if str(data.get("tax_amount") or "").strip() else percent_of(taxable, rate)
        override = 1
    else:
        basis = prof["basis"]
        if basis == "none":
            taxable = 0
        elif basis == "service_charge":
            taxable = amounts["service_charge"]
        elif basis == "markup":
            taxable = markup
        elif basis == "service_and_markup":
            taxable = amounts["service_charge"] + markup
        elif basis == "selling_price":
            taxable = amounts["selling_price"]
        else:
            taxable = parse_amount(data.get("taxable_value"), "Taxable value")
        rate = prof["rate"]
        tax = percent_of(taxable, rate)
        override = 0
    return {"tax_profile_id": pid, "tax_override": override, "taxable_value": taxable, "tax_rate": rate,
            "tax_amount": tax, "tax_split": prof["split"]}


# --------------------------------------------------------- expense cats
def list_expense_categories(conn):
    return rows(conn.execute("SELECT * FROM expense_categories ORDER BY name"))


def save_expense_category(conn, data):
    name = v.s(data, "name", 80, True, "Category name")
    active = 0 if data.get("active") in (False, 0, "0") else 1
    with tx(conn):
        conn.execute("INSERT INTO expense_categories(name, active) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET active = excluded.active",
                     (name, active))
        audit(conn, "expense_category", name, "save", None, {"name": name, "active": active})
    return name


# --------------------------------------------------------------- bookings
def _norm_ticket(t):
    if t is None:
        return None
    t = str(t).strip().upper().replace(" ", "")
    if not t:
        return None
    if len(t) > 30:
        raise ValidationError("Ticket number is too long", "passengers")
    return t


def _passengers(data):
    pax = data.get("passengers") or []
    if not isinstance(pax, list):
        raise ValidationError("Passengers must be a list", "passengers")
    out = []
    for idx, p in enumerate(pax):
        if not isinstance(p, dict):
            continue
        name = (str(p.get("name") or "")).strip()
        if not name and not p.get("ticket_number"):
            continue
        if not name:
            raise ValidationError("Passenger %d needs a name" % (idx + 1), "passengers")
        if len(name) > 150:
            raise ValidationError("Passenger name is too long", "passengers")
        pt = p.get("pax_type") or "ADT"
        if pt not in ("ADT", "CHD", "INF"):
            raise ValidationError("Passenger type must be ADT, CHD or INF", "passengers")
        out.append({"seq": len(out), "title": v.s(p, "title", 10), "name": name, "pax_type": pt,
                    "ticket_number": _norm_ticket(p.get("ticket_number")),
                    "passport_no": v.norm_code(v.s(p, "passport_no", 20), 20), "notes": v.s(p, "notes", 500)})
    if not out:
        raise ValidationError("Add at least one passenger", "passengers")
    tickets = [p["ticket_number"] for p in out if p["ticket_number"]]
    if len(tickets) != len(set(tickets)):
        raise ValidationError("The same ticket number is entered twice in this booking", "passengers")
    return out


def _segments(data):
    segs = data.get("segments") or []
    if not isinstance(segs, list):
        raise ValidationError("Flight segments must be a list", "segments")
    out = []
    for idx, sg in enumerate(segs):
        if not isinstance(sg, dict):
            continue
        if not any(str(sg.get(k) or "").strip() for k in ("airline", "flight_no", "origin", "destination", "dep_at")):
            continue
        r = {
            "seq": len(out),
            "direction": v.choice(sg, "direction", ("outbound", "return", "other"), "outbound"),
            "airline": v.s(sg, "airline", 80),
            "flight_no": v.norm_code(v.s(sg, "flight_no", 12), 12),
            "origin": v.norm_code(v.s(sg, "origin", 40), 40),
            "destination": v.norm_code(v.s(sg, "destination", 40), 40),
            "dep_at": v.dt(sg, "dep_at", "Departure (segment %d)" % (idx + 1)),
            "arr_at": v.dt(sg, "arr_at", "Arrival (segment %d)" % (idx + 1)),
            "cabin": v.s(sg, "cabin", 30),
            "baggage": v.s(sg, "baggage", 60),
            "airline_pnr": v.norm_code(v.s(sg, "airline_pnr", 20), 20),
        }
        if r["dep_at"] and r["arr_at"] and r["arr_at"] < r["dep_at"]:
            raise ValidationError("Segment %d arrives before it departs" % (idx + 1), "segments")
        out.append(r)
    return out


def _has_financial_activity(conn, bid):
    return conn.execute(
        "SELECT (SELECT COUNT(*) FROM allocations WHERE booking_id = ?) + "
        "(SELECT COUNT(*) FROM booking_adjustments WHERE booking_id = ?) + "
        "(SELECT COUNT(*) FROM invoices WHERE booking_id = ? AND status = 'issued') + "
        "(SELECT COUNT(*) FROM transactions WHERE booking_id = ?)", (bid, bid, bid, bid)).fetchone()[0] > 0


def find_duplicate_pnr(conn, pnr, exclude_id=None):
    if not pnr:
        return []
    return rows(conn.execute(
        "SELECT b.id, b.ref, b.status, b.booking_date, c.name AS customer_name FROM bookings b "
        "JOIN customers c ON c.id = b.customer_id WHERE b.pnr = ? COLLATE NOCASE AND b.id <> ?",
        (pnr, exclude_id or 0)))


def save_booking(conn, data, bid=None):
    with tx(conn):
        before = None
        if bid:
            before = one(conn.execute("SELECT * FROM bookings WHERE id = ?", (bid,)))
            if not before:
                raise NotFound("Booking not found")
        customer_id = _resolve_customer(conn, data)
        supplier_id = v.i(data, "supplier_id")
        if supplier_id and not conn.execute("SELECT 1 FROM suppliers WHERE id = ?", (supplier_id,)).fetchone():
            raise ValidationError("Selected supplier does not exist", "supplier_id")
        pnr = v.norm_code(v.s(data, "pnr", 20, label="PNR"), 20)
        status = v.choice(data, "status", BOOKING_STATUSES, "confirmed", "Booking status")
        service_type = v.choice(data, "service_type", SERVICE_TYPES, "flight", "Service type")
        trip_type = v.choice(data, "trip_type", ("one_way", "round_trip", "multi_city"), "one_way", "Trip type")
        booking_date = v.d(data, "booking_date", label="Booking date") or today()
        currency, rate = _currency_and_rate(conn, data)
        amounts = {
            "purchase_cost": parse_amount(data.get("purchase_cost"), "Purchase cost"),
            "selling_price": parse_amount(data.get("selling_price"), "Selling price"),
            "commission": parse_amount(data.get("commission"), "Commission"),
            "service_charge": parse_amount(data.get("service_charge"), "Service charge"),
            "other_income": parse_amount(data.get("other_income"), "Other income"),
        }
        tax = compute_tax(conn, data, amounts)
        pax = _passengers(data)
        segs = _segments(data)
        travel_date = v.d(data, "travel_date", label="Travel date")
        return_date = v.d(data, "return_date", label="Return date")
        if not travel_date and segs and segs[0]["dep_at"]:
            travel_date = segs[0]["dep_at"][:10]
        if not return_date:
            for sg in segs:
                if sg["direction"] == "return" and sg["dep_at"]:
                    return_date = sg["dep_at"][:10]
                    break
        if travel_date and return_date and return_date < travel_date:
            raise ValidationError("Return date is before the travel date", "return_date")

        # duplicates
        if pnr and not v.b(data, "force_duplicate"):
            dups = find_duplicate_pnr(conn, pnr, bid)
            if dups:
                raise Conflict("PNR %s already exists in %s. Open the existing booking, or confirm to save a separate booking."
                               % (pnr, ", ".join(d["ref"] for d in dups)), "duplicate_pnr", dups)
        for p in pax:
            if p["ticket_number"]:
                hit = conn.execute("SELECT b.ref FROM booking_passengers p JOIN bookings b ON b.id = p.booking_id "
                                   "WHERE p.ticket_number = ? AND p.booking_id <> ?", (p["ticket_number"], bid or 0)).fetchone()
                if hit:
                    raise Conflict("Ticket number %s is already recorded in booking %s" % (p["ticket_number"], hit["ref"]),
                                   "duplicate_ticket")

        committed = 1 if status in COMMIT_STATUSES else 0
        if before:
            if before["committed"] and status in ("enquiry", "on_hold"):
                if _has_financial_activity(conn, bid):
                    raise ValidationError("This booking already has payments, adjustments or an invoice. "
                                          "Cancel it with an adjustment instead of moving it back to %s." % status, "status")
                committed = 0
            elif before["committed"]:
                committed = 1
            if before["committed"] and status in ("cancelled", "refunded") and before["status"] not in ("cancelled", "refunded"):
                raise ValidationError("Use 'Cancel / Refund' on the booking so customer and supplier credits are recorded.", "status")
            # pricing locked by an issued invoice
            new_vals = dict(amounts, currency=currency, exchange_rate=rate, **tax)
            changed = [f for f in PRICING_FIELDS if str(before.get(f)) != str(new_vals.get(f))]
            if changed and conn.execute("SELECT 1 FROM invoices WHERE booking_id = ? AND doc_type = 'invoice' AND status = 'issued'", (bid,)).fetchone():
                raise ValidationError("An invoice has been issued for this booking. Cancel that invoice before changing prices, "
                                      "or record a reissue/adjustment.", "selling_price")
            if before["committed"] and changed and currency != before["currency"] and _has_financial_activity(conn, bid):
                raise ValidationError("Currency cannot be changed after payments are recorded against this booking", "currency")
        else:
            if status in ("cancelled", "refunded"):
                committed = 0

        ts = now_iso()
        fields = {
            "pnr": pnr, "customer_id": customer_id, "supplier_id": supplier_id, "service_type": service_type,
            "booking_date": booking_date, "travel_date": travel_date, "return_date": return_date, "trip_type": trip_type,
            "status": status, "committed": committed, "cabin": v.s(data, "cabin", 30), "baggage": v.s(data, "baggage", 60),
            "notes": v.s(data, "notes", 8000), "currency": currency, "exchange_rate": rate,
            "rate_date": v.d(data, "rate_date", label="Rate date") or (booking_date if currency != get_setting(conn, "base_currency") else None),
            "place_of_supply": v.s(data, "place_of_supply", 60),
        }
        fields.update(amounts)
        fields.update(tax)
        if before:
            conn.execute("UPDATE bookings SET %s, updated_at = ? WHERE id = ?" % ", ".join("%s = ?" % k for k in fields),
                         list(fields.values()) + [ts, bid])
            conn.execute("DELETE FROM booking_passengers WHERE booking_id = ?", (bid,))
            conn.execute("DELETE FROM booking_segments WHERE booking_id = ?", (bid,))
        else:
            yy = booking_date[2:4]
            n = next_counter(conn, "booking-" + yy)
            ref = "%s%s-%05d" % (get_setting(conn, "booking_prefix", "BK-") or "BK-", yy, n)
            fields["ref"] = ref
            cur = conn.execute("INSERT INTO bookings(%s, created_at, updated_at) VALUES (%s, ?, ?)" % (
                ", ".join(fields), ", ".join("?" * len(fields))), list(fields.values()) + [ts, ts])
            bid = cur.lastrowid
        for p in pax:
            conn.execute("INSERT INTO booking_passengers(booking_id, seq, title, name, pax_type, ticket_number, passport_no, notes) "
                         "VALUES (?,?,?,?,?,?,?,?)", (bid, p["seq"], p["title"], p["name"], p["pax_type"], p["ticket_number"],
                                                     p["passport_no"], p["notes"]))
        for sg in segs:
            conn.execute("INSERT INTO booking_segments(booking_id, seq, direction, airline, flight_no, origin, destination, dep_at, arr_at, cabin, baggage, airline_pnr) "
                         "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (bid, sg["seq"], sg["direction"], sg["airline"], sg["flight_no"], sg["origin"],
                                                             sg["destination"], sg["dep_at"], sg["arr_at"], sg["cabin"], sg["baggage"], sg["airline_pnr"]))
        after = dict(fields, passengers=pax, segments=segs)
        if before:
            changes = [k for k in fields if str(before.get(k)) != str(fields[k])]
            history(conn, bid, "updated", ("Changed: " + ", ".join(changes)) if changes else "Details updated")
            if before["status"] != status:
                history(conn, bid, "status", "%s -> %s" % (before["status"], status))
            audit(conn, "booking", bid, "update", before, after)
        else:
            history(conn, bid, "created", "Booking %s created with status %s" % (fields["ref"], status))
            audit(conn, "booking", bid, "create", None, after)
    return bid


def set_booking_status(conn, bid, status):
    if status not in BOOKING_STATUSES:
        raise ValidationError("Invalid status", "status")
    with tx(conn):
        b = one(conn.execute("SELECT * FROM bookings WHERE id = ?", (bid,)))
        if not b:
            raise NotFound("Booking not found")
        if b["status"] == status:
            return bid
        committed = b["committed"]
        if status in COMMIT_STATUSES:
            committed = 1
        elif status in ("enquiry", "on_hold"):
            if b["committed"] and _has_financial_activity(conn, bid):
                raise ValidationError("This booking has financial activity; cancel it with an adjustment instead.", "status")
            committed = 0
        elif status in ("cancelled", "refunded") and b["committed"] and b["status"] not in ("cancelled", "refunded"):
            raise ValidationError("Use 'Cancel / Refund' so credits and charges are recorded.", "status")
        conn.execute("UPDATE bookings SET status = ?, committed = ?, updated_at = ? WHERE id = ?", (status, committed, now_iso(), bid))
        history(conn, bid, "status", "%s -> %s" % (b["status"], status))
        audit(conn, "booking", bid, "status", {"status": b["status"], "committed": b["committed"]}, {"status": status, "committed": committed})
    return bid


def get_booking(conn, bid):
    L = Ledger(conn)
    b = L.bookings.get(int(bid))
    if not b:
        raise NotFound("Booking not found")
    row = dict(b)
    calc = row.pop("calc")
    txns = []
    for t in L.transactions:
        allocs = [a for a in L.allocations.get(t["id"], []) if a["booking_id"] == b["id"]]
        if allocs or t["booking_id"] == b["id"]:
            r = txn_row(L, t)
            r["allocated_here"] = sum(a["amount"] for a in allocs)
            txns.append(r)
    txns.sort(key=lambda r: (r["txn_date"], r["id"]))
    docs = rows(conn.execute("SELECT id, original_name, mime, size, label, uploaded_at FROM documents WHERE booking_id = ? AND deleted = 0 ORDER BY id", (b["id"],)))
    invoices = rows(conn.execute("SELECT id, number, doc_type, issue_date, total, currency, status, adjustment_id FROM invoices WHERE booking_id = ? ORDER BY id", (b["id"],)))
    hist = rows(conn.execute("SELECT * FROM booking_history WHERE booking_id = ? ORDER BY id DESC", (b["id"],)))
    tax_profile = one(conn.execute("SELECT * FROM tax_profiles WHERE id = ?", (b["tax_profile_id"],))) if b["tax_profile_id"] else None
    return {
        "booking": row, "calc": calc, "passengers": L.passengers.get(b["id"], []), "segments": L.segments.get(b["id"], []),
        "adjustments": L.adjustments.get(b["id"], []), "transactions": txns, "documents": docs, "invoices": invoices,
        "history": hist, "customer": L.customers.get(b["customer_id"]), "supplier": L.suppliers.get(b["supplier_id"]) if b["supplier_id"] else None,
        "tax_profile": tax_profile, "duplicates": find_duplicate_pnr(conn, b["pnr"], b["id"]),
        "base_currency": L.base,
    }


def search_bookings(conn, params):
    """PNR search: matches saved bookings only (no live airline/GDS lookup)."""
    L = Ledger(conn)
    q = (params.get("q") or "").strip().lower()
    airline = (params.get("airline") or "").strip().lower()
    origin = (params.get("origin") or "").strip().upper()
    dest = (params.get("destination") or "").strip().upper()
    tdate = (params.get("travel_date") or "").strip()
    frm = params.get("from")
    to = params.get("to")
    status = params.get("status")
    payment = params.get("payment_status")
    customer_id = params.get("customer_id")
    supplier_id = params.get("supplier_id")
    out = []
    for b in L.bookings.values():
        pax = L.passengers.get(b["id"], [])
        segs = L.segments.get(b["id"], [])
        if q:
            qd = "".join(ch for ch in q if ch.isdigit())
            hay = [b["ref"].lower(), (b["pnr"] or "").lower(), b["customer_name"].lower()]
            hay += [p["name"].lower() for p in pax]
            hay += [(p["ticket_number"] or "").lower() for p in pax]
            hay += [(s.get("airline_pnr") or "").lower() for s in segs]
            match = any(q in h for h in hay if h)
            if not match and qd and len(qd) >= 4:
                phone = "".join(ch for ch in (b["customer_phone"] or "") if ch.isdigit())
                match = qd in phone
            if not match:
                continue
        if airline and not any(airline in (s.get("airline") or "").lower() or airline in (s.get("flight_no") or "").lower() for s in segs):
            continue
        if origin and not any(origin == (s.get("origin") or "") for s in segs):
            continue
        if dest and not any(dest == (s.get("destination") or "") for s in segs):
            continue
        if tdate and not (b["travel_date"] == tdate or any((s.get("dep_at") or "")[:10] == tdate for s in segs)):
            continue
        if frm and b["booking_date"] < frm:
            continue
        if to and b["booking_date"] > to:
            continue
        if status and b["status"] != status:
            continue
        if payment and b["calc"]["payment_status"] != payment:
            continue
        if customer_id and str(b["customer_id"]) != str(customer_id):
            continue
        if supplier_id and str(b["supplier_id"] or "") != str(supplier_id):
            continue
        r = L.booking_row(b)
        r["passenger_names"] = [p["name"] for p in pax]
        r["tickets"] = [p["ticket_number"] for p in pax if p["ticket_number"]]
        r["exact_pnr"] = bool(q) and (b["pnr"] or "").lower() == q
        out.append(r)
    out.sort(key=lambda r: (not r["exact_pnr"], r["booking_date"], r["id"]), reverse=False)
    out.sort(key=lambda r: (0 if r["exact_pnr"] else 1, -(r["id"])))
    return out


# ------------------------------------------------------------ adjustments
ADJ_KINDS = ("cancellation", "refund", "reissue", "date_change", "other")


def create_adjustment(conn, bid, data):
    idem = v.s(data, "idem_key", 80)
    with tx(conn):
        if idem:
            hit = conn.execute("SELECT id FROM booking_adjustments WHERE idem_key = ?", (idem,)).fetchone()
            if hit:
                return hit["id"]
        b = one(conn.execute("SELECT * FROM bookings WHERE id = ?", (bid,)))
        if not b:
            raise NotFound("Booking not found")
        if not b["committed"]:
            raise ValidationError("This booking was never confirmed, so there is nothing to credit. Change its status instead.")
        kind = v.choice(data, "kind", ADJ_KINDS, None, "Adjustment type")
        adj_date = v.d(data, "adj_date", label="Adjustment date") or today()
        p = {f: parse_amount(data.get(f), f.replace("_", " ").capitalize()) for f in (
            "customer_credit", "customer_credit_tax", "supplier_credit", "commission_reversal",
            "extra_customer_charge", "extra_supplier_charge")}
        if not any(p.values()):
            raise ValidationError("Enter at least one amount for the adjustment")
        if p["customer_credit_tax"] > p["customer_credit"]:
            raise ValidationError("Tax portion cannot exceed the customer credit", "customer_credit_tax")
        # limits against what has been billed so far
        L = Ledger(conn)
        calc = L.bookings[b["id"]]["calc"]
        rate = b["exchange_rate"]
        cust_net = calc["customer_payable"] - calc["customer_credits"]
        if convert(p["customer_credit"], rate) > cust_net + convert(p["extra_customer_charge"], rate):
            raise ValidationError("Customer credit is more than the amount billed to the customer", "customer_credit")
        sup_net = calc["supplier_payable"] - (calc["supplier_credits"] - calc["commission_net"])
        if convert(p["supplier_credit"], rate) > sup_net + convert(p["extra_supplier_charge"], rate):
            raise ValidationError("Supplier credit is more than the supplier cost", "supplier_credit")
        if convert(p["commission_reversal"], rate) > calc["commission_net"]:
            raise ValidationError("Commission reversal is more than the commission earned", "commission_reversal")
        new_status = data.get("new_status")
        if new_status and new_status not in BOOKING_STATUSES:
            raise ValidationError("Invalid status", "new_status")
        n = next_counter(conn, "adjustment")
        adj_no = "ADJ-%05d" % n
        ts = now_iso()
        cur = conn.execute(
            "INSERT INTO booking_adjustments(adj_no, booking_id, adj_date, kind, sign, exchange_rate, customer_credit, customer_credit_tax, "
            "supplier_credit, commission_reversal, extra_customer_charge, extra_supplier_charge, notes, idem_key, created_at) "
            "VALUES (?,?,?,?,1,?,?,?,?,?,?,?,?,?,?)",
            (adj_no, bid, adj_date, kind, rate, p["customer_credit"], p["customer_credit_tax"], p["supplier_credit"],
             p["commission_reversal"], p["extra_customer_charge"], p["extra_supplier_charge"], v.s(data, "notes", 2000), idem, ts))
        aid = cur.lastrowid
        history(conn, bid, "adjustment", "%s %s recorded" % (adj_no, kind.replace("_", " ")))
        audit(conn, "adjustment", aid, "create", None, dict(p, kind=kind, adj_date=adj_date, booking_id=bid))
        if new_status and new_status != b["status"]:
            conn.execute("UPDATE bookings SET status = ?, updated_at = ? WHERE id = ?", (new_status, ts, bid))
            history(conn, bid, "status", "%s -> %s" % (b["status"], new_status))
            audit(conn, "booking", bid, "status", {"status": b["status"]}, {"status": new_status})
    return aid


def reverse_adjustment(conn, aid, data):
    with tx(conn):
        a = one(conn.execute("SELECT * FROM booking_adjustments WHERE id = ?", (aid,)))
        if not a:
            raise NotFound("Adjustment not found")
        if a["reversal_of"] or a["sign"] == -1:
            raise ValidationError("A reversal cannot itself be reversed")
        if a["reversed_by"]:
            raise ValidationError("This adjustment has already been reversed")
        if conn.execute("SELECT 1 FROM invoices WHERE adjustment_id = ? AND status = 'issued'", (aid,)).fetchone():
            raise ValidationError("Cancel the credit note issued for this adjustment first")
        n = next_counter(conn, "adjustment")
        ts = now_iso()
        reason = v.s(data, "reason", 500) or "Reversal"
        cur = conn.execute(
            "INSERT INTO booking_adjustments(adj_no, booking_id, adj_date, kind, sign, exchange_rate, customer_credit, customer_credit_tax, "
            "supplier_credit, commission_reversal, extra_customer_charge, extra_supplier_charge, notes, reversal_of, created_at) "
            "VALUES (?,?,?,?,-1,?,?,?,?,?,?,?,?,?,?)",
            ("ADJ-%05d" % n, a["booking_id"], v.d(data, "date") or today(), a["kind"], a["exchange_rate"], a["customer_credit"],
             a["customer_credit_tax"], a["supplier_credit"], a["commission_reversal"], a["extra_customer_charge"],
             a["extra_supplier_charge"], "Reversal of %s: %s" % (a["adj_no"], reason), aid, ts))
        rid = cur.lastrowid
        conn.execute("UPDATE booking_adjustments SET reversed_by = ? WHERE id = ?", (rid, aid))
        history(conn, a["booking_id"], "adjustment", "%s reversed (%s)" % (a["adj_no"], reason))
        audit(conn, "adjustment", aid, "reverse", a, {"reversal_id": rid, "reason": reason})
    return rid


# ------------------------------------------------------------ transactions
def _validate_allocations(conn, kind, party_id, base_amount, allocs, currency_ok=True, txn_id=None):
    if not allocs:
        return []
    if not isinstance(allocs, list):
        raise ValidationError("Allocations must be a list", "allocations")
    party_col = "customer_id" if PARTY_KINDS[kind] == "customer" else "supplier_id"
    out = []
    seen = set()
    total = 0
    for a in allocs:
        if not isinstance(a, dict):
            continue
        bid = v.i(a, "booking_id", True, "Booking")
        amt = parse_amount(a.get("amount"), "Allocated amount")
        if amt == 0:
            continue
        if bid in seen:
            raise ValidationError("A booking is allocated twice", "allocations")
        seen.add(bid)
        b = conn.execute("SELECT id, ref, %s AS party FROM bookings WHERE id = ?" % party_col, (bid,)).fetchone()
        if not b:
            raise ValidationError("Allocated booking does not exist", "allocations")
        if b["party"] != party_id:
            raise ValidationError("Booking %s belongs to a different %s" % (b["ref"], PARTY_KINDS[kind]), "allocations")
        total += amt
        out.append({"booking_id": bid, "amount": amt})
    if total > base_amount:
        raise ValidationError("Allocated total %s is more than the payment amount %s" % (_fmt_base(total), _fmt_base(base_amount)), "allocations")
    _check_allocation_caps(conn, kind, out, txn_id)
    return out


def _check_refund_backed(conn, kind, party_id, base_amount, allocs):
    """Money paid back to a customer (or received back from a supplier) must match an amount that is actually due back:
    a cancellation / refund credit, an overpayment or an unused advance. A refund with nothing due back would leave
    profit unchanged and make the customer appear to owe the money again - the original refund bug."""
    if kind not in ("customer_refund", "supplier_receipt"):
        return
    L = Ledger(conn)
    customer = kind == "customer_refund"
    bal = (L.customer_balances() if customer else L.supplier_balances()).get(party_id)
    if bal is None:
        return
    # the part not allocated to a booking can only come out of an unused advance / wallet balance
    free = base_amount - sum(a["amount"] for a in allocs)
    available = max(bal["unallocated"], 0)
    if free > available:
        who = "the customer" if customer else "you by the supplier"
        raise Conflict("Nothing is due back to %s for this amount (unused advance: %s %s). To refund a booking, open it and use "
                       "'Refund / cancel' - that records the refund credit, the airline / supplier refund and any fee you keep, "
                       "so the booking profit is adjusted, and then pays the refund." % (who, L.base, _fmt_base(available)),
                       "refund_not_due", {"due_back": available})
    key = "customer_outstanding" if customer else "supplier_outstanding"
    for a in allocs:
        b = L.bookings.get(a["booking_id"])
        if not b:
            continue
        due = -b["calc"][key]
        if a["amount"] > max(due, 0):
            raise Conflict("Booking %s has only %s %s due back. Use 'Refund / cancel' on the booking to record the refund first."
                           % (b["ref"], L.base, _fmt_base(max(due, 0))), "refund_not_due", {"booking_id": b["id"], "due_back": max(due, 0)})


def _check_allocation_caps(conn, kind, allocs, txn_id=None):
    """A receipt or payment may not be allocated beyond a confirmed booking's outstanding balance.
    Uncommitted bookings (enquiry / on hold) may still take advances. When re-allocating an existing
    transaction, its own current allocations are added back first."""
    if kind not in ("customer_receipt", "supplier_payment") or not allocs:
        return
    L = Ledger(conn)
    key = "customer_outstanding" if kind == "customer_receipt" else "supplier_outstanding"
    for a in allocs:
        b = L.bookings.get(a["booking_id"])
        if not b or not b["committed"]:
            continue
        due = b["calc"][key]
        if txn_id:
            due += sum(x["amount"] for x in L.allocations.get(txn_id, []) if x["booking_id"] == a["booking_id"])
        if a["amount"] > max(due, 0):
            due_txt = "%s %s" % (L.base, _fmt_base(max(due, 0)))
            raise ValidationError("Booking %s has only %s outstanding%s. Reduce the allocation to %s or less; any extra stays unallocated."
                                  % (b["ref"], due_txt, " to the supplier" if kind == "supplier_payment" else "", due_txt), "allocations")


def create_transaction(conn, data):
    kind = v.choice(data, "kind", TXN_KINDS, None, "Transaction type")
    idem = v.s(data, "idem_key", 80)
    if idem:
        existing = conn.execute("SELECT id FROM transactions WHERE idem_key = ?", (idem,)).fetchone()
        if existing:
            return existing["id"], True
    with tx(conn):
        if idem:
            existing = conn.execute("SELECT id FROM transactions WHERE idem_key = ?", (idem,)).fetchone()
            if existing:
                return existing["id"], True
        txn_date = v.d(data, "txn_date", label="Date") or today()
        account_id = v.i(data, "account_id", True, "Account")
        acc = one(conn.execute("SELECT * FROM accounts WHERE id = ?", (account_id,)))
        if not acc:
            raise ValidationError("Selected account does not exist", "account_id")
        if not acc["active"]:
            raise ValidationError("Selected account is inactive", "account_id")
        amount = parse_amount(data.get("amount"), "Amount", required=True, allow_zero=False)
        currency = v.norm_code(data.get("currency"), 3) or acc["currency"]
        if currency != acc["currency"]:
            raise ValidationError("Account %s holds %s. Record this in %s or use an account in %s."
                                  % (acc["name"], acc["currency"], acc["currency"], currency), "currency")
        _, rate = _currency_and_rate(conn, dict(data, currency=currency))
        base_amount = convert(amount, rate)
        method = v.choice(data, "method", METHODS, "cash", "Payment method")
        reference = v.s(data, "reference", 120)
        category = v.s(data, "category", 80) or DEFAULT_CATEGORY.get(kind)
        if not category:
            raise ValidationError("Category is required", "category")
        notes = v.s(data, "notes", 2000)
        payee = v.s(data, "payee", 150)
        customer_id = v.i(data, "customer_id")
        supplier_id = v.i(data, "supplier_id")
        booking_id = v.i(data, "booking_id")
        to_account_id = None
        to_amount = None
        is_direct = 0
        booking = None
        if booking_id:
            booking = one(conn.execute("SELECT * FROM bookings WHERE id = ?", (booking_id,)))
            if not booking:
                raise ValidationError("Linked booking does not exist", "booking_id")
        party = PARTY_KINDS.get(kind)
        if party == "customer":
            if not customer_id and booking:
                customer_id = booking["customer_id"]
            if not customer_id:
                raise ValidationError("Select the customer", "customer_id")
            if not conn.execute("SELECT 1 FROM customers WHERE id = ?", (customer_id,)).fetchone():
                raise ValidationError("Selected customer does not exist", "customer_id")
            supplier_id = None
        elif party == "supplier":
            if not supplier_id and booking:
                supplier_id = booking["supplier_id"]
            if not supplier_id:
                raise ValidationError("Select the supplier", "supplier_id")
            if not conn.execute("SELECT 1 FROM suppliers WHERE id = ?", (supplier_id,)).fetchone():
                raise ValidationError("Selected supplier does not exist", "supplier_id")
            customer_id = None
        elif kind == "transfer":
            to_account_id = v.i(data, "to_account_id", True, "Destination account")
            if to_account_id == account_id:
                raise ValidationError("Choose two different accounts for a transfer", "to_account_id")
            to_acc = one(conn.execute("SELECT * FROM accounts WHERE id = ?", (to_account_id,)))
            if not to_acc:
                raise ValidationError("Destination account does not exist", "to_account_id")
            if to_acc["currency"] == acc["currency"]:
                to_amount = amount
            else:
                to_amount = parse_amount(data.get("to_amount"), "Amount received in %s" % to_acc["currency"], required=True, allow_zero=False)
            customer_id = supplier_id = booking_id = None
            category = "Transfer"
        elif kind == "expense":
            if booking:
                is_direct = 1
            if supplier_id and not conn.execute("SELECT 1 FROM suppliers WHERE id = ?", (supplier_id,)).fetchone():
                raise ValidationError("Selected supplier does not exist", "supplier_id")
            customer_id = None
        elif kind == "other_income":
            customer_id = supplier_id = None
            booking_id = None
        elif kind in LOAN_KINDS:
            customer_id = supplier_id = booking_id = None
        debt_id = v.i(data, "debt_id") if kind in LOAN_KINDS or kind == "expense" else None
        if kind in LOAN_KINDS and not debt_id:
            raise ValidationError("Select the loan / debt", "debt_id")
        if debt_id:
            debt = one(conn.execute("SELECT * FROM debts WHERE id = ?", (debt_id,)))
            if not debt:
                raise ValidationError("Selected debt does not exist", "debt_id")
            if debt["status"] != "open" and not data.get("_replacing"):
                raise ValidationError("This debt is closed. Reopen it to record more entries.", "debt_id")

        allocs = []
        if party:
            party_id = customer_id if party == "customer" else supplier_id
            if booking and (booking["customer_id"] if party == "customer" else booking["supplier_id"]) != party_id:
                raise ValidationError("Linked booking belongs to a different %s" % party, "booking_id")
            if "allocations" in data and data["allocations"]:
                allocs = _validate_allocations(conn, kind, party_id, base_amount, data["allocations"])
            elif booking:
                allocs = [{"booking_id": booking_id, "amount": base_amount}]
            if len(allocs) == 1 and not booking_id:
                booking_id = allocs[0]["booking_id"]
            _check_refund_backed(conn, kind, party_id, base_amount, allocs)

        # possible duplicate entry protection
        if not v.b(data, "force_duplicate"):
            q = ("SELECT txn_no FROM transactions WHERE kind = ? AND txn_date = ? AND amount = ? AND account_id = ? AND sign = 1 "
                 "AND reversed_by IS NULL AND COALESCE(reference,'') = COALESCE(?, '') AND COALESCE(booking_id,0) = COALESCE(?,0) "
                 "AND COALESCE(customer_id,0) = ? AND COALESCE(supplier_id,0) = ?")
            dup = conn.execute(q, (kind, txn_date, amount, account_id, reference, booking_id, customer_id or 0, supplier_id or 0)).fetchone()
            if dup:
                raise Conflict("This looks like a duplicate of %s (same type, date, amount, account and reference). "
                               "Confirm to record it anyway." % dup["txn_no"], "possible_duplicate", {"txn_no": dup["txn_no"]})

        n = next_counter(conn, "txn-" + TXN_PREFIX[kind])
        txn_no = "%s-%06d" % (_txn_prefix(conn, kind), n)
        ts = now_iso()
        cur = conn.execute(
            "INSERT INTO transactions(txn_no, uid, idem_key, txn_date, kind, sign, amount, currency, exchange_rate, rate_date, base_amount, "
            "account_id, to_account_id, to_amount, method, reference, category, customer_id, supplier_id, booking_id, is_direct, payee, notes, "
            "debt_id, created_at, updated_at) VALUES (?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (txn_no, str(uuid.uuid4()), idem, txn_date, kind, amount, currency, rate,
             v.d(data, "rate_date") or (txn_date if rate != "1" else None), base_amount, account_id, to_account_id, to_amount,
             method, reference, category, customer_id, supplier_id, booking_id, is_direct, payee, notes, debt_id, ts, ts))
        tid = cur.lastrowid
        if debt_id:
            from . import debts
            debts.after_change(conn, debt_id)
        for a in allocs:
            conn.execute("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", (tid, a["booking_id"], a["amount"], ts))
            history(conn, a["booking_id"], "payment", "%s %s %s allocated" % (txn_no, KIND_LABELS[kind].lower(), _fmt_base(a["amount"])))
        if kind == "expense" and is_direct:
            history(conn, booking_id, "expense", "%s direct expense recorded" % txn_no)
        audit(conn, "transaction", tid, "create", None, {
            "txn_no": txn_no, "kind": kind, "date": txn_date, "amount": amount, "currency": currency, "rate": rate,
            "base_amount": base_amount, "account_id": account_id, "method": method, "reference": reference,
            "customer_id": customer_id, "supplier_id": supplier_id, "booking_id": booking_id, "debt_id": debt_id, "allocations": allocs})
    return tid, False


def _txn_prefix(conn, kind):
    if kind == "customer_receipt":
        return (get_setting(conn, "receipt_prefix", "") or TXN_PREFIX[kind]).rstrip("-")
    return TXN_PREFIX[kind]


def _fmt_base(minor):
    from .money import fmt
    return fmt(minor)


def reverse_transaction(conn, tid, data):
    with tx(conn):
        t = one(conn.execute("SELECT * FROM transactions WHERE id = ?", (tid,)))
        if not t:
            raise NotFound("Transaction not found")
        if t["reversal_of"] or t["sign"] == -1:
            raise ValidationError("A reversal cannot itself be reversed")
        if t["reversed_by"]:
            raise ValidationError("This transaction has already been reversed")
        reason = v.s(data, "reason", 500, True, "Reason")
        rdate = v.d(data, "date") or today()
        n = next_counter(conn, "txn-" + TXN_PREFIX[t["kind"]])
        txn_no = "%s-%06d" % (_txn_prefix(conn, t["kind"]), n)
        ts = now_iso()
        cur = conn.execute(
            "INSERT INTO transactions(txn_no, uid, idem_key, txn_date, kind, sign, amount, currency, exchange_rate, rate_date, base_amount, "
            "account_id, to_account_id, to_amount, method, reference, category, customer_id, supplier_id, booking_id, is_direct, payee, notes, "
            "debt_id, reversal_of, created_at, updated_at) VALUES (?,?,NULL,?,?,-1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (txn_no, str(uuid.uuid4()), rdate, t["kind"], t["amount"], t["currency"], t["exchange_rate"], t["rate_date"],
             t["base_amount"], t["account_id"], t["to_account_id"], t["to_amount"], t["method"], t["reference"], t["category"],
             t["customer_id"], t["supplier_id"], t["booking_id"], t["is_direct"], t["payee"],
             "Reversal of %s: %s" % (t["txn_no"], reason), t.get("debt_id"), tid, ts, ts))
        rid = cur.lastrowid
        for a in rows(conn.execute("SELECT * FROM allocations WHERE txn_id = ?", (tid,))):
            conn.execute("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", (rid, a["booking_id"], a["amount"], ts))
            history(conn, a["booking_id"], "payment", "%s reversed by %s (%s)" % (t["txn_no"], txn_no, reason))
        conn.execute("UPDATE transactions SET reversed_by = ?, updated_at = ? WHERE id = ?", (rid, ts, tid))
        if t.get("debt_id") and not data.get("_defer_debt_check"):
            from . import debts
            debts.after_change(conn, t["debt_id"])
        audit(conn, "transaction", tid, "reverse", {"txn_no": t["txn_no"], "amount": t["amount"]}, {"reversal": txn_no, "reason": reason})
    return rid


META_FIELDS = ("reference", "notes", "payee", "method", "category")


def correct_transaction(conn, tid, data):
    """Edit a posted transaction. Descriptive fields are updated in place. A change to the amount, date, account,
    party or allocation reverses the original and posts the corrected entry - both in one database transaction,
    so balances, profit and the audit trail stay consistent and a failure changes nothing."""
    idem = v.s(data, "idem_key", 80)
    if idem:
        hit = conn.execute("SELECT id FROM transactions WHERE idem_key = ?", (idem,)).fetchone()
        if hit:
            return hit["id"], "replay"
    with tx(conn):
        t = one(conn.execute("SELECT * FROM transactions WHERE id = ?", (tid,)))
        if not t:
            raise NotFound("Transaction not found")
        if t["reversed_by"] or t["sign"] < 0:
            raise ValidationError("This entry has already been deleted / reversed, so it cannot be edited")
        if data.get("kind") and data["kind"] != t["kind"]:
            raise ValidationError("The type of an entry cannot be changed. Delete it and record a new one.")
        new = dict(data, kind=t["kind"])
        old_allocs = sorted((a["booking_id"], a["amount"]) for a in rows(conn.execute("SELECT booking_id, amount FROM allocations WHERE txn_id = ?", (tid,))))
        acc = one(conn.execute("SELECT * FROM accounts WHERE id = ?", (v.i(new, "account_id") or t["account_id"],)))
        amount = parse_amount(new.get("amount"), "Amount", required=True, allow_zero=False)
        _, rate = _currency_and_rate(conn, dict(new, currency=acc["currency"] if acc else t["currency"]))
        base = convert(amount, rate)
        new_allocs = None
        if "allocations" in new:
            new_allocs = sorted((int(a["booking_id"]), parse_amount(a.get("amount"), "Allocated amount"))
                                for a in (new.get("allocations") or []) if isinstance(a, dict) and parse_amount(a.get("amount"), "Allocated amount"))
        same_money = (amount == t["amount"] and (v.d(new, "txn_date") or t["txn_date"]) == t["txn_date"]
                      and (v.i(new, "account_id") or t["account_id"]) == t["account_id"] and base == t["base_amount"]
                      and (v.i(new, "to_account_id") or t["to_account_id"]) == t["to_account_id"]
                      and (t["kind"] != "transfer" or parse_amount(new.get("to_amount") or new.get("amount"), "Amount received") == (t["to_amount"] or t["amount"]))
                      and (v.i(new, "customer_id") or t["customer_id"]) == t["customer_id"]
                      and (v.i(new, "supplier_id") or t["supplier_id"]) == t["supplier_id"]
                      and (v.i(new, "debt_id") or t.get("debt_id")) == t.get("debt_id")
                      and (new_allocs is None or new_allocs == old_allocs))
        if same_money:
            update_transaction_meta(conn, tid, {k: new[k] for k in META_FIELDS if k in new})
            return tid, "updated"
        reason = v.s(new, "reason", 300) or "Edited"
        rid = reverse_transaction(conn, tid, {"reason": reason, "date": t["txn_date"], "_defer_debt_check": True})
        if t.get("debt_id"):
            new.setdefault("debt_id", t["debt_id"])
        new.update({"force_duplicate": True, "_replacing": tid})
        new.setdefault("booking_id", t["booking_id"] if t["is_direct"] else None)
        nid, _ = create_transaction(conn, new)
        conn.execute("UPDATE transactions SET notes = COALESCE(notes || char(10), '') || ? WHERE id = ?",
                     ("Corrected from %s" % t["txn_no"], nid))
        audit(conn, "transaction", tid, "edit", {"txn_no": t["txn_no"], "amount": t["amount"], "date": t["txn_date"]},
              {"replaced_by": nid, "reversal": rid, "reason": reason})
    return nid, "replaced"


def delete_booking(conn, bid):
    """Delete a booking that has no money, adjustments, invoices or documents. Bookings with financial history are
    kept for the ledger: cancel / refund them instead."""
    with tx(conn):
        b = one(conn.execute("SELECT * FROM bookings WHERE id = ?", (bid,)))
        if not b:
            raise NotFound("Booking not found")
        if _has_financial_activity(conn, bid) or conn.execute("SELECT 1 FROM invoices WHERE booking_id = ?", (bid,)).fetchone():
            raise ValidationError("%s has payments, refunds, adjustments or invoices, so it is kept for your accounts. "
                                  "Use 'Refund / cancel' to cancel it instead." % b["ref"])
        if conn.execute("SELECT 1 FROM documents WHERE booking_id = ? AND deleted = 0", (bid,)).fetchone():
            raise ValidationError("Remove the documents attached to %s first." % b["ref"])
        pax = rows(conn.execute("SELECT name, ticket_number FROM booking_passengers WHERE booking_id = ?", (bid,)))
        conn.execute("DELETE FROM booking_history WHERE booking_id = ?", (bid,))
        conn.execute("DELETE FROM documents WHERE booking_id = ? AND deleted = 1", (bid,))
        conn.execute("DELETE FROM bookings WHERE id = ?", (bid,))  # passengers and segments cascade
        audit(conn, "booking", bid, "delete", {"ref": b["ref"], "pnr": b["pnr"], "status": b["status"], "customer_id": b["customer_id"],
                                                "selling_price": b["selling_price"], "purchase_cost": b["purchase_cost"], "passengers": pax}, None)
    return {"deleted": True, "ref": b["ref"]}


def refund_booking(conn, bid, data):
    """One step refund / cancellation of a booking.

    Profit follows what the business actually keeps:
        new profit = old profit - (refund to customer, excl. tax) + (refund from supplier/airline) - (commission given back)
    The refund credit is posted as an adjustment (dated, so period reports move in the right month); the cash
    refund and any supplier refund already received are posted as transactions allocated to the booking.
    Everything is in one database transaction and keyed, so a repeated submission is recorded once."""
    idem = v.s(data, "idem_key", 80)
    with tx(conn):
        if idem:
            hit = conn.execute("SELECT id FROM booking_adjustments WHERE idem_key = ?", (idem,)).fetchone()
            if hit:
                return dict(_refund_summary(conn, bid), adjustment_id=hit["id"], idempotent_replay=True)
        b = one(conn.execute("SELECT * FROM bookings WHERE id = ?", (bid,)))
        if not b:
            raise NotFound("Booking not found")
        before = Ledger(conn).bookings[b["id"]]["calc"]
        cust = parse_amount(data.get("customer_refund"), "Refund to customer")
        cust_tax = parse_amount(data.get("customer_refund_tax"), "Tax part of the refund")
        sup = parse_amount(data.get("supplier_refund"), "Refund from supplier / airline")
        comm = parse_amount(data.get("commission_reversal"), "Commission given back")
        if not (cust or sup or comm):
            raise ValidationError("Enter the refund to the customer and / or the refund from the supplier", "customer_refund")
        date = v.d(data, "date", label="Refund date") or today()
        status = data.get("new_status") or ""
        kind = "cancellation" if status in ("cancelled", "refunded") else "refund"
        aid = create_adjustment(conn, bid, {"kind": kind, "adj_date": date, "customer_credit": data.get("customer_refund"),
                                            "customer_credit_tax": data.get("customer_refund_tax"), "supplier_credit": data.get("supplier_refund"),
                                            "commission_reversal": data.get("commission_reversal"), "new_status": status,
                                            "notes": v.s(data, "notes", 2000), "idem_key": idem})
        txns = {}
        base_cur = get_setting(conn, "base_currency", "INR")
        for key in ("pay_customer", "supplier_received"):
            sub = data.get(key) or {}
            if sub.get("account_id"):
                acc = one(conn.execute("SELECT name, currency FROM accounts WHERE id = ?", (v.i(sub, "account_id"),)))
                if acc and acc["currency"] != base_cur:
                    raise ValidationError("Use a %s account here, or record this refund separately from the Refunds page." % base_cur, key)
        pay = data.get("pay_customer") or {}
        if pay.get("account_id") and parse_amount(pay.get("amount"), "Refund paid now"):
            tid, _ = create_transaction(conn, {"kind": "customer_refund", "txn_date": date, "account_id": pay["account_id"],
                                               "amount": pay["amount"], "exchange_rate": pay.get("exchange_rate"), "method": pay.get("method") or "bank_transfer",
                                               "reference": pay.get("reference"), "customer_id": b["customer_id"], "force_duplicate": True,
                                               "idem_key": (idem + ":customer") if idem else None,
                                               "allocations": [{"booking_id": b["id"], "amount": pay["amount"]}],
                                               "notes": "Refund for %s" % b["ref"]})
            txns["customer_refund"] = tid
        got = data.get("supplier_received") or {}
        if got.get("account_id") and parse_amount(got.get("amount"), "Supplier refund received"):
            if not b["supplier_id"]:
                raise ValidationError("This booking has no supplier", "supplier_received")
            tid, _ = create_transaction(conn, {"kind": "supplier_receipt", "txn_date": date, "account_id": got["account_id"],
                                               "amount": got["amount"], "exchange_rate": got.get("exchange_rate"), "method": got.get("method") or "portal_wallet",
                                               "reference": got.get("reference"), "supplier_id": b["supplier_id"], "force_duplicate": True,
                                               "idem_key": (idem + ":supplier") if idem else None,
                                               "allocations": [{"booking_id": b["id"], "amount": got["amount"]}],
                                               "notes": "Supplier refund for %s" % b["ref"]})
            txns["supplier_receipt"] = tid
        audit(conn, "booking", bid, "refund", {"gross_profit": before["gross_profit"]},
              {"adjustment_id": aid, "customer_refund": cust, "supplier_refund": sup, "commission_reversal": comm, "transactions": txns})
    return dict(_refund_summary(conn, bid), adjustment_id=aid, transactions=txns, profit_before=before["gross_profit"], idempotent_replay=False)


def _refund_summary(conn, bid):
    c = Ledger(conn).bookings[int(bid)]["calc"]
    return {"gross_profit": c["gross_profit"], "customer_outstanding": c["customer_outstanding"],
            "supplier_outstanding": c["supplier_outstanding"], "payment_status": c["payment_status"]}


def correct_adjustment(conn, aid, data):
    """Edit an adjustment: reverse it and post the corrected one in a single transaction."""
    with tx(conn):
        a = one(conn.execute("SELECT * FROM booking_adjustments WHERE id = ?", (aid,)))
        if not a:
            raise NotFound("Adjustment not found")
        if a["reversed_by"] or a["sign"] < 0:
            raise ValidationError("This adjustment has already been reversed, so it cannot be edited")
        reverse_adjustment(conn, aid, {"reason": v.s(data, "reason", 300) or "Edited", "date": a["adj_date"]})
        nid = create_adjustment(conn, a["booking_id"], dict(data, kind=data.get("kind") or a["kind"]))
        audit(conn, "adjustment", aid, "edit", {"adj_no": a["adj_no"]}, {"replaced_by": nid})
    return nid


def update_transaction_meta(conn, tid, data):
    """Only descriptive fields can be edited; amounts require a reversal."""
    with tx(conn):
        t = one(conn.execute("SELECT * FROM transactions WHERE id = ?", (tid,)))
        if not t:
            raise NotFound("Transaction not found")
        upd = {}
        if "reference" in data:
            upd["reference"] = v.s(data, "reference", 120)
        if "notes" in data:
            upd["notes"] = v.s(data, "notes", 2000)
        if "payee" in data:
            upd["payee"] = v.s(data, "payee", 150)
        if "method" in data:
            upd["method"] = v.choice(data, "method", METHODS, t["method"])
        if "category" in data and t["kind"] in ("expense", "other_income"):
            upd["category"] = v.s(data, "category", 80, True, "Category")
        forbidden = [k for k in ("amount", "txn_date", "account_id", "currency", "exchange_rate", "kind") if k in data and str(data[k]) != str(t.get(k))]
        if forbidden:
            raise ValidationError("Amount, date, account and currency cannot be edited on a posted transaction. Reverse it and record it again.")
        if not upd:
            return tid
        upd["updated_at"] = now_iso()
        conn.execute("UPDATE transactions SET %s WHERE id = ?" % ", ".join("%s = ?" % k for k in upd), list(upd.values()) + [tid])
        audit(conn, "transaction", tid, "edit", {k: t.get(k) for k in upd if k != "updated_at"}, {k: v2 for k, v2 in upd.items() if k != "updated_at"})
    return tid


def set_allocations(conn, tid, data):
    with tx(conn):
        t = one(conn.execute("SELECT * FROM transactions WHERE id = ?", (tid,)))
        if not t:
            raise NotFound("Transaction not found")
        if t["kind"] not in PARTY_KINDS:
            raise ValidationError("Only receipts, payments and refunds can be allocated")
        if t["reversed_by"] or t["reversal_of"]:
            raise ValidationError("Reversed transactions cannot be re-allocated")
        party_id = t["customer_id"] if PARTY_KINDS[t["kind"]] == "customer" else t["supplier_id"]
        allocs = _validate_allocations(conn, t["kind"], party_id, t["base_amount"], data.get("allocations") or [], txn_id=tid)
        before = rows(conn.execute("SELECT booking_id, amount FROM allocations WHERE txn_id = ?", (tid,)))
        conn.execute("DELETE FROM allocations WHERE txn_id = ?", (tid,))
        ts = now_iso()
        for a in allocs:
            conn.execute("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", (tid, a["booking_id"], a["amount"], ts))
        changed = {a["booking_id"] for a in before} | {a["booking_id"] for a in allocs}
        for bid in changed:
            history(conn, bid, "allocation", "Allocation of %s updated" % t["txn_no"])
        audit(conn, "allocation", tid, "update", before, allocs)
    return tid


def list_transactions(conn, params):
    L = Ledger(conn)
    kind = params.get("kind")
    kinds = set(kind.split(",")) if kind else None
    frm, to = params.get("from"), params.get("to")
    q = (params.get("q") or "").strip().lower()
    out = []
    for t in L.transactions:
        if kinds and t["kind"] not in kinds:
            continue
        if frm and t["txn_date"] < frm or to and t["txn_date"] > to:
            continue
        if params.get("category") and t["category"] != params["category"]:
            continue
        if params.get("method") and t["method"] != params["method"]:
            continue
        if params.get("direct") == "0" and t["is_direct"]:
            continue
        if not L.txn_matches(t, {k: params.get(k) for k in ("customer_id", "supplier_id", "currency", "account_id")}):
            continue
        r = txn_row(L, t)
        if q and not any(q in str(r.get(k) or "").lower() for k in ("txn_no", "reference", "notes", "customer_name", "supplier_name", "booking_ref", "category", "payee")):
            continue
        out.append(r)
    out.sort(key=lambda r: (r["txn_date"], r["id"]), reverse=True)
    return out


def get_transaction(conn, tid):
    L = Ledger(conn)
    t = L.txn_by_id.get(int(tid))
    if not t:
        raise NotFound("Transaction not found")
    r = txn_row(L, t)
    r["documents"] = rows(conn.execute("SELECT id, original_name, mime, size, label, uploaded_at FROM documents WHERE transaction_id = ? AND deleted = 0", (t["id"],)))
    r["audit"] = list_audit(conn, "transaction", t["id"])
    # open bookings for allocation
    if t["kind"] in PARTY_KINDS:
        party = PARTY_KINDS[t["kind"]]
        opts = []
        for b in L.bookings.values():
            if party == "customer" and b["customer_id"] == t["customer_id"] or party == "supplier" and b["supplier_id"] == t["supplier_id"]:
                opts.append({"id": b["id"], "ref": b["ref"], "pnr": b["pnr"], "booking_date": b["booking_date"],
                             "outstanding": b["calc"]["customer_outstanding"] if party == "customer" else b["calc"]["supplier_outstanding"]})
        r["allocation_options"] = sorted(opts, key=lambda o: o["booking_date"], reverse=True)
    return r


# ---------------------------------------------------------------- invoices
def _fy(d):
    y, m = int(d[:4]), int(d[5:7])
    start = y if m >= 4 else y - 1
    return "%d-%02d" % (start, (start + 1) % 100)


def _tax_lines(split, rate, tax_amount, taxable):
    if not tax_amount:
        return []
    if split == "cgst_sgst":
        a, b2 = split_half(tax_amount)
        from decimal import Decimal
        half = format((Decimal(rate) / 2).normalize(), "f")
        return [{"label": "CGST @ %s%%" % half, "amount": a, "taxable": taxable},
                {"label": "SGST @ %s%%" % half, "amount": b2, "taxable": taxable}]
    if split == "igst":
        return [{"label": "IGST @ %s%%" % rate, "amount": tax_amount, "taxable": taxable}]
    return [{"label": "Tax @ %s%%" % rate, "amount": tax_amount, "taxable": taxable}]


SNAPSHOT_KEYS = ("business_name", "business_tagline", "business_address", "business_phone", "business_email", "business_gstin",
                 "business_state", "invoice_terms", "invoice_footer", "legal_name", "business_country", "business_website",
                 "business_registration", "logo_file", "brand_color", "date_format")


def _business_snapshot(conn):
    """Company details frozen into an issued document, so later profile edits never change it."""
    st = db.all_settings(conn)
    return {k: st.get(k, "") for k in SNAPSHOT_KEYS}


def set_logo(conn, ctx, payload):
    """Store a new company logo (or remove it with {"remove": true}); the previous file is kept for issued documents."""
    from . import branding
    if payload.get("remove"):
        name, info = "", None
    else:
        name, kind, w, h = branding.save_logo(ctx, payload)
        info = {"file": name, "type": kind, "width": w, "height": h}
    with tx(conn):
        before = db.get_setting(conn, "logo_file", "")
        if before != name:
            conn.execute("INSERT INTO settings(key, value) VALUES ('logo_file', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", (name,))
            audit(conn, "settings", None, "logo", {"logo_file": before}, {"logo_file": name})
    return info


def create_invoice(conn, bid, data):
    with tx(conn):
        b = one(conn.execute("SELECT * FROM bookings WHERE id = ?", (bid,)))
        if not b:
            raise NotFound("Booking not found")
        if not b["committed"]:
            raise ValidationError("Confirm or ticket the booking before issuing an invoice")
        if conn.execute("SELECT 1 FROM invoices WHERE booking_id = ? AND doc_type = 'invoice' AND status = 'issued'", (bid,)).fetchone():
            raise Conflict("An invoice is already issued for this booking", "invoice_exists")
        issue_date = v.d(data, "issue_date") or today()
        fy = _fy(issue_date)
        n = next_counter(conn, "invoice-" + fy)
        number = "%s%s/%04d" % (get_setting(conn, "invoice_prefix", "INV/"), fy, n)
        pax = rows(conn.execute("SELECT name, pax_type, ticket_number FROM booking_passengers WHERE booking_id = ? ORDER BY seq", (bid,)))
        segs = rows(conn.execute("SELECT * FROM booking_segments WHERE booking_id = ? ORDER BY seq", (bid,)))
        cust = one(conn.execute("SELECT * FROM customers WHERE id = ?", (b["customer_id"],)))
        prof = one(conn.execute("SELECT * FROM tax_profiles WHERE id = ?", (b["tax_profile_id"],))) if b["tax_profile_id"] else None
        route = " / ".join("%s %s %s-%s %s" % (s["airline"] or "", s["flight_no"] or "", s["origin"] or "", s["destination"] or "",
                                               (s["dep_at"] or "")[:10]) for s in segs).strip()
        label = {"flight": "Air ticket", "visa": "Visa service", "hotel": "Hotel booking", "package": "Tour package",
                 "umrah": "Umrah package", "other": "Travel service"}[b["service_type"]]
        desc = label + (" | PNR " + b["pnr"] if b["pnr"] else "") + (" | " + route if route else "")
        lines = []
        if b["selling_price"]:
            lines.append({"description": desc, "detail": ", ".join(p["name"] + (" (" + p["ticket_number"] + ")" if p["ticket_number"] else "") for p in pax),
                          "amount": b["selling_price"], "sac": ""})
        if b["service_charge"]:
            lines.append({"description": "Service charge", "detail": "", "amount": b["service_charge"], "sac": (prof or {}).get("sac_code") or ""})
        if b["other_income"]:
            lines.append({"description": "Other charges", "detail": "", "amount": b["other_income"], "sac": ""})
        if not lines:
            raise ValidationError("This booking has no amounts to invoice")
        subtotal = sum(l["amount"] for l in lines)
        taxes = _tax_lines(b["tax_split"], b["tax_rate"], b["tax_amount"], b["taxable_value"])
        tax_total = sum(t["amount"] for t in taxes)
        doc = {"lines": lines, "customer": {k: cust.get(k) for k in ("name", "phone", "email", "address", "city", "state", "gstin")},
               "business": _business_snapshot(conn),
               "booking": {"ref": b["ref"], "pnr": b["pnr"], "travel_date": b["travel_date"], "place_of_supply": b["place_of_supply"],
                           "passengers": pax, "segments": segs}}
        cur = conn.execute("INSERT INTO invoices(number, doc_type, booking_id, customer_id, issue_date, currency, lines_json, tax_json, subtotal, tax_total, total, notes, created_at) "
                           "VALUES (?, 'invoice', ?,?,?,?,?,?,?,?,?,?,?)",
                           (number, bid, b["customer_id"], issue_date, b["currency"], json.dumps(doc, default=str), json.dumps(taxes),
                            subtotal, tax_total, subtotal + tax_total, v.s(data, "notes", 1000), now_iso()))
        iid = cur.lastrowid
        history(conn, bid, "invoice", "Invoice %s issued" % number)
        audit(conn, "invoice", iid, "create", None, {"number": number, "total": subtotal + tax_total})
    return iid


def create_credit_note(conn, aid, data):
    with tx(conn):
        a = one(conn.execute("SELECT * FROM booking_adjustments WHERE id = ?", (aid,)))
        if not a:
            raise NotFound("Adjustment not found")
        if a["sign"] != 1 or a["reversed_by"]:
            raise ValidationError("This adjustment has been reversed")
        if not a["customer_credit"]:
            raise ValidationError("This adjustment has no customer credit")
        if conn.execute("SELECT 1 FROM invoices WHERE adjustment_id = ? AND status = 'issued'", (aid,)).fetchone():
            raise Conflict("A credit note already exists for this adjustment")
        b = one(conn.execute("SELECT * FROM bookings WHERE id = ?", (a["booking_id"],)))
        cust = one(conn.execute("SELECT * FROM customers WHERE id = ?", (b["customer_id"],)))
        inv = one(conn.execute("SELECT id, number FROM invoices WHERE booking_id = ? AND doc_type = 'invoice' AND status = 'issued'", (b["id"],)))
        issue_date = v.d(data, "issue_date") or today()
        fy = _fy(issue_date)
        n = next_counter(conn, "credit-" + fy)
        number = "%s%s/%04d" % (get_setting(conn, "credit_note_prefix", "CN/"), fy, n)
        sub = a["customer_credit"] - a["customer_credit_tax"]
        lines = [{"description": "%s credit - booking %s%s" % (a["kind"].replace("_", " ").capitalize(), b["ref"], (" | PNR " + b["pnr"]) if b["pnr"] else ""),
                  "detail": a["notes"] or "", "amount": sub, "sac": ""}]
        taxes = _tax_lines(b["tax_split"], b["tax_rate"], a["customer_credit_tax"], 0) if a["customer_credit_tax"] else []
        doc = {"lines": lines, "customer": {k: cust.get(k) for k in ("name", "phone", "email", "address", "city", "state", "gstin")},
               "business": _business_snapshot(conn), "against": inv["number"] if inv else None,
               "booking": {"ref": b["ref"], "pnr": b["pnr"], "travel_date": b["travel_date"], "place_of_supply": b["place_of_supply"]}}
        cur = conn.execute("INSERT INTO invoices(number, doc_type, booking_id, customer_id, adjustment_id, related_invoice_id, issue_date, currency, lines_json, tax_json, subtotal, tax_total, total, notes, created_at) "
                           "VALUES (?, 'credit_note', ?,?,?,?,?,?,?,?,?,?,?,?,?)",
                           (number, b["id"], b["customer_id"], aid, inv["id"] if inv else None, issue_date, b["currency"], json.dumps(doc, default=str),
                            json.dumps(taxes), sub, a["customer_credit_tax"], a["customer_credit"], v.s(data, "notes", 1000), now_iso()))
        iid = cur.lastrowid
        history(conn, b["id"], "credit_note", "Credit note %s issued" % number)
        audit(conn, "invoice", iid, "create", None, {"number": number, "total": a["customer_credit"], "type": "credit_note"})
    return iid


def cancel_invoice(conn, iid, data):
    with tx(conn):
        inv = one(conn.execute("SELECT * FROM invoices WHERE id = ?", (iid,)))
        if not inv:
            raise NotFound("Invoice not found")
        if inv["status"] == "cancelled":
            raise ValidationError("Invoice is already cancelled")
        reason = v.s(data, "reason", 500, True, "Reason")
        conn.execute("UPDATE invoices SET status = 'cancelled', cancel_reason = ? WHERE id = ?", (reason, iid))
        history(conn, inv["booking_id"], "invoice", "%s %s cancelled: %s" % ("Invoice" if inv["doc_type"] == "invoice" else "Credit note", inv["number"], reason))
        audit(conn, "invoice", iid, "cancel", {"status": "issued"}, {"status": "cancelled", "reason": reason})
    return iid


def get_invoice(conn, iid):
    inv = one(conn.execute("SELECT * FROM invoices WHERE id = ?", (iid,)))
    if not inv:
        raise NotFound("Invoice not found")
    inv["doc"] = json.loads(inv.pop("lines_json"))
    inv["taxes"] = json.loads(inv.pop("tax_json"))
    L = Ledger(conn)
    b = L.bookings.get(inv["booking_id"])
    inv["booking_calc"] = {"received": b["calc"]["received"], "outstanding": b["calc"]["customer_outstanding"],
                           "currency_note": L.base} if b else None
    return inv


def list_invoices(conn, params):
    q = ("SELECT i.id, i.number, i.doc_type, i.issue_date, i.currency, i.subtotal, i.tax_total, i.total, i.status, "
         "i.booking_id, b.ref AS booking_ref, b.pnr, c.name AS customer_name, i.customer_id FROM invoices i "
         "JOIN bookings b ON b.id = i.booking_id JOIN customers c ON c.id = i.customer_id WHERE 1=1")
    args = []
    if params.get("from"):
        q += " AND i.issue_date >= ?"
        args.append(params["from"])
    if params.get("to"):
        q += " AND i.issue_date <= ?"
        args.append(params["to"])
    if params.get("doc_type"):
        q += " AND i.doc_type = ?"
        args.append(params["doc_type"])
    if params.get("q"):
        q += " AND (i.number LIKE ? OR b.ref LIKE ? OR b.pnr LIKE ? OR c.name LIKE ?)"
        like = "%" + params["q"] + "%"
        args += [like] * 4
    q += " ORDER BY i.issue_date DESC, i.id DESC"
    return rows(conn.execute(q, args))


# --------------------------------------------------------------- documents
def save_document(conn, ctx, data, booking_id=None, transaction_id=None):
    name = v.s(data, "name", 200, True, "File name")
    raw = data.get("data") or ""
    if "," in raw[:100] and raw.startswith("data:"):
        raw = raw.split(",", 1)[1]
    try:
        content = base64.b64decode(raw, validate=True)
    except Exception:
        raise ValidationError("The file could not be read")
    if not content:
        raise ValidationError("The file is empty")
    if len(content) > MAX_UPLOAD:
        raise ValidationError("Files must be 15 MB or smaller")
    ext = os.path.splitext(name)[1].lower()
    if ext not in ALLOWED_UPLOADS:
        raise ValidationError("File type %s is not allowed. Allowed: %s" % (ext or "(none)", ", ".join(sorted(ALLOWED_UPLOADS))))
    mime = ALLOWED_UPLOADS[ext]
    safe_name = "".join(ch for ch in os.path.basename(name) if ch.isalnum() or ch in "._- ()")[:150] or ("file" + ext)
    stored = uuid.uuid4().hex + ext
    ctx.upload_dir.mkdir(parents=True, exist_ok=True)
    path = ctx.upload_dir / stored
    tmp = path.with_suffix(path.suffix + ".part")
    with open(tmp, "wb") as fh:
        fh.write(content)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, path)
    try:
        with tx(conn):
            if booking_id and not conn.execute("SELECT 1 FROM bookings WHERE id = ?", (booking_id,)).fetchone():
                raise NotFound("Booking not found")
            if transaction_id and not conn.execute("SELECT 1 FROM transactions WHERE id = ?", (transaction_id,)).fetchone():
                raise NotFound("Transaction not found")
            cur = conn.execute("INSERT INTO documents(booking_id, transaction_id, original_name, stored_name, mime, size, sha256, label, uploaded_at) "
                               "VALUES (?,?,?,?,?,?,?,?,?)", (booking_id, transaction_id, safe_name, stored, mime, len(content),
                                                             hashlib.sha256(content).hexdigest(), v.s(data, "label", 120), now_iso()))
            did = cur.lastrowid
            if booking_id:
                history(conn, booking_id, "document", "Uploaded %s" % safe_name)
            audit(conn, "document", did, "upload", None, {"name": safe_name, "booking_id": booking_id, "transaction_id": transaction_id, "size": len(content)})
    except Exception:
        try:
            path.unlink()
        except OSError:
            pass
        raise
    return did


def get_document(conn, ctx, did):
    d = one(conn.execute("SELECT * FROM documents WHERE id = ? AND deleted = 0", (did,)))
    if not d:
        raise NotFound("Document not found")
    path = (ctx.upload_dir / d["stored_name"]).resolve()
    if ctx.upload_dir.resolve() not in path.parents or not path.exists():
        raise NotFound("Document file is missing")
    return d, path


def delete_document(conn, ctx, did):
    with tx(conn):
        d = one(conn.execute("SELECT * FROM documents WHERE id = ? AND deleted = 0", (did,)))
        if not d:
            raise NotFound("Document not found")
        conn.execute("UPDATE documents SET deleted = 1 WHERE id = ?", (did,))
        if d["booking_id"]:
            history(conn, d["booking_id"], "document", "Removed %s (kept in archive)" % d["original_name"])
        audit(conn, "document", did, "remove", d, {"deleted": 1})
    return did


# ------------------------------------------------------------ reconciliation
def reconciliation_view(conn, aid, as_of=None):
    L = Ledger(conn)
    acc = L.accounts.get(int(aid))
    if not acc:
        raise NotFound("Account not found")
    items = []
    for t in L.transactions:
        if as_of and t["txn_date"] > as_of:
            continue
        for acc_id, eff in L.txn_account_effects(t):
            if acc_id == acc["id"]:
                r = txn_row(L, t)
                r["effect"] = eff
                items.append(r)
    items.sort(key=lambda r: (r["txn_date"], r["id"]))
    bal = L.account_balances(as_of)[acc["id"]]
    history_rows = rows(conn.execute("SELECT * FROM reconciliations WHERE account_id = ? ORDER BY id DESC LIMIT 20", (acc["id"],)))
    return {"account": acc, "items": items, "balance": bal["balance"], "cleared_balance": bal["reconciled_balance"],
            "history": history_rows}


def save_reconciliation(conn, aid, data):
    with tx(conn):
        acc = one(conn.execute("SELECT * FROM accounts WHERE id = ?", (aid,)))
        if not acc:
            raise NotFound("Account not found")
        stmt_date = v.d(data, "statement_date", True, "Statement date")
        stmt_bal = parse_amount(data.get("statement_balance"), "Statement balance", required=True, allow_negative=True)
        cleared = data.get("cleared_ids") or []
        if not isinstance(cleared, list):
            raise ValidationError("cleared_ids must be a list")
        cleared = {int(x) for x in cleared}
        ts = now_iso()
        relevant = rows(conn.execute("SELECT id, reconciled, txn_date FROM transactions WHERE (account_id = ? OR to_account_id = ?) AND txn_date <= ?",
                                     (aid, aid, stmt_date)))
        for t in relevant:
            want = 1 if t["id"] in cleared else 0
            if want != t["reconciled"]:
                conn.execute("UPDATE transactions SET reconciled = ?, reconciled_on = ?, updated_at = ? WHERE id = ?",
                             (want, stmt_date if want else None, ts, t["id"]))
        L = Ledger(conn)
        cleared_bal = L.account_balances(stmt_date)[acc["id"]]["reconciled_balance"]
        diff = stmt_bal - cleared_bal
        cur = conn.execute("INSERT INTO reconciliations(account_id, statement_date, statement_balance, cleared_balance, difference, notes, created_at) "
                           "VALUES (?,?,?,?,?,?,?)", (aid, stmt_date, stmt_bal, cleared_bal, diff, v.s(data, "notes", 500), ts))
        audit(conn, "reconciliation", cur.lastrowid, "save", None, {"account_id": aid, "statement_date": stmt_date,
                                                                     "statement_balance": stmt_bal, "cleared_balance": cleared_bal,
                                                                     "difference": diff, "cleared_count": len(cleared)})
    return {"id": cur.lastrowid, "cleared_balance": cleared_bal, "difference": diff}


# ------------------------------------------------------------------ search
def global_search(conn, q):
    q = (q or "").strip()
    if len(q) < 2:
        return {"bookings": [], "customers": [], "invoices": [], "transactions": [], "suppliers": []}
    bookings = search_bookings(conn, {"q": q})[:8]
    like = "%" + q + "%"
    digits = "".join(ch for ch in q if ch.isdigit())
    cust = rows(conn.execute("SELECT id, name, phone, email FROM customers WHERE name LIKE ? OR email LIKE ? OR (? <> '' AND replace(replace(replace(phone,' ',''),'-',''),'+','') LIKE ?) ORDER BY name LIMIT 8",
                             (like, like, digits, "%" + digits + "%")))
    sup = rows(conn.execute("SELECT id, name, kind FROM suppliers WHERE name LIKE ? ORDER BY name LIMIT 5", (like,)))
    inv = rows(conn.execute("SELECT i.id, i.number, i.doc_type, i.total, i.currency, b.ref FROM invoices i JOIN bookings b ON b.id = i.booking_id WHERE i.number LIKE ? ORDER BY i.id DESC LIMIT 5", (like,)))
    txn = rows(conn.execute("SELECT id, txn_no, kind, amount, currency, txn_date, reference FROM transactions WHERE txn_no LIKE ? OR reference LIKE ? ORDER BY id DESC LIMIT 6", (like, like)))
    return {"bookings": bookings, "customers": cust, "suppliers": sup, "invoices": inv, "transactions": txn}
