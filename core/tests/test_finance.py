"""Financial calculation tests: markup, commission, payments, outstanding,
supplier payments, refunds, cancellations, multi-currency, profit and cash."""
import unittest

from helpers import Base
from server import services, finance, reports
from server.errors import ValidationError, Conflict
from server.money import parse_amount, convert, percent_of, split_half, fmt


class MoneyTests(unittest.TestCase):
    def test_parse_and_format(self):
        self.assertEqual(parse_amount("12,000.50"), 1200050)
        self.assertEqual(parse_amount("0.1"), 10)
        self.assertEqual(parse_amount(0.1 + 0.2), 30)  # float noise rounds to 0.30, not 0.30000000000000004
        self.assertEqual(fmt(1200050), "12000.50")
        self.assertEqual(fmt(-5), "-0.05")

    def test_rejects_bad_amounts(self):
        for bad in ("abc", "1.234", "-5", "1e400"):
            with self.assertRaises(ValidationError):
                parse_amount(bad)

    def test_half_up_rounding(self):
        self.assertEqual(convert(100, "22.505"), 2251)     # 22.505 -> 22.51 (half up)
        self.assertEqual(percent_of(1050, "18"), 189)      # 18% of 10.50 = 1.89
        self.assertEqual(percent_of(25, "18"), 5)          # 0.045 -> 0.05
        self.assertEqual(sum(split_half(189)), 189)        # CGST/SGST always add back exactly
        self.assertEqual(split_half(189), (95, 94))


class TicketPricingTests(Base):
    def test_markup_and_gross_profit(self):
        bid = self.booking(purchase_cost="10000", selling_price="12000")
        c = self.calc(bid)
        self.assertEqual(c["markup"], 200000)
        self.assertEqual(c["gross_profit"], 200000)
        self.assertEqual(c["customer_outstanding"], 1200000)
        self.assertEqual(c["supplier_outstanding"], 1000000)

    def test_commission_service_and_other_income(self):
        bid = self.booking(purchase_cost="10000", selling_price="12000", commission="150", service_charge="300", other_income="50")
        c = self.calc(bid)
        # gross = markup 2000 + commission 150 + service 300 + other 50
        self.assertEqual(c["gross_profit"], 250000)
        # commission reduces what we owe the supplier, it is not added to the customer bill
        self.assertEqual(c["supplier_outstanding"], 1000000 - 15000)
        self.assertEqual(c["customer_outstanding"], 1200000 + 30000 + 5000)

    def test_tax_is_not_revenue_or_profit(self):
        prof = self.conn.execute("SELECT id FROM tax_profiles WHERE basis = 'service_charge' AND split = 'cgst_sgst'").fetchone()["id"]
        bid = self.booking(purchase_cost="10000", selling_price="10000", service_charge="500", tax_profile_id=prof)
        b = services.get_booking(self.conn, bid)
        self.assertEqual(b["booking"]["taxable_value"], 50000)
        self.assertEqual(b["booking"]["tax_amount"], 9000)  # 18% of 500
        c = b["calc"]
        self.assertEqual(c["gross_profit"], 50000)          # only the service charge
        self.assertEqual(c["customer_outstanding"], 1000000 + 50000 + 9000)
        self.assertEqual(c["tax_net"], 9000)

    def test_direct_expense_counted_once(self):
        bid = self.booking()
        self.txn(kind="expense", amount="250", category="Direct booking cost", booking_id=bid)
        c = self.calc(bid)
        self.assertEqual(c["direct_expenses"], 25000)
        self.assertEqual(c["gross_profit"], 200000 - 25000)
        L = finance.Ledger(self.conn)
        s = L.period_summary("2026-10-01", "2026-10-31")
        self.assertEqual(s["operating_expenses"], 0)        # not double counted as operating expense
        self.assertEqual(s["net_profit"], 175000)

    def test_uncommitted_booking_has_no_revenue_and_receipt_is_advance(self):
        bid = self.booking(status="on_hold")
        self.txn(amount="5000", booking_id=bid)
        c = self.calc(bid)
        self.assertEqual(c["gross_profit"], 0)
        self.assertEqual(c["customer_outstanding"], -500000)   # advance held for the customer
        self.assertEqual(c["payment_status"], "advance")
        L = finance.Ledger(self.conn)
        self.assertEqual(L.period_summary("2026-10-01", "2026-10-31")["gross_profit"], 0)
        services.set_booking_status(self.conn, bid, "ticketed")
        c = self.calc(bid)
        self.assertEqual(c["customer_outstanding"], 700000)

    def test_ticketed_is_not_paid(self):
        bid = self.booking(status="ticketed")
        c = self.calc(bid)
        self.assertEqual(c["payment_status"], "unpaid")
        self.assertEqual(c["received"], 0)


class PaymentTests(Base):
    def test_partial_and_full_payments(self):
        bid = self.booking()
        self.txn(amount="5000", booking_id=bid, reference="R1")
        c = self.calc(bid)
        self.assertEqual(c["customer_outstanding"], 700000)
        self.assertEqual(c["payment_status"], "partial")
        self.txn(amount="4000", booking_id=bid, method="upi", reference="UPI-1", account_id=self.bank)
        self.txn(amount="3000", booking_id=bid, method="card", reference="CARD-1", account_id=self.bank)
        c = self.calc(bid)
        self.assertEqual(c["customer_outstanding"], 0)
        self.assertEqual(c["payment_status"], "paid")
        self.assertEqual(c["received"], 1200000)

    def test_one_receipt_allocated_across_bookings_and_advance(self):
        b1 = self.booking(pnr="AAA111")
        b2 = self.booking(pnr="BBB222", selling_price="8000", purchase_cost="7000")
        tid = self.txn(amount="25000", reference="NEFT-9", account_id=self.bank, method="bank_transfer",
                       customer_id=self.cust, allocations=[{"booking_id": b1, "amount": "12000"}, {"booking_id": b2, "amount": "8000"}])
        self.assertEqual(self.calc(b1)["customer_outstanding"], 0)
        self.assertEqual(self.calc(b2)["customer_outstanding"], 0)
        cust = services.get_customer(self.conn, self.cust)["balance"]
        self.assertEqual(cust["outstanding"], -500000)       # 5000 unallocated advance
        self.assertEqual(cust["unallocated"], 500000)
        t = services.get_transaction(self.conn, tid)
        self.assertEqual(t["unallocated"], 500000)

    def test_allocation_cannot_exceed_amount_or_cross_customers(self):
        b1 = self.booking(pnr="AAA111")
        other = self.booking(pnr="ZZZ999", customer_id=self.cust2)
        with self.assertRaises(ValidationError):
            self.txn(amount="1000", customer_id=self.cust, allocations=[{"booking_id": b1, "amount": "2000"}])
        with self.assertRaises(ValidationError):
            self.txn(amount="1000", customer_id=self.cust, allocations=[{"booking_id": other, "amount": "500"}])

    def test_later_allocation_of_advance(self):
        tid = self.txn(amount="12000", customer_id=self.cust, reference="ADV")
        bid = self.booking()
        self.assertEqual(self.calc(bid)["customer_outstanding"], 1200000)
        services.set_allocations(self.conn, tid, {"allocations": [{"booking_id": bid, "amount": "12000"}]})
        self.assertEqual(self.calc(bid)["customer_outstanding"], 0)
        self.assertEqual(services.get_customer(self.conn, self.cust)["balance"]["outstanding"], 0)

    def test_supplier_payments(self):
        bid = self.booking(commission="200")
        self.txn(kind="supplier_payment", amount="5000", booking_id=bid, account_id=self.bank, method="bank_transfer", reference="P1")
        c = self.calc(bid)
        self.assertEqual(c["supplier_outstanding"], 1000000 - 20000 - 500000)
        self.txn(kind="supplier_payment", amount="4800", booking_id=bid, account_id=self.bank, method="portal_wallet", reference="P2")
        c = self.calc(bid)
        self.assertEqual(c["supplier_outstanding"], 0)
        sup = services.get_supplier(self.conn, self.sup)["balance"]
        self.assertEqual(sup["outstanding"], 0)
        self.assertEqual(sup["paid"], 980000)

    def test_supplier_payment_wrong_supplier_rejected(self):
        bid = self.booking()
        with self.assertRaises(ValidationError):
            self.txn(kind="supplier_payment", amount="100", booking_id=bid, supplier_id=self.sup2)

    def test_duplicate_payment_protection(self):
        bid = self.booking()
        self.txn(amount="5000", booking_id=bid, reference="UTR123")
        with self.assertRaises(Conflict):
            self.txn(amount="5000", booking_id=bid, reference="UTR123")
        # explicit confirmation allows a genuine second payment
        self.txn(amount="5000", booking_id=bid, reference="UTR123", force_duplicate=True)
        self.assertEqual(self.calc(bid)["received"], 1000000)

    def test_idempotent_submission(self):
        bid = self.booking()
        t1, d1 = services.create_transaction(self.conn, {"kind": "customer_receipt", "amount": "100", "account_id": self.cash,
                                                          "booking_id": bid, "idem_key": "form-xyz", "txn_date": "2026-10-11"})
        t2, d2 = services.create_transaction(self.conn, {"kind": "customer_receipt", "amount": "100", "account_id": self.cash,
                                                          "booking_id": bid, "idem_key": "form-xyz", "txn_date": "2026-10-11"})
        self.assertEqual(t1, t2)
        self.assertFalse(d1)
        self.assertTrue(d2)
        self.assertEqual(self.calc(bid)["received"], 10000)

    def test_reversal_instead_of_delete(self):
        bid = self.booking()
        tid = self.txn(amount="5000", booking_id=bid, reference="X")
        with self.assertRaises(Exception):
            self.conn.execute("DELETE FROM transactions WHERE id = ?", (tid,))
        with self.assertRaises(Exception):
            self.conn.execute("UPDATE transactions SET amount = 1 WHERE id = ?", (tid,))
        services.reverse_transaction(self.conn, tid, {"reason": "Entered twice"})
        self.assertEqual(self.calc(bid)["customer_outstanding"], 1200000)
        with self.assertRaises(ValidationError):
            services.reverse_transaction(self.conn, tid, {"reason": "again"})
        audit = services.list_audit(self.conn, "transaction", tid)
        self.assertTrue(any(a["action"] == "reverse" for a in audit))

    def test_currency_must_match_account(self):
        bid = self.booking()
        with self.assertRaises(ValidationError):
            self.txn(amount="100", booking_id=bid, currency="SAR")


class RefundTests(Base):
    def test_full_cancellation_and_refund(self):
        bid = self.booking(purchase_cost="10000", selling_price="12000", commission="100")
        self.txn(amount="12000", booking_id=bid, reference="FULL")
        self.txn(kind="supplier_payment", amount="9900", booking_id=bid, reference="SP", account_id=self.bank, method="bank_transfer")
        # airline refunds 8000 to us; we refund customer 9000 (keeping 3000 as cancellation charge)
        services.create_adjustment(self.conn, bid, {"kind": "cancellation", "adj_date": "2026-10-20", "customer_credit": "9000",
                                                    "supplier_credit": "8000", "commission_reversal": "100", "new_status": "cancelled"})
        c = self.calc(bid)
        self.assertEqual(c["customer_outstanding"], -900000)
        # supplier owes 8000 refund less the 100 commission we must give back
        self.assertEqual(c["supplier_outstanding"], -790000)
        self.assertEqual(c["payment_status"], "overpaid")
        # profit after cancellation: revenue 12000-9000 = 3000, cost 10000-8000 = 2000, commission 0 -> 1000
        self.assertEqual(c["gross_profit"], 100000)
        self.txn(kind="supplier_receipt", amount="7900", booking_id=bid, reference="SR", account_id=self.bank, method="bank_transfer")
        self.txn(kind="customer_refund", amount="9000", booking_id=bid, reference="CR", account_id=self.bank, method="bank_transfer")
        c = self.calc(bid)
        self.assertEqual(c["customer_outstanding"], 0)
        self.assertEqual(c["supplier_outstanding"], 0)
        b = services.get_booking(self.conn, bid)["booking"]
        self.assertEqual(b["status"], "cancelled")
        L = finance.Ledger(self.conn)
        # period split: October has the booking (+2100) and the cancellation (-1100)
        self.assertEqual(L.profit_totals("2026-10-01", "2026-10-15")["gross_profit"], 210000)
        self.assertEqual(L.profit_totals("2026-10-16", "2026-10-31")["gross_profit"], -110000)
        cash = L.cash_totals("2026-10-01", "2026-10-31")
        self.assertEqual(cash["net_cash_flow"], 1200000 - 990000 + 790000 - 900000)

    def test_partial_refund(self):
        bid = self.booking()
        self.txn(amount="12000", booking_id=bid, reference="P")
        services.create_adjustment(self.conn, bid, {"kind": "refund", "customer_credit": "2000", "supplier_credit": "1500"})
        self.txn(kind="customer_refund", amount="1000", booking_id=bid, reference="R1")
        c = self.calc(bid)
        self.assertEqual(c["customer_outstanding"], -100000)   # 1000 still to refund
        self.txn(kind="customer_refund", amount="1000", booking_id=bid, reference="R2")
        self.assertEqual(self.calc(bid)["customer_outstanding"], 0)

    def test_credit_limits(self):
        bid = self.booking()
        with self.assertRaises(ValidationError):
            services.create_adjustment(self.conn, bid, {"kind": "refund", "customer_credit": "13000"})
        with self.assertRaises(ValidationError):
            services.create_adjustment(self.conn, bid, {"kind": "refund", "supplier_credit": "10001"})
        with self.assertRaises(ValidationError):
            services.create_adjustment(self.conn, bid, {"kind": "refund", "commission_reversal": "1"})

    def test_reissue_charges(self):
        bid = self.booking()
        services.create_adjustment(self.conn, bid, {"kind": "date_change", "extra_customer_charge": "2500", "extra_supplier_charge": "1800"})
        c = self.calc(bid)
        self.assertEqual(c["customer_outstanding"], 1450000)
        self.assertEqual(c["supplier_outstanding"], 1180000)
        self.assertEqual(c["gross_profit"], 200000 + 70000)

    def test_adjustment_reversal(self):
        bid = self.booking()
        aid = services.create_adjustment(self.conn, bid, {"kind": "refund", "customer_credit": "2000"})
        services.reverse_adjustment(self.conn, aid, {"reason": "wrong booking"})
        self.assertEqual(self.calc(bid)["customer_outstanding"], 1200000)
        with self.assertRaises(Exception):
            self.conn.execute("DELETE FROM booking_adjustments WHERE id = ?", (aid,))

    def test_cancel_requires_adjustment_flow(self):
        bid = self.booking()
        with self.assertRaises(ValidationError):
            services.set_booking_status(self.conn, bid, "cancelled")


class CurrencyTests(Base):
    def test_sar_booking_keeps_original_rate(self):
        bid = self.booking(currency="SAR", exchange_rate="22.15", purchase_cost="1000", selling_price="1150")
        c = self.calc(bid)
        self.assertEqual(c["base"]["selling_price"], 2547250)   # 1150 x 22.15
        self.assertEqual(c["markup"], 332250)
        b = services.get_booking(self.conn, bid)["booking"]
        self.assertEqual(b["selling_price"], 115000)              # original kept
        self.assertEqual(b["exchange_rate"], "22.15")
        # payment received in SAR at a different rate - stored with its own rate
        tid = self.txn(amount="1150", currency="SAR", exchange_rate="22.30", account_id=self.sar_acc, booking_id=bid)
        t = services.get_transaction(self.conn, tid)
        self.assertEqual(t["base_amount"], 2564500)
        # SAR 1150 settled; INR difference of 172.50 is the exchange difference shown on the booking
        self.assertEqual(self.calc(bid)["customer_outstanding"], 2547250 - 2564500)
        acc = [a for a in services.list_accounts(self.conn) if a["id"] == self.sar_acc][0]
        self.assertEqual(acc["balance"], 115000)                  # account balance in SAR

    def test_rate_required_for_foreign_currency(self):
        with self.assertRaises(ValidationError):
            self.booking(currency="SAR", exchange_rate="")

    def test_transfer_between_currencies(self):
        services.create_transaction(self.conn, {"kind": "transfer", "account_id": self.bank, "to_account_id": self.sar_acc,
                                                "amount": "22300", "to_amount": "1000", "txn_date": "2026-10-12", "method": "bank_transfer"})
        accs = {a["id"]: a for a in services.list_accounts(self.conn)}
        self.assertEqual(accs[self.bank]["balance"], -2230000)
        self.assertEqual(accs[self.sar_acc]["balance"], 100000)
        L = finance.Ledger(self.conn)
        self.assertEqual(L.cash_totals()["net_cash_flow"], 0)     # transfers are not cash flow


class DuplicateTests(Base):
    def test_duplicate_pnr_warns(self):
        self.booking(pnr="QWE123")
        with self.assertRaises(Conflict) as cm:
            self.booking(pnr="qwe123")
        self.assertEqual(cm.exception.code, "duplicate_pnr")
        self.booking(pnr="QWE123", force_duplicate=True)

    def test_multiple_passengers_and_tickets_one_pnr(self):
        bid = self.booking(passengers=[{"name": "A ONE", "ticket_number": "098-2400000001"},
                                       {"name": "B TWO", "ticket_number": "098-2400000002", "pax_type": "CHD"},
                                       {"name": "C THREE", "pax_type": "INF"}],
                           segments=[{"airline": "Air India", "flight_no": "AI 933", "origin": "COK", "destination": "DXB", "dep_at": "2026-11-02T10:00"},
                                     {"airline": "Air India", "flight_no": "AI 934", "origin": "DXB", "destination": "COK", "dep_at": "2026-11-20T22:00", "direction": "return"}])
        b = services.get_booking(self.conn, bid)
        self.assertEqual(len(b["passengers"]), 3)
        self.assertEqual(len(b["segments"]), 2)
        self.assertEqual(b["booking"]["return_date"], "2026-11-20")

    def test_duplicate_ticket_number_blocked(self):
        self.booking(pnr="T1", passengers=[{"name": "X", "ticket_number": "176-1111111111"}])
        with self.assertRaises(Conflict):
            self.booking(pnr="T2", passengers=[{"name": "Y", "ticket_number": "176-1111111111"}])
        with self.assertRaises(ValidationError):
            self.booking(pnr="T3", passengers=[{"name": "Y", "ticket_number": "1"}, {"name": "Z", "ticket_number": "1"}])


class ReconciliationTests(Base):
    def _data(self):
        b1 = self.booking(pnr="R1", purchase_cost="10000", selling_price="12000", commission="100", booking_date="2026-09-05")
        b2 = self.booking(pnr="R2", purchase_cost="5000", selling_price="5600", service_charge="200", booking_date="2026-10-03",
                          customer_id=self.cust2, supplier_id=self.sup2)
        self.txn(amount="12000", booking_id=b1, txn_date="2026-09-06", reference="a")
        self.txn(amount="3000", booking_id=b2, txn_date="2026-10-04", reference="b")
        self.txn(kind="supplier_payment", amount="9900", booking_id=b1, txn_date="2026-09-07", reference="c")
        self.txn(kind="expense", amount="1500", category="Rent", txn_date="2026-10-01", reference="d")
        self.txn(kind="expense", amount="99", category="Direct booking cost", booking_id=b2, txn_date="2026-10-05", reference="e")
        self.txn(kind="other_income", amount="40", category="Interest", txn_date="2026-10-06", account_id=self.bank, method="bank_transfer")
        services.create_adjustment(self.conn, b1, {"kind": "refund", "adj_date": "2026-10-10", "customer_credit": "1000", "supplier_credit": "800"})
        return b1, b2

    def test_dashboard_matches_reports_and_transactions(self):
        self._data()
        frm, to = "2026-09-01", "2026-10-31"
        d = finance.dashboard(self.conn, frm, to, today="2026-10-20")
        s = d["summary"]
        profit = reports.run_report(self.conn, "profit", {"from": frm, "to": to})
        self.assertEqual(sum(r["gross_profit"] for r in profit["rows"]), s["gross_profit"])
        sales = reports.run_report(self.conn, "sales", {"from": frm, "to": to})
        refunds = reports.run_report(self.conn, "refunds", {"from": frm, "to": to})
        self.assertEqual(sales["totals"]["selling"] + sales["totals"]["service"] + sales["totals"]["other"]
                         - (refunds["totals"]["customer_credit"]), s["sales"])
        monthly = reports.run_report(self.conn, "monthly", {"year": "2026"})
        self.assertEqual(monthly["totals"]["net_profit"], s["net_profit"])
        self.assertEqual(monthly["totals"]["cash_in"], s["cash"]["cash_in"])
        # cash: dashboard = sum of raw transactions
        raw_in = sum(r["base_amount"] for r in self.conn.execute("SELECT * FROM transactions WHERE kind IN ('customer_receipt','supplier_receipt','other_income')"))
        raw_out = sum(r["base_amount"] for r in self.conn.execute("SELECT * FROM transactions WHERE kind IN ('supplier_payment','customer_refund','expense')"))
        self.assertEqual(s["cash"]["cash_in"], raw_in)
        self.assertEqual(s["cash"]["cash_out"], raw_out)
        # outstanding: dashboard = outstanding reports
        co = reports.run_report(self.conn, "customer_outstanding", {})
        self.assertEqual(sum(r["outstanding"] for r in co["rows"] if r["outstanding"] > 0), d["customer_outstanding"])
        so = reports.run_report(self.conn, "supplier_outstanding", {})
        self.assertEqual(sum(r["outstanding"] for r in so["rows"] if r["outstanding"] > 0), d["supplier_outstanding"])
        # expected numbers worked out by hand
        # b1: 12000-10000+100 = 2100 ; refund: -(1000) + 800 => -200  ; b2: 5600+200-5000 = 800, direct -99
        self.assertEqual(s["gross_profit"], 210000 - 20000 + 80000 - 9900)
        self.assertEqual(s["net_profit"], s["gross_profit"] + 4000 - 150000)

    def test_statement_closing_equals_balance(self):
        b1, b2 = self._data()
        L = finance.Ledger(self.conn)
        st = reports.customer_statement(L, self.cust)
        self.assertEqual(st["closing"], L.customer_balances()[self.cust]["outstanding"])
        st2 = reports.supplier_statement(L, self.sup)
        self.assertEqual(st2["closing"], L.supplier_balances()[self.sup]["outstanding"])
        # opening balance + period movement = closing
        st3 = reports.customer_statement(L, self.cust, "2026-10-01", None)
        self.assertEqual(st3["opening"] + st3["total_debit"] - st3["total_credit"], st["closing"])

    def test_cashbook_running_balance(self):
        self._data()
        services.save_account(self.conn, {"name": "Cash in hand", "kind": "cash", "currency": "INR", "opening_balance": "1000",
                                          "confirm_opening_change": True}, self.cash)
        rep = reports.run_report(self.conn, "cashbook", {"account_id": str(self.cash), "from": "2026-10-01", "to": "2026-10-31"})
        closing = [x for x in rep["summary"] if x["label"] == "Closing balance"][0]["value"]
        acc = [a for a in services.list_accounts(self.conn) if a["id"] == self.cash][0]
        self.assertEqual(closing, acc["balance"])

    def test_bank_reconciliation(self):
        self._data()
        view = services.reconciliation_view(self.conn, self.bank)
        ids = [i["id"] for i in view["items"]]
        r = services.save_reconciliation(self.conn, self.bank, {"statement_date": "2026-10-31", "statement_balance": "40", "cleared_ids": ids})
        self.assertEqual(r["difference"], 0)
        r = services.save_reconciliation(self.conn, self.bank, {"statement_date": "2026-10-31", "statement_balance": "50", "cleared_ids": ids})
        self.assertEqual(r["difference"], 1000)

    def test_csv_export(self):
        self._data()
        rep = reports.run_report(self.conn, "sales", {})
        text = reports.to_csv(rep)
        self.assertIn("Total billed", text)
        self.assertIn("12000.00", text)

    def test_filters(self):
        self._data()
        L = finance.Ledger(self.conn)
        by_sup = L.period_summary(None, None, {"supplier_id": str(self.sup2)})
        self.assertEqual(by_sup["gross_profit"], 80000 - 9900)
        by_air = L.period_summary(None, None, {"airline": "indigo"})
        self.assertEqual(by_air["bookings"], 2)
        none = L.period_summary(None, None, {"airline": "Emirates"})
        self.assertEqual(none["bookings"], 0)


class InvoiceTests(Base):
    def test_invoice_and_credit_note(self):
        prof = self.conn.execute("SELECT id FROM tax_profiles WHERE split = 'cgst_sgst'").fetchone()["id"]
        bid = self.booking(service_charge="500", tax_profile_id=prof)
        iid = services.create_invoice(self.conn, bid, {"issue_date": "2026-10-10"})
        inv = services.get_invoice(self.conn, iid)
        self.assertEqual(inv["number"], "INV/2026-27/0001")
        self.assertEqual(inv["subtotal"], 1250000)
        self.assertEqual(inv["tax_total"], 9000)
        self.assertEqual([t["amount"] for t in inv["taxes"]], [4500, 4500])
        self.assertEqual(inv["total"], 1259000)
        with self.assertRaises(Conflict):
            services.create_invoice(self.conn, bid, {})
        payload = {"customer_id": self.cust, "supplier_id": self.sup, "pnr": "ABC123", "booking_date": "2026-10-10",
                   "status": "ticketed", "currency": "INR", "purchase_cost": "10000", "selling_price": "13000",
                   "service_charge": "500", "tax_profile_id": prof, "passengers": [{"name": "AHMED KHAN"}]}
        with self.assertRaises(ValidationError):  # prices locked while an invoice is issued
            services.save_booking(self.conn, payload, bid)
        payload["selling_price"] = "12000"
        payload["notes"] = "non-price edits are still allowed"
        services.save_booking(self.conn, payload, bid)
        with self.assertRaises(Exception):
            self.conn.execute("UPDATE invoices SET total = 1 WHERE id = ?", (iid,))
        aid = services.create_adjustment(self.conn, bid, {"kind": "refund", "customer_credit": "1180", "customer_credit_tax": "180"})
        cn = services.get_invoice(self.conn, services.create_credit_note(self.conn, aid, {"issue_date": "2026-10-12"}))
        self.assertEqual(cn["number"], "CN/2026-27/0001")
        self.assertEqual(cn["total"], 118000)
        self.assertEqual(cn["doc"]["against"], "INV/2026-27/0001")
        services.cancel_invoice(self.conn, iid, {"reason": "Reissue"})
        self.assertEqual(services.get_invoice(self.conn, iid)["status"], "cancelled")

    def test_financial_year_numbering(self):
        bid = self.booking(booking_date="2026-03-15")
        iid = services.create_invoice(self.conn, bid, {"issue_date": "2026-03-20"})
        self.assertEqual(services.get_invoice(self.conn, iid)["number"], "INV/2025-26/0001")


if __name__ == "__main__":
    unittest.main()
