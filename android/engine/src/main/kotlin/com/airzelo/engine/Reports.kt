package com.airzelo.engine

import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Port of core/server/reports.py and finance.dashboard (via the iPhone engine, Finance/Reports.swift). Each report
 * returns {title, columns, rows, totals} built from the same Ledger used by the dashboard, so the figures always
 * agree (and match the desktop app).
 */

typealias JRow = LinkedHashMap<String, JValue>

data class ReportColumn(val key: String, val label: String, val type: String) {
    val isMoney get() = type == "money" || type == "money_raw"
    val json: JValue get() = JValue.obj("key" to key, "label" to label, "type" to type)
}

private fun col(key: String, label: String, type: String = "text") = ReportColumn(key, label, type)
private const val M = "money"

class Report(val name: String, val title: String, val columns: List<ReportColumn>, val rows: List<Map<String, JValue>>,
             val totals: Map<String, JValue>, val summary: List<JValue>?, val note: String?, val base: String) {

    /** The report as the desktop API returns it (used by parity tests and CSV export). */
    val json: JValue
        get() {
            val o = LinkedHashMap<String, JValue>()
            o["title"] = J(title); o["columns"] = JValue.Arr(columns.map { it.json })
            o["rows"] = JValue.Arr(rows.map { JValue.Obj(it) }); o["totals"] = JValue.Obj(totals)
            if (summary != null) o["summary"] = JValue.Arr(summary)
            if (note != null) o["note"] = J(note)
            if (name == "cashbook") o["note"] = J(note)  // the desktop always returns note here, null when there is none
            return JValue.Obj(o)
        }

    /** CSV text: header, rows, then the totals row (reports.to_csv). */
    val csv: String
        get() {
            fun cell(v: JValue?, c: ReportColumn): String {
                if (v == null || v.isNull) return ""
                if (c.isMoney && v is JValue.Int) return fmt(v.v)
                if (v is JValue.Arr) return v.v.joinToString(", ") { it.py }
                return v.py
            }
            fun esc(s: String) = if (s.any { it in ",\"\n\r" }) "\"" + s.replace("\"", "\"\"") + "\"" else s
            val lines = arrayListOf(columns.joinToString(",") { esc(it.label) })
            for (r in rows) lines.add(columns.joinToString(",") { esc(cell(r[it.key], it)) })
            if (totals.isNotEmpty()) lines.add(columns.withIndex().joinToString(",") { (i, c) -> if (i == 0) "Total" else esc(cell(totals[c.key], c)) })
            return lines.joinToString("\r\n") + "\r\n"
        }
}

class ReportParams(values: Map<String, String> = emptyMap()) {
    val values: Map<String, String> = values.filterValues { it.isNotEmpty() }
    operator fun get(k: String): String? = values[k]
    val filter: LedgerFilter
        get() = LedgerFilter(customerId = values["customer_id"], supplierId = values["supplier_id"], currency = values["currency"],
            status = values["status"], serviceType = values["service_type"], airline = values["airline"])
}

val REPORT_NAMES = listOf("sales", "bookings", "profit", "customer_outstanding", "supplier_outstanding", "expenses", "cashbook",
    "refunds", "commission", "monthly", "yearly", "airline", "supplier_purchases")

private fun JValue?.n(): Long = this?.long ?: 0
private fun I(v: Long): JValue = JValue.Int(v)
private fun I(v: Int): JValue = JValue.Int(v.toLong())
private fun S(s: String): JValue = JValue.Str(s)
private fun jrow(vararg pairs: Pair<String, JValue>): JRow = linkedMapOf(*pairs)

private fun totalsOf(rows: List<Map<String, JValue>>, cols: List<ReportColumn>): JRow {
    val t = JRow()
    for (c in cols) if (c.type == M) t[c.key] = I(rows.sumOf { it[c.key].n() })
    return t
}

/** Stable sort by string/int keys ascending, like Python's sort with a tuple key. */
private fun sortRows(rows: List<JRow>, keys: List<String>): List<JRow> = rows.sortedWith { a, b ->
    for (k in keys) {
        val x = a[k] ?: JValue.Null; val y = b[k] ?: JValue.Null
        if (x is JValue.Int && y is JValue.Int) { if (x.v != y.v) return@sortedWith x.v.compareTo(y.v) }
        else if (x.py != y.py) return@sortedWith x.py.compareTo(y.py)
    }
    0
}

/** Stable sort, largest [key] first. */
private fun byDescending(rows: List<JRow>, key: String): List<JRow> = rows.sortedWith(compareBy { -it[key].n() })

private fun monthLabel(y: Int, m: Int, short: Boolean) = Dates.monthAbbr[m - 1] + " " + (if (short) String.format("%02d", y % 100) else y.toString())

/** Python "%.1f%%" (rounds the exact binary value, half to even, like C printf). */
private fun pct1(x: Double) = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + "%"

private val PERIOD_COLS = listOf(col("period", "Period"), col("bookings", "Bookings", "number"), col("sales", "Net sales", M),
    col("cost", "Net cost", M), col("commission", "Commission", M), col("direct", "Direct exp.", M), col("gross_profit", "Gross profit", M),
    col("other_income", "Other income", M), col("op_expenses", "Operating exp.", M), col("net_profit", "Net profit", M),
    col("tax", "Tax collected", M), col("cash_in", "Cash in", M), col("cash_out", "Cash out", M), col("net_cash", "Net cash flow", M))

fun Ledger.report(name: String, p: ReportParams): Report {
    val frm = p["from"]; val to = p["to"]; val f = p.filter
    fun make(title: String, cols: List<ReportColumn>, rows: List<JRow>, totals: Map<String, JValue>? = null,
             summary: List<JValue>? = null, note: String? = null) =
        Report(name, title, cols, rows, totals ?: totalsOf(rows, cols), summary, note, base)

    when (name) {
        "sales" -> {
            var out = ArrayList<JRow>()
            for (b in bookingList) {
                if (!(b.committed && inRange(b.bookingDate, frm, to) && bookingMatches(b, f))) continue
                val x = b.calc.base
                out.add(jrow("booking_date" to S(b.bookingDate), "ref" to S(b.ref), "pnr" to S(b.pnr ?: ""), "customer" to S(b.customerName),
                    "supplier" to S(b.supplierName), "airline" to S(b.airlines.joinToString(", ")), "route" to S(b.route),
                    "status" to S(b.status), "currency" to S(b.currency), "rate" to S(b.row.str("exchange_rate")),
                    "selling" to I(x.selling_price), "service" to I(x.service_charge), "other" to I(x.other_income), "tax" to I(x.tax_amount),
                    "billed" to I(x.selling_price + x.service_charge + x.other_income + x.tax_amount), "_booking_id" to I(b.id)))
            }
            val sorted = sortRows(out, listOf("booking_date", "ref"))
            val cols = listOf(col("booking_date", "Date", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("customer", "Customer"),
                col("supplier", "Supplier"), col("airline", "Airline"), col("route", "Route"), col("status", "Status", "status"),
                col("currency", "Cur"), col("selling", "Ticket fare", M), col("service", "Service charge", M),
                col("other", "Other income", M), col("tax", "Tax", M), col("billed", "Total billed", M))
            return make("Sales report", cols, sorted,
                note = "Gross bookings recorded in the period (credits for cancellations appear in the refunds report and in profit).")
        }

        "bookings" -> {
            val out = ArrayList<JRow>()
            for (b in bookingList) {
                if (!(inRange(b.bookingDate, frm, to) && bookingMatches(b, f))) continue
                out.add(jrow("booking_date" to S(b.bookingDate), "ref" to S(b.ref), "pnr" to S(b.pnr ?: ""), "customer" to S(b.customerName),
                    "lead_pax" to S(b.leadPax), "pax" to I(b.paxCount), "airline" to S(b.airlines.joinToString(", ")), "route" to S(b.route),
                    "travel_date" to S(b.travelDate ?: ""), "supplier" to S(b.supplierName), "status" to S(b.status),
                    "payment" to S(b.calc.paymentStatus), "payable" to I(b.calc.customerPayable - b.calc.customerCredits),
                    "outstanding" to I(b.calc.customerOutstanding), "_booking_id" to I(b.id)))
            }
            val sorted = sortRows(out, listOf("booking_date", "ref"))
            val cols = listOf(col("booking_date", "Booked", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("customer", "Customer"),
                col("lead_pax", "Lead passenger"), col("pax", "Pax", "number"), col("airline", "Airline"), col("route", "Route"),
                col("travel_date", "Travel", "date"), col("supplier", "Supplier"), col("status", "Status", "status"),
                col("payment", "Payment", "status"), col("payable", "Net payable", M), col("outstanding", "Outstanding", M))
            val t = totalsOf(sorted, cols)
            t["pax"] = I(sorted.sumOf { it["pax"].n() })
            return make("Booking report", cols, sorted, t)
        }

        "profit" -> {
            val agg = LinkedHashMap<Long, JRow>()
            for (e in events) {
                if (!inRange(e.date, frm, to)) continue
                val b = bookings[e.bookingId]!!
                if (!bookingMatches(b, f)) continue
                val r = agg.getOrPut(b.id) {
                    jrow("booking_date" to S(b.bookingDate), "ref" to S(b.ref), "pnr" to S(b.pnr ?: ""), "customer" to S(b.customerName),
                        "airline" to S(b.airlines.joinToString(", ")), "supplier" to S(b.supplierName), "status" to S(b.status),
                        "sales" to I(0), "cost" to I(0), "markup" to I(0), "commission" to I(0), "direct" to I(0), "gross_profit" to I(0),
                        "_booking_id" to I(b.id))
                }
                for ((k, v) in listOf("sales" to e.sales, "cost" to e.cost, "markup" to e.markup, "commission" to e.commission, "direct" to e.directExpenses)) {
                    r[k] = I(r[k].n() + v)
                }
            }
            for (r in agg.values) {
                val s = r["sales"].n()
                val gp = s - r["cost"].n() + r["commission"].n() - r["direct"].n()
                r["gross_profit"] = I(gp)
                r["margin"] = S(if (s != 0L) pct1(100.0 * gp.toDouble() / s.toDouble()) else "")
            }
            val out = sortRows(agg.values.toList(), listOf("booking_date", "ref"))
            val cols = listOf(col("booking_date", "Date", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("customer", "Customer"),
                col("airline", "Airline"), col("supplier", "Supplier"), col("status", "Status", "status"),
                col("sales", "Net sales (ex tax)", M), col("cost", "Net cost", M), col("markup", "Markup", M),
                col("commission", "Commission", M), col("direct", "Direct expenses", M), col("gross_profit", "Gross profit", M),
                col("margin", "Margin"))
            val s = periodSummary(frm, to, f)
            val summary = listOf(
                JValue.obj("label" to "Net sales (ex tax)", "value" to s.sales), JValue.obj("label" to "Net purchase cost", "value" to s.cost),
                JValue.obj("label" to "Commission (net)", "value" to s.commission), JValue.obj("label" to "Direct booking expenses", "value" to s.directExpenses),
                JValue.obj("label" to "Gross profit", "value" to s.grossProfit, "strong" to true),
                JValue.obj("label" to "Other income", "value" to s.otherIncome), JValue.obj("label" to "Operating expenses", "value" to s.operatingExpenses),
                JValue.obj("label" to "Net profit", "value" to s.netProfit, "strong" to true),
                JValue.obj("label" to "Tax collected (liability, excluded)", "value" to s.tax))
            return make("Profit report", cols, out, summary = summary, note = "Net sales are after credits; tax is excluded from revenue and profit.")
        }

        "customer_outstanding" -> {
            val bal = customerBalances()
            val out = ArrayList<JRow>()
            for (cid in customers.keys) {
                val r = bal[cid]!!
                if (p["customer_id"] != null && r.customerId.toString() != p["customer_id"]) continue
                if (p["show"] != "all" && r.outstanding == 0L) continue
                out.add(jrow("customer" to S(r.name), "phone" to S(r.phone ?: ""), "bookings" to I(r.bookings), "billed" to I(r.billed),
                    "credits" to I(r.credits), "received" to I(r.received), "refunded" to I(r.refunded), "unallocated" to I(r.unallocated),
                    "outstanding" to I(r.outstanding), "_customer_id" to I(r.customerId)))
            }
            val cols = listOf(col("customer", "Customer"), col("phone", "Phone"), col("bookings", "Bookings", "number"),
                col("billed", "Billed", M), col("credits", "Credits", M), col("received", "Received", M),
                col("refunded", "Refunded", M), col("unallocated", "Unallocated advance", M), col("outstanding", "Outstanding", M))
            return make("Customer outstanding", cols, byDescending(out, "outstanding"),
                note = "Positive = customer owes you. Negative = you owe the customer (advance or refund due).")
        }

        "supplier_outstanding" -> {
            val bal = supplierBalances()
            val out = ArrayList<JRow>()
            for (sid in suppliers.keys) {
                val r = bal[sid]!!
                if (p["supplier_id"] != null && r.supplierId.toString() != p["supplier_id"]) continue
                if (p["show"] != "all" && r.outstanding == 0L) continue
                out.add(jrow("supplier" to S(r.name), "kind" to S(r.kind), "bookings" to I(r.bookings), "payable" to I(r.payable),
                    "credits" to I(r.credits), "paid" to I(r.paid), "refunds" to I(r.refunds), "unallocated" to I(r.unallocated),
                    "outstanding" to I(r.outstanding), "_supplier_id" to I(r.supplierId)))
            }
            val cols = listOf(col("supplier", "Supplier"), col("kind", "Type"), col("bookings", "Bookings", "number"), col("payable", "Payable", M),
                col("credits", "Credits & commission", M), col("paid", "Paid", M), col("refunds", "Refunds received", M),
                col("unallocated", "Unallocated", M), col("outstanding", "Outstanding", M))
            return make("Supplier outstanding", cols, byDescending(out, "outstanding"),
                note = "Positive = you owe the supplier. Negative = the supplier owes you (credit/refund due).")
        }

        "expenses" -> {
            val out = ArrayList<JRow>()
            val byCat = LinkedHashMap<String, Long>()
            for (t in transactions) {
                if (t.kind != "expense" || !inRange(t.txnDate, frm, to)) continue
                if (p["currency"] != null && t.currency != p["currency"]) continue
                if (p["category"] != null && t.category != p["category"]) continue
                val r = txnRow(t)
                val v = t.sign * t.baseAmount
                val supplierName = r["supplier_name"]!!.py
                out.add(jrow("txn_date" to S(t.txnDate), "txn_no" to S(t.txnNo), "category" to S(t.category),
                    "type" to S(if (t.isDirect) "Direct (booking ${r["booking_ref"]!!.py})" else "Operating"),
                    "payee" to S(t.payee?.takeIf { it.isNotEmpty() } ?: supplierName),
                    "account" to r["account_name"]!!, "method" to S(t.method), "reference" to S(t.reference ?: ""), "currency" to S(t.currency),
                    "amount" to I(t.sign * t.amount), "base" to I(v), "notes" to S(t.notes ?: ""), "_txn_id" to I(t.id)))
                byCat[t.category] = (byCat[t.category] ?: 0) + v
            }
            val cols = listOf(col("txn_date", "Date", "date"), col("txn_no", "No."), col("category", "Category"), col("type", "Type"),
                col("payee", "Payee"), col("account", "Paid from"), col("method", "Method"), col("reference", "Reference"),
                col("currency", "Cur"), col("amount", "Amount", "money_raw"), col("base", "Amount ($base)", M),
                col("notes", "Notes"))
            val summary = byCat.entries.sortedWith(compareBy { -it.value }).map { JValue.obj("label" to it.key, "value" to it.value) }
            return make("Expense report", cols, sortRows(out, listOf("txn_date", "txn_no")), summary = summary)
        }

        "cashbook" -> return cashbook(p)

        "refunds" -> {
            val out = ArrayList<JRow>()
            for (a in allAdjustments) {
                val b = bookings[a.bookingId]!!
                if (!inRange(a.adjDate, frm, to) || !bookingMatches(b, f)) continue
                val s = a.sign
                fun cv(k: String) = I(s * convert(a[k], a.exchangeRate))
                out.add(jrow("date" to S(a.adjDate), "no" to S(a.adjNo), "type" to S(a.kind.replace("_", " ") + (if (s < 0) " (reversal)" else "")),
                    "booking" to S(b.ref), "pnr" to S(b.pnr ?: ""), "customer" to S(b.customerName), "supplier" to S(b.supplierName),
                    "customer_credit" to cv("customer_credit"), "supplier_credit" to cv("supplier_credit"),
                    "commission_reversal" to cv("commission_reversal"), "charges" to cv("extra_customer_charge"),
                    "supplier_charges" to cv("extra_supplier_charge"), "cash" to I(0), "notes" to S(a.notes ?: ""), "_booking_id" to I(b.id)))
            }
            for (t in transactions) {
                if (!((t.kind == "customer_refund" || t.kind == "supplier_receipt") && inRange(t.txnDate, frm, to))) continue
                if (!txnMatches(t, f)) continue
                val r = txnRow(t)
                out.add(jrow("date" to S(t.txnDate), "no" to S(t.txnNo), "type" to S(KIND_LABELS[t.kind]!! + (if (t.sign < 0) " (reversal)" else "")),
                    "booking" to r["booking_ref"]!!, "pnr" to S(""), "customer" to r["customer_name"]!!, "supplier" to r["supplier_name"]!!,
                    "customer_credit" to I(0), "supplier_credit" to I(0), "commission_reversal" to I(0), "charges" to I(0), "supplier_charges" to I(0),
                    "cash" to I((if (t.kind == "customer_refund") -1 else 1) * t.sign * t.baseAmount), "notes" to S(t.notes ?: ""), "_txn_id" to I(t.id)))
            }
            val cols = listOf(col("date", "Date", "date"), col("no", "No."), col("type", "Type"), col("booking", "Booking"), col("pnr", "PNR"),
                col("customer", "Customer"), col("supplier", "Supplier"), col("customer_credit", "Customer credit", M),
                col("supplier_credit", "Supplier credit", M), col("commission_reversal", "Commission reversed", M),
                col("charges", "Charges to customer", M), col("supplier_charges", "Supplier charges", M),
                col("cash", "Cash in(+)/out(-)", M), col("notes", "Notes"))
            return make("Refunds & cancellations", cols, sortRows(out, listOf("date", "no")))
        }

        "commission" -> {
            val agg = LinkedHashMap<Long, JRow>()
            for (e in events) {
                if (e.commission == 0L || !inRange(e.date, frm, to)) continue
                val b = bookings[e.bookingId]!!
                if (!bookingMatches(b, f)) continue
                val r = agg.getOrPut(b.id) {
                    jrow("booking_date" to S(b.bookingDate), "ref" to S(b.ref), "pnr" to S(b.pnr ?: ""), "supplier" to S(b.supplierName),
                        "airline" to S(b.airlines.joinToString(", ")), "earned" to I(0), "reversed" to I(0), "net" to I(0), "_booking_id" to I(b.id))
                }
                if (e.commission > 0) r["earned"] = I(r["earned"].n() + e.commission) else r["reversed"] = I(r["reversed"].n() - e.commission)
                r["net"] = I(r["earned"].n() - r["reversed"].n())
            }
            val cols = listOf(col("booking_date", "Date", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("supplier", "Supplier"),
                col("airline", "Airline"), col("earned", "Earned", M), col("reversed", "Reversed", M), col("net", "Net commission", M))
            return make("Commission income", cols, sortRows(agg.values.toList(), listOf("booking_date", "ref")))
        }

        "monthly" -> {
            val today = Dates.today()
            val year = (p["year"] ?: today.take(4)).toIntOrNull() ?: throw AZError.validation("Year is invalid")
            val periods = (1..12).map { m ->
                Triple(monthLabel(year, m, short = false), String.format("%04d-%02d-01", year, m),
                    String.format("%04d-%02d-%02d", year, m, Dates.daysInMonth(year, m)))
            }
            val out = periodRows(periods)
            val t = totalsOf(out, PERIOD_COLS)
            t["bookings"] = I(out.sumOf { it["bookings"].n() })
            return make("Monthly summary $year", PERIOD_COLS, out, t)
        }

        "yearly" -> {
            val years = HashSet<Int>()
            for (e in events) years.add(e.date.take(4).toIntOrNull() ?: 0)
            for (t in transactions) years.add(t.txnDate.take(4).toIntOrNull() ?: 0)
            if (years.isEmpty()) years.add(Dates.today().take(4).toInt())
            val fy = p["basis"] == "fy"
            val all = HashSet(years)
            if (fy) all.add(years.min() - 1)
            val periods = all.sorted().map { y ->
                if (fy) Triple("FY $y-" + String.format("%02d", (y + 1) % 100), "$y-04-01", "${y + 1}-03-31")
                else Triple(y.toString(), "$y-01-01", "$y-12-31")
            }
            val out = periodRows(periods).filter { r -> listOf("bookings", "cash_in", "cash_out", "op_expenses", "sales").any { r[it].n() != 0L } }
            val t = totalsOf(out, PERIOD_COLS)
            t["bookings"] = I(out.sumOf { it["bookings"].n() })
            return make("Yearly summary" + (if (fy) " (Indian financial year)" else ""), PERIOD_COLS, out, t)
        }

        "airline", "supplier_purchases" -> {
            val isAirline = name == "airline"
            val agg = LinkedHashMap<String, LinkedHashMap<String, Long>>()
            for (e in events) {
                if (!inRange(e.date, frm, to)) continue
                val b = bookings[e.bookingId]!!
                if (!bookingMatches(b, f)) continue
                val key = if (isAirline) (b.airlines.firstOrNull() ?: "(no airline)") else b.supplierName.ifEmpty { "(no supplier)" }
                val a = agg.getOrPut(key) { LinkedHashMap() }
                for ((k, v) in listOf("bookings" to e.bookings, "sales" to e.sales, "cost" to e.cost, "commission" to e.commission, "direct" to e.directExpenses)) {
                    a[k] = (a[k] ?: 0) + v
                }
            }
            val out = agg.map { (k, a) ->
                val r = jrow((if (isAirline) "airline" else "supplier") to S(k))
                for (x in listOf("bookings", "sales", "cost", "commission", "direct")) r[x] = I(a[x] ?: 0)
                r["gross_profit"] = I((a["sales"] ?: 0) - (a["cost"] ?: 0) + (a["commission"] ?: 0) - (a["direct"] ?: 0))
                r
            }
            val sorted = byDescending(out, if (isAirline) "sales" else "cost")
            val cols = if (isAirline)
                listOf(col("airline", "Airline (first segment)"), col("bookings", "Bookings", "number"), col("sales", "Net sales", M),
                    col("cost", "Net cost", M), col("commission", "Commission", M), col("direct", "Direct exp.", M), col("gross_profit", "Gross profit", M))
            else
                listOf(col("supplier", "Supplier / portal"), col("bookings", "Bookings", "number"), col("cost", "Net purchases", M),
                    col("commission", "Commission", M), col("sales", "Net sales", M), col("gross_profit", "Gross profit", M))
            val t = totalsOf(sorted, cols)
            t["bookings"] = I(sorted.sumOf { it["bookings"].n() })
            return make(if (isAirline) "Airline-wise sales" else "Supplier-wise purchases", cols, sorted, t)
        }

        else -> throw AZError.notFound("Unknown report")
    }
}

private fun Ledger.periodRows(periods: List<Triple<String, String, String>>): List<JRow> = periods.map { (label, frm, to) ->
    val s = periodSummary(frm, to); val c = s.cash
    jrow("period" to S(label), "bookings" to I(s.bookings), "sales" to I(s.sales), "cost" to I(s.cost), "commission" to I(s.commission),
        "direct" to I(s.directExpenses), "gross_profit" to I(s.grossProfit), "other_income" to I(s.otherIncome),
        "op_expenses" to I(s.operatingExpenses), "net_profit" to I(s.netProfit), "tax" to I(s.tax),
        "cash_in" to I(c.cashIn), "cash_out" to I(c.cashOut), "net_cash" to I(c.netCashFlow))
}

private fun Ledger.cashbook(p: ReportParams): Report {
    val frm = p["from"]; val to = p["to"]; val accId = p["account_id"]
    val accs = accounts.values.filter { accId == null || it.long("id").toString() == accId }
    if (accId != null && accs.isEmpty()) throw AZError.notFound("Account not found")
    val single = accs.size == 1
    var out = ArrayList<JRow>()
    var opening = if (single) accs[0].long("opening_balance") else 0L
    val ids = accs.map { it.long("id") }.toSet()
    for (t in transactions) {
        for ((aid, eff) in accountEffects(t)) {
            if (aid !in ids) continue
            if (single && frm != null && t.txnDate < frm) { opening += eff; continue }
            if (!inRange(t.txnDate, frm, to)) continue
            val r = txnRow(t)
            var party = listOf(r["customer_name"]!!.py, r["supplier_name"]!!.py, t.payee ?: "").firstOrNull { it.isNotEmpty() } ?: ""
            if (party.isEmpty() && t.kind == "transfer") party = r["to_account_name"]!!.py
            out.add(jrow("txn_date" to S(t.txnDate), "txn_no" to S(t.txnNo), "account" to S(accounts[aid]!!.str("name")),
                "type" to S(KIND_LABELS[t.kind]!! + (if (t.sign < 0) " (reversal)" else "")), "party" to S(party),
                "booking" to r["booking_ref"]!!, "reference" to S(t.reference ?: ""), "method" to S(t.method),
                "currency" to S(accounts[aid]!!.str("currency")), "in" to I(if (eff > 0) eff else 0), "out" to I(if (eff < 0) -eff else 0),
                "balance" to I(0), "_txn_id" to I(t.id), "_sup" to J(t.supplierId), "_kind" to S(t.kind)))
        }
    }
    var rows = sortRows(out, listOf("txn_date", "txn_no"))
    if (single) {
        var bal = opening
        for (r in rows) { bal += r["in"].n() - r["out"].n(); r["balance"] = I(bal) }
    }
    val closing = opening + rows.sumOf { it["in"].n() - it["out"].n() }
    val supId = p["supplier_id"]
    val kinds = (p["kind"] ?: "").split(",").filter { it.isNotEmpty() }.toSet()
    val filtered = supId != null || kinds.isNotEmpty()
    if (supId != null) rows = rows.filter { (if (it["_sup"]!!.isNull) "" else it["_sup"]!!.py) == supId }
    if (kinds.isNotEmpty()) rows = rows.filter { it["_kind"]!!.py in kinds }
    val cols = arrayListOf(col("txn_date", "Date", "date"), col("txn_no", "No."), col("account", "Account"), col("type", "Type"),
        col("party", "Party"), col("booking", "Booking"), col("reference", "Reference"), col("method", "Method"),
        col("currency", "Cur"), col("in", "Money in", "money_raw"), col("out", "Money out", "money_raw"))
    if (single) cols.add(col("balance", "Balance", "money_raw"))
    val t = JRow()
    if (rows.map { it["currency"]!!.py }.toSet().size <= 1) {
        t["in"] = I(rows.sumOf { it["in"].n() }); t["out"] = I(rows.sumOf { it["out"].n() })
    }
    val summary = if (single) listOf(
        JValue.obj("label" to "Opening balance", "value" to opening, "raw" to true),
        JValue.obj("label" to "Money in", "value" to rows.sumOf { it["in"].n() }, "raw" to true),
        JValue.obj("label" to "Money out", "value" to rows.sumOf { it["out"].n() }, "raw" to true),
        JValue.obj("label" to "Closing balance", "value" to closing, "raw" to true, "strong" to true)) else emptyList()
    var note: String? = if (single) null else "Choose a single account to see running balances. Amounts are in each account's currency."
    if (filtered) {
        note = (note?.let { "$it " } ?: "") + "Filtered view: money in / out add up the rows shown; the balance column and closing balance " +
            "are the account's full running balance."
    }
    return Report("cashbook", "Cashbook" + (if (single) " - " + accs[0].str("name") else ""), cols, rows, t, summary, note, base)
}

// ---- statements

class Statement(val party: Row, val kind: String, val opening: Long, val closing: Long, val totalDebit: Long, val totalCredit: Long,
                val lines: List<Map<String, JValue>>, val from: String?, val to: String?) {
    val json: JValue
        get() = JValue.obj("kind" to kind, "opening" to opening, "closing" to closing, "total_debit" to totalDebit,
            "total_credit" to totalCredit, "lines" to lines.map { JValue.Obj(it) }, "from" to from, "to" to to)
}

private fun pnrText(pnr: String?) = if (pnr.isNullOrEmpty()) "" else " PNR $pnr"
private fun refText(r: String?) = if (r.isNullOrEmpty()) "" else " $r"

fun Ledger.customerStatement(cid: Long, from: String? = null, to: String? = null): Statement {
    val c = customers[cid] ?: throw AZError.notFound("Customer not found")
    val lines = ArrayList<JRow>()
    for (b in bookingList) {
        if (b.customerId != cid || !b.committed) continue
        val x = b.calc.base
        val amt = x.selling_price + x.service_charge + x.other_income + x.tax_amount
        if (amt != 0L) {
            lines.add(jrow("date" to S(b.bookingDate), "type" to S("Booking"), "ref" to S(b.ref),
                "description" to S("${V.capitalize(b.serviceType)}${pnrText(b.pnr)} ${b.route}"),
                "debit" to I(amt), "credit" to I(0), "booking_id" to I(b.id)))
        }
        for (a in adjustments[b.id].orEmpty()) {
            val ch = a.sign * convert(a["extra_customer_charge"], a.exchangeRate)
            val cr = a.sign * convert(a["customer_credit"], a.exchangeRate)
            val kindText = V.capitalize(a.kind.replace("_", " "))
            if (ch != 0L) lines.add(jrow("date" to S(a.adjDate), "type" to S("Charge"), "ref" to S(a.adjNo),
                "description" to S("$kindText charges - ${b.ref}"), "debit" to I(ch), "credit" to I(0), "booking_id" to I(b.id)))
            if (cr != 0L) lines.add(jrow("date" to S(a.adjDate), "type" to S("Credit"), "ref" to S(a.adjNo),
                "description" to S("$kindText credit - ${b.ref}" + (if (a.sign < 0) " (reversal)" else "")),
                "debit" to I(0), "credit" to I(cr), "booking_id" to I(b.id)))
        }
    }
    for (t in transactions) {
        if (t.customerId != cid || !(t.kind == "customer_receipt" || t.kind == "customer_refund")) continue
        val v = t.sign * t.baseAmount
        val ref = t.bookingId?.let { bookings[it]?.ref } ?: ""
        var desc = KIND_LABELS[t.kind]!! + (if (ref.isEmpty()) "" else " - $ref") + " (" + t.method.replace("_", " ") + refText(t.reference) + ")"
        if (t.sign < 0) desc += " - reversal"
        if (t.kind == "customer_receipt") lines.add(jrow("date" to S(t.txnDate), "type" to S("Receipt"), "ref" to S(t.txnNo), "description" to S(desc),
            "debit" to I(0), "credit" to I(v), "txn_id" to I(t.id)))
        else lines.add(jrow("date" to S(t.txnDate), "type" to S("Refund"), "ref" to S(t.txnNo), "description" to S(desc),
            "debit" to I(v), "credit" to I(0), "txn_id" to I(t.id)))
    }
    return running(lines, from, to, c, "customer")
}

fun Ledger.supplierStatement(sid: Long, from: String? = null, to: String? = null): Statement {
    val s = suppliers[sid] ?: throw AZError.notFound("Supplier not found")
    val lines = ArrayList<JRow>()
    for (b in bookingList) {
        if (b.supplierId != sid || !b.committed) continue
        val x = b.calc.base
        if (x.purchase_cost != 0L) lines.add(jrow("date" to S(b.bookingDate), "type" to S("Purchase"), "ref" to S(b.ref),
            "description" to S("Purchase${pnrText(b.pnr)} ${b.route}"), "debit" to I(x.purchase_cost), "credit" to I(0), "booking_id" to I(b.id)))
        if (x.commission != 0L) lines.add(jrow("date" to S(b.bookingDate), "type" to S("Commission"), "ref" to S(b.ref),
            "description" to S("Commission earned"), "debit" to I(0), "credit" to I(x.commission), "booking_id" to I(b.id)))
        for (a in adjustments[b.id].orEmpty()) {
            fun cv(k: String) = a.sign * convert(a[k], a.exchangeRate)
            if (a["extra_supplier_charge"] != 0L) lines.add(jrow("date" to S(a.adjDate), "type" to S("Charge"), "ref" to S(a.adjNo),
                "description" to S("Supplier charges - " + b.ref), "debit" to I(cv("extra_supplier_charge")), "credit" to I(0), "booking_id" to I(b.id)))
            if (a["supplier_credit"] != 0L) lines.add(jrow("date" to S(a.adjDate), "type" to S("Credit"), "ref" to S(a.adjNo),
                "description" to S("Supplier credit - " + b.ref), "debit" to I(0), "credit" to I(cv("supplier_credit")), "booking_id" to I(b.id)))
            if (a["commission_reversal"] != 0L) lines.add(jrow("date" to S(a.adjDate), "type" to S("Commission reversed"), "ref" to S(a.adjNo),
                "description" to S("Commission reversed - " + b.ref), "debit" to I(cv("commission_reversal")), "credit" to I(0), "booking_id" to I(b.id)))
        }
    }
    for (t in transactions) {
        if (t.supplierId != sid || !(t.kind == "supplier_payment" || t.kind == "supplier_receipt")) continue
        val v = t.sign * t.baseAmount
        val ref = t.bookingId?.let { bookings[it]?.ref } ?: ""
        val desc = KIND_LABELS[t.kind]!! + (if (ref.isEmpty()) "" else " - $ref") + " (" + t.method.replace("_", " ") +
            refText(t.reference) + ")" + (if (t.sign < 0) " - reversal" else "")
        if (t.kind == "supplier_payment") lines.add(jrow("date" to S(t.txnDate), "type" to S("Payment"), "ref" to S(t.txnNo), "description" to S(desc),
            "debit" to I(0), "credit" to I(v), "txn_id" to I(t.id)))
        else lines.add(jrow("date" to S(t.txnDate), "type" to S("Refund received"), "ref" to S(t.txnNo), "description" to S(desc),
            "debit" to I(v), "credit" to I(0), "txn_id" to I(t.id)))
    }
    return running(lines, from, to, s, "supplier")
}

private val LINE_ORDER = mapOf("Booking" to 0, "Purchase" to 0, "Commission" to 1, "Charge" to 2, "Credit" to 3, "Commission reversed" to 4)

private fun running(input: List<JRow>, frm: String?, to: String?, party: Row, kind: String): Statement {
    val lines = input.sortedWith(compareBy<JRow>({ it["date"]!!.py }, { LINE_ORDER[it["type"]!!.py] ?: 5 }, { it["ref"]!!.py }))
    var opening = 0L
    val out = ArrayList<JRow>()
    for (l in lines) {
        val d = l["date"]!!.py
        if (frm != null && d < frm) { opening += l["debit"].n() - l["credit"].n(); continue }
        if (to != null && d > to) continue
        out.add(l)
    }
    var bal = opening
    for (r in out) { bal += r["debit"].n() - r["credit"].n(); r["balance"] = I(bal) }
    return Statement(party, kind, opening, bal, out.sumOf { it["debit"].n() }, out.sumOf { it["credit"].n() }, out, frm, to)
}

// ---- dashboard

class Dashboard(val summary: ProfitTotals, val today: ProfitTotals, val month: ProfitTotals, val year: ProfitTotals,
                val customerOutstanding: Long, val customerCreditBalances: Long, val supplierOutstanding: Long, val supplierCreditBalances: Long,
                val pending: List<Map<String, JValue>>, val pendingCount: Int, val cancellationsCount: Int,
                val accounts: List<Ledger.AccountBalance>, val chart: List<Map<String, JValue>>,
                val recentBookings: List<Map<String, JValue>>, val recentTransactions: List<Map<String, JValue>>,
                val upcoming: List<Map<String, JValue>>, val followUps: List<Map<String, JValue>>) {
    val json: JValue
        get() {
            fun pick(t: ProfitTotals) = JValue.obj("bookings" to t.bookings, "sales" to t.sales, "gross_profit" to t.grossProfit)
            return JValue.obj("summary" to summary.summaryJSON, "today" to pick(today), "month" to pick(month), "year" to pick(year),
                "customer_outstanding" to customerOutstanding, "customer_credit_balances" to customerCreditBalances,
                "supplier_outstanding" to supplierOutstanding, "supplier_credit_balances" to supplierCreditBalances,
                "pending_count" to pendingCount, "pending" to pending.map { JValue.Obj(it) }, "cancellations_count" to cancellationsCount,
                "accounts" to accounts.map { it.json }, "chart" to chart.map { JValue.Obj(it) },
                "upcoming" to upcoming.map { JValue.Obj(it) }, "follow_ups" to followUps.map { JValue.Obj(it) })
        }
}

fun Ledger.dashboard(from: String?, to: String?, today: String? = null): Dashboard {
    val frm = from
    val today = today ?: Dates.today()
    val s = periodSummary(frm, to)
    val monthStart = today.take(8) + "01"
    val yearStart = today.take(5) + "01-01"
    val cb = customerBalances(); val sb = supplierBalances()
    val pending = bookingList.filter { it.committed && it.calc.customerOutstanding > 0 }.map { bookingRow(it) }
        .sortedWith(compareBy<JRow>({ if (it["travel_date"]!!.isNull) "9999" else it["travel_date"]!!.py }, { it["ref"]!!.py }))
    val cancellations = allAdjustments.filter { inRange(it.adjDate, frm, to) && it.kind in listOf("cancellation", "refund") && it.sign == 1L && it.reversedBy == null }
    val accts = accountBalances().values.sortedWith(compareBy<Ledger.AccountBalance>({ it.kind }, { it.name }))

    // chart: daily when the range is 62 days or less, otherwise monthly
    val buckets = LinkedHashMap<String, JRow>()
    val d0 = frm?.takeIf { Dates.parse(it) != null }; val d1 = to?.takeIf { Dates.parse(it) != null }
    val n = if (d0 != null && d1 != null) Dates.daysBetween(d0, d1) else null
    val daily = n != null && n <= 62
    if (daily) {
        var d = d0!!
        while (d <= d1!!) {
            val day = d.substring(8).toInt(); val m = d.substring(5, 7).toInt()
            buckets[d] = jrow("label" to S(String.format("%02d ", day) + Dates.monthAbbr[m - 1]), "key" to S(d), "sales" to I(0), "gross_profit" to I(0), "cost" to I(0))
            d = Dates.addDays(d, 1)
        }
    } else {
        val end = d1 ?: Dates.today()
        val start = d0 ?: Dates.addDays(end.take(8) + "01", -334)
        var y = start.take(4).toInt(); var m = start.substring(5, 7).toInt()
        val ey = end.take(4).toInt(); val em = end.substring(5, 7).toInt()
        while (y < ey || (y == ey && m <= em)) {
            val k = String.format("%04d-%02d", y, m)
            buckets[k] = jrow("label" to S(monthLabel(y, m, short = true)), "key" to S(k), "sales" to I(0), "gross_profit" to I(0), "cost" to I(0))
            m += 1
            if (m > 12) { y += 1; m = 1 }
        }
    }
    for (e in events) {
        val k = if (daily) e.date.take(10) else e.date.take(7)
        val bk = buckets[k] ?: continue
        if (!inRange(e.date, frm, to)) continue
        bk["sales"] = I(bk["sales"].n() + e.sales)
        bk["cost"] = I(bk["cost"].n() + e.cost)
        bk["gross_profit"] = I(bk["gross_profit"].n() + e.sales - e.cost + e.commission - e.directExpenses)
    }

    val recentB = bookingList.sortedWith(compareByDescending<LedgerBooking> { it.row.str("created_at") }.thenByDescending { it.id }).take(8).map { bookingRow(it) }
    val recentT = transactions.sortedWith(compareByDescending<Txn> { it.createdAt }.thenByDescending { it.id }).take(8).map { txnRow(it) }

    val horizon = Dates.addDays(today, 14)
    val upcoming = ArrayList<JRow>()
    for (b in bookingList) {
        if (b.status in listOf("cancelled", "refunded", "enquiry")) continue
        val segs = segments[b.id].orEmpty()
        for (sg in segs) {
            val dep = sg.str("dep_at").take(10)
            if (dep.isNotEmpty() && today <= dep && dep <= horizon) {
                upcoming.add(jrow("booking_id" to I(b.id), "ref" to S(b.ref), "pnr" to J(b.pnr), "dep_at" to JValue.fromSql(sg["dep_at"]),
                    "airline" to JValue.fromSql(sg["airline"]), "flight_no" to JValue.fromSql(sg["flight_no"]), "origin" to JValue.fromSql(sg["origin"]),
                    "destination" to JValue.fromSql(sg["destination"]), "lead_pax" to S(b.leadPax), "pax_count" to I(b.paxCount),
                    "customer_name" to S(b.customerName), "outstanding" to I(b.calc.customerOutstanding)))
            }
        }
        val td = b.travelDate
        if (segs.isEmpty() && !td.isNullOrEmpty() && today <= td && td <= horizon) {
            upcoming.add(jrow("booking_id" to I(b.id), "ref" to S(b.ref), "pnr" to J(b.pnr), "dep_at" to S(td), "airline" to S(""), "flight_no" to S(""),
                "origin" to S(""), "destination" to S(""), "lead_pax" to S(b.leadPax), "pax_count" to I(b.paxCount),
                "customer_name" to S(b.customerName), "outstanding" to I(b.calc.customerOutstanding)))
        }
    }
    val upcomingSorted = upcoming.sortedWith(compareBy { if (it["dep_at"]!!.isNull) "" else it["dep_at"]!!.py })
    val follow = ArrayList<JRow>()
    for ((cid, c) in customers) {
        val fd = c.optStr("follow_up_date")
        if (fd.isNullOrEmpty() || fd > horizon || !c.bool("active")) continue
        follow.add(jrow("customer_id" to I(cid), "name" to S(c.str("name")), "phone" to JValue.fromSql(c["phone"]), "follow_up_date" to S(fd),
            "follow_up_note" to JValue.fromSql(c["follow_up_note"]), "overdue" to JValue.Bool(fd < today), "outstanding" to I(cb[cid]!!.outstanding)))
    }
    val followSorted = follow.sortedWith(compareBy { it["follow_up_date"]!!.py })

    return Dashboard(s, profitTotals(today, today), profitTotals(monthStart, today), profitTotals(yearStart, today),
        cb.values.filter { it.outstanding > 0 }.sumOf { it.outstanding },
        cb.values.filter { it.outstanding < 0 }.sumOf { -it.outstanding },
        sb.values.filter { it.outstanding > 0 }.sumOf { it.outstanding },
        sb.values.filter { it.outstanding < 0 }.sumOf { -it.outstanding },
        pending.take(8), pending.size, cancellations.size, accts, buckets.values.toList(), recentB, recentT,
        upcomingSorted.take(12), followSorted.take(12))
}
