package com.airzelo.engine

import com.airzelo.engine.PdfCanvas.Align

private fun JValue.s(): String = string ?: ""

/**
 * An issued invoice or credit note as PDF (port of the iPhone's PDF/InvoicePDF.swift). Everything printed comes from
 * the document's frozen snapshot (company, customer, lines and taxes as issued), so later edits to the profile or
 * booking never change it.
 */
fun Agency.invoicePDF(iid: Long): PdfFile {
    val inv = getInvoice(iid)
    val r = inv.row; val doc = inv.doc; val biz = doc["business"]; val cust = doc["customer"]; val bk = doc["booking"]
    val isCN = r.str("doc_type") == "credit_note"
    val cur = r.str("currency")
    val brand = biz["brand_color"].s().takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: "#1B3FCC"
    val name = biz["business_name"].s().ifEmpty { "Travel Agency" }
    val p = PdfCanvas("${if (isCN) "Credit note" else "Invoice"} ${r.str("number")}", name)
    val W = p.width; val M = 40.0
    p.newPage()
    p.rect(0.0, 0.0, W, 6.0, fill = brand)
    var y = 46.0
    val logo = logoImage(biz["logo_file"].s())
    if (logo != null) p.image(logo, M, y - 22, 44.0, 44.0)
    val lx = M + (if (logo != null) 56 else 0)
    p.text(lx, y, name, 16.0, true, "#0D1833", maxWidth = 300.0)
    var yy = y + 14
    val legal = biz["legal_name"].s()
    for (line in listOf(if (legal != name) legal else "", biz["business_address"].s(),
        listOf(biz["business_phone"].s(), biz["business_email"].s(), biz["business_website"].s()).filter { it.isNotEmpty() }.joinToString("  |  "),
        if (biz["business_gstin"].s().isEmpty()) "" else "GSTIN " + biz["business_gstin"].s())) {
        if (line.isEmpty()) continue
        for (l in PdfCanvas.wrap(line, 8.5, 300.0)) { p.text(lx, yy, l, 8.5, false, "#64738F"); yy += 11.5 }
    }
    val gstin = biz["business_gstin"].s().isNotEmpty()
    val title = if (isCN) "CREDIT NOTE" else if (gstin) "TAX INVOICE" else "INVOICE"
    p.text(W - M, y, title, 18.0, true, brand, Align.RIGHT, spacing = 0.8)
    p.text(W - M, y + 18, r.str("number"), 10.0, true, "#0D1833", Align.RIGHT)
    p.text(W - M, y + 31, "Date: " + TicketDates.dateLabel(r.str("issue_date")), 8.5, false, "#64738F", Align.RIGHT)
    if (isCN) doc["against"].string?.let { p.text(W - M, y + 43, "Against invoice $it", 8.5, false, "#64738F", Align.RIGHT) }
    y = maxOf(yy, y + 50) + 16

    // bill to + booking
    val half = (W - 2 * M - 16) / 2
    p.rect(M, y, half, 86.0, fill = "#F5F8FD", radius = 8.0)
    p.text(M + 12, y + 16, "BILL TO", 7.5, true, "#64738F", spacing = 0.5)
    var cy = y + 32
    p.text(M + 12, cy, cust["name"].s(), 11.0, true, "#0D1833", maxWidth = half - 24); cy += 13
    for (line in listOf(cust["address"].s(), listOf(cust["city"].s(), cust["state"].s()).filter { it.isNotEmpty() }.joinToString(", "),
        cust["phone"].s(), if (cust["gstin"].s().isEmpty()) "" else "GSTIN " + cust["gstin"].s())) {
        if (line.isEmpty()) continue
        p.text(M + 12, cy, line, 8.5, false, "#0D1833", maxWidth = half - 24); cy += 11.5
        if (cy > y + 82) break
    }
    val bx = M + half + 16
    p.rect(bx, y, half, 86.0, fill = "#F5F8FD", radius = 8.0)
    p.text(bx + 12, y + 16, "BOOKING", 7.5, true, "#64738F", spacing = 0.5)
    var by = y + 32
    for ((k, v) in listOf("Booking" to bk["ref"].s(), "PNR" to bk["pnr"].s(), "Travel date" to TicketDates.dateLabel(bk["travel_date"].string),
        "Place of supply" to (bk["place_of_supply"].string ?: biz["business_state"].s()))) {
        if (v.isEmpty()) continue
        p.text(bx + 12, by, k, 8.5, false, "#64738F")
        p.text(bx + half - 12, by, v, 8.5, true, "#0D1833", Align.RIGHT, maxWidth = half - 110)
        by += 13
    }
    y += 104

    // lines
    p.rect(M, y, W - 2 * M, 22.0, fill = "#0A1A3F", radius = 5.0)
    p.text(M + 10, y + 14.5, "DESCRIPTION", 7.5, true, "#FFFFFF", spacing = 0.4)
    p.text(W - M - 120, y + 14.5, "SAC", 7.5, true, "#FFFFFF", spacing = 0.4)
    p.text(W - M - 10, y + 14.5, "AMOUNT ($cur)", 7.5, true, "#FFFFFF", Align.RIGHT, spacing = 0.4)
    y += 30
    for (l in doc["lines"].array.orEmpty()) {
        val descLines = PdfCanvas.wrap(l["description"].s(), 9.0, W - 2 * M - 200, true)
        for ((i, d) in descLines.withIndex()) p.text(M + 10, y + i * 12, d, 9.0, true, "#0D1833")
        var ly = y + descLines.size * 12
        val det = l["detail"].s()
        if (det.isNotEmpty()) for (d in PdfCanvas.wrap(det, 8.0, W - 2 * M - 200)) { p.text(M + 10, ly, d, 8.0, false, "#64738F"); ly += 10.5 }
        p.text(W - M - 120, y, l["sac"].s(), 8.5, false, "#0D1833")
        val amt = l["amount"].long ?: 0
        p.text(W - M - 10, y, if (cur == "INR") indianGrouping(fmt(amt)) else fmt(amt), 9.5, true, "#0D1833", Align.RIGHT)
        y = ly + 8
        p.line(M, y - 4, W - M, y - 4, "#E2E8F2", 0.6)
        y += 6
    }

    // totals
    val rows = arrayListOf(Triple("Subtotal", r.long("subtotal"), false))
    for (t in inv.taxes) rows.add(Triple(t["label"].string ?: "Tax", t["amount"].long ?: 0, false))
    rows.add(Triple(if (isCN) "Total credit" else "Total", r.long("total"), true))
    val tx = W - M - 230
    for ((label, v, strong) in rows) {
        if (strong) p.rect(tx - 10, y - 4, W - M - tx + 10, 24.0, fill = "#EAF0FF", radius = 5.0)
        p.text(tx, y + 11, label, if (strong) 10.5 else 9.0, strong, if (strong) brand else "#0D1833")
        p.text(W - M - 10, y + 11, money(v, cur), if (strong) 11.0 else 9.5, true, if (strong) brand else "#0D1833", Align.RIGHT)
        y += if (strong) 30 else 18
    }
    val rec = inv.received; val out = inv.outstanding
    if (!isCN && rec != null && out != null) {
        y += 4
        p.text(tx, y + 8, "Received to date", 8.5, false, "#64738F")
        p.text(W - M - 10, y + 8, money(rec, inv.base), 8.5, false, "#64738F", Align.RIGHT)
        p.text(tx, y + 22, "Balance due", 8.5, true, "#0D1833")
        p.text(W - M - 10, y + 22, money(maxOf(out, 0), inv.base), 8.5, true, if (out > 0) "#D33447" else "#0E9F6B", Align.RIGHT)
        y += 34
    }
    y += 18
    biz["invoice_terms"].string?.takeIf { it.isNotEmpty() }?.let { terms ->
        p.text(M, y, "TERMS", 7.5, true, "#64738F", spacing = 0.5)
        y = p.paragraph(M, y + 13, terms, 8.5, W - 2 * M, false, "#0D1833") + 6
    }
    r.optStr("notes")?.takeIf { it.isNotEmpty() }?.let { notes ->
        p.text(M, y, "NOTES", 7.5, true, "#64738F", spacing = 0.5)
        y = p.paragraph(M, y + 13, notes, 8.5, W - 2 * M, false, "#0D1833") + 6
    }
    biz["invoice_footer"].string?.takeIf { it.isNotEmpty() }?.let { p.text(W / 2, y + 14, it, 9.5, true, brand, Align.CENTER) }
    if (r.str("status") == "cancelled") {
        p.rotated(30.0, W / 2, p.height / 2) { p.text(W / 2, p.height / 2, "CANCELLED", 72.0, true, "#D33447", Align.CENTER) }
        p.text(M, p.height - 50, "Cancelled: " + (r.optStr("cancel_reason") ?: ""), 9.0, true, "#D33447")
    }
    p.line(M, p.height - 36, W - M, p.height - 36)
    p.text(M, p.height - 22, "Computer-generated document. Generated on this device with AirZelo.", 7.5, false, "#64738F")
    val safeNumber = r.str("number").replace(Regex("[^A-Za-z0-9-]+"), "-")
    return PdfFile(p.finish(), "${if (isCN) "CreditNote" else "Invoice"}_$safeNumber.pdf")
}

/**
 * A payment receipt for a customer receipt (or a refund paid to the customer). The desktop app has no receipt
 * document, so this follows the invoice's look: business block, received-from, amount, method, the bookings it
 * pays for, and the receipt_footer setting.
 */
fun Agency.receiptPDF(tid: Long): PdfFile {
    val L = Ledger(this)
    val t = L.txnById[tid] ?: throw AZError.notFound("Transaction not found")
    if (t.kind != "customer_receipt" && t.kind != "customer_refund") throw AZError.validation("Receipts are made for customer payments and refunds only")
    val row = L.txnRow(t)
    val s = allSettings()
    val isRefund = t.kind == "customer_refund"
    val brand = (s["brand_color"] ?: "").takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: "#1B3FCC"
    val name = (s["business_name"] ?: "").ifEmpty { "Travel Agency" }
    val p = PdfCanvas("${if (isRefund) "Refund voucher" else "Receipt"} ${t.txnNo}", name)
    val W = p.width; val M = 40.0
    p.newPage()
    p.rect(0.0, 0.0, W, 6.0, fill = brand)
    var y = 46.0
    val logo = logoImage(s["logo_file"] ?: "")
    if (logo != null) p.image(logo, M, y - 22, 44.0, 44.0)
    val lx = M + (if (logo != null) 56 else 0)
    p.text(lx, y, name, 16.0, true, "#0D1833", maxWidth = 300.0)
    var yy = y + 14
    for (line in listOf(s["business_address"] ?: "", listOf(s["business_phone"] ?: "", s["business_email"] ?: "").filter { it.isNotEmpty() }.joinToString("  |  "),
        (s["business_gstin"] ?: "").let { if (it.isEmpty()) "" else "GSTIN $it" })) {
        if (line.isEmpty()) continue
        for (l in PdfCanvas.wrap(line, 8.5, 300.0)) { p.text(lx, yy, l, 8.5, false, "#64738F"); yy += 11.5 }
    }
    p.text(W - M, y, if (isRefund) "REFUND VOUCHER" else "PAYMENT RECEIPT", 18.0, true, brand, Align.RIGHT, spacing = 0.8)
    p.text(W - M, y + 18, t.txnNo, 10.0, true, "#0D1833", Align.RIGHT)
    p.text(W - M, y + 31, "Date: " + TicketDates.dateLabel(t.txnDate), 8.5, false, "#64738F", Align.RIGHT)
    y = maxOf(yy, y + 50) + 16

    val customer = row["customer_name"]!!.py
    p.rect(M, y, W - 2 * M, 96.0, fill = "#F5F8FD", radius = 8.0)
    p.text(M + 14, y + 18, if (isRefund) "PAID TO" else "RECEIVED FROM", 7.5, true, "#64738F", spacing = 0.5)
    p.text(M + 14, y + 36, customer.ifEmpty { "-" }, 12.0, true, "#0D1833", maxWidth = W / 2 - M)
    p.text(W - M - 14, y + 18, "AMOUNT", 7.5, true, "#64738F", Align.RIGHT, spacing = 0.5)
    p.text(W - M - 14, y + 40, money(t.sign * t.amount, t.currency), 20.0, true, brand, Align.RIGHT)
    if (t.currency != L.base) p.text(W - M - 14, y + 54, "= ${money(t.sign * t.baseAmount, L.base)} at ${t.exchangeRate}", 8.0, false, "#64738F", Align.RIGHT)
    val details = listOf("Method" to t.method.replace("_", " ").replaceFirstChar { it.uppercase() }, "Reference" to (t.reference ?: ""),
        "Account" to row["account_name"]!!.py).filter { it.second.isNotEmpty() }
    var dy = y + 60
    for ((k, v) in details) {
        p.text(M + 14, dy, k, 8.5, false, "#64738F")
        p.text(M + 90, dy, v, 8.5, true, "#0D1833", maxWidth = W / 2 - 60)
        dy += 12
    }
    y += 112

    val allocs = row["allocations"]!!.array.orEmpty()
    if (allocs.isNotEmpty()) {
        p.rect(M, y, W - 2 * M, 22.0, fill = "#0A1A3F", radius = 5.0)
        p.text(M + 10, y + 14.5, "FOR BOOKING", 7.5, true, "#FFFFFF", spacing = 0.4)
        p.text(W - M - 10, y + 14.5, "AMOUNT (${L.base})", 7.5, true, "#FFFFFF", Align.RIGHT, spacing = 0.4)
        y += 36
        for (a in allocs) {
            val b = L.bookings[a["booking_id"].long ?: -1]
            val label = listOfNotNull(a["ref"].string, b?.pnr?.takeIf { it.isNotEmpty() }?.let { "PNR $it" }, b?.route?.takeIf { it.isNotEmpty() }).joinToString("  |  ")
            p.text(M + 10, y, label, 9.0, true, "#0D1833", maxWidth = W - 2 * M - 160)
            p.text(W - M - 10, y, money(a["amount"].long ?: 0, L.base), 9.5, true, "#0D1833", Align.RIGHT)
            if (b != null && !isRefund) {
                p.text(M + 10, y + 12, "Balance after all payments: ${money(maxOf(b.calc.customerOutstanding, 0), L.base)}", 8.0, false, "#64738F")
            }
            y += 30
            p.line(M, y - 10, W - M, y - 10, "#E2E8F2", 0.6)
        }
    }
    if (t.sign < 0 || t.reversedBy != null) {
        y += 10
        p.text(M, y, "This entry has been reversed in the books and is not a valid ${if (isRefund) "refund" else "receipt"}.", 9.0, true, "#D33447")
        y += 16
    }
    t.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
        y += 8
        p.text(M, y, "NOTES", 7.5, true, "#64738F", spacing = 0.5)
        y = p.paragraph(M, y + 13, notes, 8.5, W - 2 * M, false, "#0D1833") + 6
    }
    (s["receipt_footer"] ?: "").takeIf { it.isNotEmpty() }?.let { p.text(W / 2, y + 24, it, 9.5, true, brand, Align.CENTER) }
    p.line(M, p.height - 36, W - M, p.height - 36)
    p.text(M, p.height - 22, "Computer-generated document. Generated on this device with AirZelo.", 7.5, false, "#64738F")
    val safe = t.txnNo.replace(Regex("[^A-Za-z0-9-]+"), "-")
    return PdfFile(p.finish(), "${if (isRefund) "Refund" else "Receipt"}_$safe.pdf")
}
