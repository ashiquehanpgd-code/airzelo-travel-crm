package com.airzelo.engine

// Port of the invoice, credit note, reconciliation and search services (core/server/services.py).

internal val SNAPSHOT_KEYS = listOf("business_name", "business_tagline", "business_address", "business_phone", "business_email", "business_gstin",
    "business_state", "invoice_terms", "invoice_footer", "legal_name", "business_country", "business_website", "business_registration",
    "logo_file", "brand_color", "date_format")
internal val SERVICE_LABELS = mapOf("flight" to "Air ticket", "visa" to "Visa service", "hotel" to "Hotel booking", "package" to "Tour package",
    "umrah" to "Umrah package", "other" to "Travel service")

/** Indian financial year of a date: "2026-27" for 2026-04-01 ... 2027-03-31. */
internal fun financialYear(d: String): String {
    val y = d.take(4).toInt(); val m = d.substring(5, 7).toInt()
    val start = if (m >= 4) y else y - 1
    return "$start-" + String.format("%02d", (start + 1) % 100)
}

internal fun taxLines(split: String, rate: String, taxAmount: Long, taxable: Long): List<JValue> {
    if (taxAmount == 0L) return emptyList()
    if (split == "cgst_sgst") {
        val (a, b) = splitHalf(taxAmount); val half = halfRate(rate)
        return listOf(JValue.obj("label" to "CGST @ $half%", "amount" to a, "taxable" to taxable), JValue.obj("label" to "SGST @ $half%", "amount" to b, "taxable" to taxable))
    }
    if (split == "igst") return listOf(JValue.obj("label" to "IGST @ $rate%", "amount" to taxAmount, "taxable" to taxable))
    return listOf(JValue.obj("label" to "Tax @ $rate%", "amount" to taxAmount, "taxable" to taxable))
}

/** Company details frozen into an issued document, so later profile edits never change it. */
internal fun Agency.businessSnapshot(): JValue { val st = allSettings(); return JValue.Obj(SNAPSHOT_KEYS.associateWith { J(st[it] ?: "") }) }

private fun Agency.customerSnapshot(cid: Long): JValue {
    val c = db.one("SELECT * FROM customers WHERE id = ?", listOf(cid))!!
    return JValue.Obj(listOf("name", "phone", "email", "address", "city", "state", "gstin").associateWith { JValue.fromSql(c[it]) })
}

fun Agency.createInvoice(bid: Long, data: Fields = emptyMap()): Long = tx {
    val b = db.one("SELECT * FROM bookings WHERE id = ?", listOf(bid)) ?: throw AZError.notFound("Booking not found")
    if (!b.bool("committed")) throw AZError.validation("Confirm or ticket the booking before issuing an invoice")
    if (db.one("SELECT 1 FROM invoices WHERE booking_id = ? AND doc_type = 'invoice' AND status = 'issued'", listOf(bid)) != null)
        throw AZError.conflict("An invoice is already issued for this booking", "invoice_exists")
    val issueDate = V.d(data, "issue_date") ?: Dates.today()
    val fy = financialYear(issueDate)
    val number = (setting("invoice_prefix", "INV/") ?: "INV/") + fy + "/" + String.format("%04d", nextCounter("invoice-$fy"))
    val pax = db.query("SELECT name, pax_type, ticket_number FROM booking_passengers WHERE booking_id = ? ORDER BY seq", listOf(bid))
    val segs = db.query("SELECT * FROM booking_segments WHERE booking_id = ? ORDER BY seq", listOf(bid))
    val prof = b.optLong("tax_profile_id")?.let { db.one("SELECT * FROM tax_profiles WHERE id = ?", listOf(it)) }
    val route = segs.joinToString(" / ") { s -> "${s.str("airline")} ${s.str("flight_no")} ${s.str("origin")}-${s.str("destination")} ${s.str("dep_at").take(10)}" }.trim()
    var desc = SERVICE_LABELS[b.str("service_type")]!!
    if (!b.optStr("pnr").isNullOrEmpty()) desc += " | PNR " + b.str("pnr")
    if (route.isNotEmpty()) desc += " | $route"
    val lines = ArrayList<JValue>()
    if (b.long("selling_price") != 0L) lines.add(JValue.obj("description" to desc,
        "detail" to pax.joinToString(", ") { p -> p.str("name") + (p.optStr("ticket_number")?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") },
        "amount" to b.long("selling_price"), "sac" to ""))
    if (b.long("service_charge") != 0L) lines.add(JValue.obj("description" to "Service charge", "detail" to "", "amount" to b.long("service_charge"), "sac" to (prof?.optStr("sac_code") ?: "")))
    if (b.long("other_income") != 0L) lines.add(JValue.obj("description" to "Other charges", "detail" to "", "amount" to b.long("other_income"), "sac" to ""))
    if (lines.isEmpty()) throw AZError.validation("This booking has no amounts to invoice")
    val subtotal = lines.sumOf { it["amount"].long ?: 0 }
    val taxes = taxLines(b.str("tax_split"), b.str("tax_rate"), b.long("tax_amount"), b.long("taxable_value"))
    val taxTotal = taxes.sumOf { it["amount"].long ?: 0 }
    val doc = JValue.obj("lines" to lines, "customer" to customerSnapshot(b.long("customer_id")), "business" to businessSnapshot(),
        "booking" to JValue.obj("ref" to b.str("ref"), "pnr" to b["pnr"], "travel_date" to b["travel_date"], "place_of_supply" to b["place_of_supply"],
            "passengers" to pax.map { JValue.Obj(it.json) }, "segments" to segs.map { JValue.Obj(it.json) }))
    val iid = db.run("INSERT INTO invoices(number, doc_type, booking_id, customer_id, issue_date, currency, lines_json, tax_json, subtotal, tax_total, total, notes, created_at) " +
        "VALUES (?, 'invoice', ?,?,?,?,?,?,?,?,?,?,?)", listOf(number, bid, b.long("customer_id"), issueDate, b.str("currency"), JValue.serialize(doc, false),
        JValue.serialize(JValue.Arr(taxes), false), subtotal, taxTotal, subtotal + taxTotal, V.s(data, "notes", 1000), Dates.nowISO()))
    history(bid, "invoice", "Invoice $number issued")
    audit("invoice", iid, "create", null, JValue.obj("number" to number, "total" to subtotal + taxTotal))
    iid
}

fun Agency.createCreditNote(aid: Long, data: Fields = emptyMap()): Long = tx {
    val a = db.one("SELECT * FROM booking_adjustments WHERE id = ?", listOf(aid)) ?: throw AZError.notFound("Adjustment not found")
    if (a.long("sign") != 1L || a.optLong("reversed_by") != null) throw AZError.validation("This adjustment has been reversed")
    if (a.long("customer_credit") == 0L) throw AZError.validation("This adjustment has no customer credit")
    if (db.one("SELECT 1 FROM invoices WHERE adjustment_id = ? AND status = 'issued'", listOf(aid)) != null) throw AZError.conflict("A credit note already exists for this adjustment")
    val b = db.one("SELECT * FROM bookings WHERE id = ?", listOf(a.long("booking_id")))!!
    val inv = db.one("SELECT id, number FROM invoices WHERE booking_id = ? AND doc_type = 'invoice' AND status = 'issued'", listOf(b.long("id")))
    val issueDate = V.d(data, "issue_date") ?: Dates.today()
    val fy = financialYear(issueDate)
    val number = (setting("credit_note_prefix", "CN/") ?: "CN/") + fy + "/" + String.format("%04d", nextCounter("credit-$fy"))
    val sub = a.long("customer_credit") - a.long("customer_credit_tax")
    val pnrPart = b.optStr("pnr")?.takeIf { it.isNotEmpty() }?.let { " | PNR $it" } ?: ""
    val lines = listOf(JValue.obj("description" to "${V.capitalize(a.str("kind").replace("_", " "))} credit - booking ${b.str("ref")}$pnrPart",
        "detail" to (a.optStr("notes") ?: ""), "amount" to sub, "sac" to ""))
    val taxes = if (a.long("customer_credit_tax") != 0L) taxLines(b.str("tax_split"), b.str("tax_rate"), a.long("customer_credit_tax"), 0) else emptyList()
    val doc = JValue.obj("lines" to lines, "customer" to customerSnapshot(b.long("customer_id")), "business" to businessSnapshot(), "against" to inv?.str("number"),
        "booking" to JValue.obj("ref" to b.str("ref"), "pnr" to b["pnr"], "travel_date" to b["travel_date"], "place_of_supply" to b["place_of_supply"]))
    val iid = db.run("INSERT INTO invoices(number, doc_type, booking_id, customer_id, adjustment_id, related_invoice_id, issue_date, currency, lines_json, tax_json, subtotal, tax_total, total, notes, created_at) " +
        "VALUES (?, 'credit_note', ?,?,?,?,?,?,?,?,?,?,?,?,?)", listOf(number, b.long("id"), b.long("customer_id"), aid, inv?.optLong("id"), issueDate, b.str("currency"),
        JValue.serialize(doc, false), JValue.serialize(JValue.Arr(taxes), false), sub, a.long("customer_credit_tax"), a.long("customer_credit"), V.s(data, "notes", 1000), Dates.nowISO()))
    history(b.long("id"), "credit_note", "Credit note $number issued")
    audit("invoice", iid, "create", null, JValue.obj("number" to number, "total" to a.long("customer_credit"), "type" to "credit_note"))
    iid
}

fun Agency.cancelInvoice(iid: Long, data: Fields): Long = tx {
    val inv = db.one("SELECT * FROM invoices WHERE id = ?", listOf(iid)) ?: throw AZError.notFound("Invoice not found")
    if (inv.str("status") == "cancelled") throw AZError.validation("Invoice is already cancelled")
    val reason = V.s(data, "reason", 500, required = true, label = "Reason")!!
    db.run("UPDATE invoices SET status = 'cancelled', cancel_reason = ? WHERE id = ?", listOf(reason, iid))
    history(inv.long("booking_id"), "invoice", "${if (inv.str("doc_type") == "invoice") "Invoice" else "Credit note"} ${inv.str("number")} cancelled: $reason")
    audit("invoice", iid, "cancel", JValue.obj("status" to "issued"), JValue.obj("status" to "cancelled", "reason" to reason))
    iid
}

class InvoiceDoc(val row: Row, val doc: JValue, val taxes: List<JValue>, val received: Long?, val outstanding: Long?, val base: String)

fun Agency.getInvoice(iid: Long): InvoiceDoc {
    val inv = db.one("SELECT * FROM invoices WHERE id = ?", listOf(iid)) ?: throw AZError.notFound("Invoice not found")
    val L = Ledger(this)
    val b = L.bookings[inv.long("booking_id")]
    return InvoiceDoc(inv, JValue.parse(inv.str("lines_json")), JValue.parse(inv.str("tax_json")).array ?: emptyList(), b?.calc?.received, b?.calc?.customerOutstanding, L.base)
}

fun Agency.listInvoices(params: Map<String, String> = emptyMap()): List<Row> {
    var q = "SELECT i.id, i.number, i.doc_type, i.issue_date, i.currency, i.subtotal, i.tax_total, i.total, i.status, i.booking_id, b.ref AS booking_ref, b.pnr, " +
        "c.name AS customer_name, i.customer_id FROM invoices i JOIN bookings b ON b.id = i.booking_id JOIN customers c ON c.id = i.customer_id WHERE 1=1"
    val args = ArrayList<Any?>()
    params["from"]?.takeIf { it.isNotEmpty() }?.let { q += " AND i.issue_date >= ?"; args += it }
    params["to"]?.takeIf { it.isNotEmpty() }?.let { q += " AND i.issue_date <= ?"; args += it }
    params["doc_type"]?.takeIf { it.isNotEmpty() }?.let { q += " AND i.doc_type = ?"; args += it }
    params["q"]?.takeIf { it.isNotEmpty() }?.let { q += " AND (i.number LIKE ? OR b.ref LIKE ? OR b.pnr LIKE ? OR c.name LIKE ?)"; repeat(4) { _ -> args += "%$it%" } }
    q += " ORDER BY i.issue_date DESC, i.id DESC"
    return db.query(q, args)
}

// ---- reconciliation

class ReconciliationView(val account: Row, val items: List<Map<String, JValue>>, val balance: Long, val clearedBalance: Long, val history: List<Row>)

fun Agency.reconciliationView(aid: Long, asOf: String? = null): ReconciliationView {
    val L = Ledger(this)
    val acc = L.accounts[aid] ?: throw AZError.notFound("Account not found")
    val items = ArrayList<LinkedHashMap<String, JValue>>()
    for (t in L.transactions) {
        if (asOf != null && t.txnDate > asOf) continue
        for ((accId, eff) in L.accountEffects(t)) if (accId == aid) items.add(L.txnRow(t).also { it["effect"] = J(eff) })
    }
    val bal = L.accountBalances(asOf)[aid]!!
    return ReconciliationView(acc, items.sortedWith(compareBy({ it["txn_date"]!!.py }, { it["id"]!!.long!! })), bal.balance, bal.reconciledBalance,
        db.query("SELECT * FROM reconciliations WHERE account_id = ? ORDER BY id DESC LIMIT 20", listOf(aid)))
}

fun Agency.saveReconciliation(aid: Long, data: Fields): Triple<Long, Long, Long> = tx {
    db.one("SELECT * FROM accounts WHERE id = ?", listOf(aid)) ?: throw AZError.notFound("Account not found")
    val stmtDate = V.d(data, "statement_date", required = true, label = "Statement date")!!
    val stmtBal = parseAmount(data.arg("statement_balance"), "Statement balance", required = true, allowNegative = true)
    val raw = data.arg("cleared_ids")
    if (!raw.isNull && raw.array == null && raw.truthy) throw AZError.validation("cleared_ids must be a list")
    val cleared = (raw.array ?: emptyList()).mapNotNull { it.long }.toSet()
    val ts = Dates.nowISO()
    for (t in db.query("SELECT id, reconciled, txn_date FROM transactions WHERE (account_id = ? OR to_account_id = ?) AND txn_date <= ?", listOf(aid, aid, stmtDate))) {
        val want = if (t.long("id") in cleared) 1L else 0L
        if (want != t.long("reconciled")) db.run("UPDATE transactions SET reconciled = ?, reconciled_on = ?, updated_at = ? WHERE id = ?",
            listOf(want, if (want == 1L) stmtDate else null, ts, t.long("id")))
    }
    val clearedBal = Ledger(this).accountBalances(stmtDate)[aid]!!.reconciledBalance
    val diff = stmtBal - clearedBal
    val rid = db.run("INSERT INTO reconciliations(account_id, statement_date, statement_balance, cleared_balance, difference, notes, created_at) VALUES (?,?,?,?,?,?,?)",
        listOf(aid, stmtDate, stmtBal, clearedBal, diff, V.s(data, "notes", 500), ts))
    audit("reconciliation", rid, "save", null, JValue.obj("account_id" to aid, "statement_date" to stmtDate, "statement_balance" to stmtBal,
        "cleared_balance" to clearedBal, "difference" to diff, "cleared_count" to cleared.size))
    Triple(rid, clearedBal, diff)
}

// ---- search

class SearchResults(val bookings: List<Map<String, JValue>> = emptyList(), val customers: List<Row> = emptyList(), val suppliers: List<Row> = emptyList(),
                    val invoices: List<Row> = emptyList(), val transactions: List<Row> = emptyList())

fun Agency.globalSearch(query: String): SearchResults {
    val q = query.trim()
    if (q.length < 2) return SearchResults()
    val like = "%$q%"; val digits = q.filter { it.isDigit() }
    return SearchResults(searchBookings(mapOf("q" to q)).take(8),
        db.query("SELECT id, name, phone, email FROM customers WHERE name LIKE ? OR email LIKE ? OR (? <> '' AND replace(replace(replace(phone,' ',''),'-',''),'+','') LIKE ?) ORDER BY name LIMIT 8",
            listOf(like, like, digits, "%$digits%")),
        db.query("SELECT id, name, kind FROM suppliers WHERE name LIKE ? ORDER BY name LIMIT 5", listOf(like)),
        db.query("SELECT i.id, i.number, i.doc_type, i.total, i.currency, b.ref FROM invoices i JOIN bookings b ON b.id = i.booking_id WHERE i.number LIKE ? ORDER BY i.id DESC LIMIT 5", listOf(like)),
        db.query("SELECT id, txn_no, kind, amount, currency, txn_date, reference FROM transactions WHERE txn_no LIKE ? OR reference LIKE ? ORDER BY id DESC LIMIT 6", listOf(like, like)))
}
