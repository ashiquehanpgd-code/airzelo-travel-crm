"""Bank accounts, supplier payments from an account, allocation limits and the v2 migration."""
import sqlite3

from helpers import Base
from server import services
from server.db import connect, migrate, rollback, current_version, MIGRATIONS
LATEST = MIGRATIONS[-1][0]
from server.errors import ValidationError, Conflict


def balance(conn, aid):
    return next(a for a in services.list_accounts(conn) if a["id"] == aid)["balance"]


class AccountTypes(Base):
    def test_types_map_to_reporting_kind_and_keep_last4_only(self):
        aid = services.save_account(self.conn, {"name": "SBI Savings", "account_type": "savings", "bank_name": "State Bank of India",
                                                "account_last4": "1234 5678 9012", "opening_balance": "50000"})
        a = self.conn.execute("SELECT * FROM accounts WHERE id = ?", (aid,)).fetchone()
        self.assertEqual((a["kind"], a["account_type"], a["bank_name"], a["account_last4"]), ("bank", "savings", "State Bank of India", "9012"))
        for t, kind in (("current", "bank"), ("upi", "wallet"), ("credit_card", "bank"), ("cash", "cash"), ("gateway", "gateway")):
            x = services.save_account(self.conn, {"name": "Acc " + t, "account_type": t})
            self.assertEqual(self.conn.execute("SELECT kind FROM accounts WHERE id = ?", (x,)).fetchone()[0], kind)
        with self.assertRaises(ValidationError):
            services.save_account(self.conn, {"name": "Bad", "account_type": "crypto"})
        with self.assertRaises(ValidationError):
            services.save_account(self.conn, {"name": "Short", "account_type": "bank", "account_last4": "12"})

    def test_legacy_kind_payload_still_works(self):
        a = self.conn.execute("SELECT kind, account_type FROM accounts WHERE id = ?", (self.bank,)).fetchone()
        self.assertEqual((a["kind"], a["account_type"]), ("bank", "bank"))

    def test_deactivate_hides_from_payments_but_keeps_history(self):
        services.set_account_active(self.conn, self.bank, False)
        with self.assertRaises(ValidationError):
            self.txn(kind="supplier_payment", supplier_id=self.sup, amount="100", account_id=self.bank)
        services.set_account_active(self.conn, self.bank, True)
        self.txn(kind="supplier_payment", supplier_id=self.sup, amount="100", account_id=self.bank)

    def test_cannot_deactivate_last_active_account(self):
        for a in services.list_accounts(self.conn):
            if a["id"] != self.cash:
                services.set_account_active(self.conn, a["id"], False)
        with self.assertRaises(ValidationError):
            services.set_account_active(self.conn, self.cash, False)


class SupplierPaymentFromBank(Base):
    def setUp(self):
        super().setUp()
        self.hdfc = services.save_account(self.conn, {"name": "HDFC Current 2", "account_type": "current", "opening_balance": "200000"})
        self.b1 = self.booking(pnr="AAA111", purchase_cost="10000", selling_price="12000")
        self.b2 = self.booking(pnr="BBB222", purchase_cost="5000", selling_price="6000")

    def pay(self, amount, allocs, **kw):
        data = dict(kind="supplier_payment", supplier_id=self.sup, account_id=self.hdfc, amount=amount, method="bank_transfer",
                    allocations=[{"booking_id": b, "amount": a} for b, a in allocs])
        data.update(kw)
        return services.create_transaction(self.conn, data)

    def test_balance_ledger_and_allocation(self):
        tid, replay = self.pay("12000", [(self.b1, "10000"), (self.b2, "2000")], idem_key="k-1", reference="UTR1")
        self.assertFalse(replay)
        self.assertEqual(balance(self.conn, self.hdfc), 20000000 - 1200000)
        self.assertEqual(self.calc(self.b1)["supplier_outstanding"], 0)
        self.assertEqual(self.calc(self.b2)["supplier_outstanding"], 300000)
        sup = services.get_supplier(self.conn, self.sup)["balance"]
        self.assertEqual(sup["outstanding"], 300000)
        self.assertEqual(sup["unallocated"], 0)
        ledger = services.list_transactions(self.conn, {"account_id": str(self.hdfc), "supplier_id": str(self.sup), "kind": "supplier_payment"})
        self.assertEqual([r["id"] for r in ledger], [tid])
        self.assertEqual(ledger[0]["account_name"], "HDFC Current 2")

    def test_double_submit_does_not_deduct_twice(self):
        tid, _ = self.pay("2000", [(self.b2, "2000")], idem_key="same-key")
        tid2, replay = self.pay("2000", [(self.b2, "2000")], idem_key="same-key")
        self.assertEqual((tid2, replay), (tid, True))
        self.assertEqual(balance(self.conn, self.hdfc), 20000000 - 200000)
        # a different submission with identical details needs confirmation
        with self.assertRaises(Conflict):
            self.pay("2000", [(self.b2, "2000")], idem_key="other-key")

    def test_allocation_cannot_exceed_payment_or_booking_balance(self):
        with self.assertRaises(ValidationError):
            self.pay("1000", [(self.b1, "1500")])
        with self.assertRaises(ValidationError) as e:
            self.pay("20000", [(self.b1, "10500")])
        self.assertIn("outstanding", str(e.exception))
        self.assertEqual(balance(self.conn, self.hdfc), 20000000)  # nothing posted

    def test_partial_allocation_leaves_unallocated(self):
        tid, _ = self.pay("15000", [(self.b1, "10000")])
        t = services.get_transaction(self.conn, tid)
        self.assertEqual((t["allocated"], t["unallocated"]), (1000000, 500000))
        # re-allocating the same payment may use what it already holds on the booking
        services.set_allocations(self.conn, tid, {"allocations": [{"booking_id": self.b1, "amount": "10000"}, {"booking_id": self.b2, "amount": "5000"}]})
        with self.assertRaises(ValidationError):
            services.set_allocations(self.conn, tid, {"allocations": [{"booking_id": self.b2, "amount": "6000"}]})

    def test_reversal_restores_balance_and_outstanding(self):
        tid, _ = self.pay("10000", [(self.b1, "10000")])
        services.reverse_transaction(self.conn, tid, {"reason": "wrong account"})
        self.assertEqual(balance(self.conn, self.hdfc), 20000000)
        self.assertEqual(self.calc(self.b1)["supplier_outstanding"], 1000000)

    def test_transfer_debits_and_credits(self):
        before_cash = balance(self.conn, self.cash)
        self.txn(kind="transfer", account_id=self.hdfc, to_account_id=self.cash, amount="2500", method="bank_transfer")
        self.assertEqual(balance(self.conn, self.hdfc), 20000000 - 250000)
        self.assertEqual(balance(self.conn, self.cash), before_cash + 250000)

    def test_credit_card_payment_increases_amount_owed(self):
        cc = services.save_account(self.conn, {"name": "ICICI Card", "account_type": "credit_card", "account_last4": "4321"})
        self.txn(kind="supplier_payment", supplier_id=self.sup, account_id=cc, amount="3000", method="card")
        self.assertEqual(balance(self.conn, cc), -300000)


class MigrationV2(Base):
    def test_rollback_and_reapply_preserve_data(self):
        aid = services.save_account(self.conn, {"name": "Federal", "account_type": "savings", "account_last4": "9999"})
        self.txn(kind="other_income", category="Interest", account_id=aid, amount="10")
        self.assertEqual(current_version(self.conn), LATEST)
        self.assertEqual(rollback(self.conn, 1), 1)
        cols = [r[1] for r in self.conn.execute("PRAGMA table_info(accounts)")]
        self.assertNotIn("account_type", cols)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 1)
        self.assertEqual(migrate(self.conn), LATEST)
        a = self.conn.execute("SELECT kind, account_type FROM accounts WHERE id = ?", (aid,)).fetchone()
        self.assertEqual((a["kind"], a["account_type"]), ("bank", "bank"))
        self.assertEqual(self.conn.execute("PRAGMA integrity_check").fetchone()[0], "ok")

    def test_v1_database_upgrades_in_place(self):
        path = self.tmp / "v1.db"
        conn = connect(path)
        from server import db
        db.current_version(conn)
        conn.executescript("BEGIN;" + db.SCHEMA_V1 + "INSERT INTO schema_migrations VALUES (1, 'initial schema', 'x');"
                           "INSERT INTO currencies(code, name) VALUES ('INR', 'Indian Rupee');"
                           "INSERT INTO accounts(name, kind, currency, created_at, updated_at) VALUES ('Cash in hand', 'cash', 'INR', 'x', 'x');COMMIT;")
        conn.execute("INSERT INTO accounts(name, kind, currency, created_at, updated_at) VALUES ('Old wallet', 'wallet', 'INR', 'x', 'x')")
        self.assertEqual(migrate(conn), LATEST)
        types = dict(conn.execute("SELECT name, account_type FROM accounts").fetchall())
        self.assertEqual(types, {"Cash in hand": "cash", "Old wallet": "wallet"})
        # running the startup migration again is a no-op: v2 is applied exactly once
        self.assertEqual(migrate(conn), LATEST)
        self.assertEqual(conn.execute("SELECT COUNT(*) FROM schema_migrations WHERE version = 2").fetchone()[0], 1)
        conn.close()


class LedgerAndReconciliation(Base):
    def test_cashbook_filters_keep_true_running_balance(self):
        hdfc = services.save_account(self.conn, {"name": "HDFC Ledger", "account_type": "current", "opening_balance": "1000"})
        b = self.booking(pnr="LED001", purchase_cost="300", selling_price="400")
        self.txn(kind="supplier_payment", supplier_id=self.sup, account_id=hdfc, amount="300", txn_date="2026-10-12",
                 allocations=[{"booking_id": b, "amount": "300"}])
        self.txn(kind="supplier_payment", supplier_id=self.sup2, account_id=hdfc, amount="100", txn_date="2026-10-13")
        self.txn(kind="other_income", category="Interest", account_id=hdfc, amount="5", txn_date="2026-10-14")
        from server import reports
        full = reports.run_report(self.conn, "cashbook", {"account_id": str(hdfc)})
        self.assertEqual([r["balance"] for r in full["rows"]], [70000, 60000, 60500])
        f = reports.run_report(self.conn, "cashbook", {"account_id": str(hdfc), "supplier_id": str(self.sup2)})
        self.assertEqual([(r["party"], r["out"], r["balance"]) for r in f["rows"]], [("Portal B", 10000, 60000)])
        self.assertEqual(f["summary"][-1]["value"], 60500)  # closing balance is the whole account
        k = reports.run_report(self.conn, "cashbook", {"account_id": str(hdfc), "kind": "other_income"})
        self.assertEqual([r["in"] for r in k["rows"]], [500])

    def test_reconcile_new_account_type(self):
        upi = services.save_account(self.conn, {"name": "GPay", "account_type": "upi", "opening_balance": "100"})
        self.txn(kind="customer_receipt", customer_id=self.cust, account_id=upi, amount="50", method="upi")
        view = services.reconciliation_view(self.conn, upi)
        r = services.save_reconciliation(self.conn, upi, {"statement_date": "2026-10-31", "statement_balance": "150",
                                                          "cleared_ids": [i["id"] for i in view["items"]]})
        self.assertEqual(r["difference"], 0)


class AccountRoutes(Base):
    """The new account fields and the activate route over HTTP."""
    def test_http_routes(self):
        import http.client, json, threading, socket
        from http.server import ThreadingHTTPServer
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
            return r.status, json.loads(data) if data else None
        try:
            self.assertEqual(call("POST", "/api/accounts/%d/active" % self.bank, {"active": False})[0], 401)
            self.assertEqual(call("POST", "/api/auth/login", {"username": "owner", "password": "Str0ng-Passw0rd!"})[0], 200)
            st, r = call("POST", "/api/accounts", {"name": "Axis Card", "account_type": "credit_card", "bank_name": "Axis", "account_last4": "4111 1111 1111 1234"})
            self.assertEqual(st, 201, r)
            meta = call("GET", "/api/meta")[1]
            acc = next(a for a in meta["accounts"] if a["id"] == r["id"])
            self.assertEqual((acc["account_type"], acc["bank_name"], acc["account_last4"], acc["kind"]), ("credit_card", "Axis", "1234", "bank"))
            self.assertNotIn("4111", json.dumps(meta))
            self.assertEqual(call("POST", "/api/accounts/%d/active" % r["id"], {"active": False})[0], 200)
            self.assertFalse(next(a for a in call("GET", "/api/meta")[1]["accounts"] if a["id"] == r["id"])["active"])
            st, e = call("POST", "/api/transactions", {"kind": "supplier_payment", "supplier_id": self.sup, "account_id": r["id"], "amount": "10"})
            self.assertEqual(st, 400)
            self.assertIn("inactive", e["error"])
        finally:
            httpd.shutdown()
            httpd.server_close()
