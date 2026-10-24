package com.airzelo.engine

import org.junit.Test
import java.io.File

/** Port of AirZeloCheckSuite/ServiceChecks.swift: a worked example with hand-computed figures, and the rules that protect posted money. */
class ServiceTest {
    data class Basics(val cash: Long, val bank: Long, val sar: Long, val cust: Long, val sup: Long)

    companion object {
        /** Sets up an agency with two accounts, a customer and a supplier. */
        fun basics(a: Agency): Basics {
            val cash = a.scalarInt("SELECT id FROM accounts WHERE name = 'Cash in hand'")
            val bank = a.saveAccount(f("name" to "HDFC Current", "account_type" to "current", "currency" to "INR"))
            val sar = a.saveAccount(f("name" to "SAR Cash", "account_type" to "cash", "currency" to "SAR"))
            val cust = a.createCustomer(f("name" to "Test Customer", "phone" to "+91 90000 00001", "passport_no" to "k1234567"))
            val sup = a.createSupplier(f("name" to "Portal A", "kind" to "b2b_portal"))
            return Basics(cash, bank, sar, cust, sup)
        }
    }

    @Test fun workedExample() = withClock("2026-10-25") {
        TempAgency().use { t ->
            val a = t.agency
            val x = basics(a)
            // INR ticket: cost 40,000, sell 43,000, commission 500, service charge 1,000 with GST 18% on service charge (CGST+SGST)
            val b1 = a.saveBooking(f("customer_id" to x.cust, "supplier_id" to x.sup, "status" to "ticketed", "booking_date" to "2026-10-01",
                "purchase_cost" to "40000", "selling_price" to "43000", "commission" to "500", "service_charge" to "1000",
                "tax_profile_id" to 2, "pnr" to "abc123",
                "passengers" to listOf(mapOf("name" to "Test Passenger", "ticket_number" to "065 1234567890", "passport_no" to "z7654321")),
                "segments" to listOf(mapOf("airline" to "Saudia", "flight_no" to "sv 756", "origin" to "cok", "destination" to "jed",
                    "dep_at" to "2026-11-02T04:10", "arr_at" to "2026-11-02T08:40"))))
            var L = Ledger(a)
            var c = L.bookings[b1]!!.calc
            expectEq(c.base.tax_amount, 18_000L, "GST 18% of 1,000.00")
            expectEq(c.customerPayable, 4_418_000L, "43,000 + 1,000 + 180 tax")
            expectEq(c.grossProfit, 450_000L, "3,000 markup + 500 commission + 1,000 service")
            expectEq(c.supplierOutstanding, 3_950_000L, "40,000 - 500 commission")
            expectEq(c.paymentStatus, "unpaid")
            // SAR ticket at 22.15: cost SAR 1,000.00 -> INR 22,150.00; sell SAR 1,100.00 -> INR 24,365.00
            val b2 = a.saveBooking(f("customer_id" to x.cust, "status" to "confirmed", "booking_date" to "2026-10-05", "currency" to "SAR",
                "exchange_rate" to "22.15", "purchase_cost" to "1000", "selling_price" to "1100", "passengers" to listOf(mapOf("name" to "Second Pax"))))
            L = Ledger(a)
            expectEq(L.bookings[b2]!!.calc.grossProfit, 221_500L, "SAR 100 margin at 22.15")
            a.createTransaction(f("kind" to "customer_receipt", "txn_date" to "2026-10-06", "account_id" to x.bank, "amount" to "44180", "booking_id" to b1, "method" to "upi"))
            a.createTransaction(f("kind" to "customer_receipt", "txn_date" to "2026-10-07", "account_id" to x.sar, "amount" to "500", "currency" to "SAR",
                "exchange_rate" to "22.15", "booking_id" to b2, "method" to "cash"))
            a.createTransaction(f("kind" to "supplier_payment", "txn_date" to "2026-10-07", "account_id" to x.bank, "amount" to "39500", "booking_id" to b1))
            a.createTransaction(f("kind" to "expense", "txn_date" to "2026-10-08", "account_id" to x.cash, "amount" to "750", "category" to "Rent"))
            L = Ledger(a)
            c = L.bookings[b1]!!.calc
            expectEq(c.paymentStatus, "paid")
            expectEq(c.supplierOutstanding, 0L)
            expectEq(L.bookings[b2]!!.calc.customerOutstanding, 2_436_500L - 1_107_500L, "SAR 600 still due at 22.15")
            val s = L.periodSummary("2026-10-01", "2026-10-31")
            expectEq(s.grossProfit, 671_500L)
            expectEq(s.netProfit, 596_500L, "gross - rent 750")
            expectEq(s.tax, 18_000L, "tax is a liability, not profit")
            val acct = L.accountBalances()
            expectEq(acct[x.bank]!!.balance, 4_418_000L - 3_950_000L)
            expectEq(acct[x.sar]!!.balance, 50_000L, "account balances stay in the account's own currency")
            // invoice: 2 lines + CGST/SGST 9% each
            val inv = a.createInvoice(b1, f("issue_date" to "2026-10-06"))
            val doc = a.getInvoice(inv)
            expectEq(doc.row.str("number"), "INV/2026-27/0001")
            expectEq(doc.taxes.map { it["label"].string }, listOf("CGST @ 9%", "SGST @ 9%"))
            expectEq(doc.row.long("total"), 4_418_000L)
            expectError("validation") {  // an invoice locks the prices
                a.saveBooking(f("customer_id" to x.cust, "selling_price" to "50000", "booking_date" to "2026-10-01",
                    "passengers" to listOf(mapOf("name" to "Test Passenger"))), b1)
            }
            // refund booking 1: customer gets 30,000 back, airline refunds 35,000, commission 500 reversed
            val r = a.refundBooking(b1, f("customer_refund" to "30000", "supplier_refund" to "35000", "commission_reversal" to "500", "date" to "2026-10-20",
                "new_status" to "cancelled", "pay_customer" to mapOf("account_id" to x.bank, "amount" to "30000")))
            expectEq(r.grossProfit, 900_000L)  // 4,500 - 30,000 + 35,000 - 500 = 9,000
            expectEq(r.customerOutstanding, 0L)
            expectEq(r.supplierOutstanding, -3_450_000L, "the supplier owes the 35,000 refund less the 500 commission given back")
            val cn = a.createCreditNote(r.adjustmentId, f("issue_date" to "2026-10-20"))
            expectEq(a.getInvoice(cn).row.str("number"), "CN/2026-27/0001")
            // a refund with nothing due back is refused (the desktop's original refund bug)
            expectError("refund_not_due") {
                a.createTransaction(f("kind" to "customer_refund", "txn_date" to "2026-10-21", "account_id" to x.bank, "amount" to "5000", "customer_id" to x.cust))
            }
            // reports agree with the summary
            L = Ledger(a)
            val profit = L.report("profit", ReportParams(mapOf("from" to "2026-10-01", "to" to "2026-10-31")))
            expectEq(profit.totals["gross_profit"]?.long, L.periodSummary("2026-10-01", "2026-10-31").grossProfit)
            val st = L.customerStatement(x.cust)
            expectEq(st.closing, L.customerBalances()[x.cust]!!.outstanding, "statement closing balance = outstanding")
            note("booking profit after refund ${fmt(r.grossProfit)}, statement closing ${fmt(st.closing)}")
        }
    }

    @Test fun postedMoneyCannotBeEdited() = TempAgency().use { t ->
        val a = t.agency
        val x = basics(a)
        val (tid, _) = a.createTransaction(f("kind" to "other_income", "account_id" to x.cash, "amount" to "100", "category" to "Other income"))
        expectTrue(runCatching { a.tx { a.db.run("DELETE FROM transactions WHERE id = ?", listOf(tid)) } }.isFailure, "delete must be refused by the database")
        expectTrue(runCatching { a.tx { a.db.run("UPDATE transactions SET amount = 1 WHERE id = ?", listOf(tid)) } }.isFailure, "amount change must be refused")
        val refusal = runCatching { a.tx { a.db.run("DELETE FROM transactions WHERE id = ?", listOf(tid)) } }.exceptionOrNull()
        expectTrue(refusal is AZError && refusal.code == "validation", "a trigger refusal reaches the user as a validation message: $refusal")
        expectError("validation") { a.updateTransactionMeta(tid, f("amount" to "5")) }
        val (nid, outcome) = a.correctTransaction(tid, f("amount" to "150", "reason" to "typo", "account_id" to x.cash, "category" to "Other income"))
        expectEq(outcome, "replaced")
        val L = Ledger(a)
        expectEq(L.accountBalances()[x.cash]!!.balance, 15_000L, "original reversed, corrected one posted")
        expectTrue(L.txnById[tid]!!.reversedBy != null && L.txnById[nid]!!.sign == 1L, "audit trail kept")
        expectError("validation", "This transaction has already been reversed") { a.reverseTransaction(tid, f("reason" to "again")) }
    }

    @Test fun repeatedSubmissionsPostOnce() = TempAgency().use { t ->
        val a = t.agency
        val x = basics(a)
        val data = f("kind" to "customer_receipt", "account_id" to x.cash, "amount" to "1000", "customer_id" to x.cust, "idem_key" to "tap-1")
        val first = a.createTransaction(data)
        val second = a.createTransaction(data)
        expectTrue(!first.second && second.second && first.first == second.first, "a double tap must post once")
        expectError("possible_duplicate") { a.createTransaction(data + ("idem_key" to J("tap-2"))) }
    }

    /** Every booking of the desktop parity data is re-saved from its edit payload: nothing refused, no stored field changed, no figure moved. */
    @Test fun resavingEveryDesktopBookingChangesNothing() {
        val fx = Parity.load()
        withClock(fx.today) {
            val dir = tempDir("az-edit-")
            try {
                val file = File(dir, "agency.db")
                fx.dbFile.copyTo(file)
                val a = Agency.open(JdbcDb.factory, file, FieldCrypto.ephemeral())
                try {
                    fun state(): JValue {
                        val b = ArrayList<JValue>()
                        a.db.query("SELECT * FROM bookings ORDER BY id").forEach { r -> b.add(JValue.Obj(r.json.apply { remove("updated_at") })) }
                        a.db.query("SELECT booking_id, seq, title, name, pax_type, ticket_number, passport_no, notes FROM booking_passengers ORDER BY booking_id, seq")
                            .forEach { r -> b.add(JValue.Obj(r.json.apply { put("passport_no", J(a.crypto.open(r.optStr("passport_no"), "passport_no"))) })) }
                        a.db.query("SELECT booking_id, seq, direction, airline, flight_no, origin, destination, dep_at, arr_at, cabin, baggage, airline_pnr " +
                            "FROM booking_segments ORDER BY booking_id, seq").forEach { b.add(JValue.Obj(it.json)) }
                        return JValue.Arr(b)
                    }
                    val before = state()
                    val calcBefore = Ledger(a).bookingList.map { it.calc.json }
                    val ids = a.db.query("SELECT id FROM bookings ORDER BY id").map { it.long("id") }
                    val refused = ArrayList<String>()
                    for (id in ids) {
                        val fields = a.bookingEditFields(id)
                        fields["force_duplicate"] = JValue.Bool(true)  // the scenario deliberately has duplicate PNRs
                        try { a.saveBooking(fields, id) } catch (e: AZError) { refused.add("booking $id: ${e.message}") }
                    }
                    expectTrue(refused.isEmpty(), "${refused.size} re-saves refused:\n" + refused.take(10).joinToString("\n"))
                    val diffs = Parity.diff(state(), before)
                    expectTrue(diffs.isEmpty(), "${diffs.size} stored values changed:\n" + diffs.take(15).joinToString("\n"))
                    expectTrue(Ledger(a).bookingList.map { it.calc.json } == calcBefore, "figures moved after re-saving")
                    val manual = a.scalarInt("SELECT COUNT(*) FROM bookings WHERE tax_override = 1")
                    val invoiced = a.scalarInt("SELECT COUNT(DISTINCT booking_id) FROM invoices WHERE status = 'issued' AND doc_type = 'invoice'")
                    note("${ids.size} bookings re-saved unchanged ($manual with manual tax, $invoiced with an issued invoice)")
                } finally { a.close() }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun passportSealedOnAnotherDeviceIsKept() = TempAgency().use { t ->
        val a = t.agency
        val other = FieldCrypto.ephemeral()
        val foreign = other.seal("X7654321", "passport_no")!!
        val cid = a.createCustomer(f("name" to "Moved Customer"))
        a.db.run("UPDATE customers SET passport_no = ? WHERE id = ?", listOf(foreign, cid))
        val b = a.saveBooking(f("customer_id" to cid, "status" to "confirmed", "passengers" to listOf(mapOf("name" to "Moved Pax"))))
        a.db.run("UPDATE booking_passengers SET passport_no = ? WHERE booking_id = ?", listOf(foreign, b))
        expectEq(FieldCrypto.display(a.getCustomer(cid).customer["passport_no"]?.string), "Encrypted on another device")
        a.updateCustomer(cid, f("city" to "Kochi"))
        a.saveBooking(a.bookingEditFields(b), b)
        expectEq(a.db.one("SELECT passport_no FROM customers WHERE id = ?", listOf(cid))!!.str("passport_no"), foreign, "customer value kept")
        expectEq(a.db.one("SELECT passport_no FROM booking_passengers WHERE booking_id = ?", listOf(b))!!.str("passport_no"), foreign, "passenger value kept")
        expectEq(other.open(foreign, "passport_no"), "X7654321", "still readable with its own key")
    }
}
