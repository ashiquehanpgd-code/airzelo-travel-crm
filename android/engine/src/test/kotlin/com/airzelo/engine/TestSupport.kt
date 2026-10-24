package com.airzelo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

/*
 * Shared helpers for the engine tests: the JUnit port of ios/AirZeloKit/Sources/AirZeloCheckSuite/Suite.swift.
 */

fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

/** A fresh agency in a temporary folder; [close] removes it. */
class TempAgency(crypto: FieldCrypto = FieldCrypto.ephemeral()) : AutoCloseable {
    val dir = tempDir("az-test-")
    val agency = Agency.open(JdbcDb.factory, File(dir, "agency.db"), crypto)
    override fun close() { agency.close(); dir.deleteRecursively() }
}

/** Pins the services' clock (dates default to "today"). */
fun pinClock(day: String) { Dates.now = { LocalDateTime.parse(day + "T12:00:00") } }
fun unpinClock() { Dates.now = { LocalDateTime.now() } }

fun <T> withClock(day: String, body: () -> T): T { pinClock(day); try { return body() } finally { unpinClock() } }

/** Runs [body] and requires it to throw an AZError with this code (and message, if given). */
fun expectError(code: String, message: String? = null, body: () -> Unit) {
    try {
        body()
    } catch (e: AZError) {
        if (e.code != code || (message != null && e.message != message)) fail("expected error $code ${message ?: ""}, got ${e.code}: ${e.message}")
        return
    }
    fail("expected error $code, but it succeeded")
}

fun expectTrue(cond: Boolean, msg: String) { if (!cond) fail(msg) }
fun <T> expectEq(got: T, want: T, msg: String = "") = assertEquals(msg, want, got)

/** Payload helper: fields("name" to "x", "passengers" to listOf(mapOf(...))). */
fun f(vararg pairs: Pair<String, Any?>): Fields = fieldsOf(*pairs)

fun Agency.scalarInt(sql: String, args: List<Any?> = emptyList()) = db.scalarLong(sql, args)

/** Prints a note under the test in the Gradle log (like the iOS check runner's log lines). */
fun note(s: String) = println("    note: $s")

// ---- desktop parity fixture (ios/tools/parity.py)

class Fixture(val dir: File, val today: String, val ops: List<JValue>, val snapshot: JValue, val money: JValue) {
    val dbFile get() = File(dir, "desktop.db")
}

object Parity {
    private var cached: Fixture? = null

    /** The fixture, or a skipped test without one. With AZ_REQUIRE_PARITY=1 a missing fixture fails instead. */
    fun load(): Fixture {
        cached?.let { return it }
        val dirName = System.getenv("AZ_PARITY_DIR").orEmpty()
        val required = System.getenv("AZ_REQUIRE_PARITY") == "1"
        val dir = File(dirName)
        if (dirName.isEmpty() || !File(dir, "fixture.json").exists()) {
            if (required) fail("AZ_REQUIRE_PARITY=1 but AZ_PARITY_DIR ('$dirName') has no fixture.json (run ios/tools/parity.py first)")
            Assume.assumeTrue("no AZ_PARITY_DIR (run ios/tools/parity.py first)", false)
        }
        val fx = JValue.parse(File(dir, "fixture.json").readText())
        return Fixture(dir, fx["today"].string ?: "2026-10-25", fx["ops"].array ?: emptyList(), fx["snapshot"], fx["money"]).also { cached = it }
    }

    fun outcome(body: () -> JValue): JValue = try {
        JValue.obj("ok" to body())
    } catch (e: AZError) {
        JValue.obj("error" to e.code, "message" to e.message)
    } catch (e: Exception) {
        JValue.obj("error" to "exception", "message" to e.toString())
    }

    private fun fields(v: JValue): Fields = v.obj ?: emptyMap()
    private fun id(v: JValue): Long? = v.long

    /** One desktop service call, by name, through the Kotlin services; results normalised like parity.py does. */
    fun apply(a: Agency, op: String, x: List<JValue>): JValue = when (op) {
        "update_settings" -> { a.updateSettings(fields(x[0])); J("ok") }
        "save_currency" -> { a.saveCurrency(fields(x[0])); J("ok") }
        "save_account" -> J(a.saveAccount(fields(x[0]), id(x[1])))
        "set_account_active" -> { a.setAccountActive(id(x[0])!!, x[1].truthy); x[0] }
        "save_tax_profile" -> J(a.saveTaxProfile(fields(x[0]), id(x[1])))
        "save_expense_category" -> J(a.saveExpenseCategory(fields(x[0])))
        "create_supplier" -> J(a.createSupplier(fields(x[0])))
        "update_supplier" -> { a.updateSupplier(id(x[0])!!, fields(x[1])); J("ok") }
        "delete_supplier" -> J(a.deleteSupplier(id(x[0])!!))
        "create_customer" -> J(a.createCustomer(fields(x[0])))
        "update_customer" -> { a.updateCustomer(id(x[0])!!, fields(x[1])); J("ok") }
        "delete_customer" -> J(a.deleteCustomer(id(x[0])!!))
        "save_booking" -> J(a.saveBooking(fields(x[0]), id(x[1])))
        "set_booking_status" -> J(a.setBookingStatus(id(x[0])!!, x[1].string!!))
        "delete_booking" -> J(a.deleteBooking(id(x[0])!!))
        "create_transaction" -> { val (tid, replayed) = a.createTransaction(fields(x[0])); JValue.arr(listOf(tid, replayed)) }
        "reverse_transaction" -> J(a.reverseTransaction(id(x[0])!!, fields(x[1])))
        "correct_transaction" -> { val (tid, outcome) = a.correctTransaction(id(x[0])!!, fields(x[1])); JValue.arr(listOf(tid, outcome)) }
        "update_transaction_meta" -> J(a.updateTransactionMeta(id(x[0])!!, fields(x[1])))
        "set_allocations" -> J(a.setAllocations(id(x[0])!!, fields(x[1])))
        "create_adjustment" -> J(a.createAdjustment(id(x[0])!!, fields(x[1])))
        "reverse_adjustment" -> J(a.reverseAdjustment(id(x[0])!!, fields(x[1])))
        "correct_adjustment" -> J(a.correctAdjustment(id(x[0])!!, fields(x[1])))
        "refund_booking" -> {
            val r = a.refundBooking(id(x[0])!!, fields(x[1]))
            JValue.obj("adjustment_id" to r.adjustmentId, "transactions" to (if (r.replayed) null else r.transactions),
                "gross_profit" to r.grossProfit, "customer_outstanding" to r.customerOutstanding,
                "supplier_outstanding" to r.supplierOutstanding, "payment_status" to r.paymentStatus, "idempotent_replay" to r.replayed)
        }
        "create_invoice" -> J(a.createInvoice(id(x[0])!!, fields(x[1])))
        "create_credit_note" -> J(a.createCreditNote(id(x[0])!!, fields(x[1])))
        "cancel_invoice" -> J(a.cancelInvoice(id(x[0])!!, fields(x[1])))
        "save_reconciliation" -> { val (rid, cleared, diff) = a.saveReconciliation(id(x[0])!!, fields(x[1])); JValue.arr(listOf(rid, cleared, diff)) }
        else -> throw AssertionError("parity op not mapped: $op")
    }

    // ---- snapshot (mirror of parity.py snapshot())

    private val paramSets = listOf(mapOf(), mapOf("from" to "2026-03-01", "to" to "2026-06-30"), mapOf("currency" to "SAR"), mapOf("status" to "ticketed"),
        mapOf("airline" to "emirates"), mapOf("service_type" to "flight", "from" to "2026-07-01"))
    private val volatile = setOf("created_at", "updated_at", "uid", "applied_at", "at")
    private val skipTables = setOf("audit_log", "sessions", "owner", "sqlite_sequence")

    private fun key(p: Map<String, String>) = JValue.serialize(JValue.Obj(p.mapValues { J(it.value) }))

    fun snapshot(a: Agency, today: String): JValue {
        val L = Ledger(a)
        val s = LinkedHashMap<String, JValue>()
        s["bookings"] = JValue.Obj(L.bookingList.associate { b ->
            b.id.toString() to JValue.obj("calc" to b.calc.json, "airlines" to b.airlines, "route" to b.route, "pax_count" to b.paxCount,
                "lead_pax" to b.leadPax, "customer_name" to b.customerName, "supplier_name" to b.supplierName)
        })
        val filters = ArrayList(paramSets)
        a.db.one("SELECT id FROM suppliers ORDER BY id LIMIT 1")?.let { filters.add(mapOf("supplier_id" to it.long("id").toString())) }
        a.db.one("SELECT id FROM customers ORDER BY id LIMIT 1")?.let { filters.add(mapOf("customer_id" to it.long("id").toString())) }
        for (m in 1..12) filters.add(mapOf("from" to String.format("2026-%02d-01", m), "to" to String.format("2026-%02d-28", m)))
        s["periods"] = JValue.Obj(filters.associate { fl ->
            val p = ReportParams(fl)
            key(fl) to L.periodSummary(fl["from"], fl["to"], LedgerFilter(customerId = p["customer_id"], supplierId = p["supplier_id"],
                currency = p["currency"], status = p["status"], serviceType = p["service_type"], airline = p["airline"])).summaryJSON
        })
        s["customer_balances"] = JValue.Obj(L.customerBalances().entries.associate { it.key.toString() to it.value.json })
        s["supplier_balances"] = JValue.Obj(L.supplierBalances().entries.associate { it.key.toString() to it.value.json })
        s["account_balances"] = JValue.Obj(L.accountBalances().entries.associate { it.key.toString() to it.value.json })
        s["account_balances_asof"] = JValue.Obj(L.accountBalances(asOf = "2026-06-30").entries.associate { it.key.toString() to it.value.json })
        val reports = LinkedHashMap<String, JValue>()
        for (name in REPORT_NAMES) {
            val sets: List<Map<String, String>> = when (name) {
                "cashbook" -> listOf(mapOf(), mapOf("from" to "2026-04-01", "to" to "2026-08-31")) +
                    L.accounts.keys.map { mapOf("account_id" to it.toString(), "from" to "2026-04-01") } +
                    L.accounts.keys.map { mapOf("account_id" to it.toString(), "kind" to "customer_receipt,expense") }
                "customer_outstanding", "supplier_outstanding" -> listOf(mapOf(), mapOf("show" to "all"))
                "monthly" -> listOf(mapOf("year" to "2026"), mapOf("year" to "2027"))
                "yearly" -> listOf(mapOf(), mapOf("basis" to "fy"))
                "expenses" -> listOf(mapOf(), mapOf("from" to "2026-03-01", "to" to "2026-09-30"), mapOf("category" to "Rent"))
                else -> filters
            }
            for (p in sets) reports[name + "|" + key(p)] = L.report(name, ReportParams(p)).json
        }
        s["reports"] = JValue.Obj(reports)
        val statements = LinkedHashMap<String, JValue>()
        for (cid in L.customers.keys) {
            statements["c$cid"] = L.customerStatement(cid).json
            statements["c$cid:range"] = L.customerStatement(cid, "2026-04-01", "2026-09-30").json
        }
        for (sid in L.suppliers.keys) {
            statements["s$sid"] = L.supplierStatement(sid).json
            statements["s$sid:range"] = L.supplierStatement(sid, "2026-04-01", "2026-09-30").json
        }
        s["statements"] = JValue.Obj(statements)
        s["dashboard"] = L.dashboard("2026-10-01", "2026-10-31", today).json
        s["dashboard_year"] = L.dashboard("2026-01-01", "2026-12-31", today).json
        s["search"] = JValue.Obj(listOf("ahmed", "ek", "9", "pb-26", "065", "zz").associateWith { q ->
            JValue.Arr(a.searchBookings(mapOf("q" to q), L).map { it["id"]!! })
        })
        s["tables"] = tableDump(a)
        return JValue.Obj(s)
    }

    fun tableDump(a: Agency): JValue {
        val out = LinkedHashMap<String, JValue>()
        for (t in a.db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
            val name = t.str("name")
            if (name in skipTables) continue
            var rows = a.db.query("SELECT * FROM \"$name\" ORDER BY rowid").map { r ->
                val d = r.json
                for (k in volatile) d.remove(k)
                for (k in listOf("lines_json", "tax_json")) d[k]?.string?.let { d[k] = JValue.parse(it) }
                if (d.containsKey("passport_no")) d["passport_no"] = J(a.crypto.open(r.optStr("passport_no"), "passport_no"))
                JValue.Obj(d) as JValue
            }
            if (name == "booking_history") {
                // the desktop writes some history lines while iterating a Python set (hash order), so compare them unordered
                rows = rows.map { JValue.Obj(it.obj!!.filterKeys { k -> k != "id" }) as JValue }.sortedBy { JValue.serialize(it) }
            }
            out[name] = JValue.Arr(rows)
        }
        return JValue.Obj(out)
    }

    // ---- comparison

    fun count(v: JValue): Int = when (v) {
        is JValue.Arr -> v.v.sumOf { count(it) }
        is JValue.Obj -> v.v.values.sumOf { count(it) }
        else -> 1
    }

    fun diff(got: JValue, want: JValue): List<String> = ArrayList<String>().also { diff(got, want, "", it) }

    private fun short(v: JValue) = JValue.serialize(v).take(140)

    private fun diff(got: JValue, want: JValue, path: String, out: MutableList<String>) {
        if (out.size > 200) return
        when {
            got is JValue.Obj && want is JValue.Obj -> for (k in (got.v.keys + want.v.keys).toSortedSet()) {
                val gv = got.v[k]; val wv = want.v[k]
                if (gv == null) { out.add("$path/$k: missing on Android (desktop ${short(wv!!)})"); continue }
                if (wv == null) { out.add("$path/$k: extra on Android (${short(gv)})"); continue }
                diff(gv, wv, "$path/$k", out)
            }
            got is JValue.Arr && want is JValue.Arr -> {
                if (got.v.size != want.v.size) out.add("$path: ${got.v.size} items on Android, ${want.v.size} on desktop")
                for (i in 0 until minOf(got.v.size, want.v.size)) diff(got.v[i], want.v[i], "$path[$i]", out)
            }
            got is JValue.Dbl && want is JValue.Int && got.v == want.v.toDouble() -> return
            got is JValue.Int && want is JValue.Dbl && got.v.toDouble() == want.v -> return
            got != want -> out.add("$path: Android ${short(got)} vs desktop ${short(want)}")
        }
    }
}
