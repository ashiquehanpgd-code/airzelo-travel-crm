"""Reports. Each report returns {title, columns, rows, totals} built from the
same Ledger used by the dashboard, so the figures always agree."""
import csv
import io
from datetime import date

from .finance import Ledger, txn_row, in_range, KIND_LABELS, INFLOW_KINDS, OUTFLOW_KINDS
from .money import fmt, convert
from .errors import ValidationError, NotFound

M = "money"  # column type marker


def col(key, label, kind="text"):
    return {"key": key, "label": label, "type": kind}


def _filters(p):
    return {k: p.get(k) for k in ("customer_id", "supplier_id", "currency", "status", "airline", "service_type") if p.get(k)}


def _totals(rows_, columns, skip=()):
    t = {}
    for c in columns:
        if c["type"] == M and c["key"] not in skip:
            t[c["key"]] = sum(int(r.get(c["key"]) or 0) for r in rows_)
    return t


def report_sales(L, p):
    frm, to, f = p.get("from"), p.get("to"), _filters(p)
    out = []
    for b in L.bookings.values():
        if not b["committed"] or not in_range(b["booking_date"], frm, to) or not L.booking_matches(b, f):
            continue
        base = b["calc"]["base"]
        out.append({"booking_date": b["booking_date"], "ref": b["ref"], "pnr": b["pnr"] or "", "customer": b["customer_name"],
                    "supplier": b["supplier_name"], "airline": ", ".join(b["airlines"]), "route": b["route"],
                    "status": b["status"], "currency": b["currency"], "rate": b["exchange_rate"],
                    "selling": base["selling_price"], "service": base["service_charge"], "other": base["other_income"],
                    "tax": base["tax_amount"],
                    "billed": base["selling_price"] + base["service_charge"] + base["other_income"] + base["tax_amount"],
                    "_booking_id": b["id"]})
    out.sort(key=lambda r: (r["booking_date"], r["ref"]))
    cols = [col("booking_date", "Date", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("customer", "Customer"),
            col("supplier", "Supplier"), col("airline", "Airline"), col("route", "Route"), col("status", "Status", "status"),
            col("currency", "Cur"), col("selling", "Ticket fare", M), col("service", "Service charge", M),
            col("other", "Other income", M), col("tax", "Tax", M), col("billed", "Total billed", M)]
    return {"title": "Sales report", "columns": cols, "rows": out, "totals": _totals(out, cols),
            "note": "Gross bookings recorded in the period (credits for cancellations appear in the refunds report and in profit)."}


def report_bookings(L, p):
    frm, to, f = p.get("from"), p.get("to"), _filters(p)
    out = []
    for b in L.bookings.values():
        if not in_range(b["booking_date"], frm, to) or not L.booking_matches(b, f):
            continue
        out.append({"booking_date": b["booking_date"], "ref": b["ref"], "pnr": b["pnr"] or "", "customer": b["customer_name"],
                    "lead_pax": b["lead_pax"], "pax": b["pax_count"], "airline": ", ".join(b["airlines"]), "route": b["route"],
                    "travel_date": b["travel_date"] or "", "supplier": b["supplier_name"], "status": b["status"],
                    "payment": b["calc"]["payment_status"], "payable": b["calc"]["customer_payable"] - b["calc"]["customer_credits"],
                    "outstanding": b["calc"]["customer_outstanding"], "_booking_id": b["id"]})
    out.sort(key=lambda r: (r["booking_date"], r["ref"]))
    cols = [col("booking_date", "Booked", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("customer", "Customer"),
            col("lead_pax", "Lead passenger"), col("pax", "Pax", "number"), col("airline", "Airline"), col("route", "Route"),
            col("travel_date", "Travel", "date"), col("supplier", "Supplier"), col("status", "Status", "status"),
            col("payment", "Payment", "status"), col("payable", "Net payable", M), col("outstanding", "Outstanding", M)]
    t = _totals(out, cols)
    t["pax"] = sum(r["pax"] for r in out)
    return {"title": "Booking report", "columns": cols, "rows": out, "totals": t}


def report_profit(L, p):
    """Per-booking profit for events dated within the period."""
    frm, to, f = p.get("from"), p.get("to"), _filters(p)
    agg = {}
    for e in L.events:
        if not in_range(e["date"], frm, to):
            continue
        b = L.bookings[e["booking_id"]]
        if not L.booking_matches(b, f):
            continue
        r = agg.setdefault(b["id"], {"booking_date": b["booking_date"], "ref": b["ref"], "pnr": b["pnr"] or "",
                                     "customer": b["customer_name"], "airline": ", ".join(b["airlines"]),
                                     "supplier": b["supplier_name"], "status": b["status"],
                                     "sales": 0, "cost": 0, "markup": 0, "commission": 0, "direct": 0, "gross_profit": 0,
                                     "_booking_id": b["id"]})
        r["sales"] += e.get("sales", 0)
        r["cost"] += e.get("cost", 0)
        r["markup"] += e.get("markup", 0)
        r["commission"] += e.get("commission", 0)
        r["direct"] += e.get("direct_expenses", 0)
    out = list(agg.values())
    for r in out:
        r["gross_profit"] = r["sales"] - r["cost"] + r["commission"] - r["direct"]
        r["margin"] = ("%.1f%%" % (100.0 * r["gross_profit"] / r["sales"])) if r["sales"] else ""
    out.sort(key=lambda r: (r["booking_date"], r["ref"]))
    cols = [col("booking_date", "Date", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("customer", "Customer"),
            col("airline", "Airline"), col("supplier", "Supplier"), col("status", "Status", "status"),
            col("sales", "Net sales (ex tax)", M), col("cost", "Net cost", M), col("markup", "Markup", M),
            col("commission", "Commission", M), col("direct", "Direct expenses", M), col("gross_profit", "Gross profit", M),
            col("margin", "Margin")]
    s = L.period_summary(frm, to, f)
    summary = [
        {"label": "Net sales (ex tax)", "value": s["sales"]}, {"label": "Net purchase cost", "value": s["cost"]},
        {"label": "Commission (net)", "value": s["commission"]}, {"label": "Direct booking expenses", "value": s["direct_expenses"]},
        {"label": "Gross profit", "value": s["gross_profit"], "strong": True},
        {"label": "Other income", "value": s["other_income"]}, {"label": "Operating expenses", "value": s["operating_expenses"]},
        {"label": "Net profit", "value": s["net_profit"], "strong": True},
        {"label": "Tax collected (liability, excluded)", "value": s["tax"]},
    ]
    return {"title": "Profit report", "columns": cols, "rows": out, "totals": _totals(out, cols), "summary": summary,
            "note": "Net sales are after credits; tax is excluded from revenue and profit."}


def report_customer_outstanding(L, p):
    bal = L.customer_balances()
    show_all = p.get("show") == "all"
    out = []
    for r in bal.values():
        if p.get("customer_id") and str(r["customer_id"]) != str(p["customer_id"]):
            continue
        if not show_all and r["outstanding"] == 0:
            continue
        out.append({"customer": r["name"], "phone": r["phone"] or "", "bookings": r["bookings"], "billed": r["billed"],
                    "credits": r["credits"], "received": r["received"], "refunded": r["refunded"],
                    "unallocated": r["unallocated"], "outstanding": r["outstanding"], "_customer_id": r["customer_id"]})
    out.sort(key=lambda r: -r["outstanding"])
    cols = [col("customer", "Customer"), col("phone", "Phone"), col("bookings", "Bookings", "number"),
            col("billed", "Billed", M), col("credits", "Credits", M), col("received", "Received", M),
            col("refunded", "Refunded", M), col("unallocated", "Unallocated advance", M), col("outstanding", "Outstanding", M)]
    return {"title": "Customer outstanding", "columns": cols, "rows": out, "totals": _totals(out, cols),
            "note": "Positive = customer owes you. Negative = you owe the customer (advance or refund due)."}


def report_supplier_outstanding(L, p):
    bal = L.supplier_balances()
    show_all = p.get("show") == "all"
    out = []
    for r in bal.values():
        if p.get("supplier_id") and str(r["supplier_id"]) != str(p["supplier_id"]):
            continue
        if not show_all and r["outstanding"] == 0:
            continue
        out.append({"supplier": r["name"], "kind": r["kind"], "bookings": r["bookings"], "payable": r["payable"],
                    "credits": r["credits"], "paid": r["paid"], "refunds": r["refunds"], "unallocated": r["unallocated"],
                    "outstanding": r["outstanding"], "_supplier_id": r["supplier_id"]})
    out.sort(key=lambda r: -r["outstanding"])
    cols = [col("supplier", "Supplier"), col("kind", "Type"), col("bookings", "Bookings", "number"), col("payable", "Payable", M),
            col("credits", "Credits & commission", M), col("paid", "Paid", M), col("refunds", "Refunds received", M),
            col("unallocated", "Unallocated", M), col("outstanding", "Outstanding", M)]
    return {"title": "Supplier outstanding", "columns": cols, "rows": out, "totals": _totals(out, cols),
            "note": "Positive = you owe the supplier. Negative = the supplier owes you (credit/refund due)."}


def report_expenses(L, p):
    frm, to = p.get("from"), p.get("to")
    out = []
    by_cat = {}
    for t in L.transactions:
        if t["kind"] != "expense" or not in_range(t["txn_date"], frm, to):
            continue
        if p.get("currency") and t["currency"] != p["currency"]:
            continue
        if p.get("category") and t["category"] != p["category"]:
            continue
        r = txn_row(L, t)
        v = t["sign"] * t["base_amount"]
        out.append({"txn_date": t["txn_date"], "txn_no": t["txn_no"], "category": t["category"],
                    "type": "Direct (booking %s)" % r["booking_ref"] if t["is_direct"] else "Operating",
                    "payee": t["payee"] or r["supplier_name"] or "", "account": r["account_name"], "method": t["method"],
                    "reference": t["reference"] or "", "currency": t["currency"],
                    "amount": t["sign"] * t["amount"], "base": v, "notes": t["notes"] or "", "_txn_id": t["id"]})
        by_cat[t["category"]] = by_cat.get(t["category"], 0) + v
    out.sort(key=lambda r: (r["txn_date"], r["txn_no"]))
    cols = [col("txn_date", "Date", "date"), col("txn_no", "No."), col("category", "Category"), col("type", "Type"),
            col("payee", "Payee"), col("account", "Paid from"), col("method", "Method"), col("reference", "Reference"),
            col("currency", "Cur"), col("amount", "Amount", "money_raw"), col("base", "Amount (" + L.base + ")", M),
            col("notes", "Notes")]
    summary = [{"label": k, "value": v} for k, v in sorted(by_cat.items(), key=lambda kv: -kv[1])]
    return {"title": "Expense report", "columns": cols, "rows": out, "totals": _totals(out, cols), "summary": summary}


def report_cashbook(L, p):
    frm, to = p.get("from"), p.get("to")
    acc_id = p.get("account_id")
    accounts = [a for a in L.accounts.values() if not acc_id or str(a["id"]) == str(acc_id)]
    if acc_id and not accounts:
        raise NotFound("Account not found")
    single = len(accounts) == 1
    out = []
    opening = 0
    ids = {a["id"] for a in accounts}
    if single:
        opening = accounts[0]["opening_balance"]
    for t in L.transactions:
        for aid, eff in L.txn_account_effects(t):
            if aid not in ids:
                continue
            if single and frm and t["txn_date"] < frm:
                opening += eff
                continue
            if not in_range(t["txn_date"], frm, to):
                continue
            r = txn_row(L, t)
            party = r["customer_name"] or r["supplier_name"] or r["payee"] or (r["to_account_name"] if t["kind"] == "transfer" else "")
            out.append({"txn_date": t["txn_date"], "txn_no": t["txn_no"], "account": L.accounts[aid]["name"],
                        "type": KIND_LABELS[t["kind"]] + (" (reversal)" if t["sign"] < 0 else ""), "party": party or "",
                        "booking": r["booking_ref"], "reference": t["reference"] or "", "method": t["method"],
                        "currency": L.accounts[aid]["currency"], "in": eff if eff > 0 else 0, "out": -eff if eff < 0 else 0,
                        "balance": 0, "_txn_id": t["id"], "_sup": t["supplier_id"], "_kind": t["kind"]})
    out.sort(key=lambda r: (r["txn_date"], r["txn_no"]))
    if single:
        bal = opening
        for r in out:
            bal += r["in"] - r["out"]
            r["balance"] = bal
    closing = opening + sum(r["in"] - r["out"] for r in out)
    # supplier / type filters narrow the rows shown; running balances stay those of the whole account
    sup_id, kinds = p.get("supplier_id"), set(filter(None, (p.get("kind") or "").split(",")))
    filtered = bool(sup_id or kinds)
    if sup_id:
        out = [r for r in out if str(r["_sup"] or "") == str(sup_id)]
    if kinds:
        out = [r for r in out if r["_kind"] in kinds]
    cols = [col("txn_date", "Date", "date"), col("txn_no", "No."), col("account", "Account"), col("type", "Type"),
            col("party", "Party"), col("booking", "Booking"), col("reference", "Reference"), col("method", "Method"),
            col("currency", "Cur"), col("in", "Money in", "money_raw"), col("out", "Money out", "money_raw")]
    if single:
        cols.append(col("balance", "Balance", "money_raw"))
    t = {}
    currencies = {r["currency"] for r in out}
    if len(currencies) <= 1:
        t = {"in": sum(r["in"] for r in out), "out": sum(r["out"] for r in out)}
    summary = []
    if single:
        summary = [{"label": "Opening balance", "value": opening, "raw": True},
                   {"label": "Money in", "value": sum(r["in"] for r in out), "raw": True},
                   {"label": "Money out", "value": sum(r["out"] for r in out), "raw": True},
                   {"label": "Closing balance", "value": closing, "raw": True, "strong": True}]
    note = None if single else "Choose a single account to see running balances. Amounts are in each account's currency."
    if filtered:
        note = ((note + " ") if note else "") + ("Filtered view: money in / out add up the rows shown; the balance column and closing balance "
                                                 "are the account's full running balance.")
    return {"title": "Cashbook" + (" - " + accounts[0]["name"] if single else ""), "columns": cols, "rows": out,
            "totals": t, "summary": summary, "note": note}


def report_refunds(L, p):
    frm, to, f = p.get("from"), p.get("to"), _filters(p)
    out = []
    for a in L.all_adjustments:
        b = L.bookings[a["booking_id"]]
        if not in_range(a["adj_date"], frm, to) or not L.booking_matches(b, f):
            continue
        s = a["sign"]
        cv = lambda k: s * convert(a[k], a["exchange_rate"])
        out.append({"date": a["adj_date"], "no": a["adj_no"], "type": a["kind"].replace("_", " ") + (" (reversal)" if s < 0 else ""),
                    "booking": b["ref"], "pnr": b["pnr"] or "", "customer": b["customer_name"], "supplier": b["supplier_name"],
                    "customer_credit": cv("customer_credit"), "supplier_credit": cv("supplier_credit"),
                    "commission_reversal": cv("commission_reversal"), "charges": cv("extra_customer_charge"),
                    "supplier_charges": cv("extra_supplier_charge"), "cash": 0, "notes": a["notes"] or "", "_booking_id": b["id"]})
    for t in L.transactions:
        if t["kind"] not in ("customer_refund", "supplier_receipt") or not in_range(t["txn_date"], frm, to):
            continue
        if not L.txn_matches(t, f):
            continue
        r = txn_row(L, t)
        out.append({"date": t["txn_date"], "no": t["txn_no"], "type": KIND_LABELS[t["kind"]] + (" (reversal)" if t["sign"] < 0 else ""),
                    "booking": r["booking_ref"], "pnr": "", "customer": r["customer_name"], "supplier": r["supplier_name"],
                    "customer_credit": 0, "supplier_credit": 0, "commission_reversal": 0, "charges": 0, "supplier_charges": 0,
                    "cash": (-1 if t["kind"] == "customer_refund" else 1) * t["sign"] * t["base_amount"],
                    "notes": t["notes"] or "", "_txn_id": t["id"]})
    out.sort(key=lambda r: (r["date"], r["no"]))
    cols = [col("date", "Date", "date"), col("no", "No."), col("type", "Type"), col("booking", "Booking"), col("pnr", "PNR"),
            col("customer", "Customer"), col("supplier", "Supplier"), col("customer_credit", "Customer credit", M),
            col("supplier_credit", "Supplier credit", M), col("commission_reversal", "Commission reversed", M),
            col("charges", "Charges to customer", M), col("supplier_charges", "Supplier charges", M),
            col("cash", "Cash in(+)/out(-)", M), col("notes", "Notes")]
    return {"title": "Refunds & cancellations", "columns": cols, "rows": out, "totals": _totals(out, cols)}


def report_commission(L, p):
    frm, to, f = p.get("from"), p.get("to"), _filters(p)
    agg = {}
    for e in L.events:
        if not e.get("commission") or not in_range(e["date"], frm, to):
            continue
        b = L.bookings[e["booking_id"]]
        if not L.booking_matches(b, f):
            continue
        r = agg.setdefault(b["id"], {"booking_date": b["booking_date"], "ref": b["ref"], "pnr": b["pnr"] or "",
                                     "supplier": b["supplier_name"], "airline": ", ".join(b["airlines"]),
                                     "earned": 0, "reversed": 0, "net": 0, "_booking_id": b["id"]})
        if e["commission"] > 0:
            r["earned"] += e["commission"]
        else:
            r["reversed"] += -e["commission"]
        r["net"] = r["earned"] - r["reversed"]
    out = sorted(agg.values(), key=lambda r: (r["booking_date"], r["ref"]))
    cols = [col("booking_date", "Date", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("supplier", "Supplier"),
            col("airline", "Airline"), col("earned", "Earned", M), col("reversed", "Reversed", M), col("net", "Net commission", M)]
    return {"title": "Commission income", "columns": cols, "rows": out, "totals": _totals(out, cols)}


def _period_rows(L, periods, f=None):
    out = []
    for label, frm, to in periods:
        s = L.period_summary(frm, to, f)
        c = s["cash"]
        out.append({"period": label, "bookings": s["bookings"], "sales": s["sales"], "cost": s["cost"], "commission": s["commission"],
                    "direct": s["direct_expenses"], "gross_profit": s["gross_profit"], "other_income": s["other_income"],
                    "op_expenses": s["operating_expenses"], "net_profit": s["net_profit"], "tax": s["tax"],
                    "cash_in": c["cash_in"], "cash_out": c["cash_out"], "net_cash": c["net_cash_flow"]})
    return out


PERIOD_COLS = [col("period", "Period"), col("bookings", "Bookings", "number"), col("sales", "Net sales", M), col("cost", "Net cost", M),
               col("commission", "Commission", M), col("direct", "Direct exp.", M), col("gross_profit", "Gross profit", M),
               col("other_income", "Other income", M), col("op_expenses", "Operating exp.", M), col("net_profit", "Net profit", M),
               col("tax", "Tax collected", M), col("cash_in", "Cash in", M), col("cash_out", "Cash out", M), col("net_cash", "Net cash flow", M)]


def report_monthly(L, p):
    try:
        year = int(p.get("year") or date.today().year)
    except ValueError:
        raise ValidationError("Year is invalid")
    import calendar
    periods = []
    for m in range(1, 13):
        last = calendar.monthrange(year, m)[1]
        periods.append((date(year, m, 1).strftime("%b %Y"), "%04d-%02d-01" % (year, m), "%04d-%02d-%02d" % (year, m, last)))
    out = _period_rows(L, periods)
    t = _totals(out, PERIOD_COLS)
    t["bookings"] = sum(r["bookings"] for r in out)
    return {"title": "Monthly summary %d" % year, "columns": PERIOD_COLS, "rows": out, "totals": t}


def report_yearly(L, p):
    years = set()
    for e in L.events:
        years.add(int(e["date"][:4]))
    for t in L.transactions:
        years.add(int(t["txn_date"][:4]))
    if not years:
        years = {date.today().year}
    fy = p.get("basis") == "fy"
    periods = []
    for y in sorted(years | ({min(years) - 1} if fy else set())):
        if fy:
            periods.append(("FY %d-%02d" % (y, (y + 1) % 100), "%d-04-01" % y, "%d-03-31" % (y + 1)))
        else:
            periods.append((str(y), "%d-01-01" % y, "%d-12-31" % y))
    out = [r for r in _period_rows(L, periods) if any(r[k] for k in ("bookings", "cash_in", "cash_out", "op_expenses", "sales"))]
    t = _totals(out, PERIOD_COLS)
    t["bookings"] = sum(r["bookings"] for r in out)
    return {"title": "Yearly summary" + (" (Indian financial year)" if fy else ""), "columns": PERIOD_COLS, "rows": out, "totals": t}


def report_airline(L, p):
    frm, to, f = p.get("from"), p.get("to"), _filters(p)
    agg = {}
    for e in L.events:
        if not in_range(e["date"], frm, to):
            continue
        b = L.bookings[e["booking_id"]]
        if not L.booking_matches(b, f):
            continue
        key = b["airlines"][0] if b["airlines"] else "(no airline)"
        r = agg.setdefault(key, {"airline": key, "bookings": 0, "sales": 0, "cost": 0, "commission": 0, "direct": 0, "gross_profit": 0})
        r["bookings"] += e.get("bookings", 0)
        r["sales"] += e.get("sales", 0)
        r["cost"] += e.get("cost", 0)
        r["commission"] += e.get("commission", 0)
        r["direct"] += e.get("direct_expenses", 0)
    out = list(agg.values())
    for r in out:
        r["gross_profit"] = r["sales"] - r["cost"] + r["commission"] - r["direct"]
    out.sort(key=lambda r: -r["sales"])
    cols = [col("airline", "Airline (first segment)"), col("bookings", "Bookings", "number"), col("sales", "Net sales", M),
            col("cost", "Net cost", M), col("commission", "Commission", M), col("direct", "Direct exp.", M), col("gross_profit", "Gross profit", M)]
    t = _totals(out, cols)
    t["bookings"] = sum(r["bookings"] for r in out)
    return {"title": "Airline-wise sales", "columns": cols, "rows": out, "totals": t}


def report_supplier_purchases(L, p):
    frm, to, f = p.get("from"), p.get("to"), _filters(p)
    agg = {}
    for e in L.events:
        if not in_range(e["date"], frm, to):
            continue
        b = L.bookings[e["booking_id"]]
        if not L.booking_matches(b, f):
            continue
        key = b["supplier_name"] or "(no supplier)"
        r = agg.setdefault(key, {"supplier": key, "bookings": 0, "cost": 0, "commission": 0, "sales": 0, "gross_profit": 0, "direct": 0})
        r["bookings"] += e.get("bookings", 0)
        r["cost"] += e.get("cost", 0)
        r["commission"] += e.get("commission", 0)
        r["sales"] += e.get("sales", 0)
        r["direct"] += e.get("direct_expenses", 0)
    out = list(agg.values())
    for r in out:
        r["gross_profit"] = r["sales"] - r["cost"] + r["commission"] - r["direct"]
    out.sort(key=lambda r: -r["cost"])
    cols = [col("supplier", "Supplier / portal"), col("bookings", "Bookings", "number"), col("cost", "Net purchases", M),
            col("commission", "Commission", M), col("sales", "Net sales", M), col("gross_profit", "Gross profit", M)]
    t = _totals(out, cols)
    t["bookings"] = sum(r["bookings"] for r in out)
    return {"title": "Supplier-wise purchases", "columns": cols, "rows": out, "totals": t}


# ------------------------------------------------------------ statements
def customer_statement(L, cid, frm=None, to=None):
    c = L.customers.get(int(cid))
    if not c:
        raise NotFound("Customer not found")
    lines = []
    for b in L.bookings.values():
        if b["customer_id"] != c["id"]:
            continue
        base = b["calc"]["base"]
        if b["committed"]:
            amt = base["selling_price"] + base["service_charge"] + base["other_income"] + base["tax_amount"]
            if amt:
                lines.append({"date": b["booking_date"], "type": "Booking", "ref": b["ref"],
                              "description": "%s%s %s" % (b["service_type"].capitalize(), (" PNR " + b["pnr"]) if b["pnr"] else "", b["route"]),
                              "debit": amt, "credit": 0, "booking_id": b["id"]})
            for a in L.adjustments.get(b["id"], []):
                s = a["sign"]
                ch = s * convert(a["extra_customer_charge"], a["exchange_rate"])
                cr = s * convert(a["customer_credit"], a["exchange_rate"])
                if ch:
                    lines.append({"date": a["adj_date"], "type": "Charge", "ref": a["adj_no"],
                                  "description": "%s charges - %s" % (a["kind"].replace("_", " ").capitalize(), b["ref"]),
                                  "debit": ch, "credit": 0, "booking_id": b["id"]})
                if cr:
                    lines.append({"date": a["adj_date"], "type": "Credit", "ref": a["adj_no"],
                                  "description": "%s credit - %s%s" % (a["kind"].replace("_", " ").capitalize(), b["ref"], " (reversal)" if s < 0 else ""),
                                  "debit": 0, "credit": cr, "booking_id": b["id"]})
    for t in L.transactions:
        if t["customer_id"] != c["id"] or t["kind"] not in ("customer_receipt", "customer_refund"):
            continue
        v = t["sign"] * t["base_amount"]
        r = txn_row(L, t)
        desc = "%s%s%s" % (KIND_LABELS[t["kind"]], " - " + r["booking_ref"] if r["booking_ref"] else "",
                           " (" + t["method"].replace("_", " ") + (" " + t["reference"] if t["reference"] else "") + ")")
        if t["sign"] < 0:
            desc += " - reversal"
        if t["kind"] == "customer_receipt":
            lines.append({"date": t["txn_date"], "type": "Receipt", "ref": t["txn_no"], "description": desc, "debit": 0, "credit": v, "txn_id": t["id"]})
        else:
            lines.append({"date": t["txn_date"], "type": "Refund", "ref": t["txn_no"], "description": desc, "debit": v, "credit": 0, "txn_id": t["id"]})
    return _running(lines, frm, to, {"party": c, "kind": "customer", "base": L.base})


def supplier_statement(L, sid, frm=None, to=None):
    s_ = L.suppliers.get(int(sid))
    if not s_:
        raise NotFound("Supplier not found")
    lines = []
    for b in L.bookings.values():
        if b["supplier_id"] != s_["id"] or not b["committed"]:
            continue
        base = b["calc"]["base"]
        if base["purchase_cost"]:
            lines.append({"date": b["booking_date"], "type": "Purchase", "ref": b["ref"],
                          "description": "Purchase%s %s" % ((" PNR " + b["pnr"]) if b["pnr"] else "", b["route"]),
                          "debit": base["purchase_cost"], "credit": 0, "booking_id": b["id"]})
        if base["commission"]:
            lines.append({"date": b["booking_date"], "type": "Commission", "ref": b["ref"], "description": "Commission earned",
                          "debit": 0, "credit": base["commission"], "booking_id": b["id"]})
        for a in L.adjustments.get(b["id"], []):
            sg = a["sign"]
            cv = lambda k: sg * convert(a[k], a["exchange_rate"])
            if a["extra_supplier_charge"]:
                lines.append({"date": a["adj_date"], "type": "Charge", "ref": a["adj_no"], "description": "Supplier charges - " + b["ref"],
                              "debit": cv("extra_supplier_charge"), "credit": 0, "booking_id": b["id"]})
            if a["supplier_credit"]:
                lines.append({"date": a["adj_date"], "type": "Credit", "ref": a["adj_no"], "description": "Supplier credit - " + b["ref"],
                              "debit": 0, "credit": cv("supplier_credit"), "booking_id": b["id"]})
            if a["commission_reversal"]:
                lines.append({"date": a["adj_date"], "type": "Commission reversed", "ref": a["adj_no"], "description": "Commission reversed - " + b["ref"],
                              "debit": cv("commission_reversal"), "credit": 0, "booking_id": b["id"]})
    for t in L.transactions:
        if t["supplier_id"] != s_["id"] or t["kind"] not in ("supplier_payment", "supplier_receipt"):
            continue
        v = t["sign"] * t["base_amount"]
        r = txn_row(L, t)
        desc = "%s%s (%s%s)%s" % (KIND_LABELS[t["kind"]], " - " + r["booking_ref"] if r["booking_ref"] else "", t["method"].replace("_", " "),
                                  " " + t["reference"] if t["reference"] else "", " - reversal" if t["sign"] < 0 else "")
        if t["kind"] == "supplier_payment":
            lines.append({"date": t["txn_date"], "type": "Payment", "ref": t["txn_no"], "description": desc, "debit": 0, "credit": v, "txn_id": t["id"]})
        else:
            lines.append({"date": t["txn_date"], "type": "Refund received", "ref": t["txn_no"], "description": desc, "debit": v, "credit": 0, "txn_id": t["id"]})
    return _running(lines, frm, to, {"party": s_, "kind": "supplier", "base": L.base})


def _running(lines, frm, to, meta):
    order = {"Booking": 0, "Purchase": 0, "Commission": 1, "Charge": 2, "Credit": 3, "Commission reversed": 4}
    lines.sort(key=lambda l: (l["date"], order.get(l["type"], 5), l["ref"]))
    opening = 0
    out = []
    for l in lines:
        if frm and l["date"] < frm:
            opening += l["debit"] - l["credit"]
            continue
        if to and l["date"] > to:
            continue
        out.append(l)
    bal = opening
    for l in out:
        bal += l["debit"] - l["credit"]
        l["balance"] = bal
    meta.update({"from": frm, "to": to, "opening": opening, "closing": bal, "lines": out,
                 "total_debit": sum(l["debit"] for l in out), "total_credit": sum(l["credit"] for l in out)})
    return meta


REPORTS = {
    "sales": report_sales, "bookings": report_bookings, "profit": report_profit,
    "customer_outstanding": report_customer_outstanding, "supplier_outstanding": report_supplier_outstanding,
    "expenses": report_expenses, "cashbook": report_cashbook, "refunds": report_refunds, "commission": report_commission,
    "monthly": report_monthly, "yearly": report_yearly, "airline": report_airline, "supplier_purchases": report_supplier_purchases,
}


def run_report(conn, name, params):
    fn = REPORTS.get(name)
    if not fn:
        raise NotFound("Unknown report")
    L = Ledger(conn)
    r = fn(L, params)
    r["base_currency"] = L.base
    r["name"] = name
    r["params"] = {k: v for k, v in params.items() if v}
    return r


def to_csv(report):
    buf = io.StringIO()
    w = csv.writer(buf)
    cols = report["columns"]
    w.writerow([c["label"] for c in cols])
    for r in report["rows"]:
        line = []
        for c in cols:
            val = r.get(c["key"])
            if c["type"] in (M, "money_raw"):
                line.append(fmt(val or 0))
            else:
                val = "" if val is None else str(val)
                if val[:1] in ("=", "+", "-", "@"):
                    val = "'" + val  # avoid spreadsheet formula injection
                line.append(val)
        w.writerow(line)
    if report.get("totals"):
        line = []
        for i, c in enumerate(cols):
            if c["key"] in report["totals"]:
                tv = report["totals"][c["key"]]
                line.append(fmt(tv) if c["type"] in (M, "money_raw") else str(tv))
            else:
                line.append("TOTAL" if i == 0 else "")
        w.writerow(line)
    return "﻿" + buf.getvalue()


def statement_csv(st):
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["Date", "Type", "Reference", "Description", "Debit", "Credit", "Balance"])
    w.writerow([st["from"] or "", "Opening balance", "", "", "", "", fmt(st["opening"])])
    for l in st["lines"]:
        desc = l["description"]
        if desc[:1] in ("=", "+", "-", "@"):
            desc = "'" + desc
        w.writerow([l["date"], l["type"], l["ref"], desc, fmt(l["debit"]) if l["debit"] else "", fmt(l["credit"]) if l["credit"] else "", fmt(l["balance"])])
    w.writerow([st["to"] or "", "Closing balance", "", "", fmt(st["total_debit"]), fmt(st["total_credit"]), fmt(st["closing"])])
    return "﻿" + buf.getvalue()
