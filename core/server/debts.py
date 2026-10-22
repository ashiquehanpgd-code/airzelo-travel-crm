"""Debt register: company loans and other debts, linked to the cash / bank ledger.

How the money is recorded (one record each, so nothing is counted twice):
- Loan received    -> a `loan_received` transaction into the chosen account, linked to the debt.
                      It raises the account balance and the debt; it is never sales, income or profit.
- Principal repaid -> a `loan_repayment` transaction from the chosen account, linked to the debt.
                      It lowers the account balance and the debt; it is not an expense.
- Interest paid    -> an ordinary expense ("Loan interest") linked to the debt, so it follows the
                      existing expense rules and reduces net profit like any other expense.
- Interest charged but not yet paid -> a debt entry (no money moves; not in profit until paid).
- A debt already owed before it was recorded here -> the debt's opening amount (no money moves).

The debt balances are calculated from these records every time; nothing is stored twice.
"""
import uuid

from . import validate as v
from .db import tx, now_iso, rows, one, get_setting, next_counter
from .errors import ValidationError, NotFound, Conflict
from .finance import Ledger, txn_row, dashboard
from .money import parse_amount, parse_percent
from .services import audit, today, create_transaction, reverse_transaction, list_accounts

CREDITOR_TYPES = ("bank", "nbfc", "person", "supplier", "credit_card", "other")
INTEREST_CATEGORY = "Loan interest"


def _linked_txns(conn, debt_id=None):
    sql = ("SELECT id, debt_id, kind, sign, base_amount, txn_date, reversed_by FROM transactions WHERE debt_id IS NOT NULL"
           + (" AND debt_id = ?" if debt_id else "") + " ORDER BY txn_date, id")
    return rows(conn.execute(sql, (debt_id,) if debt_id else ()))


def _entries(conn, debt_id=None):
    sql = "SELECT * FROM debt_entries" + (" WHERE debt_id = ?" if debt_id else "") + " ORDER BY entry_date, id"
    return rows(conn.execute(sql, (debt_id,) if debt_id else ()))


def _calc(debt, txns, entries):
    """Balances for one debt. Reversals carry sign -1, so a reversed pair nets to zero."""
    c = {"borrowed": debt["opening_amount"], "loan_received": 0, "principal_repaid": 0, "interest_charged": 0,
         "interest_paid": 0, "last_payment_date": None}
    for t in txns:
        v_ = t["sign"] * t["base_amount"]
        if t["kind"] == "loan_received":
            c["loan_received"] += v_
        elif t["kind"] == "loan_repayment":
            c["principal_repaid"] += v_
        elif t["kind"] == "expense":
            c["interest_paid"] += v_
        if t["sign"] > 0 and not t["reversed_by"] and t["kind"] in ("loan_repayment", "expense"):
            if (c["last_payment_date"] or "") < t["txn_date"]:
                c["last_payment_date"] = t["txn_date"]
    for e in entries:
        c["interest_charged"] += e["sign"] * e["amount"]
    c["borrowed"] += c["loan_received"]
    c["principal_outstanding"] = c["borrowed"] - c["principal_repaid"]
    c["interest_due"] = max(0, c["interest_charged"] - c["interest_paid"])
    c["total_outstanding"] = c["principal_outstanding"] + c["interest_due"]
    c["total_paid"] = c["principal_repaid"] + c["interest_paid"]
    return c


def _load(conn, did):
    d = one(conn.execute("SELECT * FROM debts WHERE id = ?", (did,)))
    if not d:
        raise NotFound("Debt not found")
    return d


def balances(conn, did):
    d = _load(conn, did)
    d.update(_calc(d, _linked_txns(conn, did), _entries(conn, did)))
    return d


def after_change(conn, did):
    """Called inside the write transaction after a linked entry is posted or reversed."""
    d = balances(conn, did)
    if d["principal_outstanding"] < 0:
        raise ValidationError("This would make the principal repaid %s more than the amount borrowed. "
                              "Check the amounts, or reverse the later repayments first." % _fmt(-d["principal_outstanding"]), "amount")
    if d["status"] == "closed" and d["total_outstanding"] != 0:
        conn.execute("UPDATE debts SET status = 'open', updated_at = ? WHERE id = ?", (now_iso(), did))
        audit(conn, "debt", did, "reopen", {"status": "closed"}, {"status": "open", "reason": "balance changed"})


def _fmt(minor):
    from .money import fmt
    return fmt(minor)


def list_debts(conn, status=None):
    by_t, by_e = {}, {}
    for t in _linked_txns(conn):
        by_t.setdefault(t["debt_id"], []).append(t)
    for e in _entries(conn):
        by_e.setdefault(e["debt_id"], []).append(e)
    out = []
    today_s = today()
    for d in rows(conn.execute("SELECT * FROM debts ORDER BY status DESC, COALESCE(due_date, '9999'), creditor COLLATE NOCASE")):
        if status and d["status"] != status:
            continue
        d.update(_calc(d, by_t.get(d["id"], []), by_e.get(d["id"], [])))
        d["overdue"] = bool(d["status"] == "open" and d["due_date"] and d["due_date"] < today_s and d["total_outstanding"] > 0)
        out.append(d)
    return out


def get_debt(conn, did):
    d = balances(conn, did)
    L = Ledger(conn)
    d["transactions"] = sorted((txn_row(L, t) for t in L.transactions if t.get("debt_id") == did),
                               key=lambda r: (r["txn_date"], r["id"]), reverse=True)
    d["entries"] = list(reversed(_entries(conn, did)))
    return d


def _payload(data, require_opening):
    p = {
        "creditor": v.s(data, "creditor", 120, True, "Lender name"),
        "creditor_type": v.choice(data, "creditor_type", CREDITOR_TYPES, "other", "Lender type"),
        "creditor_phone": v.s(data, "creditor_phone", 40),
        "reference": v.s(data, "reference", 120, label="Loan / account reference"),
        "start_date": v.d(data, "start_date", True, "Date borrowed"),
        "due_date": v.d(data, "due_date", label="Due date"),
        "opening_amount": parse_amount(data.get("opening_amount"), "Amount owed", required=require_opening,
                                       allow_zero=not require_opening),
        "interest_rate": parse_percent(data.get("interest_rate"), "Interest rate") if str(data.get("interest_rate") or "").strip() else None,
        "interest_terms": v.s(data, "interest_terms", 200, label="Interest terms"),
        "notes": v.s(data, "notes", 2000),
    }
    if p["due_date"] and p["due_date"] < p["start_date"]:
        raise ValidationError("Due date is before the date borrowed", "due_date")
    return p


def save_debt(conn, data, did=None):
    """Create a debt that is already owed (opening amount, no money moves now), or edit a debt's details."""
    idem = v.s(data, "idem_key", 80)
    ts = now_iso()
    with tx(conn):
        if did:
            before = _load(conn, did)
            p = _payload(data, require_opening=False)
            conn.execute("UPDATE debts SET creditor=?, creditor_type=?, creditor_phone=?, reference=?, start_date=?, due_date=?, "
                         "opening_amount=?, interest_rate=?, interest_terms=?, notes=?, updated_at=? WHERE id=?",
                         (p["creditor"], p["creditor_type"], p["creditor_phone"], p["reference"], p["start_date"], p["due_date"],
                          p["opening_amount"], p["interest_rate"], p["interest_terms"], p["notes"], ts, did))
            d = balances(conn, did)
            if d["principal_outstanding"] < 0:
                raise ValidationError("The amount owed cannot be less than the principal already repaid", "opening_amount")
            if d["borrowed"] <= 0:
                raise ValidationError("Enter the amount owed, or record the loan with Loan received", "opening_amount")
            audit(conn, "debt", did, "update", {k: before[k] for k in p}, p)
            return did
        if idem:
            hit = conn.execute("SELECT id FROM debts WHERE idem_key = ?", (idem,)).fetchone()
            if hit:
                return hit["id"]
        p = _payload(data, require_opening=not data.get("_via_loan_receipt"))
        return _insert_debt(conn, p, idem, ts)


def _insert_debt(conn, p, idem, ts):
    debt_no = "DEBT-%04d" % next_counter(conn, "debt")
    cur = get_setting(conn, "base_currency", "INR")
    c = conn.execute("INSERT INTO debts(debt_no, creditor, creditor_type, creditor_phone, reference, start_date, due_date, currency, "
                     "opening_amount, interest_rate, interest_terms, notes, idem_key, created_at, updated_at) "
                     "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     (debt_no, p["creditor"], p["creditor_type"], p["creditor_phone"], p["reference"], p["start_date"], p["due_date"],
                      cur, p["opening_amount"], p["interest_rate"], p["interest_terms"], p["notes"], idem, ts, ts))
    did = c.lastrowid
    audit(conn, "debt", did, "create", None, dict(p, debt_no=debt_no, currency=cur))
    return did


def receive_loan(conn, data):
    """Loan money received into a cash / bank account: one `loan_received` transaction, linked to an existing
    debt or to a new one created in the same step. Returns (debt_id, transaction_id)."""
    idem = v.s(data, "idem_key", 80)
    with tx(conn):
        if idem:
            hit = conn.execute("SELECT id, debt_id FROM transactions WHERE idem_key = ?", (idem,)).fetchone()
            if hit:
                return hit["debt_id"], hit["id"]
        txn_date = v.d(data, "txn_date", True, "Date received")
        did = v.i(data, "debt_id")
        if did:
            debt = _load(conn, did)
        else:
            p = _payload(dict(data, start_date=data.get("start_date") or txn_date, opening_amount=None), require_opening=False)
            did = _insert_debt(conn, p, None, now_iso())
            debt = _load(conn, did)
        tid, _ = create_transaction(conn, {
            "kind": "loan_received", "debt_id": did, "idem_key": idem, "txn_date": txn_date,
            "account_id": data.get("account_id"), "amount": data.get("amount"), "currency": data.get("currency"),
            "exchange_rate": data.get("exchange_rate"), "rate_date": data.get("rate_date"),
            "method": data.get("method") or "bank_transfer", "reference": data.get("txn_reference") or data.get("reference"),
            "payee": debt["creditor"], "notes": data.get("txn_notes"), "force_duplicate": data.get("force_duplicate")})
    return did, tid


def repay_loan(conn, did, data):
    """Pay a loan from a cash / bank account: principal as a `loan_repayment` transaction and interest as a
    "Loan interest" expense, both linked to the debt and posted together. Returns the transaction ids.
    The two parts share one submission key (<key>:p / <key>:i), which also ties them together for reversal."""
    idem = v.s(data, "idem_key", 60) or str(uuid.uuid4())
    principal = parse_amount(data.get("principal"), "Principal repaid")
    interest = parse_amount(data.get("interest"), "Interest paid")
    if not (principal or interest):
        raise ValidationError("Enter the principal and / or interest paid", "principal")
    with tx(conn):
        debt = _load(conn, did)
        common = {"debt_id": did, "txn_date": data.get("txn_date"), "account_id": data.get("account_id"),
                  "currency": data.get("currency"), "exchange_rate": data.get("exchange_rate"), "rate_date": data.get("rate_date"),
                  "method": data.get("method") or "bank_transfer", "reference": data.get("reference"),
                  "payee": debt["creditor"], "notes": data.get("notes"), "force_duplicate": data.get("force_duplicate")}
        out = {}
        if principal:
            out["principal_txn_id"], _ = create_transaction(conn, dict(common, kind="loan_repayment", amount=data.get("principal"),
                                                                       idem_key=(idem + ":p") if idem else None))
        if interest:
            out["interest_txn_id"], _ = create_transaction(conn, dict(common, kind="expense", amount=data.get("interest"),
                                                                      category=INTEREST_CATEGORY, idem_key=(idem + ":i") if idem else None))
    return out


REPAYMENT_PARTS = (":p", ":i")


def _repayment_parts(conn, tid):
    """The entries posted together as one repayment: the principal and / or interest part of `tid`."""
    t = one(conn.execute("SELECT * FROM transactions WHERE id = ?", (tid,)))
    if not t:
        raise NotFound("Transaction not found")
    if not t["debt_id"] or t["kind"] not in ("loan_repayment", "expense"):
        raise ValidationError("This entry is not a loan repayment")
    if t["sign"] < 0:
        raise ValidationError("This is a reversing entry. Open the original repayment instead.")
    key = t["idem_key"] or ""
    if key.endswith(REPAYMENT_PARTS):
        base = key[:-2]
        parts = rows(conn.execute("SELECT * FROM transactions WHERE idem_key IN (?, ?) ORDER BY id", (base + ":p", base + ":i")))
    else:
        parts = [t]  # recorded on its own (e.g. a corrected entry)
    return t, parts


def repayment(conn, tid):
    """What reversing this repayment would undo, for the confirmation prompt."""
    _, parts = _repayment_parts(conn, tid)
    accounts = {r["id"]: r["name"] for r in conn.execute("SELECT id, name FROM accounts")}
    out = [{"id": p["id"], "txn_no": p["txn_no"], "part": "principal" if p["kind"] == "loan_repayment" else "interest",
            "txn_date": p["txn_date"], "amount": p["amount"], "currency": p["currency"], "base_amount": p["base_amount"],
            "account_name": accounts.get(p["account_id"], ""), "reversed": bool(p["reversed_by"]),
            "reconciled": bool(p["reconciled"]), "reconciled_on": p["reconciled_on"]} for p in parts]
    debt = _load(conn, parts[0]["debt_id"])
    return {"debt_id": debt["id"], "debt_no": debt["debt_no"], "creditor": debt["creditor"], "parts": out,
            "live": [p for p in out if not p["reversed"]], "reconciled": any(p["reconciled"] for p in out if not p["reversed"])}


def reverse_repayment(conn, tid, data):
    """Undo a whole repayment: reverse its principal and interest entries together, in one database transaction.
    Each original stays in the ledger marked as reversed, with a matching reversing entry (nothing is erased).
    The account balance, the debt and (for interest) the expenses are restored. Returns the reversing entry ids."""
    reason = v.s(data, "reason", 300) or "Repayment reversed"
    with tx(conn):
        _, parts = _repayment_parts(conn, tid)
        live = [p for p in parts if not p["reversed_by"]]
        if not live:
            raise Conflict("This repayment has already been reversed.", "already_reversed")
        if any(p["reconciled"] for p in live) and not v.b(data, "confirm_reconciled"):
            raise Conflict("Part of this repayment is ticked as reconciled with a bank statement. Reversing it adds a new, "
                           "unreconciled entry: the account's book balance changes and you will need to tick the reversal "
                           "at your next reconciliation. Confirm to reverse it anyway.", "confirm_reconciled")
        out = []
        for p in live:
            # dated like the original (as Delete does), so balances on that date are corrected
            out.append(reverse_transaction(conn, p["id"], {"reason": reason, "date": p["txn_date"]}))
        audit(conn, "debt", parts[0]["debt_id"], "reverse_repayment", {"entries": [p["txn_no"] for p in live]},
              {"reversals": out, "reason": reason})
    return out


def add_interest_charge(conn, did, data):
    """Interest charged by the lender and not yet paid. No money moves, so it is not a transaction or an expense."""
    idem = v.s(data, "idem_key", 80)
    with tx(conn):
        if idem:
            hit = conn.execute("SELECT id FROM debt_entries WHERE idem_key = ?", (idem,)).fetchone()
            if hit:
                return hit["id"]
        d = _load(conn, did)
        if d["status"] != "open":
            raise ValidationError("This debt is closed. Reopen it to record more entries.")
        amount = parse_amount(data.get("amount"), "Interest charged", required=True, allow_zero=False)
        entry_date = v.d(data, "entry_date", label="Date") or today()
        entry_no = "DE-%05d" % next_counter(conn, "debt_entry")
        cur = conn.execute("INSERT INTO debt_entries(entry_no, debt_id, entry_date, kind, sign, amount, reference, notes, idem_key, created_at) "
                           "VALUES (?,?,?,'interest_charge',1,?,?,?,?,?)",
                           (entry_no, did, entry_date, amount, v.s(data, "reference", 120), v.s(data, "notes", 1000), idem, now_iso()))
        eid = cur.lastrowid
        audit(conn, "debt_entry", eid, "create", None, {"debt_id": did, "entry_no": entry_no, "entry_date": entry_date, "amount": amount})
    return eid


def reverse_entry(conn, eid, data):
    reason = v.s(data, "reason", 500, True, "Reason")
    with tx(conn):
        e = one(conn.execute("SELECT * FROM debt_entries WHERE id = ?", (eid,)))
        if not e:
            raise NotFound("Entry not found")
        if e["sign"] < 0 or e["reversed_by"]:
            raise Conflict("This entry has already been reversed", "already_reversed")
        entry_no = "DE-%05d" % next_counter(conn, "debt_entry")
        cur = conn.execute("INSERT INTO debt_entries(entry_no, debt_id, entry_date, kind, sign, amount, reference, notes, reversal_of, created_at) "
                           "VALUES (?,?,?,?,-1,?,?,?,?,?)",
                           (entry_no, e["debt_id"], today(), e["kind"], e["amount"], e["entry_no"], "Reversal: " + reason, eid, now_iso()))
        rid = cur.lastrowid
        conn.execute("UPDATE debt_entries SET reversed_by = ? WHERE id = ?", (rid, eid))
        after_change(conn, e["debt_id"])
        audit(conn, "debt_entry", eid, "reverse", {"entry_no": e["entry_no"]}, {"reversal": entry_no, "reason": reason})
    return rid


def set_status(conn, did, status):
    if status not in ("open", "closed"):
        raise ValidationError("Status must be open or closed", "status")
    with tx(conn):
        d = balances(conn, did)
        if status == "closed" and d["total_outstanding"] != 0:
            raise ValidationError("This debt still has %s outstanding. Record the final repayment before closing it."
                                  % _fmt(d["total_outstanding"]))
        conn.execute("UPDATE debts SET status = ?, updated_at = ? WHERE id = ?", (status, now_iso(), did))
        audit(conn, "debt", did, "close" if status == "closed" else "reopen", {"status": d["status"]}, {"status": status})
    return did


def delete_debt(conn, did):
    with tx(conn):
        d = _load(conn, did)
        if (conn.execute("SELECT 1 FROM transactions WHERE debt_id = ? LIMIT 1", (did,)).fetchone()
                or conn.execute("SELECT 1 FROM debt_entries WHERE debt_id = ? LIMIT 1", (did,)).fetchone()):
            raise Conflict("This debt has money or interest recorded against it, so it is kept for your accounts. Close it instead.",
                           "has_entries")
        conn.execute("DELETE FROM debts WHERE id = ?", (did,))
        audit(conn, "debt", did, "delete", d, None)
    return did


def summary(conn, frm, to):
    """Debt dashboard. Business profit and account balances come from the existing calculations unchanged."""
    debts = list_debts(conn)
    keys = ("borrowed", "loan_received", "principal_repaid", "principal_outstanding", "interest_charged", "interest_paid",
            "interest_due", "total_outstanding", "total_paid")
    totals = {k: sum(d[k] for d in debts) for k in keys}
    period = {"loan_received": 0, "principal_repaid": 0, "interest_paid": 0, "interest_charged": 0}
    for t in _linked_txns(conn):
        if frm <= t["txn_date"] <= to:
            key = {"loan_received": "loan_received", "loan_repayment": "principal_repaid", "expense": "interest_paid"}[t["kind"]]
            period[key] += t["sign"] * t["base_amount"]
    for e in _entries(conn):
        if frm <= e["entry_date"] <= to:
            period["interest_charged"] += e["sign"] * e["amount"]
    dash = dashboard(conn, frm, to)
    base = get_setting(conn, "base_currency", "INR")
    bank_total = sum(a["balance"] for a in list_accounts(conn) if a["active"] and a["currency"] == base)
    open_debts = [d for d in debts if d["status"] == "open"]
    by_type = {}
    for d in open_debts:
        by_type[d["creditor_type"]] = by_type.get(d["creditor_type"], 0) + d["total_outstanding"]
    return {
        "base_currency": base,
        "period": {"from": frm, "to": to},
        "totals": totals,
        "open_count": len(open_debts),
        "overdue_count": sum(1 for d in open_debts if d["overdue"]),
        "by_type": by_type,
        "upcoming": sorted([d for d in open_debts if d["due_date"] and d["total_outstanding"] > 0], key=lambda d: d["due_date"])[:8],
        "in_period": period,
        "combined": {
            "business_net_profit": dash["summary"]["net_profit"],
            "loan_interest_in_expenses": period["interest_paid"],
            "loan_received": period["loan_received"],
            "principal_repaid": period["principal_repaid"],
            "debt_outstanding": totals["total_outstanding"],
            "cash_and_bank": bank_total,
            "net_position": bank_total - totals["total_outstanding"],
        },
    }
