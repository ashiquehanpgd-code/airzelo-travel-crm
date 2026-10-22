"""Financial engine. Every dashboard figure, report and ledger is produced here
from the raw database records, so all totals reconcile with one another.

Definitions (all amounts converted to the reporting currency at the rate stored
on each record - never today's rate):

  Markup                = Selling price - Purchase cost
  Gross booking profit  = Markup + Commission + Service charge + Other income
                          - Direct booking expenses (+/- adjustments)
  Net profit            = Gross profit + Other (non-booking) income - Operating expenses
  Customer outstanding  = Customer payable - Credits - Allocated receipts + Refunds paid
  Supplier outstanding  = Supplier payable - Supplier credits (incl. commission)
                          - Allocated payments + Supplier refunds/receipts
  Cash flow             = Actual cash received - Actual cash paid (transfers excluded)

Tax collected is a liability: it is never part of revenue or profit.
A booking only creates payables/revenue once it has been confirmed or ticketed
("committed"). Receipts against uncommitted bookings remain customer advances.
"""
from collections import defaultdict
from datetime import date, datetime, timedelta

from .money import convert
from . import db

INFLOW_KINDS = ("customer_receipt", "supplier_receipt", "other_income")
OUTFLOW_KINDS = ("supplier_payment", "customer_refund", "expense")
# Financing: loan money in and principal paid back. They move account balances only; they are not
# sales, income, expenses or business cash flow. Interest paid on a loan is an ordinary expense.
FINANCING_IN_KINDS = ("loan_received",)
FINANCING_OUT_KINDS = ("loan_repayment",)
KIND_LABELS = {
    "customer_receipt": "Customer receipt",
    "supplier_payment": "Supplier payment",
    "customer_refund": "Refund to customer",
    "supplier_receipt": "Supplier refund / receipt",
    "expense": "Expense",
    "other_income": "Other income",
    "transfer": "Transfer",
    "loan_received": "Loan received (financing)",
    "loan_repayment": "Loan repayment (principal)",
}

BOOKING_FIELDS = ("purchase_cost", "selling_price", "commission", "service_charge", "other_income", "tax_amount")
ADJ_FIELDS = ("customer_credit", "customer_credit_tax", "supplier_credit", "commission_reversal",
              "extra_customer_charge", "extra_supplier_charge")


def _zero():
    return {
        "bookings": 0, "sales": 0, "billed": 0, "selling": 0, "purchase": 0, "cost": 0, "markup": 0,
        "commission": 0, "service": 0, "other_booking_income": 0, "tax": 0, "direct_expenses": 0,
        "gross_profit": 0, "customer_credits": 0, "supplier_credits": 0, "commission_reversed": 0,
        "extra_customer_charges": 0, "extra_supplier_charges": 0,
    }


def in_range(d, frm, to):
    if not d:
        return False
    d = d[:10]
    if frm and d < frm:
        return False
    if to and d > to:
        return False
    return True


class Ledger:
    def __init__(self, conn):
        self.conn = conn
        self.base = db.get_setting(conn, "base_currency", "INR")
        self._load()

    # ------------------------------------------------------------------ load
    def _load(self):
        c = self.conn
        self.customers = {r["id"]: dict(r) for r in c.execute("SELECT * FROM customers")}
        self.suppliers = {r["id"]: dict(r) for r in c.execute("SELECT * FROM suppliers")}
        self.accounts = {r["id"]: dict(r) for r in c.execute("SELECT * FROM accounts")}
        self.bookings = {}
        for r in c.execute("SELECT * FROM bookings ORDER BY booking_date, id"):
            self.bookings[r["id"]] = dict(r)
        self.segments = defaultdict(list)
        for r in c.execute("SELECT * FROM booking_segments ORDER BY booking_id, seq, id"):
            self.segments[r["booking_id"]].append(dict(r))
        self.passengers = defaultdict(list)
        for r in c.execute("SELECT * FROM booking_passengers ORDER BY booking_id, seq, id"):
            self.passengers[r["booking_id"]].append(dict(r))
        self.adjustments = defaultdict(list)
        self.all_adjustments = []
        for r in c.execute("SELECT * FROM booking_adjustments ORDER BY adj_date, id"):
            a = dict(r)
            self.adjustments[a["booking_id"]].append(a)
            self.all_adjustments.append(a)
        self.transactions = [dict(r) for r in c.execute("SELECT * FROM transactions ORDER BY txn_date, id")]
        self.debts = {}
        if c.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'debts'").fetchone():
            self.debts = {r["id"]: dict(r) for r in c.execute("SELECT id, debt_no, creditor FROM debts")}
        self.txn_by_id = {t["id"]: t for t in self.transactions}
        self.allocations = defaultdict(list)  # txn_id -> list
        self.alloc_by_booking = defaultdict(list)
        for r in c.execute("SELECT * FROM allocations ORDER BY id"):
            a = dict(r)
            self.allocations[a["txn_id"]].append(a)
            self.alloc_by_booking[a["booking_id"]].append(a)
        self.direct_by_booking = defaultdict(list)
        for t in self.transactions:
            if t["kind"] == "expense" and t["is_direct"] and t["booking_id"]:
                self.direct_by_booking[t["booking_id"]].append(t)
        self.events = []
        for b in self.bookings.values():
            self._compute_booking(b)

    # -------------------------------------------------------------- bookings
    def _compute_booking(self, b):
        rate = b["exchange_rate"]
        base = {f: convert(b[f], rate) for f in BOOKING_FIELDS}
        committed = bool(b["committed"])
        k = 1 if committed else 0
        adj = {f: 0 for f in ADJ_FIELDS}
        if committed:
            for a in self.adjustments.get(b["id"], []):
                for f in ADJ_FIELDS:
                    adj[f] += a["sign"] * convert(a[f], a["exchange_rate"])
        alloc = defaultdict(int)
        for a in self.alloc_by_booking.get(b["id"], []):
            t = self.txn_by_id[a["txn_id"]]
            alloc[t["kind"]] += t["sign"] * a["amount"]
        direct = 0
        for t in self.direct_by_booking.get(b["id"], []):
            direct += t["sign"] * t["base_amount"]

        income_core = base["selling_price"] + base["service_charge"] + base["other_income"]
        customer_payable = k * (income_core + base["tax_amount"]) + adj["extra_customer_charge"]
        customer_credits = adj["customer_credit"]
        supplier_payable = k * base["purchase_cost"] + adj["extra_supplier_charge"]
        supplier_credits = k * base["commission"] - adj["commission_reversal"] + adj["supplier_credit"]
        received = alloc["customer_receipt"]
        refunded = alloc["customer_refund"]
        paid_supplier = alloc["supplier_payment"]
        supplier_refunds = alloc["supplier_receipt"]

        revenue = k * income_core + adj["extra_customer_charge"] - (adj["customer_credit"] - adj["customer_credit_tax"])
        cost = k * base["purchase_cost"] + adj["extra_supplier_charge"] - adj["supplier_credit"]
        commission_net = k * base["commission"] - adj["commission_reversal"]
        tax_net = k * base["tax_amount"] - adj["customer_credit_tax"]
        gross = revenue - cost + commission_net - direct

        cust_out = customer_payable - customer_credits - received + refunded
        sup_out = supplier_payable - supplier_credits - paid_supplier + supplier_refunds

        if not committed:
            pay_status = "advance" if received - refunded > 0 else "not_billed"
        elif cust_out <= 0 and customer_payable - customer_credits > 0:
            pay_status = "paid" if cust_out == 0 else "overpaid"
        elif cust_out <= 0:
            pay_status = "settled" if cust_out == 0 else "refund_due"
        elif received > 0:
            pay_status = "partial"
        else:
            pay_status = "unpaid"

        segs = self.segments.get(b["id"], [])
        airlines = []
        for s in segs:
            if s.get("airline") and s["airline"] not in airlines:
                airlines.append(s["airline"])
        route = ""
        if segs:
            pts = []
            for s in segs:
                for p in (s.get("origin"), s.get("destination")):
                    if p and (not pts or pts[-1] != p):
                        pts.append(p)
            route = "-".join(pts)
        pax = self.passengers.get(b["id"], [])
        b["calc"] = {
            "base": base,
            "markup": k * (base["selling_price"] - base["purchase_cost"]),
            "markup_original": b["selling_price"] - b["purchase_cost"],
            "customer_payable": customer_payable,
            "customer_credits": customer_credits,
            "received": received,
            "refunded": refunded,
            "customer_outstanding": cust_out,
            "supplier_payable": supplier_payable,
            "supplier_credits": supplier_credits,
            "paid_supplier": paid_supplier,
            "supplier_refunds": supplier_refunds,
            "supplier_outstanding": sup_out,
            "revenue": revenue,
            "cost": cost,
            "commission_net": commission_net,
            "tax_net": tax_net,
            "direct_expenses": direct,
            "gross_profit": gross,
            "payment_status": pay_status,
        }
        b["airlines"] = airlines
        b["route"] = route
        b["pax_count"] = len(pax)
        b["lead_pax"] = pax[0]["name"] if pax else ""
        b["customer_name"] = self.customers.get(b["customer_id"], {}).get("name", "")
        b["customer_phone"] = self.customers.get(b["customer_id"], {}).get("phone", "")
        b["supplier_name"] = self.suppliers.get(b["supplier_id"], {}).get("name", "") if b["supplier_id"] else ""

        # dated profit events so period reports are exact
        if committed:
            self.events.append({
                "date": b["booking_date"], "booking_id": b["id"], "type": "booking",
                "bookings": 1,
                "selling": base["selling_price"], "purchase": base["purchase_cost"],
                "sales": income_core, "billed": income_core + base["tax_amount"],
                "cost": base["purchase_cost"], "markup": base["selling_price"] - base["purchase_cost"],
                "commission": base["commission"], "service": base["service_charge"],
                "other_booking_income": base["other_income"], "tax": base["tax_amount"],
            })
            for a in self.adjustments.get(b["id"], []):
                s = a["sign"]
                cv = {f: s * convert(a[f], a["exchange_rate"]) for f in ADJ_FIELDS}
                self.events.append({
                    "date": a["adj_date"], "booking_id": b["id"], "type": "adjustment", "adjustment_id": a["id"],
                    "sales": cv["extra_customer_charge"] - (cv["customer_credit"] - cv["customer_credit_tax"]),
                    "billed": cv["extra_customer_charge"] - cv["customer_credit"],
                    "cost": cv["extra_supplier_charge"] - cv["supplier_credit"],
                    "commission": -cv["commission_reversal"],
                    "tax": -cv["customer_credit_tax"],
                    "customer_credits": cv["customer_credit"],
                    "supplier_credits": cv["supplier_credit"],
                    "commission_reversed": cv["commission_reversal"],
                    "extra_customer_charges": cv["extra_customer_charge"],
                    "extra_supplier_charges": cv["extra_supplier_charge"],
                })
        for t in self.direct_by_booking.get(b["id"], []):
            if True:
                self.events.append({
                    "date": t["txn_date"], "booking_id": b["id"], "type": "direct_expense",
                    "direct_expenses": t["sign"] * t["base_amount"],
                })

    # ------------------------------------------------------------- filters
    def booking_matches(self, b, f):
        if not f:
            return True
        if f.get("customer_id") and str(b["customer_id"]) != str(f["customer_id"]):
            return False
        if f.get("supplier_id") and str(b["supplier_id"] or "") != str(f["supplier_id"]):
            return False
        if f.get("currency") and b["currency"] != f["currency"]:
            return False
        if f.get("status") and b["status"] != f["status"]:
            return False
        if f.get("service_type") and b["service_type"] != f["service_type"]:
            return False
        if f.get("airline"):
            needle = f["airline"].strip().lower()
            if not any(needle in (a or "").lower() for a in b["airlines"]):
                return False
        return True

    def txn_matches(self, t, f):
        if not f:
            return True
        if f.get("customer_id") and str(t["customer_id"] or "") != str(f["customer_id"]):
            return False
        if f.get("supplier_id") and str(t["supplier_id"] or "") != str(f["supplier_id"]):
            return False
        if f.get("currency") and t["currency"] != f["currency"]:
            return False
        if f.get("account_id") and str(t["account_id"]) != str(f["account_id"]) and str(t.get("to_account_id") or "") != str(f["account_id"]):
            return False
        booking_filters = {k: f.get(k) for k in ("airline", "status", "service_type") if f.get(k)}
        if booking_filters:
            if not t["booking_id"]:
                return False
            b = self.bookings.get(t["booking_id"])
            if not b or not self.booking_matches(b, booking_filters):
                return False
        return True

    # -------------------------------------------------------------- summaries
    def profit_totals(self, frm=None, to=None, f=None):
        tot = _zero()
        for e in self.events:
            if not in_range(e["date"], frm, to):
                continue
            b = self.bookings[e["booking_id"]]
            if not self.booking_matches(b, f):
                continue
            for k2, v in e.items():
                if k2 in tot:
                    tot[k2] += v
        tot["gross_profit"] = tot["sales"] - tot["cost"] + tot["commission"] - tot["direct_expenses"]
        return tot

    def cash_totals(self, frm=None, to=None, f=None):
        out = {k: 0 for k in ("customer_receipts", "supplier_payments", "customer_refunds", "supplier_receipts",
                              "expenses", "operating_expenses", "direct_expenses_paid", "other_income",
                              "cash_in", "cash_out", "net_cash_flow", "loan_received", "loan_repaid", "net_financing")}
        for t in self.transactions:
            if not in_range(t["txn_date"], frm, to) or not self.txn_matches(t, f):
                continue
            v = t["sign"] * t["base_amount"]
            k = t["kind"]
            if k == "customer_receipt":
                out["customer_receipts"] += v
            elif k == "supplier_payment":
                out["supplier_payments"] += v
            elif k == "customer_refund":
                out["customer_refunds"] += v
            elif k == "supplier_receipt":
                out["supplier_receipts"] += v
            elif k == "expense":
                out["expenses"] += v
                if t["is_direct"] and t["booking_id"]:
                    out["direct_expenses_paid"] += v
                else:
                    out["operating_expenses"] += v
            elif k == "other_income":
                out["other_income"] += v
            elif k == "loan_received":
                out["loan_received"] += v
            elif k == "loan_repayment":
                out["loan_repaid"] += v
            if k in INFLOW_KINDS:
                out["cash_in"] += v
            elif k in OUTFLOW_KINDS:
                out["cash_out"] += v
        out["net_cash_flow"] = out["cash_in"] - out["cash_out"]
        out["net_financing"] = out["loan_received"] - out["loan_repaid"]
        return out

    def period_summary(self, frm=None, to=None, f=None):
        p = self.profit_totals(frm, to, f)
        cash = self.cash_totals(frm, to, f)
        # operating expenses & other income only apply when not filtering to specific bookings
        booking_scoped = bool(f and any(f.get(k) for k in ("airline", "status", "service_type", "supplier_id", "customer_id")))
        op_exp = 0 if booking_scoped else cash["operating_expenses"]
        other_inc = 0 if booking_scoped else cash["other_income"]
        p.update({
            "operating_expenses": op_exp,
            "other_income": other_inc,
            "net_profit": p["gross_profit"] + other_inc - op_exp,
        })
        p["cash"] = cash
        return p

    # ------------------------------------------------------------ balances
    def customer_balances(self, as_of=None):
        res = {}
        for cid, c in self.customers.items():
            res[cid] = {"customer_id": cid, "name": c["name"], "phone": c["phone"], "billed": 0, "credits": 0,
                        "received": 0, "refunded": 0, "allocated_received": 0, "allocated_refunded": 0,
                        "outstanding": 0, "unallocated": 0, "bookings": 0}
        for b in self.bookings.values():
            r = res[b["customer_id"]]
            r["bookings"] += 1
            r["billed"] += b["calc"]["customer_payable"]
            r["credits"] += b["calc"]["customer_credits"]
            r["allocated_received"] += b["calc"]["received"]
            r["allocated_refunded"] += b["calc"]["refunded"]
        for t in self.transactions:
            if t["customer_id"] and t["kind"] in ("customer_receipt", "customer_refund"):
                r = res[t["customer_id"]]
                v = t["sign"] * t["base_amount"]
                if t["kind"] == "customer_receipt":
                    r["received"] += v
                else:
                    r["refunded"] += v
        for r in res.values():
            r["outstanding"] = r["billed"] - r["credits"] - r["received"] + r["refunded"]
            r["unallocated"] = (r["received"] - r["allocated_received"]) - (r["refunded"] - r["allocated_refunded"])
        return res

    def supplier_balances(self):
        res = {}
        for sid, s in self.suppliers.items():
            res[sid] = {"supplier_id": sid, "name": s["name"], "kind": s["kind"], "payable": 0, "credits": 0,
                        "paid": 0, "refunds": 0, "allocated_paid": 0, "allocated_refunds": 0,
                        "outstanding": 0, "unallocated": 0, "bookings": 0}
        for b in self.bookings.values():
            if not b["supplier_id"]:
                continue
            r = res[b["supplier_id"]]
            r["bookings"] += 1
            r["payable"] += b["calc"]["supplier_payable"]
            r["credits"] += b["calc"]["supplier_credits"]
            r["allocated_paid"] += b["calc"]["paid_supplier"]
            r["allocated_refunds"] += b["calc"]["supplier_refunds"]
        for t in self.transactions:
            if t["supplier_id"] and t["kind"] in ("supplier_payment", "supplier_receipt"):
                r = res[t["supplier_id"]]
                v = t["sign"] * t["base_amount"]
                if t["kind"] == "supplier_payment":
                    r["paid"] += v
                else:
                    r["refunds"] += v
        for r in res.values():
            r["outstanding"] = r["payable"] - r["credits"] - r["paid"] + r["refunds"]
            r["unallocated"] = (r["paid"] - r["allocated_paid"]) - (r["refunds"] - r["allocated_refunds"])
        return res

    def txn_account_effects(self, t):
        """Yield (account_id, signed amount in account currency) for a transaction."""
        s = t["sign"]
        if t["kind"] in INFLOW_KINDS or t["kind"] in FINANCING_IN_KINDS:
            yield t["account_id"], s * t["amount"]
        elif t["kind"] in OUTFLOW_KINDS or t["kind"] in FINANCING_OUT_KINDS:
            yield t["account_id"], -s * t["amount"]
        elif t["kind"] == "transfer":
            yield t["account_id"], -s * t["amount"]
            yield t["to_account_id"], s * (t["to_amount"] if t["to_amount"] is not None else t["amount"])

    def account_balances(self, as_of=None):
        res = {}
        for aid, a in self.accounts.items():
            res[aid] = {"account_id": aid, "name": a["name"], "kind": a["kind"], "currency": a["currency"],
                        "opening": a["opening_balance"], "inflow": 0, "outflow": 0, "balance": a["opening_balance"],
                        "reconciled_balance": a["opening_balance"], "active": a["active"]}
        for t in self.transactions:
            if as_of and t["txn_date"] > as_of:
                continue
            for aid, v in self.txn_account_effects(t):
                r = res[aid]
                if v >= 0:
                    r["inflow"] += v
                else:
                    r["outflow"] += -v
                r["balance"] += v
                if t["reconciled"]:
                    r["reconciled_balance"] += v
        return res

    # ------------------------------------------------------------ helpers
    def booking_row(self, b):
        c = b["calc"]
        return {
            "id": b["id"], "ref": b["ref"], "pnr": b["pnr"], "status": b["status"], "service_type": b["service_type"],
            "booking_date": b["booking_date"], "travel_date": b["travel_date"], "return_date": b["return_date"],
            "customer_id": b["customer_id"], "customer_name": b["customer_name"], "customer_phone": b["customer_phone"],
            "supplier_id": b["supplier_id"], "supplier_name": b["supplier_name"],
            "airlines": b["airlines"], "route": b["route"], "pax_count": b["pax_count"], "lead_pax": b["lead_pax"],
            "currency": b["currency"], "exchange_rate": b["exchange_rate"], "committed": b["committed"],
            "selling_price": b["selling_price"], "purchase_cost": b["purchase_cost"],
            "customer_payable": c["customer_payable"], "customer_outstanding": c["customer_outstanding"],
            "supplier_outstanding": c["supplier_outstanding"], "gross_profit": c["gross_profit"],
            "revenue": c["revenue"], "cost": c["cost"], "commission_net": c["commission_net"],
            "tax_net": c["tax_net"], "direct_expenses": c["direct_expenses"], "markup": c["markup"],
            "received": c["received"], "payment_status": c["payment_status"],
        }


# ---------------------------------------------------------------- dashboard
def month_key(d):
    return d[:7]


def dashboard(conn, frm, to, today=None):
    L = Ledger(conn)
    today = today or date.today().isoformat()
    s = L.period_summary(frm, to)
    month_start = today[:8] + "01"
    year_start = today[:5] + "01-01"
    today_tot = L.profit_totals(today, today)
    month_tot = L.profit_totals(month_start, today)
    year_tot = L.profit_totals(year_start, today)

    cb = L.customer_balances()
    sb = L.supplier_balances()
    cust_out = sum(r["outstanding"] for r in cb.values() if r["outstanding"] > 0)
    cust_credit = sum(-r["outstanding"] for r in cb.values() if r["outstanding"] < 0)
    sup_out = sum(r["outstanding"] for r in sb.values() if r["outstanding"] > 0)
    sup_credit = sum(-r["outstanding"] for r in sb.values() if r["outstanding"] < 0)
    pending = [L.booking_row(b) for b in L.bookings.values()
               if b["committed"] and b["calc"]["customer_outstanding"] > 0]
    pending.sort(key=lambda r: (r["travel_date"] or "9999", r["ref"]))

    cancellations = [a for a in L.all_adjustments if in_range(a["adj_date"], frm, to)
                     and a["kind"] in ("cancellation", "refund") and a["sign"] == 1 and not a["reversed_by"]]

    accounts = sorted(L.account_balances().values(), key=lambda r: (r["kind"], r["name"]))

    # chart: daily when range <= 62 days else monthly
    chart = []
    try:
        d0 = datetime.strptime(frm, "%Y-%m-%d").date() if frm else None
        d1 = datetime.strptime(to, "%Y-%m-%d").date() if to else None
    except ValueError:
        d0 = d1 = None
    if d0 and d1 and (d1 - d0).days <= 62:
        buckets = {}
        d = d0
        while d <= d1:
            buckets[d.isoformat()] = {"label": d.strftime("%d %b"), "key": d.isoformat(), "sales": 0, "gross_profit": 0, "cost": 0}
            d += timedelta(days=1)
        keyf = lambda x: x[:10]
    else:
        end = d1 or date.today()
        start = d0 or date(end.year, end.month, 1) - timedelta(days=334)
        buckets = {}
        y, m = start.year, start.month
        while (y, m) <= (end.year, end.month):
            k = "%04d-%02d" % (y, m)
            buckets[k] = {"label": date(y, m, 1).strftime("%b %y"), "key": k, "sales": 0, "gross_profit": 0, "cost": 0}
            m += 1
            if m > 12:
                y, m = y + 1, 1
        keyf = month_key
    for e in L.events:
        k = keyf(e["date"])
        if k in buckets and in_range(e["date"], frm, to):
            bkt = buckets[k]
            bkt["sales"] += e.get("sales", 0)
            bkt["cost"] += e.get("cost", 0)
            bkt["gross_profit"] += e.get("sales", 0) - e.get("cost", 0) + e.get("commission", 0) - e.get("direct_expenses", 0)
    chart = list(buckets.values())

    recent_bookings = [L.booking_row(b) for b in sorted(L.bookings.values(), key=lambda b: (b["created_at"], b["id"]), reverse=True)[:8]]
    recent_txns = []
    for t in sorted(L.transactions, key=lambda t: (t["created_at"], t["id"]), reverse=True)[:8]:
        recent_txns.append(txn_row(L, t))

    horizon = (datetime.strptime(today, "%Y-%m-%d").date() + timedelta(days=14)).isoformat()
    upcoming = []
    for b in L.bookings.values():
        if b["status"] in ("cancelled", "refunded", "enquiry"):
            continue
        segs = L.segments.get(b["id"], [])
        for sg in segs:
            dep = (sg.get("dep_at") or "")[:10]
            if dep and today <= dep <= horizon:
                upcoming.append({"booking_id": b["id"], "ref": b["ref"], "pnr": b["pnr"], "dep_at": sg["dep_at"],
                                 "airline": sg["airline"], "flight_no": sg["flight_no"], "origin": sg["origin"],
                                 "destination": sg["destination"], "lead_pax": b["lead_pax"], "pax_count": b["pax_count"],
                                 "customer_name": b["customer_name"], "outstanding": b["calc"]["customer_outstanding"]})
        if not segs and b["travel_date"] and today <= b["travel_date"] <= horizon:
            upcoming.append({"booking_id": b["id"], "ref": b["ref"], "pnr": b["pnr"], "dep_at": b["travel_date"],
                             "airline": "", "flight_no": "", "origin": "", "destination": "", "lead_pax": b["lead_pax"],
                             "pax_count": b["pax_count"], "customer_name": b["customer_name"],
                             "outstanding": b["calc"]["customer_outstanding"]})
    upcoming.sort(key=lambda r: r["dep_at"] or "")
    follow_ups = []
    for c in L.customers.values():
        if c["follow_up_date"] and c["follow_up_date"] <= horizon and c["active"]:
            follow_ups.append({"customer_id": c["id"], "name": c["name"], "phone": c["phone"],
                               "follow_up_date": c["follow_up_date"], "follow_up_note": c["follow_up_note"],
                               "overdue": c["follow_up_date"] < today,
                               "outstanding": cb[c["id"]]["outstanding"]})
    follow_ups.sort(key=lambda r: r["follow_up_date"])

    return {
        "base_currency": L.base,
        "period": {"from": frm, "to": to},
        "summary": s,
        "today": {"bookings": today_tot["bookings"], "sales": today_tot["sales"], "gross_profit": today_tot["gross_profit"]},
        "month": {"bookings": month_tot["bookings"], "sales": month_tot["sales"], "gross_profit": month_tot["gross_profit"]},
        "year": {"bookings": year_tot["bookings"], "sales": year_tot["sales"], "gross_profit": year_tot["gross_profit"]},
        "customer_outstanding": cust_out,
        "customer_credit_balances": cust_credit,
        "supplier_outstanding": sup_out,
        "supplier_credit_balances": sup_credit,
        "pending_count": len(pending),
        "pending": pending[:8],
        "cancellations_count": len(cancellations),
        "accounts": accounts,
        "chart": chart,
        "recent_bookings": recent_bookings,
        "recent_transactions": recent_txns,
        "upcoming": upcoming[:12],
        "follow_ups": follow_ups[:12],
    }


def txn_row(L, t):
    acc = L.accounts.get(t["account_id"], {})
    to_acc = L.accounts.get(t["to_account_id"], {}) if t.get("to_account_id") else {}
    b = L.bookings.get(t["booking_id"]) if t["booking_id"] else None
    allocs = []
    for a in L.allocations.get(t["id"], []):
        bb = L.bookings.get(a["booking_id"])
        allocs.append({"booking_id": a["booking_id"], "ref": bb["ref"] if bb else "", "amount": a["amount"]})
    allocated = sum(a["amount"] for a in allocs)
    return {
        "id": t["id"], "txn_no": t["txn_no"], "uid": t["uid"], "txn_date": t["txn_date"], "kind": t["kind"],
        "kind_label": KIND_LABELS.get(t["kind"], t["kind"]),
        "sign": t["sign"], "amount": t["amount"], "currency": t["currency"], "exchange_rate": t["exchange_rate"],
        "rate_date": t["rate_date"], "base_amount": t["base_amount"], "signed_base": t["sign"] * t["base_amount"],
        "account_id": t["account_id"], "account_name": acc.get("name", ""),
        "to_account_id": t.get("to_account_id"), "to_account_name": to_acc.get("name", ""), "to_amount": t.get("to_amount"),
        "method": t["method"], "reference": t["reference"], "category": t["category"],
        "customer_id": t["customer_id"], "customer_name": L.customers.get(t["customer_id"], {}).get("name", "") if t["customer_id"] else "",
        "supplier_id": t["supplier_id"], "supplier_name": L.suppliers.get(t["supplier_id"], {}).get("name", "") if t["supplier_id"] else "",
        "booking_id": t["booking_id"], "booking_ref": b["ref"] if b else "", "is_direct": t["is_direct"],
        "payee": t["payee"], "notes": t["notes"], "reversal_of": t["reversal_of"], "reversed_by": t["reversed_by"],
        "reconciled": t["reconciled"], "reconciled_on": t["reconciled_on"], "created_at": t["created_at"],
        "debt_id": t.get("debt_id"), "debt_name": (L.debts.get(t.get("debt_id")) or {}).get("creditor", ""),
        "debt_no": (L.debts.get(t.get("debt_id")) or {}).get("debt_no", ""),
        "allocations": allocs, "allocated": allocated, "unallocated": t["base_amount"] - allocated if t["kind"] in (
            "customer_receipt", "customer_refund", "supplier_payment", "supplier_receipt") else 0,
    }
