package com.airzelo.engine

// Port of the booking services (core/server/services.py).

val BOOKING_STATUSES = listOf("enquiry", "on_hold", "confirmed", "ticketed", "cancelled", "refunded")
internal val COMMIT_STATUSES = setOf("confirmed", "ticketed")
val SERVICE_TYPES = listOf("flight", "visa", "hotel", "package", "umrah", "other")
internal val PRICING_FIELDS = listOf("purchase_cost", "selling_price", "commission", "service_charge", "other_income",
    "currency", "exchange_rate", "tax_amount", "taxable_value", "tax_rate", "tax_profile_id")

internal fun normTicket(t: JValue): String? {
    if (t.isNull) return null
    val s = t.py.trim().uppercase().replace(" ", "")
    if (s.isEmpty()) return null
    if (s.codePointCount(0, s.length) > 30) throw AZError.validation("Ticket number is too long", "passengers")
    return s
}

internal data class Pax(val seq: Int, val title: String?, val name: String, val paxType: String, val ticket: String?, val passport: String?, val notes: String?)

internal fun passengersPayload(data: Fields): List<Pax> {
    val raw = data.arg("passengers")
    val list = when (raw) {
        JValue.Null -> emptyList()
        is JValue.Arr -> raw.v
        else -> if (!raw.truthy) emptyList() else throw AZError.validation("Passengers must be a list", "passengers")
    }
    val out = ArrayList<Pax>()
    for ((idx, pv) in list.withIndex()) {
        val p = pv.obj ?: continue
        val name = (if (p.arg("name").truthy) p.arg("name").py else "").trim()
        if (name.isEmpty() && !p.arg("ticket_number").truthy) continue
        if (name.isEmpty()) throw AZError.validation("Passenger ${idx + 1} needs a name", "passengers")
        if (name.codePointCount(0, name.length) > 150) throw AZError.validation("Passenger name is too long", "passengers")
        val pt = if (p.arg("pax_type").truthy) p.arg("pax_type") else J("ADT")
        if (pt !is JValue.Str || pt.v !in listOf("ADT", "CHD", "INF")) throw AZError.validation("Passenger type must be ADT, CHD or INF", "passengers")
        out.add(Pax(out.size, V.s(p, "title", 10), name, pt.v, normTicket(p.arg("ticket_number")), passportValue(p), V.s(p, "notes", 500)))
    }
    if (out.isEmpty()) throw AZError.validation("Add at least one passenger", "passengers")
    val tickets = out.mapNotNull { it.ticket }
    if (tickets.size != tickets.toSet().size) throw AZError.validation("The same ticket number is entered twice in this booking", "passengers")
    return out
}

internal data class Seg(val seq: Int, val direction: String, val airline: String?, val flightNo: String?, val origin: String?, val destination: String?,
                        val depAt: String?, val arrAt: String?, val cabin: String?, val baggage: String?, val airlinePnr: String?) {
    val json: JValue get() = JValue.obj("seq" to seq, "direction" to direction, "airline" to airline, "flight_no" to flightNo, "origin" to origin,
        "destination" to destination, "dep_at" to depAt, "arr_at" to arrAt, "cabin" to cabin, "baggage" to baggage, "airline_pnr" to airlinePnr)
}

internal fun segmentsPayload(data: Fields): List<Seg> {
    val raw = data.arg("segments")
    val list = when (raw) {
        JValue.Null -> emptyList()
        is JValue.Arr -> raw.v
        else -> if (!raw.truthy) emptyList() else throw AZError.validation("Flight segments must be a list", "segments")
    }
    val out = ArrayList<Seg>()
    for ((idx, sv) in list.withIndex()) {
        val sg = sv.obj ?: continue
        if (listOf("airline", "flight_no", "origin", "destination", "dep_at").none { sg.arg(it).truthy && sg.arg(it).py.isNotBlank() }) continue
        val s = Seg(out.size, V.choice(sg, "direction", listOf("outbound", "return", "other"), "outbound"), V.s(sg, "airline", 80),
            V.normCode(J(V.s(sg, "flight_no", 12)), 12), V.normCode(J(V.s(sg, "origin", 40)), 40), V.normCode(J(V.s(sg, "destination", 40)), 40),
            V.dt(sg, "dep_at", "Departure (segment ${idx + 1})"), V.dt(sg, "arr_at", "Arrival (segment ${idx + 1})"),
            V.s(sg, "cabin", 30), V.s(sg, "baggage", 60), V.normCode(J(V.s(sg, "airline_pnr", 20)), 20))
        if (s.depAt != null && s.arrAt != null && s.arrAt < s.depAt) throw AZError.validation("Segment ${idx + 1} arrives before it departs", "segments")
        out.add(s)
    }
    return out
}

internal fun Agency.hasFinancialActivity(bid: Long): Boolean = db.scalarLong(
    "SELECT (SELECT COUNT(*) FROM allocations WHERE booking_id = ?) + (SELECT COUNT(*) FROM booking_adjustments WHERE booking_id = ?) + " +
        "(SELECT COUNT(*) FROM invoices WHERE booking_id = ? AND status = 'issued') + (SELECT COUNT(*) FROM transactions WHERE booking_id = ?)",
    listOf(bid, bid, bid, bid)) > 0

fun Agency.findDuplicatePNR(pnr: String?, excluding: Long? = null): List<Row> {
    if (pnr.isNullOrEmpty()) return emptyList()
    return db.query("SELECT b.id, b.ref, b.status, b.booking_date, c.name AS customer_name FROM bookings b " +
        "JOIN customers c ON c.id = b.customer_id WHERE b.pnr = ? COLLATE NOCASE AND b.id <> ?", listOf(pnr, excluding ?: 0))
}

/** Python str() of a stored value, for "did this field change" comparisons. */
private fun pyText(v: SqlValue): String = when (v) {
    SqlValue.Null -> "None"
    is SqlValue.I -> v.v.toString()
    is SqlValue.R -> JValue.pyFloat(v.v)
    is SqlValue.T -> v.v
    is SqlValue.B -> ""
}

/** Create (id == null) or update a booking with its passengers and flight segments. */
fun Agency.saveBooking(data: Fields, id: Long? = null): Long = tx {
    var bid = id
    val before = bid?.let { db.one("SELECT * FROM bookings WHERE id = ?", listOf(it)) ?: throw AZError.notFound("Booking not found") }
    val customerId = resolveCustomer(data)
    val supplierId = V.i(data, "supplier_id")
    if (supplierId != null && db.one("SELECT 1 FROM suppliers WHERE id = ?", listOf(supplierId)) == null) throw AZError.validation("Selected supplier does not exist", "supplier_id")
    val pnr = V.normCode(J(V.s(data, "pnr", 20, label = "PNR")), 20)
    val status = V.choice(data, "status", BOOKING_STATUSES, "confirmed", "Booking status")
    val serviceType = V.choice(data, "service_type", SERVICE_TYPES, "flight", "Service type")
    val tripType = V.choice(data, "trip_type", listOf("one_way", "round_trip", "multi_city"), "one_way", "Trip type")
    val bookingDate = V.d(data, "booking_date", label = "Booking date") ?: Dates.today()
    val (currency, rate) = currencyAndRate(data)
    val amounts = linkedMapOf(
        "purchase_cost" to parseAmount(data.arg("purchase_cost"), "Purchase cost"),
        "selling_price" to parseAmount(data.arg("selling_price"), "Selling price"),
        "commission" to parseAmount(data.arg("commission"), "Commission"),
        "service_charge" to parseAmount(data.arg("service_charge"), "Service charge"),
        "other_income" to parseAmount(data.arg("other_income"), "Other income"))
    val tax = computeTax(data, amounts)
    val pax = passengersPayload(data)
    val segs = segmentsPayload(data)
    var travelDate = V.d(data, "travel_date", label = "Travel date")
    var returnDate = V.d(data, "return_date", label = "Return date")
    if (travelDate == null) segs.firstOrNull()?.depAt?.let { travelDate = it.take(10) }
    if (returnDate == null) segs.firstOrNull { it.direction == "return" && it.depAt != null }?.let { returnDate = it.depAt!!.take(10) }
    if (travelDate != null && returnDate != null && returnDate!! < travelDate!!) throw AZError.validation("Return date is before the travel date", "return_date")

    if (pnr != null && !V.b(data, "force_duplicate")) {
        val dups = findDuplicatePNR(pnr, bid)
        if (dups.isNotEmpty()) throw AZError.conflict("PNR $pnr already exists in ${dups.joinToString(", ") { it.str("ref") }}. " +
            "Open the existing booking, or confirm to save a separate booking.", "duplicate_pnr", JValue.Arr(dups.map { JValue.Obj(it.json) }))
    }
    for (p in pax) {
        val t = p.ticket ?: continue
        db.one("SELECT b.ref FROM booking_passengers p JOIN bookings b ON b.id = p.booking_id WHERE p.ticket_number = ? AND p.booking_id <> ?", listOf(t, bid ?: 0))?.let {
            throw AZError.conflict("Ticket number $t is already recorded in booking ${it.str("ref")}", "duplicate_ticket")
        }
    }

    var committed = if (status in COMMIT_STATUSES) 1L else 0L
    if (before != null && bid != null) {
        val was = before.bool("committed")
        if (was && (status == "enquiry" || status == "on_hold")) {
            if (hasFinancialActivity(bid)) throw AZError.validation("This booking already has payments, adjustments or an invoice. " +
                "Cancel it with an adjustment instead of moving it back to $status.", "status")
            committed = 0
        } else if (was) committed = 1
        if (was && (status == "cancelled" || status == "refunded") && before.str("status") !in listOf("cancelled", "refunded"))
            throw AZError.validation("Use 'Cancel / Refund' on the booking so customer and supplier credits are recorded.", "status")
        val newVals = HashMap<String, SqlValue>()
        for ((k, v) in tax) newVals[k] = SqlValue.of(v)
        for ((k, v) in amounts) newVals[k] = SqlValue.of(v)
        newVals["currency"] = SqlValue.T(currency); newVals["exchange_rate"] = SqlValue.T(rate)
        val changed = PRICING_FIELDS.filter { pyText(before[it]) != pyText(newVals[it] ?: SqlValue.Null) }
        if (changed.isNotEmpty() && db.one("SELECT 1 FROM invoices WHERE booking_id = ? AND doc_type = 'invoice' AND status = 'issued'", listOf(bid)) != null)
            throw AZError.validation("An invoice has been issued for this booking. Cancel that invoice before changing prices, or record a reissue/adjustment.", "selling_price")
        if (was && changed.isNotEmpty() && currency != before.str("currency") && hasFinancialActivity(bid))
            throw AZError.validation("Currency cannot be changed after payments are recorded against this booking", "currency")
    } else if (status == "cancelled" || status == "refunded") committed = 0

    val ts = Dates.nowISO()
    val base = setting("base_currency")
    val fields = linkedMapOf<String, Any?>(
        "pnr" to pnr, "customer_id" to customerId, "supplier_id" to supplierId, "service_type" to serviceType, "booking_date" to bookingDate,
        "travel_date" to travelDate, "return_date" to returnDate, "trip_type" to tripType, "status" to status, "committed" to committed,
        "cabin" to V.s(data, "cabin", 30), "baggage" to V.s(data, "baggage", 60), "notes" to V.s(data, "notes", 8000),
        "currency" to currency, "exchange_rate" to rate,
        "rate_date" to (V.d(data, "rate_date", label = "Rate date") ?: if (currency != base) bookingDate else null),
        "place_of_supply" to V.s(data, "place_of_supply", 60))
    fields.putAll(amounts)
    fields.putAll(tax)

    val ref: String
    if (before != null && bid != null) {
        ref = before.str("ref")
        db.run("UPDATE bookings SET ${fields.keys.joinToString(", ") { "$it = ?" }}, updated_at = ? WHERE id = ?", fields.values.toList() + listOf(ts, bid))
        db.run("DELETE FROM booking_passengers WHERE booking_id = ?", listOf(bid))
        db.run("DELETE FROM booking_segments WHERE booking_id = ?", listOf(bid))
    } else {
        val yy = bookingDate.substring(2, 4)
        val n = nextCounter("booking-$yy")
        val prefix = setting("booking_prefix", "BK-").takeUnless { it.isNullOrEmpty() } ?: "BK-"
        ref = prefix + yy + "-" + String.format("%05d", n)
        fields["ref"] = ref
        bid = db.run("INSERT INTO bookings(${fields.keys.joinToString(", ")}, created_at, updated_at) VALUES (${fields.keys.joinToString(", ") { "?" }}, ?, ?)",
            fields.values.toList() + listOf(ts, ts))
    }
    val newId = bid!!
    for (p in pax) db.run("INSERT INTO booking_passengers(booking_id, seq, title, name, pax_type, ticket_number, passport_no, notes) VALUES (?,?,?,?,?,?,?,?)",
        listOf(newId, p.seq, p.title, p.name, p.paxType, p.ticket, crypto.seal(p.passport, "passport_no"), p.notes))
    for (s in segs) db.run("INSERT INTO booking_segments(booking_id, seq, direction, airline, flight_no, origin, destination, dep_at, arr_at, cabin, baggage, airline_pnr) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        listOf(newId, s.seq, s.direction, s.airline, s.flightNo, s.origin, s.destination, s.depAt, s.arrAt, s.cabin, s.baggage, s.airlinePnr))
    val after = LinkedHashMap<String, JValue>()
    for ((k, v) in fields) after[k] = JValue.wrap(v)
    after["passengers"] = JValue.Arr(pax.map { JValue.obj("seq" to it.seq, "title" to it.title, "name" to it.name, "pax_type" to it.paxType,
        "ticket_number" to it.ticket, "passport_no" to (if (it.passport == null) null else "(encrypted)"), "notes" to it.notes) })
    after["segments"] = JValue.Arr(segs.map { it.json })
    if (before != null) {
        val changes = fields.keys.filter { pyText(before[it]) != pyText(SqlValue.of(fields[it])) }
        history(newId, "updated", if (changes.isEmpty()) "Details updated" else "Changed: " + changes.joinToString(", "))
        if (before.str("status") != status) history(newId, "status", "${before.str("status")} -> $status")
        audit("booking", newId, "update", JValue.Obj(before.json), JValue.Obj(after))
    } else {
        history(newId, "created", "Booking $ref created with status $status")
        audit("booking", newId, "create", null, JValue.Obj(after))
    }
    newId
}

/**
 * The complete save payload of an existing booking: every field saveBooking accepts, as stored. An edit screen
 * starts from this and changes only what the user edits, so fields it does not show (cabin, place of supply,
 * manual tax, passenger notes ...) are kept and the prices are re-sent exactly.
 */
fun Agency.bookingEditFields(bid: Long): LinkedHashMap<String, JValue> {
    val b = db.one("SELECT * FROM bookings WHERE id = ?", listOf(bid)) ?: throw AZError.notFound("Booking not found")
    fun money(k: String) = J(fmt(b.long(k)))
    fun js(k: String) = JValue.fromSql(b[k])
    val f = linkedMapOf("customer_id" to J(b.long("customer_id")), "supplier_id" to js("supplier_id"), "pnr" to js("pnr"),
        "status" to J(b.str("status")), "service_type" to J(b.str("service_type")), "trip_type" to J(b.str("trip_type")),
        "booking_date" to J(b.str("booking_date")), "travel_date" to js("travel_date"), "return_date" to js("return_date"),
        "cabin" to js("cabin"), "baggage" to js("baggage"), "notes" to js("notes"), "currency" to J(b.str("currency")),
        "exchange_rate" to J(b.str("exchange_rate")), "rate_date" to js("rate_date"), "place_of_supply" to js("place_of_supply"),
        "purchase_cost" to money("purchase_cost"), "selling_price" to money("selling_price"), "commission" to money("commission"),
        "service_charge" to money("service_charge"), "other_income" to money("other_income"),
        "tax_profile_id" to js("tax_profile_id"), "taxable_value" to money("taxable_value"))
    if (b.bool("tax_override")) {
        f["tax_override"] = JValue.Bool(true); f["tax_rate"] = J(b.str("tax_rate")); f["tax_amount"] = money("tax_amount"); f["tax_split"] = J(b.str("tax_split"))
    }
    f["passengers"] = JValue.Arr(db.query("SELECT * FROM booking_passengers WHERE booking_id = ? ORDER BY seq, id", listOf(bid)).map { p ->
        JValue.obj("title" to p["title"], "name" to p.str("name"), "pax_type" to p.str("pax_type"), "ticket_number" to p["ticket_number"],
            "passport_no" to crypto.open(p.optStr("passport_no"), "passport_no"), "notes" to p["notes"])
    })
    f["segments"] = JValue.Arr(db.query("SELECT * FROM booking_segments WHERE booking_id = ? ORDER BY seq, id", listOf(bid)).map { s ->
        JValue.Obj(listOf("direction", "airline", "flight_no", "origin", "destination", "dep_at", "arr_at", "cabin", "baggage", "airline_pnr").associateWith { JValue.fromSql(s[it]) })
    })
    return f
}

fun Agency.setBookingStatus(bid: Long, status: String): Long {
    if (status !in BOOKING_STATUSES) throw AZError.validation("Invalid status", "status")
    return tx {
        val b = db.one("SELECT * FROM bookings WHERE id = ?", listOf(bid)) ?: throw AZError.notFound("Booking not found")
        if (b.str("status") == status) return@tx bid
        var committed = b.long("committed")
        if (status in COMMIT_STATUSES) committed = 1
        else if (status == "enquiry" || status == "on_hold") {
            if (b.bool("committed") && hasFinancialActivity(bid)) throw AZError.validation("This booking has financial activity; cancel it with an adjustment instead.", "status")
            committed = 0
        } else if ((status == "cancelled" || status == "refunded") && b.bool("committed") && b.str("status") !in listOf("cancelled", "refunded"))
            throw AZError.validation("Use 'Cancel / Refund' so credits and charges are recorded.", "status")
        db.run("UPDATE bookings SET status = ?, committed = ?, updated_at = ? WHERE id = ?", listOf(status, committed, Dates.nowISO(), bid))
        history(bid, "status", "${b.str("status")} -> $status")
        audit("booking", bid, "status", JValue.obj("status" to b.str("status"), "committed" to b.long("committed")), JValue.obj("status" to status, "committed" to committed))
        bid
    }
}

/** Delete a booking with no money, adjustments, invoices or documents (others must be cancelled / refunded). Returns its ref. */
fun Agency.deleteBooking(bid: Long): String = tx {
    val b = db.one("SELECT * FROM bookings WHERE id = ?", listOf(bid)) ?: throw AZError.notFound("Booking not found")
    if (hasFinancialActivity(bid) || db.one("SELECT 1 FROM invoices WHERE booking_id = ?", listOf(bid)) != null)
        throw AZError.validation("${b.str("ref")} has payments, refunds, adjustments or invoices, so it is kept for your accounts. Use 'Refund / cancel' to cancel it instead.")
    if (db.one("SELECT 1 FROM documents WHERE booking_id = ? AND deleted = 0", listOf(bid)) != null) throw AZError.validation("Remove the documents attached to ${b.str("ref")} first.")
    val pax = db.query("SELECT name, ticket_number FROM booking_passengers WHERE booking_id = ?", listOf(bid)).map { JValue.Obj(it.json) }
    db.run("DELETE FROM booking_history WHERE booking_id = ?", listOf(bid))
    db.run("DELETE FROM documents WHERE booking_id = ? AND deleted = 1", listOf(bid))
    db.run("DELETE FROM bookings WHERE id = ?", listOf(bid))
    audit("booking", bid, "delete", JValue.obj("ref" to b.str("ref"), "pnr" to b.optStr("pnr"), "status" to b.str("status"), "customer_id" to b.long("customer_id"),
        "selling_price" to b.long("selling_price"), "purchase_cost" to b.long("purchase_cost"), "passengers" to pax), null)
    b.str("ref")
}

class BookingDetail(val booking: LedgerBooking, val passengers: List<Map<String, JValue>>, val segments: List<Row>, val adjustments: List<Adjustment>,
                    val transactions: List<Map<String, JValue>>, val invoices: List<Row>, val history: List<Row>, val customer: Map<String, JValue>?,
                    val supplier: Row?, val taxProfile: Row?, val duplicates: List<Row>, val base: String)

fun Agency.getBooking(bid: Long): BookingDetail {
    val L = Ledger(this)
    val b = L.bookings[bid] ?: throw AZError.notFound("Booking not found")
    val txns = L.transactions.mapNotNull { t ->
        val allocs = L.allocations[t.id].orEmpty().filter { it.first == bid }
        if (allocs.isNotEmpty() || t.bookingId == bid) L.txnRow(t).also { it["allocated_here"] = J(allocs.sumOf { a -> a.second }) } else null
    }.sortedWith(compareBy({ it["txn_date"]!!.py }, { it["id"]!!.long!! }))
    val pax = L.passengers[bid].orEmpty().map { p -> p.json.also { it["passport_no"] = J(crypto.open(p.optStr("passport_no"), "passport_no")) } }
    return BookingDetail(b, pax, L.segments[bid].orEmpty(), L.adjustments[bid].orEmpty(), txns,
        db.query("SELECT id, number, doc_type, issue_date, total, currency, status, adjustment_id FROM invoices WHERE booking_id = ? ORDER BY id", listOf(bid)),
        db.query("SELECT * FROM booking_history WHERE booking_id = ? ORDER BY id DESC", listOf(bid)),
        L.customers[b.customerId]?.let { openCustomer(it) }, b.supplierId?.let { L.suppliers[it] },
        b.row.optLong("tax_profile_id")?.let { db.one("SELECT * FROM tax_profiles WHERE id = ?", listOf(it)) },
        findDuplicatePNR(b.pnr, bid), L.base)
}

/** Saved-booking search (PNR, name, ticket number, phone ...). This device's records only: no live airline / GDS lookup. */
fun Agency.searchBookings(params: Map<String, String>, ledger: Ledger? = null): List<LinkedHashMap<String, JValue>> {
    val L = ledger ?: Ledger(this)
    fun p(k: String) = (params[k] ?: "").trim()
    fun opt(k: String) = params[k]?.takeIf { it.isNotEmpty() }
    val q = p("q").lowercase(); val airline = p("airline").lowercase(); val origin = p("origin").uppercase(); val dest = p("destination").uppercase()
    val tdate = p("travel_date")
    val out = ArrayList<LinkedHashMap<String, JValue>>()
    for (b in L.bookingList) {
        val pax = L.passengers[b.id].orEmpty(); val segs = L.segments[b.id].orEmpty()
        if (q.isNotEmpty()) {
            val qd = q.filter { it.isDigit() }
            val hay = listOf(b.ref.lowercase(), (b.pnr ?: "").lowercase(), b.customerName.lowercase()) + pax.map { it.str("name").lowercase() } +
                pax.map { it.str("ticket_number").lowercase() } + segs.map { it.str("airline_pnr").lowercase() }
            var match = hay.any { it.isNotEmpty() && it.contains(q) }
            if (!match && qd.length >= 4) match = (b.customerPhone ?: "").filter { it.isDigit() }.contains(qd)
            if (!match) continue
        }
        if (airline.isNotEmpty() && segs.none { it.str("airline").lowercase().contains(airline) || it.str("flight_no").lowercase().contains(airline) }) continue
        if (origin.isNotEmpty() && segs.none { it.str("origin") == origin }) continue
        if (dest.isNotEmpty() && segs.none { it.str("destination") == dest }) continue
        if (tdate.isNotEmpty() && !(b.travelDate == tdate || segs.any { it.str("dep_at").take(10) == tdate })) continue
        val frm = opt("from"); val to = opt("to")
        if (frm != null && b.bookingDate < frm) continue
        if (to != null && b.bookingDate > to) continue
        if (opt("status") != null && b.status != opt("status")) continue
        if (opt("payment_status") != null && b.calc.paymentStatus != opt("payment_status")) continue
        if (opt("customer_id") != null && b.customerId.toString() != opt("customer_id")) continue
        if (opt("supplier_id") != null && (b.supplierId?.toString() ?: "") != opt("supplier_id")) continue
        val r = L.bookingRow(b)
        r["passenger_names"] = JValue.Arr(pax.map { J(it.str("name")) })
        r["tickets"] = JValue.Arr(pax.mapNotNull { it.optStr("ticket_number") }.filter { it.isNotEmpty() }.map { J(it) })
        r["exact_pnr"] = JValue.Bool(q.isNotEmpty() && (b.pnr ?: "").lowercase() == q)
        out.add(r)
    }
    return out.sortedWith(compareBy({ if (it["exact_pnr"] == JValue.Bool(true)) 0 else 1 }, { -(it["id"]!!.long!!) }))
}
