"""Debt register linked to the ledger: loan receipts and repayments move bank balances, never sales or profit;
interest is an expense; balances reconcile; duplicates and over-repayment are refused; v4 migration and rollback."""
import http.client
import json
import socket
import sqlite3
import threading
from http.server import ThreadingHTTPServer

from helpers import Base
from server import debts, finance, reports, services
from server.db import migrate, rollback, current_version, MIGRATIONS
from server.errors import ValidationError, Conflict

LATEST = MIGRATIONS[-1][0]


def balance(conn, aid):
    return {a["id"]: a["balance"] for a in services.list_accounts(conn)}[aid]


class LoanLedger(Base):
    def receive(self, amount="100000", **kw):
        data = {"creditor": "Federal Bank", "creditor_type": "bank", "txn_date": "2026-10-01", "account_id": self.bank,
                "amount": amount, "method": "bank_transfer", "reference": "LN-1"}
        data.update(kw)
        return debts.receive_loan(self.conn, data)

    def test_loan_received_raises_bank_and_debt_but_not_sales_or_profit(self):
        bid = self.booking(purchase_cost="10000", selling_price="12000")
        self.txn(kind="customer_receipt", customer_id=self.cust, amount="12000", allocations=[{"booking_id": bid, "amount": "12000"}])
        before = finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"]
        did, tid = self.receive("100000")
        after = finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"]
        self.assertEqual(balance(self.conn, self.bank), 10000000)
        d = debts.get_debt(self.conn, did)
        self.assertEqual((d["borrowed"], d["principal_outstanding"], d["opening_amount"]), (10000000, 10000000, 0))
        self.assertEqual([t["id"] for t in d["transactions"]], [tid])
        for k in ("sales", "gross_profit", "net_profit", "other_income", "operating_expenses"):
            self.assertEqual(before[k], after[k], k)
        for k in ("cash_in", "cash_out", "net_cash_flow", "customer_receipts", "other_income"):
            self.assertEqual(before["cash"][k], after["cash"][k], k)
        self.assertEqual((after["cash"]["loan_received"], after["cash"]["net_financing"]), (10000000, 10000000))
        t = services.get_transaction(self.conn, tid)
        self.assertEqual((t["kind_label"], t["debt_id"], t["debt_name"], t["txn_no"]), ("Loan received (financing)", did, "Federal Bank", "LNR-000001"))

    def test_repayment_principal_lowers_bank_and_debt_interest_is_an_expense(self):
        did, _ = self.receive("100000")
        before = finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"]
        r = debts.repay_loan(self.conn, did, {"txn_date": "2026-11-05", "account_id": self.bank, "principal": "9000", "interest": "1000"})
        after = finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"]
        self.assertEqual(balance(self.conn, self.bank), 10000000 - 1000000)
        d = debts.get_debt(self.conn, did)
        self.assertEqual((d["principal_repaid"], d["interest_paid"], d["principal_outstanding"], d["total_paid"]),
                         (900000, 100000, 9100000, 1000000))
        # principal is not an expense; interest is an ordinary operating expense
        self.assertEqual(after["operating_expenses"] - before["operating_expenses"], 100000)
        self.assertEqual(before["net_profit"] - after["net_profit"], 100000)
        self.assertEqual(after["cash"]["loan_repaid"], 900000)
        self.assertEqual(after["cash"]["cash_out"] - before["cash"]["cash_out"], 100000)
        interest = services.get_transaction(self.conn, r["interest_txn_id"])
        self.assertEqual((interest["kind"], interest["category"], interest["debt_id"]), ("expense", "Loan interest", did))

    def test_cashbook_and_reconciliation_include_loans(self):
        did, _ = self.receive("50000")
        debts.repay_loan(self.conn, did, {"txn_date": "2026-10-20", "account_id": self.bank, "principal": "5000", "interest": "300"})
        rep = reports.run_report(self.conn, "cashbook", {"account_id": self.bank})
        types = [r["type"] for r in rep["rows"]]
        self.assertEqual(sorted(types), ["Expense", "Loan received (financing)", "Loan repayment (principal)"])
        self.assertEqual(rep["summary"][-1]["value"], balance(self.conn, self.bank))
        self.assertEqual(rep["summary"][-1]["value"], 5000000 - 500000 - 30000)
        rec = services.reconciliation_view(self.conn, self.bank)
        self.assertEqual(rec["balance"], balance(self.conn, self.bank))
        loans = services.list_transactions(self.conn, {"kind": "loan_received,loan_repayment"})
        self.assertEqual(sorted(t["kind"] for t in loans), ["loan_received", "loan_repayment"])

    def test_bank_and_debt_reconcile_after_many_entries(self):
        did, _ = self.receive("100000")
        debts.receive_loan(self.conn, {"debt_id": did, "txn_date": "2026-10-10", "account_id": self.cash, "amount": "20000", "method": "cash"})
        for i in range(5):
            debts.repay_loan(self.conn, did, {"txn_date": "2026-11-%02d" % (i + 1), "account_id": self.bank, "principal": "10000", "interest": "500"})
        d = debts.get_debt(self.conn, did)
        self.assertEqual(d["borrowed"], 12000000)
        self.assertEqual(d["principal_outstanding"], 12000000 - 5000000)
        self.assertEqual(balance(self.conn, self.bank) + balance(self.conn, self.cash), 12000000 - 5000000 - 250000)
        self.assertEqual(len(d["transactions"]), 1 + 1 + 10)

    def test_double_submit_is_recorded_once(self):
        a = self.receive("1000", idem_key="k1")
        b = self.receive("1000", idem_key="k1")
        self.assertEqual(a, b)
        did = a[0]
        r1 = debts.repay_loan(self.conn, did, {"txn_date": "2026-10-05", "account_id": self.bank, "principal": "100", "interest": "5", "idem_key": "p"})
        r2 = debts.repay_loan(self.conn, did, {"txn_date": "2026-10-05", "account_id": self.bank, "principal": "100", "interest": "5", "idem_key": "p"})
        self.assertEqual(r1, r2)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM debts").fetchone()[0], 1)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 3)
        self.assertEqual(balance(self.conn, self.bank), 100000 - 10500)

    def test_likely_duplicate_needs_confirmation(self):
        did, _ = self.receive("1000")
        with self.assertRaises(Conflict):
            debts.receive_loan(self.conn, {"debt_id": did, "txn_date": "2026-10-01", "account_id": self.bank, "amount": "1000",
                                           "method": "bank_transfer", "reference": "LN-1"})
        debts.receive_loan(self.conn, {"debt_id": did, "txn_date": "2026-10-01", "account_id": self.bank, "amount": "1000",
                                       "method": "bank_transfer", "reference": "LN-1", "force_duplicate": True})
        self.assertEqual(debts.get_debt(self.conn, did)["borrowed"], 200000)

    def test_cannot_repay_more_principal_than_borrowed_and_nothing_is_posted(self):
        did, _ = self.receive("10000")
        with self.assertRaises(ValidationError):
            debts.repay_loan(self.conn, did, {"txn_date": "2026-10-05", "account_id": self.bank, "principal": "10000.01", "interest": "50"})
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 1)
        self.assertEqual(balance(self.conn, self.bank), 1000000)
        debts.repay_loan(self.conn, did, {"txn_date": "2026-10-05", "account_id": self.bank, "principal": "10000"})
        self.assertEqual(debts.get_debt(self.conn, did)["principal_outstanding"], 0)

    def test_loan_transactions_need_a_debt(self):
        with self.assertRaises(ValidationError):
            services.create_transaction(self.conn, {"kind": "loan_received", "account_id": self.bank, "amount": "10"})
        with self.assertRaises(sqlite3.DatabaseError):
            self.conn.execute("INSERT INTO transactions(txn_no, uid, txn_date, kind, amount, currency, exchange_rate, base_amount, account_id, "
                              "method, category, created_at, updated_at) VALUES ('X','u','2026-10-01','loan_received',1,'INR','1',1,?,'cash','x','x','x')",
                              (self.bank,))
        did, _ = self.receive("100")
        with self.assertRaises(ValidationError):
            debts.repay_loan(self.conn, did, {"account_id": self.bank})

    def test_delete_and_edit_use_reversals_and_keep_the_link(self):
        did, tid = self.receive("10000")
        r = debts.repay_loan(self.conn, did, {"txn_date": "2026-10-05", "account_id": self.bank, "principal": "4000", "interest": "200"})
        # deleting the loan receipt would leave more repaid than borrowed
        with self.assertRaises(ValidationError):
            services.reverse_transaction(self.conn, tid, {"reason": "mistake"})
        services.reverse_transaction(self.conn, r["principal_txn_id"], {"reason": "bounced"})
        d = debts.get_debt(self.conn, did)
        self.assertEqual((d["principal_repaid"], d["interest_paid"]), (0, 20000))
        self.assertEqual(balance(self.conn, self.bank), 1000000 - 20000)
        # edit the loan amount: reverse + repost, still linked to the same debt
        nid, how = services.correct_transaction(self.conn, tid, {"amount": "12000", "txn_date": "2026-10-01", "account_id": self.bank,
                                                                 "method": "bank_transfer", "reference": "LN-1"})
        self.assertEqual(how, "replaced")
        self.assertEqual(services.get_transaction(self.conn, nid)["debt_id"], did)
        self.assertEqual(debts.get_debt(self.conn, did)["borrowed"], 1200000)
        self.assertEqual(balance(self.conn, self.bank), 1200000 - 20000)
        with self.assertRaises(sqlite3.DatabaseError):
            self.conn.execute("UPDATE transactions SET debt_id = NULL WHERE id = ?", (nid,))
        with self.assertRaises(sqlite3.DatabaseError):
            self.conn.execute("DELETE FROM debts WHERE id = ?", (did,))

    def test_existing_debt_opening_amount_and_interest_charged(self):
        did = debts.save_debt(self.conn, {"creditor": "Mr. Rahman", "creditor_type": "person", "start_date": "2026-01-01",
                                          "opening_amount": "50000", "interest_rate": "12", "idem_key": "o1"})
        self.assertEqual(debts.save_debt(self.conn, {"creditor": "Mr. Rahman", "start_date": "2026-01-01", "opening_amount": "50000", "idem_key": "o1"}), did)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 0)
        eid = debts.add_interest_charge(self.conn, did, {"amount": "750", "entry_date": "2026-10-31"})
        d = debts.get_debt(self.conn, did)
        self.assertEqual((d["interest_due"], d["total_outstanding"]), (75000, 5075000))
        debts.repay_loan(self.conn, did, {"txn_date": "2026-11-01", "account_id": self.cash, "interest": "750", "method": "cash"})
        self.assertEqual(debts.get_debt(self.conn, did)["interest_due"], 0)
        debts.reverse_entry(self.conn, eid, {"reason": "waived"})
        with self.assertRaises(Conflict):
            debts.reverse_entry(self.conn, eid, {"reason": "again"})
        with self.assertRaises(sqlite3.DatabaseError):
            self.conn.execute("DELETE FROM debt_entries WHERE id = ?", (eid,))
        with self.assertRaises(ValidationError):
            debts.save_debt(self.conn, {"creditor": "X", "start_date": "2026-01-01", "opening_amount": "0"})

    def test_close_reopen_delete(self):
        did, tid = self.receive("1000")
        with self.assertRaises(ValidationError):
            debts.set_status(self.conn, did, "closed")
        r = debts.repay_loan(self.conn, did, {"txn_date": "2026-10-02", "account_id": self.bank, "principal": "1000"})
        debts.set_status(self.conn, did, "closed")
        with self.assertRaises(ValidationError):
            debts.repay_loan(self.conn, did, {"txn_date": "2026-10-02", "account_id": self.bank, "interest": "5"})
        with self.assertRaises(Conflict):
            debts.delete_debt(self.conn, did)
        services.reverse_transaction(self.conn, r["principal_txn_id"], {"reason": "bounced"})
        self.assertEqual(debts.get_debt(self.conn, did)["status"], "open")
        empty = debts.save_debt(self.conn, {"creditor": "Y", "start_date": "2026-10-01", "opening_amount": "5"})
        debts.delete_debt(self.conn, empty)
        self.assertEqual([d["id"] for d in debts.list_debts(self.conn)], [did])

    def test_summary(self):
        did, _ = self.receive("100000", txn_date="2026-10-02")
        debts.repay_loan(self.conn, did, {"txn_date": "2026-10-20", "account_id": self.bank, "principal": "10000", "interest": "800"})
        old = debts.save_debt(self.conn, {"creditor": "Mr. Rahman", "creditor_type": "person", "start_date": "2026-01-01",
                                          "due_date": "2026-01-31", "opening_amount": "2000"})
        s = debts.summary(self.conn, "2026-10-01", "2026-10-31")
        net = finance.dashboard(self.conn, "2026-10-01", "2026-10-31")["summary"]["net_profit"]
        self.assertEqual(s["combined"], {"business_net_profit": net, "loan_interest_in_expenses": 80000, "loan_received": 10000000,
                                         "principal_repaid": 1000000, "debt_outstanding": 9000000 + 200000,
                                         "cash_and_bank": balance(self.conn, self.bank) + balance(self.conn, self.cash),
                                         "net_position": balance(self.conn, self.bank) + balance(self.conn, self.cash) - 9200000})
        self.assertEqual(s["overdue_count"], 1)
        self.assertEqual(s["by_type"], {"bank": 9000000, "person": 200000})
        self.assertEqual([d["id"] for d in s["upcoming"]], [old])


class ReverseRepayment(Base):
    def setUp(self):
        super().setUp()
        self.did, _ = debts.receive_loan(self.conn, {"creditor": "Federal Bank", "txn_date": "2026-10-01", "account_id": self.bank,
                                                     "amount": "100000", "method": "bank_transfer"})
        self.before = (balance(self.conn, self.bank), finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"],
                       debts.get_debt(self.conn, self.did)["total_outstanding"])

    def repay(self, **kw):
        data = {"txn_date": "2026-10-05", "account_id": self.bank, "principal": "9000", "interest": "1000", "method": "bank_transfer"}
        data.update(kw)
        return debts.repay_loan(self.conn, self.did, data)

    def assert_restored(self):
        self.assertEqual(balance(self.conn, self.bank), self.before[0])
        self.assertEqual(finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"], self.before[1])
        d = debts.get_debt(self.conn, self.did)
        self.assertEqual((d["total_outstanding"], d["principal_repaid"], d["interest_paid"]), (self.before[2], 0, 0))

    def test_reverses_principal_and_interest_together_from_either_part(self):
        for part in ("principal_txn_id", "interest_txn_id"):
            r = self.repay()
            g = debts.repayment(self.conn, r[part])
            self.assertEqual(sorted(p["part"] for p in g["live"]), ["interest", "principal"])
            rev = debts.reverse_repayment(self.conn, r[part], {"reason": "wrong amount"})
            self.assertEqual(len(rev), 2)
            self.assert_restored()
            for tid in r.values():
                t = services.get_transaction(self.conn, tid)
                self.assertTrue(t["reversed_by"])           # original kept, marked reversed
                rv = services.get_transaction(self.conn, t["reversed_by"])
                self.assertEqual((rv["sign"], rv["reversal_of"], rv["debt_id"]), (-1, tid, self.did))
                self.assertEqual(sorted(a["action"] for a in t["audit"]), ["create", "reverse"])

    def test_no_duplicate_reversal(self):
        r = self.repay()
        debts.reverse_repayment(self.conn, r["principal_txn_id"], {})
        with self.assertRaises(Conflict):
            debts.reverse_repayment(self.conn, r["interest_txn_id"], {})
        with self.assertRaises(ValidationError):
            services.reverse_transaction(self.conn, r["principal_txn_id"], {"reason": "again"})
        rev = services.get_transaction(self.conn, r["principal_txn_id"])["reversed_by"]
        with self.assertRaises(ValidationError):
            debts.reverse_repayment(self.conn, rev, {})
        self.assert_restored()

    def test_only_the_remaining_part_is_reversed_after_a_single_delete(self):
        r = self.repay()
        services.reverse_transaction(self.conn, r["interest_txn_id"], {"reason": "wrong interest"})
        self.assertEqual([p["part"] for p in debts.repayment(self.conn, r["principal_txn_id"])["live"]], ["principal"])
        self.assertEqual(len(debts.reverse_repayment(self.conn, r["principal_txn_id"], {})), 1)
        self.assert_restored()

    def test_principal_only_and_interest_only_repayments(self):
        a = self.repay(interest="0")
        b = self.repay(principal="0", interest="300", txn_date="2026-10-06")
        self.assertEqual(len(debts.reverse_repayment(self.conn, a["principal_txn_id"], {})), 1)
        self.assertEqual(len(debts.reverse_repayment(self.conn, b["interest_txn_id"], {})), 1)
        self.assert_restored()

    def test_wrong_account_and_duplicate_payment(self):
        wrong = self.repay(account_id=self.cash, method="cash")
        dup1 = self.repay()
        dup2 = self.repay(force_duplicate=True)
        debts.reverse_repayment(self.conn, wrong["principal_txn_id"], {"reason": "wrong account"})
        debts.reverse_repayment(self.conn, dup2["principal_txn_id"], {"reason": "duplicate"})
        self.assertEqual(balance(self.conn, self.cash), 0)
        self.assertEqual(balance(self.conn, self.bank), self.before[0] - 1000000)
        d = debts.get_debt(self.conn, self.did)
        self.assertEqual((d["principal_repaid"], d["interest_paid"]), (900000, 100000))
        self.assertTrue(services.get_transaction(self.conn, dup1["principal_txn_id"])["reversed_by"] is None)

    def test_reconciled_entries_need_confirmation(self):
        r = self.repay()
        services.save_reconciliation(self.conn, self.bank, {"statement_date": "2026-10-31", "statement_balance": "90000",
                                                            "cleared_ids": [t["id"] for t in services.list_transactions(self.conn, {"account_id": self.bank})]})
        self.assertTrue(debts.repayment(self.conn, r["principal_txn_id"])["reconciled"])
        with self.assertRaises(Conflict) as cm:
            debts.reverse_repayment(self.conn, r["principal_txn_id"], {})
        self.assertEqual(cm.exception.code, "confirm_reconciled")
        self.assertIsNone(services.get_transaction(self.conn, r["principal_txn_id"])["reversed_by"])
        debts.reverse_repayment(self.conn, r["principal_txn_id"], {"confirm_reconciled": True})
        self.assert_restored()
        rv = services.get_transaction(self.conn, services.get_transaction(self.conn, r["principal_txn_id"])["reversed_by"])
        self.assertEqual(rv["reconciled"], 0)

    def test_reversal_reopens_a_closed_debt_and_rejects_other_entries(self):
        r = self.repay(principal="100000", interest="0")
        debts.set_status(self.conn, self.did, "closed")
        debts.reverse_repayment(self.conn, r["principal_txn_id"], {})
        self.assertEqual(debts.get_debt(self.conn, self.did)["status"], "open")
        loan = self.conn.execute("SELECT id FROM transactions WHERE kind = 'loan_received'").fetchone()["id"]
        with self.assertRaises(ValidationError):
            debts.repayment(self.conn, loan)
        rent = self.txn(kind="expense", category="Rent", amount="100")
        with self.assertRaises(ValidationError):
            debts.reverse_repayment(self.conn, rent, {})


class MigrationV4(Base):
    def test_rollback_restores_v3_and_reapplies(self):
        bid = self.booking()
        self.txn(kind="customer_receipt", customer_id=self.cust, amount="5000", allocations=[{"booking_id": bid, "amount": "5000"}])
        did = debts.save_debt(self.conn, {"creditor": "X", "start_date": "2026-10-01", "opening_amount": "10"})
        debts.repay_loan(self.conn, did, {"txn_date": "2026-10-02", "account_id": self.cash, "principal": "5", "interest": "1"})
        self.assertEqual(current_version(self.conn), LATEST)
        # refused while loan transactions exist, and nothing changes
        with self.assertRaises(RuntimeError):
            rollback(self.conn, 3)
        self.assertEqual(current_version(self.conn), LATEST)
        loan = self.conn.execute("SELECT id FROM transactions WHERE kind = 'loan_repayment'").fetchone()["id"]
        services.reverse_transaction(self.conn, loan, {"reason": "test"})
        with self.assertRaises(RuntimeError):
            rollback(self.conn, 3)


class MigrationV4Clean(Base):
    def test_rollback_and_reapply_preserve_transactions(self):
        bid = self.booking()
        self.txn(kind="customer_receipt", customer_id=self.cust, amount="5000", allocations=[{"booking_id": bid, "amount": "5000"}])
        self.txn(kind="expense", category="Rent", amount="300")
        debts.save_debt(self.conn, {"creditor": "X", "start_date": "2026-10-01", "opening_amount": "10"})
        dash = finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"]
        txns = [tuple(r) for r in self.conn.execute("SELECT * FROM transactions ORDER BY id")]
        objs = sorted(r[0] for r in self.conn.execute("SELECT name FROM sqlite_master WHERE tbl_name = 'transactions'"))
        self.assertEqual(rollback(self.conn, 3), 3)
        names = {r[0] for r in self.conn.execute("SELECT name FROM sqlite_master")}
        self.assertFalse({"debts", "debt_entries", "trg_txn_debt_valid"} & names)
        self.assertNotIn("debt_id", [r[1] for r in self.conn.execute("PRAGMA table_info(transactions)")])
        self.assertEqual([tuple(r) for r in self.conn.execute("SELECT * FROM transactions ORDER BY id")], [t[:-1] for t in txns])
        self.assertEqual(self.conn.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        self.assertEqual(self.conn.execute("PRAGMA foreign_keys").fetchone()[0], 1)
        with self.assertRaises(sqlite3.DatabaseError):
            self.conn.execute("DELETE FROM transactions")
        self.assertEqual(migrate(self.conn), LATEST)
        self.assertEqual(migrate(self.conn), LATEST)
        self.assertEqual(sorted(r[0] for r in self.conn.execute("SELECT name FROM sqlite_master WHERE tbl_name = 'transactions'")), objs)
        self.assertEqual([tuple(r) for r in self.conn.execute("SELECT * FROM transactions ORDER BY id")], txns)
        self.assertEqual(finance.dashboard(self.conn, "2026-01-01", "2026-12-31")["summary"], dash)
        self.assertEqual(self.conn.execute("PRAGMA foreign_key_check").fetchall(), [])


class DebtRoutes(Base):
    def test_routes_need_login_and_work(self):
        from server import web, auth
        s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
        self.ctx.port = port
        auth.setup_owner(self.conn, {"username": "owner", "password": "Str0ng-Passw0rd!", "setup_code": "123456"}, "123456")
        httpd = ThreadingHTTPServer(("127.0.0.1", port), web.make_handler(self.ctx, log=lambda *a: None))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        cookie = {}

        def call(method, path, body=None):
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=20)
            h = {"Host": "127.0.0.1:%d" % port, "Content-Type": "application/json", "X-QT-CSRF": "1"}
            if cookie:
                h["Cookie"] = cookie["v"]
            c.request(method, path, json.dumps(body) if body is not None else None, h)
            r = c.getresponse()
            data = r.read()
            sc = r.getheader("Set-Cookie")
            if sc and "qt_session=" in sc:
                cookie["v"] = sc.split(";")[0]
            c.close()
            return r.status, json.loads(data or b"null")
        try:
            self.assertEqual(call("GET", "/api/debts")[0], 401)
            self.assertEqual(call("POST", "/api/debts/receive", {})[0], 401)
            self.assertEqual(call("POST", "/api/auth/login", {"username": "owner", "password": "Str0ng-Passw0rd!"})[0], 200)
            st, r = call("POST", "/api/debts/receive", {"creditor": "SBI", "creditor_type": "bank", "txn_date": "2026-10-01",
                                                        "account_id": self.bank, "amount": "25000", "idem_key": "x1"})
            self.assertEqual(st, 201)
            did = r["debt_id"]
            st, r = call("POST", "/api/debts/%d/repay" % did, {"txn_date": "2026-10-05", "account_id": self.bank, "principal": "5000", "interest": "250"})
            self.assertEqual(st, 201)
            st, bad = call("POST", "/api/debts/%d/repay" % did, {"txn_date": "2026-10-06", "account_id": self.bank, "principal": "999999"})
            self.assertEqual(st, 400)
            st, d = call("GET", "/api/debts/%d" % did)
            self.assertEqual((st, d["total_outstanding"], len(d["transactions"])), (200, 2000000, 3))
            self.assertEqual(call("POST", "/api/debts/%d/interest" % did, {"amount": "300"})[0], 201)
            st, s = call("GET", "/api/debts/summary?from=2026-10-01&to=2026-10-31")
            self.assertEqual((st, s["totals"]["total_outstanding"]), (200, 2005000))
            st, txns = call("GET", "/api/transactions?kind=loan_received,loan_repayment")
            self.assertEqual(sorted(t["kind_label"] for t in txns), ["Loan received (financing)", "Loan repayment (principal)"])
            st, meta = call("GET", "/api/meta")
            self.assertIn("loan_received", meta["kinds"])
            self.assertEqual(call("DELETE", "/api/debts/%d" % did)[0], 409)
        finally:
            httpd.shutdown()
            httpd.server_close()
