"""Regression tests: refunds must move profit through the refund credit (not the cash refund alone), and posted
entries can be edited (reverse + repost, atomically) or deleted safely."""
from helpers import Base
from server import services, finance, reports
from server.errors import ValidationError, Conflict


def bal(conn, aid):
    return next(a for a in services.list_accounts(conn) if a["id"] == aid)["balance"]


class RefundProfit(Base):
    """Ticket sold for 10,000 that cost 9,300: profit 700 (the example from the bug report)."""

    def setUp(self):
        super().setUp()
        self.bid = self.booking(pnr="RFD001", purchase_cost="9300", selling_price="10000")
        self.txn(amount="10000", customer_id=self.cust, account_id=self.bank, method="upi", reference="IN",
                 allocations=[{"booking_id": self.bid, "amount": "10000"}])
        self.txn(kind="supplier_payment", supplier_id=self.sup, amount="9300", account_id=self.bank, method="portal_wallet", reference="OUT",
                 allocations=[{"booking_id": self.bid, "amount": "9300"}])

    def refund(self, **kw):
        data = {"date": "2026-10-21", "customer_refund": "10000", "supplier_refund": "9300", "new_status": "cancelled"}
        data.update(kw)
        return services.refund_booking(self.conn, self.bid, data)

    def profit(self):
        return self.calc(self.bid)["gross_profit"]

    def test_sale_profit(self):
        self.assertEqual(self.profit(), 70000)

    def test_cash_refund_without_credit_is_refused(self):
        # the original bug: a refund posted with nothing due back left profit unchanged and the customer owing again
        with self.assertRaises(Conflict) as e:
            self.txn(kind="customer_refund", customer_id=self.cust, amount="10000", account_id=self.bank)
        self.assertEqual(e.exception.code, "refund_not_due")
        with self.assertRaises(Conflict):
            self.txn(kind="customer_refund", amount="10000", booking_id=self.bid, account_id=self.bank)
        self.assertEqual(self.profit(), 70000)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions WHERE kind='customer_refund'").fetchone()[0], 0)

    def test_full_refund_supplier_refunds_cost(self):
        bank_before = bal(self.conn, self.bank)
        r = self.refund(pay_customer={"account_id": self.bank, "amount": "10000", "method": "bank_transfer"},
                        supplier_received={"account_id": self.bank, "amount": "9300"})
        self.assertEqual((r["profit_before"], r["gross_profit"]), (70000, 0))
        c = self.calc(self.bid)
        self.assertEqual((c["customer_outstanding"], c["supplier_outstanding"]), (0, 0))
        self.assertEqual(bal(self.conn, self.bank), bank_before - 1000000 + 930000)
        self.assertEqual(services.get_booking(self.conn, self.bid)["booking"]["status"], "cancelled")

    def test_refund_allowed_while_customer_owes_on_another_booking(self):
        # found with real data: the customer still owed on a second booking, which blocked a legitimate refund
        self.booking(pnr="OWE001", purchase_cost="15300", selling_price="18300")
        r = self.refund(pay_customer={"account_id": self.bank, "amount": "10000"})
        self.assertEqual(r["gross_profit"], 0)
        self.assertEqual(self.calc(self.bid)["customer_outstanding"], 0)
        # an unlinked refund is still refused: there is no unused advance
        with self.assertRaises(Conflict):
            self.txn(kind="customer_refund", customer_id=self.cust, amount="100", account_id=self.bank, reference="Z")

    def test_unused_advance_can_be_refunded(self):
        self.txn(amount="500", customer_id=self.cust, account_id=self.bank, reference="ADV")  # advance, not allocated
        self.txn(kind="customer_refund", customer_id=self.cust, amount="500", account_id=self.bank, reference="ADVBACK")
        with self.assertRaises(Conflict):
            self.txn(kind="customer_refund", customer_id=self.cust, amount="1", account_id=self.bank, reference="MORE")

    def test_full_refund_with_airline_penalty(self):
        self.refund(supplier_refund="9000")  # airline keeps 300
        self.assertEqual(self.profit(), -30000)
        self.assertEqual(self.calc(self.bid)["customer_outstanding"], -1000000)  # 10,000 to pay back
        self.assertEqual(self.calc(self.bid)["supplier_outstanding"], -900000)   # 9,000 coming back

    def test_agency_keeps_a_cancellation_fee(self):
        self.refund(customer_refund="9500")  # customer gets 9,500 back, Demo Travels keeps 500
        self.assertEqual(self.profit(), 50000)

    def test_partial_refund(self):
        self.refund(customer_refund="2000", supplier_refund="1800", new_status="")
        self.assertEqual(self.profit(), 50000)  # 700 - 2000 + 1800
        self.assertEqual(services.get_booking(self.conn, self.bid)["booking"]["status"], "ticketed")
        # paying the refund later is allowed only up to what is due back
        self.txn(kind="customer_refund", customer_id=self.cust, amount="2000", account_id=self.bank, allocations=[{"booking_id": self.bid, "amount": "2000"}])
        with self.assertRaises(Conflict):
            self.txn(kind="customer_refund", customer_id=self.cust, amount="1", account_id=self.bank, reference="X")
        self.assertEqual(self.calc(self.bid)["customer_outstanding"], 0)

    def test_retained_commission(self):
        bid = self.booking(pnr="COM001", purchase_cost="9300", selling_price="10000", commission="100")
        self.assertEqual(self.calc(bid)["gross_profit"], 80000)
        services.refund_booking(self.conn, bid, {"customer_refund": "10000", "supplier_refund": "9300", "commission_reversal": "100"})
        self.assertEqual(self.calc(bid)["gross_profit"], 0)
        bid2 = self.booking(pnr="COM002", purchase_cost="9300", selling_price="10000", commission="100")
        services.refund_booking(self.conn, bid2, {"customer_refund": "10000", "supplier_refund": "9300"})  # portal lets us keep it
        self.assertEqual(self.calc(bid2)["gross_profit"], 10000)

    def test_repeated_submission_counts_once(self):
        kw = dict(idem_key="refund-click-1", pay_customer={"account_id": self.bank, "amount": "10000"})
        first = self.refund(**kw)
        again = self.refund(**kw)
        self.assertTrue(again["idempotent_replay"])
        self.assertEqual(first["adjustment_id"], again["adjustment_id"])
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM booking_adjustments").fetchone()[0], 1)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions WHERE kind='customer_refund'").fetchone()[0], 1)
        self.assertEqual(self.profit(), 0)
        # a second, different full refund cannot credit more than was billed
        with self.assertRaises(ValidationError):
            self.refund(idem_key="refund-click-2")

    def test_reverse_and_edit_refund(self):
        r = self.refund(customer_refund="10000", supplier_refund="9300")
        services.reverse_adjustment(self.conn, r["adjustment_id"], {"reason": "customer changed mind"})
        self.assertEqual(self.profit(), 70000)
        r = self.refund(customer_refund="10000", supplier_refund="9300", idem_key="k2")
        nid = services.correct_adjustment(self.conn, r["adjustment_id"], {"customer_credit": "9500", "supplier_credit": "9300", "kind": "cancellation"})
        self.assertNotEqual(nid, r["adjustment_id"])
        self.assertEqual(self.profit(), 50000)
        live = self.conn.execute("SELECT COUNT(*) FROM booking_adjustments WHERE reversed_by IS NULL AND sign = 1").fetchone()[0]
        self.assertEqual(live, 1)

    def test_refund_is_atomic(self):
        # paying from an account that does not exist fails -> the refund credit is not posted either
        with self.assertRaises(ValidationError):
            self.refund(pay_customer={"account_id": 9999, "amount": "10000"})
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM booking_adjustments").fetchone()[0], 0)
        self.assertEqual(self.profit(), 70000)

    def test_reports_agree_with_bookings(self):
        self.refund(customer_refund="9500", supplier_refund="9000", date="2026-11-03",
                    pay_customer={"account_id": self.bank, "amount": "9500"})
        L = finance.Ledger(self.conn)
        self.assertEqual(L.period_summary()["gross_profit"], sum(b["calc"]["gross_profit"] for b in L.bookings.values()))
        self.assertEqual(L.period_summary("2026-10-01", "2026-10-31")["gross_profit"], 70000)    # sale month
        self.assertEqual(L.period_summary("2026-11-01", "2026-11-30")["gross_profit"], -50000)   # refund month: -9500 + 9000
        rep = reports.run_report(self.conn, "profit", {})
        self.assertEqual(sum(r.get("gross_profit", 0) for r in rep["rows"]), 20000)


class EditAndDelete(Base):
    def setUp(self):
        super().setUp()
        self.bid = self.booking(pnr="EDT001", purchase_cost="9300", selling_price="10000")
        self.rct = self.txn(amount="10000", customer_id=self.cust, account_id=self.bank, method="upi", reference="UTR1",
                            allocations=[{"booking_id": self.bid, "amount": "10000"}])

    def live_receipts(self):
        return self.conn.execute("SELECT COUNT(*) FROM transactions WHERE kind='customer_receipt' AND sign=1 AND reversed_by IS NULL").fetchone()[0]

    def test_edit_details_updates_in_place(self):
        nid, how = services.correct_transaction(self.conn, self.rct, {"amount": "10000", "account_id": self.bank, "txn_date": "2026-10-11",
                                                                      "customer_id": self.cust, "reference": "UTR-NEW", "notes": "fixed ref",
                                                                      "allocations": [{"booking_id": self.bid, "amount": "10000"}]})
        self.assertEqual((nid, how), (self.rct, "updated"))
        t = services.get_transaction(self.conn, self.rct)
        self.assertEqual((t["reference"], t["notes"], t["reversed_by"]), ("UTR-NEW", "fixed ref", None))
        self.assertEqual(self.live_receipts(), 1)

    def test_edit_amount_replaces_entry_and_rebalances(self):
        before = bal(self.conn, self.bank)
        nid, how = services.correct_transaction(self.conn, self.rct, {"amount": "9000", "account_id": self.bank, "txn_date": "2026-10-12",
                                                                      "customer_id": self.cust, "method": "upi", "reference": "UTR1",
                                                                      "allocations": [{"booking_id": self.bid, "amount": "9000"}]})
        self.assertEqual(how, "replaced")
        self.assertEqual(self.live_receipts(), 1)  # no duplicate
        self.assertEqual(bal(self.conn, self.bank), before - 100000)
        self.assertEqual(self.calc(self.bid)["customer_outstanding"], 100000)
        old = services.get_transaction(self.conn, self.rct)
        self.assertIsNotNone(old["reversed_by"])
        self.assertIn("Corrected from", services.get_transaction(self.conn, nid)["notes"])
        self.assertTrue(any(a["action"] == "edit" for a in old["audit"]))

    def test_edit_moves_money_to_another_account(self):
        nid, _ = services.correct_transaction(self.conn, self.rct, {"amount": "10000", "account_id": self.cash, "txn_date": "2026-10-11",
                                                                    "customer_id": self.cust, "method": "cash",
                                                                    "allocations": [{"booking_id": self.bid, "amount": "10000"}]})
        self.assertEqual(bal(self.conn, self.bank), 0)
        self.assertEqual(bal(self.conn, self.cash), 1000000)

    def test_failed_edit_changes_nothing(self):
        before = bal(self.conn, self.bank)
        with self.assertRaises(ValidationError):  # allocating more than the booking's balance
            services.correct_transaction(self.conn, self.rct, {"amount": "12000", "account_id": self.bank, "txn_date": "2026-10-11",
                                                               "customer_id": self.cust, "allocations": [{"booking_id": self.bid, "amount": "12000"}]})
        t = services.get_transaction(self.conn, self.rct)
        self.assertIsNone(t["reversed_by"])  # the reversal was rolled back too
        self.assertEqual(bal(self.conn, self.bank), before)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 1)

    def test_edit_expense_and_transfer(self):
        exp = self.txn(kind="expense", category="Office expenses", amount="500", account_id=self.cash, payee="Shop")
        nid, how = services.correct_transaction(self.conn, exp, {"amount": "650", "account_id": self.cash, "txn_date": "2026-10-11",
                                                                 "category": "Office expenses", "payee": "Shop"})
        self.assertEqual(how, "replaced")
        self.assertEqual(bal(self.conn, self.cash), -65000)
        trf = self.txn(kind="transfer", account_id=self.bank, to_account_id=self.cash, amount="1000")
        services.correct_transaction(self.conn, trf, {"amount": "1500", "account_id": self.bank, "to_account_id": self.cash, "txn_date": "2026-10-11"})
        self.assertEqual(bal(self.conn, self.cash), -65000 + 150000)

    def test_cannot_edit_deleted_entry_or_change_type(self):
        with self.assertRaises(ValidationError):
            services.correct_transaction(self.conn, self.rct, {"kind": "expense", "amount": "1", "account_id": self.bank})
        services.reverse_transaction(self.conn, self.rct, {"reason": "deleted"})
        with self.assertRaises(ValidationError):
            services.correct_transaction(self.conn, self.rct, {"amount": "1", "account_id": self.bank})

    def test_repeated_edit_submission_counts_once(self):
        data = {"amount": "9000", "account_id": self.bank, "txn_date": "2026-10-12", "customer_id": self.cust, "idem_key": "edit-1",
                "allocations": [{"booking_id": self.bid, "amount": "9000"}]}
        a, _ = services.correct_transaction(self.conn, self.rct, dict(data))
        b, how = services.correct_transaction(self.conn, self.rct, dict(data))
        self.assertEqual((a, how), (b, "replay"))
        self.assertEqual(self.live_receipts(), 1)

    def test_delete_transaction_by_reversal_restores_balances(self):
        services.reverse_transaction(self.conn, self.rct, {"reason": "entered twice"})
        self.assertEqual(bal(self.conn, self.bank), 0)
        self.assertEqual(self.calc(self.bid)["customer_outstanding"], 1000000)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 2)  # kept for the audit trail

    def test_delete_booking(self):
        empty = self.booking(pnr="DEL001", passengers=[{"name": "A B", "pax_type": "ADT", "ticket_number": "111-2223334445"}])
        r = services.delete_booking(self.conn, empty)
        self.assertTrue(r["deleted"])
        self.assertIsNone(self.conn.execute("SELECT 1 FROM bookings WHERE id = ?", (empty,)).fetchone())
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM booking_passengers WHERE booking_id = ?", (empty,)).fetchone()[0], 0)
        self.assertTrue(self.conn.execute("SELECT 1 FROM audit_log WHERE entity='booking' AND entity_id=? AND action='delete'", (str(empty),)).fetchone())
        # the ticket number is free again once its booking is gone
        self.booking(pnr="DEL002", passengers=[{"name": "A B", "pax_type": "ADT", "ticket_number": "111-2223334445"}])
        # bookings with money stay
        with self.assertRaises(ValidationError):
            services.delete_booking(self.conn, self.bid)
        self.assertIsNotNone(services.get_booking(self.conn, self.bid))
        self.assertEqual(self.conn.execute("PRAGMA foreign_key_check").fetchall(), [])
