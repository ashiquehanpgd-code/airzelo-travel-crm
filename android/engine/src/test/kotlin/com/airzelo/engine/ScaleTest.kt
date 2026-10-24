package com.airzelo.engine

import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/** Port of AirZeloCheckSuite/ScaleChecks.swift: large-dataset performance and accounting consistency. */
class ScaleTest {
    private fun <T> time(label: String, limit: Double, body: () -> T): T {
        val start = System.nanoTime()
        val r = body()
        val s = (System.nanoTime() - start) / 1e9
        note(String.format("%-34s %8.3f s  (limit %.0f s)", label, s, limit))
        expectTrue(s <= limit, "$label took ${String.format("%.2f", s)} s, limit $limit s")
        return r
    }

    /** Bulk-loads n bookings (with passengers, segments, receipts, supplier payments, refunds and expenses) in one transaction,
     *  writing exactly the rows the services write, then exercises the app at that size. AZ_SCALE_BOOKINGS sets n (20,000). */
    @Test fun largeAgencyStaysFastAndConsistent() {
        val n = System.getenv("AZ_SCALE_BOOKINGS")?.toIntOrNull() ?: 20_000
        val root = tempDir("az-scale-")
        val store = AgencyStore(root, BackupTest.randomKey(), JdbcDb.factory)
        try {
            val a = store.open()
            val x = ServiceTest.basics(a)
            val rng = Random(42)
            val customers = maxOf(50, n / 4); val suppliers = 12
            val ts = Dates.nowISO()
            time("bulk load $n bookings", 600.0) {
                a.tx {
                    for (i in 0 until customers) a.db.run("INSERT INTO customers(name, phone, passport_no, created_at, updated_at) VALUES (?,?,?,?,?)",
                        listOf("Customer $i", "+91 9" + String.format("%09d", i), a.crypto.seal("P" + String.format("%07d", i), "passport_no"), ts, ts))
                    for (i in 0 until suppliers) a.db.run("INSERT INTO suppliers(name, kind, created_at, updated_at) VALUES (?, 'b2b_portal', ?, ?)", listOf("Portal $i", ts, ts))
                    val custBase = a.scalarInt("SELECT MIN(id) FROM customers WHERE name LIKE 'Customer %'")
                    val supBase = a.scalarInt("SELECT MIN(id) FROM suppliers WHERE name LIKE 'Portal %'")
                    for (i in 0 until n) {
                        val day = String.format("202%d-%02d-%02d", 4 + i % 3, 1 + i % 12, 1 + i % 28)
                        val sar = i % 5 == 0
                        val rate = if (sar) "22.15" else "1"
                        val cost = rng.nextLong(200_000, 6_000_001) / (if (sar) 22 else 1)
                        val sell = cost + rng.nextLong(0, 300_001) / (if (sar) 22 else 1)
                        val comm = if (i % 3 == 0) 25_000L else 0L
                        val status = listOf("ticketed", "ticketed", "confirmed", "enquiry")[i % 4]
                        val committed = if (status == "enquiry") 0 else 1
                        val cid = custBase + i % customers; val sid = supBase + i % suppliers
                        val bid = a.db.run("INSERT INTO bookings(ref, pnr, customer_id, supplier_id, service_type, booking_date, travel_date, trip_type, status, committed, " +
                            "currency, exchange_rate, purchase_cost, selling_price, commission, service_charge, created_at, updated_at) " +
                            "VALUES (?,?,?,?,'flight',?,?,'one_way',?,?,?,?,?,?,?,?,?,?)",
                            listOf(String.format("BK-%02d-%06d", 24 + i % 3, i), String.format("P%05d", i), cid, sid, day, day, status, committed,
                                if (sar) "SAR" else "INR", rate, cost, sell, comm, 50_000, ts, ts))
                        a.db.run("INSERT INTO booking_passengers(booking_id, seq, name, pax_type, ticket_number) VALUES (?,0,?,'ADT',?)",
                            listOf(bid, "Passenger $i", String.format("065%010d", i)))
                        a.db.run("INSERT INTO booking_segments(booking_id, seq, direction, airline, flight_no, origin, destination, dep_at) VALUES (?,0,'outbound',?,?,?,?,?)",
                            listOf(bid, listOf("IndiGo", "Emirates", "Saudia", "Air India Express")[i % 4], "XX ${100 + i % 900}", "COK", listOf("DXB", "JED", "DOH")[i % 3], day + "T10:00"))
                        if (committed == 0) continue
                        val payable = convert(sell + 50_000, rate)
                        fun post(kind: String, amount: Long, base: Long, account: Long, cust: Long?, sup: Long?) {
                            val tid = a.db.run("INSERT INTO transactions(txn_no, uid, txn_date, kind, sign, amount, currency, exchange_rate, base_amount, account_id, method, " +
                                "category, customer_id, supplier_id, booking_id, created_at, updated_at) VALUES (?,?,?,?,1,?,'INR','1',?,?,'cash',?,?,?,?,?,?)",
                                listOf("${kind.take(3).uppercase()}-$i", UUID.randomUUID().toString(), day, kind, amount, base, account, kind, cust, sup, bid, ts, ts))
                            a.db.run("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", listOf(tid, bid, base, ts))
                        }
                        val paid = if (i % 7 == 0) payable / 2 else payable
                        post("customer_receipt", paid, paid, x.bank, cid, null)
                        val supDue = convert(cost, rate) - convert(comm, rate)
                        if (i % 2 == 0 && supDue > 0) post("supplier_payment", supDue, supDue, x.bank, null, sid)
                        if (i % 50 == 0) a.db.run("INSERT INTO booking_adjustments(adj_no, booking_id, adj_date, kind, sign, exchange_rate, customer_credit, supplier_credit, created_at) " +
                            "VALUES (?,?,?,'cancellation',1,?,?,?,?)", listOf(String.format("ADJ-%06d", i), bid, day, rate, sell / 2, cost / 2, ts))
                        if (i % 20 == 0) a.db.run("INSERT INTO transactions(txn_no, uid, txn_date, kind, sign, amount, currency, exchange_rate, base_amount, account_id, method, category, created_at, updated_at) " +
                            "VALUES (?,?,?,'expense',1,?,'INR','1',?,?,'cash','Office expenses',?,?)", listOf("EXP-$i", UUID.randomUUID().toString(), day, 150_000, 150_000, x.cash, ts, ts))
                    }
                }
            }
            note(String.format("database %.1f MB, %d transactions", store.dbFile.length() / 1_048_576.0, a.scalarInt("SELECT COUNT(*) FROM transactions")))
            val rt = Runtime.getRuntime()
            val memBefore = (rt.totalMemory() - rt.freeMemory()) / 1_048_576.0
            val L = time("load ledger (all figures)", 15.0) { Ledger(a) }
            note(String.format("heap: %.0f MB before, %.0f MB with the ledger loaded", memBefore, (rt.totalMemory() - rt.freeMemory()) / 1_048_576.0))
            val dash = time("dashboard", 10.0) { L.dashboard("2026-01-01", "2026-12-31") }
            for (name in listOf("sales", "profit", "customer_outstanding", "cashbook", "monthly", "yearly", "airline", "refunds")) {
                time("report: $name", 10.0) { L.report(name, ReportParams(if (name == "monthly") mapOf("year" to "2026") else mapOf("from" to "2026-01-01", "to" to "2026-12-31"))) }
            }
            time("search \"P01234\"", 10.0) { a.searchBookings(mapOf("q" to "P01234"), L) }
            val custId = a.scalarInt("SELECT MIN(id) FROM customers WHERE name LIKE 'Customer %'")
            val newBooking = time("save booking (service)", 5.0) {
                a.saveBooking(f("customer_id" to custId, "status" to "ticketed", "purchase_cost" to "1000", "selling_price" to "1200", "passengers" to listOf(mapOf("name" to "Scale Pax"))))
            }
            time("receipt with allocation check", 15.0) { a.createTransaction(f("kind" to "customer_receipt", "account_id" to x.cash, "amount" to "1200", "booking_id" to newBooking)) }
            time("ticket PDF", 5.0) { a.ticketPDF(newBooking, withPayment = true) }
            val backup = time("encrypted backup (create + verify)", 120.0) { store.createBackup() }
            note(String.format("backup %.1f MB", backup.size / 1_048_576.0))
            time("restore (verify + safety copy + swap)", 240.0) { store.restore(store.backupData(backup)) }

            // accounting consistency at scale: independent paths must agree
            val L2 = Ledger(store.open())
            val all = L2.periodSummary()
            val committed = L2.bookingList.filter { it.committed }
            expectEq(committed.sumOf { it.calc.grossProfit }, all.grossProfit, "sum of booking profit = period gross profit")
            expectEq(committed.sumOf { it.calc.revenue }, all.sales, "sum of booking revenue = period sales")
            expectEq(L2.report("profit", ReportParams()).totals["gross_profit"]?.long, all.grossProfit, "profit report total")
            expectEq(L2.report("yearly", ReportParams()).totals["gross_profit"]?.long, all.grossProfit, "yearly report total")
            val cb = L2.customerBalances()
            expectEq(cb.values.sumOf { it.outstanding }, L2.bookingList.sumOf { it.calc.customerOutstanding } - cb.values.sumOf { it.unallocated },
                "customer balances = sum of booking outstanding - unallocated")
            val inr = L2.accountBalances().values.filter { it.currency == "INR" }
            expectEq(inr.sumOf { it.balance - it.opening }, all.cash.netCashFlow, "INR account movements = net cash flow (no transfers here)")
            note("${committed.size} committed bookings; gross profit ${money(all.grossProfit)}; dashboard pending ${dash.pendingCount}")
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }
}
