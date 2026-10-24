#!/usr/bin/env python3
"""Desktop side of the iPhone parity tests.

Runs a seeded, randomised but repeatable series of business operations (bookings in INR and SAR, GST,
receipts and allocations, supplier payments, expenses, transfers, refunds, adjustments, reversals,
corrections, invoices, credit notes, reconciliation and deliberately invalid inputs) through the DESKTOP
core (core/server), then writes:

  <out>/fixture.json  - every operation, its outcome (result or exact error) and a snapshot of all the
                        figures: per-booking calculations, period summaries, balances, every report,
                        statements, the dashboard, invoice documents and the database tables
  <out>/desktop.db    - the desktop database those operations produced
  <out>/desktop-backup.zip - the same books as a real desktop backup (for the iPhone import check)

The iPhone check writes <out>/iphone-export.zip (a portable export of the replayed books); then
  python3 ios/tools/parity.py --check-export <out>
The Android engine tests write <out>/android-export.zip; check it with --export-name android-export.zip.
restores it in the desktop app and requires the same snapshot again (iPhone -> desktop direction).

The Swift checks (ios/AirZeloKit, `swift run AirZeloChecks --parity <out>`) then
  1. open desktop.db with the iPhone engine and must reproduce the snapshot exactly, and
  2. replay the operations through the iPhone services and must reproduce every outcome and the snapshot.

  python3 ios/tools/parity.py --out /tmp/parity --seed 7 --ops 400
"""
import argparse
import json
import random
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "core"))

from server import db, services, reports, backup, money  # noqa: E402
from server.web import Context  # noqa: E402
from server.errors import ApiError  # noqa: E402
from server.finance import Ledger, dashboard  # noqa: E402

TODAY = "2026-10-25"
services.today = lambda: TODAY  # every default date resolves to the same day on both sides

VOLATILE = {"created_at", "updated_at", "uid", "applied_at", "at"}
SKIP_TABLES = {"audit_log", "sessions", "owner", "sqlite_sequence"}
AIRLINES = [("IndiGo", "6E"), ("Emirates", "EK"), ("Saudia", "SV"), ("Air India Express", "IX"), ("Qatar Airways", "QR"), ("flydubai", "FZ")]
AIRPORTS = ["COK", "TRV", "CCJ", "BLR", "DXB", "JED", "RUH", "DOH", "MCT", "SHJ"]
FIRST = ["Ahmed", "Fathima", "Rahul", "Anitha", "Joseph", "Mariam", "Suresh", "Divya", "Imran", "Zayan", "Nisha", "Arjun"]
LAST = ["Khan", "Rahman", "Nair", "Menon", "Thomas", "Ali", "Pillai", "Varghese", "Kumar", "Hassan"]


def outcome(fn):
    try:
        r = fn()
    except ApiError as e:
        return {"error": e.code, "message": e.message}
    return {"ok": r}


class Scenario:
    def __init__(self, conn, seed):
        self.conn = conn
        self.rnd = random.Random(seed)
        self.ops = []
        self.day = "2026-01-05"

    # ------------------------------------------------------------- helpers
    def ids(self, sql, args=()):
        return [r[0] for r in self.conn.execute(sql, args)]

    def pick(self, sql, args=()):
        ids = self.ids(sql, args)
        return self.rnd.choice(ids) if ids else None

    def amount(self, lo, hi, step=1):
        v = self.rnd.randrange(lo, hi, step)
        cents = self.rnd.choice([0, 0, 0, 50, 25, 99])
        return "%d.%02d" % (v, cents) if cents else str(v)

    def advance(self):
        if self.rnd.random() < 0.35:
            from datetime import date, timedelta
            d = date.fromisoformat(self.day) + timedelta(days=self.rnd.randint(1, 6))
            self.day = min(d.isoformat(), "2026-12-28")
        return self.day

    def run(self, op, *args):
        fn = getattr(self, "_do_" + op, None) or getattr(services, op)
        res = outcome(lambda: fn(self.conn, *args))
        if "ok" in res:
            res["ok"] = normalise(op, res["ok"])
        self.ops.append({"op": op, "args": list(args), "outcome": res})
        return res

    # ---------------------------------------------------------- generator
    def setup(self):
        self.run("update_settings", {"business_name": "Parity Travels", "invoice_prefix": "PT/", "booking_prefix": "PB-",
                                     "receipt_prefix": "REC", "business_gstin": "32abcde1234f1z5", "pay_ifsc": "abcd0123456",
                                     "pay_account_number": "5010 0012 3456 78", "brand_color": "#0E7C66"})
        self.run("update_settings", {"business_email": "not-an-email"})  # refused
        self.run("save_currency", {"code": "eur", "name": "Euro", "symbol": "€"})
        self.run("save_account", {"name": "Federal Current", "account_type": "current", "currency": "INR", "opening_balance": "25000",
                                  "bank_name": "Federal Bank", "account_last4": "XXXX 4321"}, None)
        self.run("save_account", {"name": "SAR Wallet", "account_type": "wallet", "currency": "SAR", "opening_balance": "1500.50"}, None)
        self.run("save_account", {"name": "UPI", "account_type": "upi", "currency": "INR"}, None)
        self.run("save_account", {"name": "Federal current", "account_type": "bank"}, None)  # duplicate name refused
        self.run("save_tax_profile", {"name": "GST 5% on markup", "rate": "5", "basis": "markup", "split": "cgst_sgst"}, None)
        self.run("save_tax_profile", {"name": "Bad rate", "rate": "150", "basis": "none"}, None)  # refused
        self.run("save_expense_category", {"name": "Visa processing"})
        for name, kind in (("Akbar Portal", "b2b_portal"), ("Riya Consolidator", "consolidator"), ("Alhind B2B", "b2b_portal"),
                           ("Saudia Direct", "airline")):
            self.run("create_supplier", {"name": name, "kind": kind, "phone": "+91 484 2000 %03d" % self.rnd.randint(0, 999)})
        self.run("create_supplier", {"name": "akbar portal"})  # duplicate refused
        for i in range(10):
            self.new_customer(i)
        self.run("create_customer", {"name": "Dup Phone", "phone": self.conn.execute("SELECT phone FROM customers LIMIT 1").fetchone()[0]})
        self.run("create_customer", {"name": "", "phone": "123"})  # refused

    def new_customer(self, i=None):
        name = "%s %s" % (self.rnd.choice(FIRST), self.rnd.choice(LAST))
        data = {"name": name, "phone": "+91 9%04d %05d" % (self.rnd.randint(0, 9999), self.rnd.randint(0, 99999)),
                "email": "%s%d@example.com" % (name.split()[0].lower(), self.rnd.randint(1, 999)),
                "city": self.rnd.choice(["Kochi", "Kozhikode", "Malappuram", ""]), "state": "Kerala"}
        if self.rnd.random() < 0.5:
            data["passport_no"] = " p%07d " % self.rnd.randint(0, 9999999)
        if self.rnd.random() < 0.3:
            data["follow_up_date"] = "2026-%02d-%02d" % (self.rnd.randint(10, 11), self.rnd.randint(1, 28))
            data["follow_up_note"] = "Call about return ticket"
        if self.rnd.random() < 0.15:
            data["gstin"] = "32abcde%04df1z5" % self.rnd.randint(0, 9999)
        return self.run("create_customer", data)

    def booking_payload(self, status=None):
        cust = self.pick("SELECT id FROM customers WHERE active = 1")
        sup = self.pick("SELECT id FROM suppliers WHERE active = 1")
        sar = self.rnd.random() < 0.25
        day = self.advance()
        cost = self.rnd.randrange(800, 3000) if sar else self.rnd.randrange(4000, 60000)
        sell = cost + self.rnd.randrange(0, 4000 if not sar else 300)
        al, code = self.rnd.choice(AIRLINES)
        o, d = self.rnd.sample(AIRPORTS, 2)
        rt = self.rnd.random() < 0.4
        segs = [{"direction": "outbound", "airline": al, "flight_no": "%s %d" % (code, self.rnd.randint(100, 999)), "origin": o,
                 "destination": d, "dep_at": "2026-%02d-%02dT%02d:%02d" % (self.rnd.randint(10, 12), self.rnd.randint(1, 28),
                                                                       self.rnd.randint(0, 23), self.rnd.choice([0, 15, 30, 45])),
                 "baggage": "30 kg"}]
        if rt:
            segs.append({"direction": "return", "airline": al, "flight_no": "%s %d" % (code, self.rnd.randint(100, 999)), "origin": d,
                         "destination": o, "dep_at": "2027-01-%02dT10:00" % self.rnd.randint(1, 28)})
        pax = []
        for i in range(self.rnd.choice([1, 1, 2, 3])):
            p = {"name": "%s %s" % (self.rnd.choice(FIRST), self.rnd.choice(LAST)), "pax_type": self.rnd.choice(["ADT", "ADT", "CHD", "INF"]),
                 "title": self.rnd.choice(["MR", "MRS", "MSTR", ""])}
            if self.rnd.random() < 0.6:
                p["ticket_number"] = "%03d-%010d" % (self.rnd.choice([65, 176, 157, 98]), self.rnd.randint(0, 9999999999))
            if self.rnd.random() < 0.3:
                p["passport_no"] = "n%07d" % self.rnd.randint(0, 9999999)
            pax.append(p)
        data = {"customer_id": cust, "supplier_id": sup if self.rnd.random() < 0.9 else None,
                "pnr": "".join(self.rnd.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(6)),
                "status": status or self.rnd.choice(["confirmed", "ticketed", "ticketed", "enquiry", "on_hold"]),
                "booking_date": day, "trip_type": "round_trip" if rt else "one_way",
                "currency": "SAR" if sar else "INR", "exchange_rate": self.rnd.choice(["22.15", "22.4", "21.9875"]) if sar else None,
                "purchase_cost": str(cost), "selling_price": str(sell),
                "commission": self.amount(0, 400) if self.rnd.random() < 0.4 else "0",
                "service_charge": str(self.rnd.choice([0, 0, 250, 500, 999])), "other_income": "0",
                "passengers": pax, "segments": segs}
        tp = self.rnd.random()
        if tp < 0.25:
            data["tax_profile_id"] = 2
        elif tp < 0.35:
            data["tax_profile_id"] = 3
        elif tp < 0.42:
            data["tax_profile_id"] = 5
        elif tp < 0.47:
            data.update({"tax_override": True, "taxable_value": "1000", "tax_rate": "18", "tax_split": "igst"})
        return data

    def step(self):
        r = self.rnd.random()
        bk = lambda extra="": self.pick("SELECT id FROM bookings WHERE 1=1 " + extra)
        cash = self.pick("SELECT id FROM accounts WHERE currency = 'INR' AND active = 1")
        if r < 0.20:
            self.run("save_booking", self.booking_payload(), None)
        elif r < 0.24:
            data = self.booking_payload()
            dup = self.conn.execute("SELECT pnr FROM bookings ORDER BY RANDOM() LIMIT 1").fetchone()
            if dup and self.rnd.random() < 0.5:
                data["pnr"] = dup[0]  # duplicate PNR, refused unless forced
                data["force_duplicate"] = self.rnd.random() < 0.5
            self.run("save_booking", data, None)
        elif r < 0.30:
            b = bk()
            if b:
                cur = services.get_booking(self.conn, b)
                data = self.booking_payload(status=self.rnd.choice(["ticketed", "confirmed", "on_hold", "cancelled"]))
                data["customer_id"] = cur["booking"]["customer_id"]
                if self.rnd.random() < 0.6:  # keep prices, change details
                    for k in ("purchase_cost", "selling_price", "commission", "service_charge", "currency", "exchange_rate"):
                        data[k] = (cur["booking"][k] / 100.0) if isinstance(cur["booking"][k], int) else cur["booking"][k]
                    data["tax_profile_id"] = cur["booking"]["tax_profile_id"]
                self.run("save_booking", data, b)
        elif r < 0.34:
            b = bk()
            if b:
                self.run("set_booking_status", b, self.rnd.choice(["ticketed", "confirmed", "on_hold", "enquiry", "cancelled"]))
        elif r < 0.50:
            b = bk("AND committed = 1") if self.rnd.random() < 0.8 else bk()
            if b:
                L = Ledger(self.conn)
                out = L.bookings[b]["calc"]["customer_outstanding"]
                amt = max(out, 100000) if self.rnd.random() < 0.1 else max(min(out, self.rnd.randrange(1000, 8000000)), 100)
                data = {"kind": "customer_receipt", "txn_date": self.advance(), "account_id": cash, "amount": "%.2f" % (amt / 100.0),
                        "method": self.rnd.choice(["cash", "upi", "bank_transfer"]), "reference": "UTR%06d" % self.rnd.randint(0, 999999),
                        "booking_id": b}
                if self.rnd.random() < 0.2:
                    data.pop("booking_id")
                    data["customer_id"] = L.bookings[b]["customer_id"]
                    data["allocations"] = [{"booking_id": b, "amount": "%.2f" % (min(amt, max(out, 0)) / 200.0)}]
                self.run("create_transaction", data)
        elif r < 0.58:
            b = bk("AND committed = 1 AND supplier_id IS NOT NULL")
            if b:
                L = Ledger(self.conn)
                out = L.bookings[b]["calc"]["supplier_outstanding"]
                if out > 0:
                    acc = self.pick("SELECT id FROM accounts WHERE currency = 'INR' AND active = 1")
                    self.run("create_transaction", {"kind": "supplier_payment", "txn_date": self.advance(), "account_id": acc,
                                                    "amount": "%.2f" % (out / 100.0), "method": "portal_wallet", "booking_id": b})
        elif r < 0.64:
            direct = self.rnd.random() < 0.3
            b = bk("AND committed = 1") if direct else None
            self.run("create_transaction", {"kind": "expense", "txn_date": self.advance(), "account_id": cash, "amount": self.amount(50, 9000),
                                            "category": self.rnd.choice(["Rent", "Bank charges", "Visa processing", "Office expenses"]),
                                            "payee": self.rnd.choice(["Landlord", "Federal Bank", "", "Courier"]), "booking_id": b,
                                            "method": "cash"})
        elif r < 0.66:
            self.run("create_transaction", {"kind": "other_income", "txn_date": self.advance(), "account_id": cash,
                                            "amount": self.amount(100, 3000), "category": "Other income", "method": "upi"})
        elif r < 0.69:
            sar = self.ids("SELECT id FROM accounts WHERE currency = 'SAR'")
            if sar and self.rnd.random() < 0.5:
                self.run("create_transaction", {"kind": "transfer", "txn_date": self.advance(), "account_id": sar[0], "to_account_id": cash,
                                                "amount": self.amount(50, 400), "exchange_rate": "22.3", "to_amount": self.amount(1000, 9000)})
            else:
                to = self.pick("SELECT id FROM accounts WHERE currency = 'INR' AND id <> ?", (cash,))
                self.run("create_transaction", {"kind": "transfer", "txn_date": self.advance(), "account_id": cash, "to_account_id": to,
                                                "amount": self.amount(100, 20000)})
        elif r < 0.75:
            b = bk("AND committed = 1")
            if b:
                L = Ledger(self.conn)
                c = L.bookings[b]["calc"]
                billed = c["customer_payable"] - c["customer_credits"]
                if billed > 0:
                    cust = self.rnd.randrange(0, billed + 1) if self.rnd.random() < 0.9 else billed + 50000
                    data = {"customer_refund": "%.2f" % (cust / 100.0), "supplier_refund": "%.2f" % (max(0, c["supplier_payable"] - c["supplier_credits"]) * 0.8 / 100.0),
                            "date": self.advance(), "new_status": self.rnd.choice(["cancelled", "refunded", ""]),
                            "idem_key": "rf-%d-%d" % (b, len(self.ops)), "notes": "Customer cancelled"}
                    if self.rnd.random() < 0.5 and c["received"] > 0:
                        data["pay_customer"] = {"account_id": cash, "amount": "%.2f" % (min(cust, c["received"]) / 100.0), "method": "bank_transfer"}
                    self.run("refund_booking", b, data)
        elif r < 0.78:
            b = bk("AND committed = 1")
            if b:
                self.run("create_adjustment", b, {"kind": self.rnd.choice(["reissue", "date_change", "other"]), "adj_date": self.advance(),
                                                  "extra_customer_charge": self.amount(500, 3000), "extra_supplier_charge": self.amount(200, 2500),
                                                  "notes": "Date change fee"})
        elif r < 0.82:
            t = self.pick("SELECT id FROM transactions WHERE sign = 1 AND reversed_by IS NULL")
            if t:
                self.run("reverse_transaction", t, {"reason": "Entered twice", "date": self.advance()})
        elif r < 0.86:
            t = self.pick("SELECT id FROM transactions WHERE sign = 1 AND reversed_by IS NULL AND kind IN ('customer_receipt','expense','other_income')")
            if t:
                row = dict(self.conn.execute("SELECT * FROM transactions WHERE id = ?", (t,)).fetchone())
                data = {"amount": "%.2f" % (row["amount"] / 100.0 * self.rnd.choice([1, 1, 0.5, 1.1])), "txn_date": row["txn_date"],
                        "account_id": row["account_id"], "reference": "EDIT-%d" % t, "notes": "corrected", "reason": "Wrong amount"}
                self.run("correct_transaction", t, data)
        elif r < 0.88:
            a = self.pick("SELECT id FROM booking_adjustments WHERE sign = 1 AND reversed_by IS NULL")
            if a:
                self.run("reverse_adjustment", a, {"reason": "Airline waived fee", "date": self.advance()})
        elif r < 0.92:
            b = bk("AND committed = 1")
            if b:
                self.run("create_invoice", b, {"issue_date": self.advance()})
        elif r < 0.94:
            a = self.pick("SELECT id FROM booking_adjustments WHERE sign = 1 AND reversed_by IS NULL AND customer_credit > 0")
            if a:
                self.run("create_credit_note", a, {"issue_date": self.advance()})
        elif r < 0.95:
            i = self.pick("SELECT id FROM invoices WHERE status = 'issued'")
            if i:
                self.run("cancel_invoice", i, {"reason": "Wrong customer name"})
        elif r < 0.96:
            t = self.pick("SELECT id FROM transactions WHERE kind = 'customer_receipt' AND sign = 1 AND reversed_by IS NULL")
            if t:
                row = dict(self.conn.execute("SELECT * FROM transactions WHERE id = ?", (t,)).fetchone())
                b = self.pick("SELECT id FROM bookings WHERE customer_id = ?", (row["customer_id"],))
                self.run("set_allocations", t, {"allocations": [{"booking_id": b, "amount": "%.2f" % (row["base_amount"] / 200.0)}]})
        elif r < 0.97:
            c = self.pick("SELECT id FROM customers")
            self.run("update_customer", c, {"city": "Thrissur", "notes": "VIP", "passport_no": "z%07d" % self.rnd.randint(0, 9999999)})
        elif r < 0.975:
            self.run("delete_customer", self.pick("SELECT id FROM customers"))
        elif r < 0.98:
            self.run("delete_booking", self.pick("SELECT id FROM bookings"))
        elif r < 0.985:
            t = self.pick("SELECT id FROM transactions")
            if t:
                self.run("update_transaction_meta", t, {"notes": "checked", "method": "upi"})
        elif r < 0.99:
            acc = self.pick("SELECT id FROM accounts")
            ids = self.ids("SELECT id FROM transactions WHERE account_id = ? OR to_account_id = ?", (acc, acc))
            cleared = [i for i in ids if self.rnd.random() < 0.7]
            self.run("save_reconciliation", acc, {"statement_date": self.day, "statement_balance": self.amount(1000, 90000), "cleared_ids": cleared})
        else:
            self.new_customer()

    def generate(self, n):
        self.setup()
        while len(self.ops) < n:
            self.step()


def normalise(op, r):
    """Results as JSON, keeping what the iPhone service also returns."""
    if op in ("create_transaction", "correct_transaction"):
        return list(r)
    if op == "refund_booking":
        return {k: r.get(k) for k in ("adjustment_id", "transactions", "gross_profit", "customer_outstanding", "supplier_outstanding",
                                      "payment_status", "idempotent_replay")}
    if op in ("update_customer", "update_supplier", "save_currency", "update_settings"):
        return "ok"
    if op in ("delete_customer", "delete_supplier"):
        return "deleted" if r.get("deleted") else "deactivated"
    if op == "delete_booking":
        return r["ref"]
    if op == "save_reconciliation":
        return [r["id"], r["cleared_balance"], r["difference"]]
    return r


# ------------------------------------------------------------------ snapshot
def table_dump(conn):
    out = {}
    for (name,) in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"):
        if name in SKIP_TABLES:
            continue
        rows = []
        for r in conn.execute('SELECT * FROM "%s" ORDER BY rowid' % name):
            d = {k: r[k] for k in r.keys() if k not in VOLATILE}
            for k in ("lines_json", "tax_json"):
                if k in d and d[k] is not None:
                    d[k] = json.loads(d[k])
            rows.append(d)
        if name == "booking_history":
            # the desktop writes some history lines while iterating a Python set (hash order), so compare them unordered
            for d in rows:
                d.pop("id")
            rows.sort(key=lambda d: json.dumps(d, sort_keys=True))
        out[name] = rows
    return out


PARAM_SETS = [{}, {"from": "2026-03-01", "to": "2026-06-30"}, {"currency": "SAR"}, {"status": "ticketed"}, {"airline": "emirates"},
              {"service_type": "flight", "from": "2026-07-01"}]


def snapshot(conn):
    L = Ledger(conn)
    s = {"bookings": {}, "periods": {}, "reports": {}, "statements": {}}
    for b in L.bookings.values():
        s["bookings"][str(b["id"])] = {"calc": b["calc"], "airlines": b["airlines"], "route": b["route"], "pax_count": b["pax_count"],
                                       "lead_pax": b["lead_pax"], "customer_name": b["customer_name"], "supplier_name": b["supplier_name"]}
    sup = conn.execute("SELECT id FROM suppliers ORDER BY id LIMIT 1").fetchone()
    cus = conn.execute("SELECT id FROM customers ORDER BY id LIMIT 1").fetchone()
    filters = list(PARAM_SETS)
    if sup:
        filters.append({"supplier_id": str(sup[0])})
    if cus:
        filters.append({"customer_id": str(cus[0])})
    for m in range(1, 13):
        filters.append({"from": "2026-%02d-01" % m, "to": "2026-%02d-28" % m})
    for f in filters:
        frm, to = f.get("from"), f.get("to")
        ff = {k: v for k, v in f.items() if k not in ("from", "to")}
        s["periods"][json.dumps(f, sort_keys=True)] = L.period_summary(frm, to, ff)
    s["customer_balances"] = {str(k): v for k, v in L.customer_balances().items()}
    s["supplier_balances"] = {str(k): v for k, v in L.supplier_balances().items()}
    s["account_balances"] = {str(k): v for k, v in L.account_balances().items()}
    s["account_balances_asof"] = {str(k): v for k, v in L.account_balances("2026-06-30").items()}
    for name, fn in reports.REPORTS.items():
        sets = list(filters)
        if name == "cashbook":
            sets = [{}, {"from": "2026-04-01", "to": "2026-08-31"}] + [{"account_id": str(a), "from": "2026-04-01"} for a in L.accounts] + \
                   [{"account_id": str(a), "kind": "customer_receipt,expense"} for a in L.accounts]
        if name in ("customer_outstanding", "supplier_outstanding"):
            sets = [{}, {"show": "all"}]
        if name == "monthly":
            sets = [{"year": "2026"}, {"year": "2027"}]
        if name == "yearly":
            sets = [{}, {"basis": "fy"}]
        if name == "expenses":
            sets = [{}, {"from": "2026-03-01", "to": "2026-09-30"}, {"category": "Rent"}]
        for p in sets:
            rep = fn(L, dict(p))
            s["reports"][name + "|" + json.dumps(p, sort_keys=True)] = rep
    for cid in L.customers:
        s["statements"]["c%d" % cid] = strip_party(reports.customer_statement(L, cid))
        s["statements"]["c%d:range" % cid] = strip_party(reports.customer_statement(L, cid, "2026-04-01", "2026-09-30"))
    for sid in L.suppliers:
        s["statements"]["s%d" % sid] = strip_party(reports.supplier_statement(L, sid))
        s["statements"]["s%d:range" % sid] = strip_party(reports.supplier_statement(L, sid, "2026-04-01", "2026-09-30"))
    dash = dashboard(conn, "2026-10-01", "2026-10-31", TODAY)
    dash_year = dashboard(conn, "2026-01-01", "2026-12-31", TODAY)
    keep = ("summary", "today", "month", "year", "customer_outstanding", "customer_credit_balances", "supplier_outstanding",
            "supplier_credit_balances", "pending_count", "pending", "cancellations_count", "accounts", "chart", "upcoming", "follow_ups")
    s["dashboard"] = {k: dash[k] for k in keep}
    s["dashboard_year"] = {k: dash_year[k] for k in keep}
    s["search"] = {q: [r["id"] for r in services.search_bookings(conn, {"q": q})] for q in ("ahmed", "ek", "9", "pb-26", "065", "zz")}
    s["tables"] = table_dump(conn)
    return s


def strip_party(st):
    st = dict(st)
    st.pop("party")
    st.pop("base")
    return st


MONEY_INPUTS = ["12000", "12000.5", "12,000.50", "₹ 1,500", " 99.99 ", "0", "0.00", "", "abc", "1e3", "1E-2", "12.345", "12.340",
                 "-5", "-0", "100000000000", "100000000000.01", ".5", "5.", "+7", "nan", "inf", "1_000", 12000, 0, -3, 12.5, 0.1, 22.75,
                 1e-05, 99.995, 1000000.0, True, None, "22.15", "21.9875", "0.04460000", "3.123456789", "1000000.5", "18", "4.5", "100.0001",
                 "33.33333", "150"]


def money_vectors():
    out = []
    for v in MONEY_INPUTS:
        row = {"input": v}
        for name, fn in (("amount", lambda x: money.parse_amount(x, "Amount")),
                         ("amount_neg", lambda x: money.parse_amount(x, "Amount", allow_negative=True, allow_zero=False)),
                         ("rate", lambda x: money.parse_rate(x, "Rate")), ("percent", lambda x: money.parse_percent(x, "Pct"))):
            try:
                row[name] = {"ok": fn(v)}
            except ApiError as e:
                row[name] = {"error": e.message}
        out.append(row)
    calc = []
    for minor in (0, 1, 5, 99, 12345, 1050, -1050, 999999999, 10 ** 13):
        for rate in ("1", "22.15", "21.9875", "0.0446", "3.12345678", "18", "4.5", "2.5", "0.5"):
            calc.append({"minor": minor, "rate": rate, "convert": money.convert(minor, rate), "percent_of": money.percent_of(minor, rate),
                         "split_half": list(money.split_half(minor)), "fmt": money.fmt(minor)})
    return {"parse": out, "calc": calc}


def check_export(out, name="iphone-export.zip"):
    """Restore a phone's export (iPhone or Android) in the desktop app and compare every figure with the fixture."""
    fixture = json.loads((out / "fixture.json").read_text(encoding="utf-8"))
    tmp = Path(tempfile.mkdtemp(prefix="az-export-"))
    try:
        ctx = Context(tmp / "agency")
        ctx.init()
        v = backup.verify_backup(out / name)
        if not v["ok"]:
            print("desktop rejected the phone export %s:" % name, v["problems"])
            sys.exit(1)
        backup.restore_backup(ctx, out / name)
        conn = db.connect(ctx.db_path)
        snap = json.loads(json.dumps(snapshot(conn), default=str, sort_keys=True))
        conn.close()
        want = fixture["snapshot"]
        diffs = []
        compare(snap, want, "", diffs)
        if diffs:
            print("%d differences after the desktop restored the phone export %s:" % (len(diffs), name))
            print("\n".join(diffs[:25]))
            sys.exit(1)
        print("desktop restored the phone export %s (manifest %s, schema %s) and reproduced all %d bookings and every report"
              % (name, v["manifest"].get("app_version"), v["manifest"].get("schema_version"), len(want["bookings"])))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def compare(got, want, path, out):
    if isinstance(want, dict) and isinstance(got, dict):
        for k in sorted(set(got) | set(want)):
            if k not in got:
                out.append("%s/%s missing" % (path, k))
            elif k not in want:
                out.append("%s/%s extra" % (path, k))
            else:
                compare(got[k], want[k], path + "/" + k, out)
    elif isinstance(want, list) and isinstance(got, list):
        if len(got) != len(want):
            out.append("%s: %d vs %d items" % (path, len(got), len(want)))
        for i, (g, w) in enumerate(zip(got, want)):
            compare(g, w, "%s[%d]" % (path, i), out)
    elif got != want:
        out.append("%s: %r vs %r" % (path, got, want))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check-export", metavar="DIR")
    ap.add_argument("--export-name", default="iphone-export.zip", help="file in DIR to check (android-export.zip for Android)")
    ap.add_argument("--out")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--ops", type=int, default=400)
    a = ap.parse_args()
    if a.check_export:
        check_export(Path(a.check_export), a.export_name)
        return
    if not a.out:
        ap.error("--out is required")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="az-parity-"))
    try:
        ctx = Context(tmp / "agency")
        ctx.init()
        conn = db.connect(ctx.db_path)
        sc = Scenario(conn, a.seed)
        sc.generate(a.ops)
        snap = snapshot(conn)
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.close()
        shutil.copy(ctx.db_path, out / "desktop.db")
        made = backup.create_backup(ctx, "manual", "parity fixture")
        shutil.copy(ctx.backup_dir / made["name"], out / "desktop-backup.zip")
        fixture = {"seed": a.seed, "today": TODAY, "ops": sc.ops, "snapshot": snap, "money": money_vectors()}
        (out / "fixture.json").write_text(json.dumps(fixture, default=str, sort_keys=True), encoding="utf-8")
        errors = sum(1 for o in sc.ops if "error" in o["outcome"])
        print("parity fixture: %d operations (%d refused), %d bookings, %d transactions -> %s" % (
            len(sc.ops), errors, len(snap["bookings"]), len(snap["tables"]["transactions"]), out))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
