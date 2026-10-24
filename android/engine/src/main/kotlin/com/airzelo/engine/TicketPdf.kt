package com.airzelo.engine

import com.airzelo.engine.PdfCanvas.Align
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Port of core/server/ticket.py (via the iPhone's PDF/TicketPDF.swift): ticketData() is the single source for
 * everything shown on a ticket. Only saved booking data is used - missing details are left out, never guessed.
 */

val STATUS_LABELS = mapOf("enquiry" to "Enquiry - not confirmed", "on_hold" to "On hold - not confirmed", "confirmed" to "Confirmed",
    "ticketed" to "Ticketed", "cancelled" to "Cancelled", "refunded" to "Refunded")
private val DOC_TITLES = mapOf("ticketed" to "E-TICKET", "confirmed" to "BOOKING CONFIRMATION", "enquiry" to "ITINERARY (NOT CONFIRMED)",
    "on_hold" to "ITINERARY (ON HOLD)", "cancelled" to "CANCELLED BOOKING", "refunded" to "CANCELLED BOOKING")
private val PAX_TYPES = mapOf("ADT" to "Adult", "CHD" to "Child", "INF" to "Infant")
private val TRIP_TYPES = mapOf("one_way" to "One way", "round_trip" to "Return", "multi_city" to "Multi-city")
private val DIRECTIONS = mapOf("outbound" to "Onward journey", "return" to "Return journey", "other" to "Journey")
val AIRPORTS = mapOf(
    "COK" to "Kochi", "TRV" to "Thiruvananthapuram", "CCJ" to "Kozhikode", "CNN" to "Kannur", "BLR" to "Bengaluru", "MAA" to "Chennai",
    "HYD" to "Hyderabad", "BOM" to "Mumbai", "DEL" to "Delhi", "CCU" to "Kolkata", "AMD" to "Ahmedabad", "GOI" to "Goa", "GOX" to "Goa (Mopa)",
    "PNQ" to "Pune", "IXE" to "Mangaluru", "CJB" to "Coimbatore", "TRZ" to "Tiruchirappalli", "IXM" to "Madurai", "LKO" to "Lucknow",
    "JAI" to "Jaipur", "ATQ" to "Amritsar", "VNS" to "Varanasi", "GAU" to "Guwahati", "BBI" to "Bhubaneswar", "IXZ" to "Port Blair",
    "JED" to "Jeddah", "RUH" to "Riyadh", "DMM" to "Dammam", "MED" to "Madinah", "TIF" to "Taif", "AHB" to "Abha", "TUU" to "Tabuk",
    "GIZ" to "Jizan", "ELQ" to "Qassim", "HAS" to "Hail", "YNB" to "Yanbu", "DXB" to "Dubai", "DWC" to "Dubai World Central",
    "AUH" to "Abu Dhabi", "SHJ" to "Sharjah", "RKT" to "Ras Al Khaimah", "DOH" to "Doha", "MCT" to "Muscat", "SLL" to "Salalah",
    "KWI" to "Kuwait", "BAH" to "Bahrain", "CMB" to "Colombo", "MLE" to "Male", "KTM" to "Kathmandu", "DAC" to "Dhaka",
    "SIN" to "Singapore", "KUL" to "Kuala Lumpur", "BKK" to "Bangkok", "HKG" to "Hong Kong", "IST" to "Istanbul", "SAW" to "Istanbul (Sabiha)",
    "CAI" to "Cairo", "AMM" to "Amman", "LHR" to "London Heathrow", "LGW" to "London Gatwick", "MAN" to "Manchester",
    "CDG" to "Paris", "FRA" to "Frankfurt", "AMS" to "Amsterdam", "JFK" to "New York", "YYZ" to "Toronto", "SYD" to "Sydney",
    "MEL" to "Melbourne", "CHC" to "Christchurch", "AKL" to "Auckland", "NBO" to "Nairobi", "ADD" to "Addis Ababa")

class TicketData(
    val businessName: String, val tagline: String, val phone: String, val email: String, val address: String, val website: String,
    val gstin: String, val brandColor: String, val logo: PdfImage?,
    val bookingId: Long, val ref: String, val pnr: String, val status: String, val statusLabel: String, val docTitle: String,
    val tripLabel: String, val bookingDateLabel: String, val ticketedOn: String,
    val isTicketed: Boolean, val isConfirmed: Boolean, val isCancelled: Boolean,
    val customerName: String, val customerPhone: String, val passengers: List<Passenger>, val journeys: List<Journey>,
    val currency: String, val total: Long, val received: Long, val pending: Long, val credit: Long,
    val payStatus: String, val payLabel: String, val committed: Boolean,
    val payment: Map<String, String>,  // account_name, account_number, ifsc, branch, bank_name, upi_number, upi_apps
) {
    class Layover(val airport: String, val city: String, val duration: String)
    class Leg(val airline: String, val flightNo: String, val airlinePnr: String, val origin: String, val originCity: String,
              val destination: String, val destinationCity: String, val depAt: String, val depDate: String, val depTime: String,
              val arrAt: String, val arrDate: String, val arrTime: String, val cabin: String, val baggage: String) {
        var layover: Layover? = null
    }
    class Journey(val direction: String, val label: String, val legs: List<Leg>, val stops: Int)
    class Passenger(val title: String, val name: String, val displayName: String, val type: String, val ticketNumber: String)
}

internal object TicketDates {
    private val formats = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm").map { DateTimeFormatter.ofPattern(it, Locale.US) }

    fun parse(s: String?): LocalDateTime? {
        if (s.isNullOrEmpty()) return null
        val t = s.take(19)
        for (f in formats) runCatching { return LocalDateTime.parse(t, f) }
        return runCatching { java.time.LocalDate.parse(t.take(10)).atStartOfDay() }.getOrNull()?.takeIf { t.length == 10 }
    }
    fun fmt(d: LocalDateTime, pattern: String): String = d.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
    fun dateLabel(s: String?) = parse(s)?.let { fmt(it, "EEE, dd MMM yyyy") } ?: ""
    fun timeLabel(s: String?): String { if (s == null || s.length <= 10) return ""; return parse(s)?.let { fmt(it, "HH:mm") } ?: "" }
    fun minutes(a: String?, b: String?): Int? {
        if (a == null || b == null || a.length <= 10 || b.length <= 10) return null
        val da = parse(a) ?: return null; val db = parse(b) ?: return null
        val m = ChronoUnit.MINUTES.between(da, db).toInt()
        return if (m > 0 && m < 7 * 24 * 60) m else null
    }
    fun duration(m: Int) = if (m >= 60) String.format(Locale.ROOT, "%dh %02dm", m / 60, m % 60) else "${m}m"
}

internal fun paxDisplayName(title: String, name: String): String {
    val t = title.trim()
    if (t.isEmpty()) return name
    val bare = Regex.escape(if (t.endsWith(".")) t.dropLast(1) else t)
    val pattern = Regex("^(MR|MRS|MS|MISS|MSTR|MASTER|DR|$bare)\\b\\.?\\s", RegexOption.IGNORE_CASE)
    if (pattern.containsMatchIn(name.trim() + " ")) return name
    return "$t $name"
}

/** A logo imported from the desktop (branding/logo-*), or null. */
internal fun Agency.logoImage(name: String): PdfImage? {
    if (!name.startsWith("logo-") || name.contains("/") || name.contains("\\")) return null
    val f = File(File(file.parentFile, "branding"), name)
    if (!f.isFile || f.length() > 5_000_000) return null
    return PdfImage.from(f.readBytes())
}

fun Agency.ticketData(bid: Long): TicketData {
    val L = Ledger(this)
    val b = L.bookings[bid] ?: throw AZError.notFound("Booking not found")
    val s = allSettings()
    val calc = b.calc
    val cust = L.customers[b.customerId]
    val segs = L.segments[bid].orEmpty()
    val journeys = ArrayList<TicketData.Journey>()
    for (direction in listOf("outbound", "return", "other")) {
        val group = segs.filter { it.str("direction") == direction }
        if (group.isEmpty()) continue
        val legs = group.mapIndexed { i, x ->
            val o = x.str("origin").uppercase(); val d = x.str("destination").uppercase()
            val leg = TicketData.Leg(x.str("airline"), x.str("flight_no"), x.str("airline_pnr"), o, AIRPORTS[o.trim()] ?: "", d, AIRPORTS[d.trim()] ?: "",
                x.str("dep_at"), TicketDates.dateLabel(x.optStr("dep_at")), TicketDates.timeLabel(x.optStr("dep_at")),
                x.str("arr_at"), TicketDates.dateLabel(x.optStr("arr_at")), TicketDates.timeLabel(x.optStr("arr_at")),
                x.optStr("cabin")?.takeIf { it.isNotEmpty() } ?: b.row.str("cabin"), x.optStr("baggage")?.takeIf { it.isNotEmpty() } ?: b.row.str("baggage"))
            if (i + 1 < group.size) {
                val nxt = group[i + 1]
                if (x.str("destination").isNotEmpty() && d == nxt.str("origin").uppercase()) {
                    val m = TicketDates.minutes(x.optStr("arr_at"), nxt.optStr("dep_at"))
                    leg.layover = TicketData.Layover(leg.destination, leg.destinationCity, m?.let { TicketDates.duration(it) } ?: "")
                }
            }
            leg
        }
        journeys.add(TicketData.Journey(direction, DIRECTIONS[direction]!!, legs, legs.size - 1))
    }
    var ticketedOn = ""
    for (h in db.query("SELECT at, detail FROM booking_history WHERE booking_id = ? AND action IN ('status','created') ORDER BY id", listOf(bid))) {
        if (Regex("(-> ticketed|status ticketed)$").containsMatchIn(h.str("detail"))) ticketedOn = h.str("at").take(10)
    }
    val total = calc.customerPayable - calc.customerCredits
    val received = calc.received - calc.refunded
    val pending = calc.customerOutstanding
    val (ps, pl) = when {
        !b.committed -> "not_billed" to "Not billed yet"
        pending <= 0 -> "paid" to "Fully paid"
        received > 0 -> "partial" to "Partly paid"
        else -> "unpaid" to "Payment pending"
    }
    val st = b.status
    val pay = listOf("account_name", "account_number", "ifsc", "branch", "bank_name", "upi_number", "upi_apps").associateWith { (s["pay_$it"] ?: "").trim() }
    fun nz(k: String) = s[k] ?: ""
    return TicketData(
        nz("business_name").ifEmpty { "Travel Agency" }, nz("business_tagline"), nz("business_phone"), nz("business_email"), nz("business_address"),
        nz("business_website"), nz("business_gstin"), nz("brand_color"), logoImage(nz("logo_file")),
        bid, b.ref, b.pnr ?: "", st, STATUS_LABELS[st] ?: st, DOC_TITLES[st] ?: "BOOKING", TRIP_TYPES[b.row.str("trip_type")] ?: "",
        TicketDates.dateLabel(b.bookingDate), TicketDates.dateLabel(ticketedOn), st == "ticketed", st in listOf("confirmed", "ticketed"),
        st in listOf("cancelled", "refunded"), cust?.str("name") ?: "", cust?.str("phone") ?: "",
        L.passengers[bid].orEmpty().map { p ->
            TicketData.Passenger(p.str("title"), p.str("name"), paxDisplayName(p.str("title"), p.str("name")), PAX_TYPES[p.str("pax_type")] ?: p.str("pax_type"),
                p.str("ticket_number"))
        },
        journeys, L.base, total, received, maxOf(pending, 0), maxOf(-pending, 0), ps, pl, b.committed, pay)
}

class PdfFile(val data: ByteArray, val fileName: String)

/** The ticket (and optionally the payment page) as PDF, generated on the device. */
fun Agency.ticketPDF(bid: Long, withPayment: Boolean = false): PdfFile {
    val td = ticketData(bid)
    val lead = td.passengers.firstOrNull()?.name ?: td.customerName
    val parts = listOf("Booking", td.ref, td.pnr, lead, if (withPayment) "Ticket+Payment" else "Ticket").filter { it.isNotEmpty() }
        .map { it.replace(Regex("[^A-Za-z0-9+-]+"), "-").trim('-').take(40) }.filter { it.isNotEmpty() }
    return PdfFile(TicketLayout(td).build(withPayment), parts.joinToString("_") + ".pdf")
}

// ---- layout

private const val NAVY = "#0A1A3F"; private const val NAVY2 = "#0F2457"; private const val ROYAL = "#2350E6"; private const val SKY = "#35C9EA"
private const val SKY_LIGHT = "#E6F7FD"; private const val INK = "#0D1833"; private const val MUTED = "#64738F"; private const val LINE = "#E2E8F2"
private const val BG = "#F5F8FD"
private val STATUS_COLORS = mapOf("ticketed" to ("#0E9F6B" to "#E3F6EE"), "confirmed" to ("#2350E6" to "#EAF0FF"), "enquiry" to ("#C27A06" to "#FDF3DF"),
    "on_hold" to ("#C27A06" to "#FDF3DF"), "cancelled" to ("#D33447" to "#FDE9EC"), "refunded" to ("#D33447" to "#FDE9EC"))
private val PAY_COLORS = mapOf("paid" to ("#0E9F6B" to "#E3F6EE"), "partial" to ("#C27A06" to "#FDF3DF"), "unpaid" to ("#D33447" to "#FDE9EC"),
    "not_billed" to ("#64738F" to "#EEF2F9"))

private class TicketLayout(val td: TicketData) {
    val p = PdfCanvas("${td.docTitle} ${td.ref}", td.businessName)
    val M = 34.0
    var y = 0.0
    val brand = if (Regex("^#[0-9A-Fa-f]{6}$").matches(td.brandColor)) td.brandColor else ROYAL
    val W get() = p.width
    val H get() = p.height
    val cont get() = td.docTitle + " (CONTINUED)"
    val pnrText get() = td.pnr.ifEmpty { "-" }

    fun header(title: String, sub: String, compact: Boolean = false) {
        p.newPage()
        val h = if (compact) 74.0 else 104.0
        p.gradient(0.0, 0.0, W, h, NAVY, brand)
        var x = M
        p.rect(M, 20.0, 44.0, 44.0, fill = "#FFFFFF", radius = 10.0)
        if (td.logo != null) p.image(td.logo, M + 4, 24.0, 36.0, 36.0) else p.plane(M + 22, 42.0, 26.0, brand)
        x += 56
        p.text(x, 40.0, td.businessName.uppercase(), 17.0, true, "#FFFFFF", maxWidth = W - x - 200)
        if (td.tagline.isNotEmpty()) p.text(x, 56.0, td.tagline, 8.5, false, "#CFE3FF", maxWidth = W - x - 200)
        p.text(W - M, 38.0, title, if (compact) 13.0 else 15.0, true, "#FFFFFF", Align.RIGHT, spacing = 0.6)
        p.text(W - M, 54.0, sub, 8.5, false, "#CFE3FF", Align.RIGHT)
        p.rect(0.0, h, W, 4.0, fill = SKY)
        y = h + 22
    }

    fun footer() {
        val contact = listOf(td.phone, td.email, td.website).filter { it.isNotEmpty() }.joinToString("  |  ")
        p.line(M, H - 40, W - M, H - 40, LINE)
        p.text(M, H - 26, td.businessName.uppercase(), 8.0, true, NAVY)
        if (contact.isNotEmpty()) p.text(M, H - 15, contact, 7.5, false, MUTED, maxWidth = W - 2 * M - 120)
        p.text(W - M, H - 26, "Generated ${TicketDates.fmt(Dates.now(), "dd MMM yyyy")} with AirZelo", 7.5, false, MUTED, Align.RIGHT)
        p.text(W - M, H - 15, "Page ${p.pageCount}", 7.5, false, MUTED, Align.RIGHT)
    }

    /** Starts a new page when fewer than h points are left. */
    fun need(h: Double, contTitle: String) {
        if (y + h > H - 56) {
            footer()
            header(contTitle, "${td.ref}  |  PNR $pnrText", compact = true)
        }
    }

    fun section(label: String) {
        need(40.0, cont)
        p.rect(M, y - 9, 3.0, 13.0, fill = brand)
        p.text(M + 10, y + 2, label.uppercase(), 9.0, true, NAVY, spacing = 0.6)
        y += 16
    }

    fun notice(text: String, fg: String, bg: String) {
        val lines = PdfCanvas.wrap(text, 8.8, W - 2 * M - 30)
        val h = lines.size * 13.0 + 14
        need(h + 12, cont)
        p.rect(M, y, W - 2 * M, h, fill = bg, radius = 8.0)
        p.rect(M, y, 3.5, h, fill = fg, radius = 1.5)
        var yy = y + 16
        for (l in lines) { p.text(M + 16, yy, l, 8.8, true, fg); yy += 13 }
        y += h + 12
    }

    fun summaryCards() {
        val cw = (W - 2 * M - 3 * 10) / 4; val h = 56.0
        val (sfg, sbg) = STATUS_COLORS[td.status] ?: (MUTED to BG)
        data class Card(val label: String, val value: String, val fg: String?, val bg: String?)
        val cards = listOf(Card("PNR / BOOKING REFERENCE", pnrText, NAVY, null), Card("BOOKING REF", td.ref, null, null),
            Card("STATUS", td.statusLabel, sfg, sbg),
            Card(if (td.isTicketed) "TICKET ISSUED" else "BOOKED ON", if (td.isTicketed && td.ticketedOn.isNotEmpty()) td.ticketedOn else td.bookingDateLabel, null, null))
        for ((i, c) in cards.withIndex()) {
            val x = M + i * (cw + 10)
            if (i == 0) {
                p.rect(x, y, cw, h, fill = NAVY, radius = 10.0)
                p.text(x + 12, y + 18, c.label, 6.8, true, SKY, spacing = 0.4)
                p.text(x + 12, y + 41, c.value, 17.0, true, "#FFFFFF", spacing = 1.0, maxWidth = cw - 20)
            } else {
                p.rect(x, y, cw, h, fill = BG, stroke = LINE, radius = 10.0)
                p.text(x + 12, y + 18, c.label, 6.8, true, MUTED, spacing = 0.4)
                if (c.fg != null && c.bg != null) {
                    val tw = PdfCanvas.textWidth(c.value, 9.0, true) + 16
                    p.rect(x + 12, y + 28, minOf(tw, cw - 20), 18.0, fill = c.bg, radius = 9.0)
                    p.text(x + 20, y + 41, c.value, 9.0, true, c.fg, maxWidth = cw - 36)
                } else {
                    p.text(x + 12, y + 41, c.value, 11.0, true, INK, maxWidth = cw - 20)
                }
            }
        }
        y += h + 18
    }

    fun journey(j: TicketData.Journey) {
        val first = j.legs.first(); val last = j.legs.last()
        val stops = if (j.stops == 0) "Non-stop" else "${j.stops} stop${if (j.stops > 1) "s" else ""}"
        val legH = 86.0
        val h = 74 + j.legs.size * legH + j.legs.count { it.layover != null } * 22
        need(minOf(h, 400.0), cont)
        p.rect(M, y, W - 2 * M, 28.0, fill = SKY_LIGHT, radius = 10.0)
        p.text(M + 14, y + 18, j.label.uppercase(), 8.5, true, NAVY, spacing = 0.5)
        p.text(W - M - 14, y + 18, stops + (if (first.depDate.isEmpty()) "" else "  |  " + first.depDate), 8.5, true, brand, Align.RIGHT)
        y += 40
        p.text(M + 14, y + 18, first.origin.ifEmpty { "-" }, 24.0, true, NAVY)
        p.text(M + 14, y + 32, first.originCity, 8.0, false, MUTED)
        p.text(W - M - 14, y + 18, last.destination.ifEmpty { "-" }, 24.0, true, NAVY, Align.RIGHT)
        p.text(W - M - 14, y + 32, last.destinationCity, 8.0, false, MUTED, Align.RIGHT)
        p.line(M + 110, y + 10, W - M - 110, y + 10, SKY, 1.2, listOf(4.0, 3.0))
        p.circle(W / 2, y + 10, 11.0, fill = "#FFFFFF", stroke = SKY, lineWidth = 1.2)
        p.plane(W / 2, y + 10, 13.0, brand)
        y += 44
        for (leg in j.legs) {
            need(legH + 10, cont)
            p.line(M + 14, y, W - M - 14, y, LINE)
            val name = listOf(leg.airline, leg.flightNo).filter { it.isNotEmpty() }.joinToString(" ")
            p.text(M + 14, y + 16, name.ifEmpty { "Flight" }, 10.0, true, INK)
            val right = listOf(leg.cabin, if (leg.airlinePnr.isEmpty()) "" else "Airline PNR " + leg.airlinePnr).filter { it.isNotEmpty() }.joinToString("  |  ")
            p.text(W - M - 14, y + 16, right, 8.0, false, MUTED, Align.RIGHT)
            val colW = (W - 2 * M - 28) / 3
            val cols = listOf(
                listOf("DEPARTURE", listOf(leg.depTime, leg.origin).filter { it.isNotEmpty() }.joinToString("  "), leg.depDate, leg.originCity),
                listOf("ARRIVAL", listOf(leg.arrTime, leg.destination).filter { it.isNotEmpty() }.joinToString("  "), leg.arrDate, leg.destinationCity),
                listOf("BAGGAGE", leg.baggage.ifEmpty { "As per airline" }, "", ""))
            for ((i, c) in cols.withIndex()) {
                val x = M + 14 + i * colW
                p.text(x, y + 34, c[0], 6.8, true, MUTED, spacing = 0.4)
                p.text(x, y + 50, c[1], if (i == 2) 9.5 else 13.0, true, NAVY, maxWidth = colW - 8)
                if (c[2].isNotEmpty()) p.text(x, y + 63, c[2], 8.0, false, INK)
                if (c[3].isNotEmpty()) p.text(x, y + 74, c[3], 7.5, false, MUTED)
            }
            y += legH
            leg.layover?.let { l ->
                val text = "Layover at ${l.airport}" + (if (l.city.isEmpty()) "" else " (${l.city})") + (if (l.duration.isEmpty()) "" else "  |  " + l.duration)
                val tw = PdfCanvas.textWidth(text, 8.0, true) + 20
                p.rect(M + 14, y - 6, tw, 16.0, fill = "#FDF3DF", radius = 8.0)
                p.text(M + 24, y + 5, text, 8.0, true, "#C27A06")
                y += 22
            }
        }
        y += 12
    }

    fun passengersTable() {
        section("Passengers (${td.passengers.size})")
        val cols = listOf("#" to 24.0, "PASSENGER NAME" to 250.0, "TYPE" to 80.0, "E-TICKET NUMBER" to W - 2 * M - 354)
        need(26 + td.passengers.size * 22.0, cont)
        p.rect(M, y, W - 2 * M, 22.0, fill = NAVY, radius = 6.0)
        var x = M + 10
        for (c in cols) { p.text(x, y + 14.5, c.first, 7.5, true, "#FFFFFF", spacing = 0.4); x += c.second }
        y += 22
        for ((i, px) in td.passengers.withIndex()) {
            need(24.0, cont)
            if (i % 2 == 1) p.rect(M, y, W - 2 * M, 22.0, fill = BG)
            x = M + 10
            val vals = listOf((i + 1).toString(), px.displayName.uppercase(), px.type, px.ticketNumber.ifEmpty { "Not issued yet" })
            for ((k, c) in cols.withIndex()) {
                val isTicket = k == 3 && px.ticketNumber.isNotEmpty()
                p.text(x, y + 14.5, vals[k], 9.0, k == 1 || isTicket, if (isTicket) brand else if (k == 3) MUTED else INK, maxWidth = c.second - 8)
                x += c.second
            }
            y += 22
        }
        y += 16
    }

    fun goodToKnow() {
        val tips = arrayListOf("Carry a valid passport or government photo ID. Names must match the ticket exactly.",
            "Reach the airport at least 3 hours before international and 2 hours before domestic departures.",
            "All times are local airport times. Please reconfirm timings with the airline before travel.",
            "Baggage, cancellation and change rules are as per the airline fare conditions.")
        if (!td.isTicketed && !td.isCancelled && td.passengers.none { it.ticketNumber.isNotEmpty() }) {
            tips.add(0, "This is not an e-ticket yet. Travel is possible only after the ticket is issued.")
        }
        val h = 26 + 13.0 * tips.sumOf { PdfCanvas.wrap(it, 8.3, W - 2 * M - 40).size }
        need(h + 30, cont)
        p.rect(M, y, W - 2 * M, h, fill = SKY_LIGHT, radius = 10.0)
        p.text(M + 14, y + 16, "GOOD TO KNOW", 8.5, true, NAVY, spacing = 0.6)
        var yy = y + 31
        for (t in tips) {
            p.circle(M + 18, yy - 3, 1.8, fill = brand)
            yy = p.paragraph(M + 26, yy, t, 8.3, W - 2 * M - 40, false, INK, leading = 13.0)
        }
        y += h + 16
        p.text(W / 2, y + 4, "We wish you a safe, comfortable and wonderful journey!", 10.0, true, brand, Align.CENTER)
        y += 22
    }

    fun paymentPage() {
        footer()
        header("PAYMENT DETAILS", "${td.ref}  |  PNR $pnrText", compact = true)
        val cur = td.currency
        val (fg, bg) = PAY_COLORS[td.payStatus]!!
        val h = 118.0
        p.rect(M, y, W - 2 * M, h, fill = "#FFFFFF", stroke = LINE, radius = 10.0)
        p.text(M + 16, y + 20, "PAYMENT SUMMARY", 9.0, true, NAVY, spacing = 0.6)
        val tw = PdfCanvas.textWidth(td.payLabel, 9.0, true) + 18
        p.rect(W - M - 16 - tw, y + 9, tw, 17.0, fill = bg, radius = 8.5)
        p.text(W - M - 16 - tw + 9, y + 20.5, td.payLabel, 9.0, true, fg)
        val rows = listOf("Booking reference" to "${td.ref}  |  PNR $pnrText",
            "Total amount" to (if (td.committed) money(td.total, cur) else "Not billed yet"), "Amount received" to money(td.received, cur))
        var yy = y + 44
        for ((k, v) in rows) {
            p.text(M + 16, yy, k, 9.0, false, MUTED)
            p.text(W - M - 16, yy, v, 9.5, true, INK, Align.RIGHT)
            p.line(M + 16, yy + 6, W - M - 16, yy + 6, LINE, 0.5)
            yy += 19
        }
        p.text(M + 16, yy + 4, "Balance pending", 11.0, true, NAVY)
        p.text(W - M - 16, yy + 5, money(td.pending, cur), 15.0, true, if (td.pending > 0) "#D33447" else "#0E9F6B", Align.RIGHT)
        y += h + 16
        if (td.credit > 0) notice("Customer credit of ${money(td.credit, cur)} is held for this booking (paid more than billed).", "#6B4CE6", "#EFEBFE")
        if (td.payStatus == "paid") { notice("Paid in full - thank you! No payment is due for this booking.", "#0E9F6B", "#E3F6EE"); return }
        if (!td.committed) {
            notice("The amount is not billed yet. We will share the payable amount once the booking is confirmed.", MUTED, "#EEF2F9")
            return
        }
        val pd = td.payment
        val bank = listOf("Account name" to pd["account_name"]!!, "Account number" to pd["account_number"]!!, "IFSC code" to pd["ifsc"]!!,
            "Bank" to pd["bank_name"]!!, "Branch" to pd["branch"]!!).filter { it.second.isNotEmpty() }
        val cw = (W - 2 * M - 14) / 2
        val ch = 52 + 28.0 * maxOf(bank.size, 3)
        if (bank.isNotEmpty()) {
            p.rect(M, y, cw, ch, fill = BG, stroke = LINE, radius = 10.0)
            p.gradient(M, y, cw, 30.0, NAVY, brand, radius = 10.0)
            p.text(M + 14, y + 19.5, "BANK TRANSFER", 9.5, true, "#FFFFFF", spacing = 0.8)
            var by = y + 48
            for ((k, v) in bank) {
                p.text(M + 14, by, k, 7.5, false, MUTED)
                p.text(M + 14, by + 12.5, v, 10.5, true, INK, spacing = 0.4, maxWidth = cw - 24)
                by += 28
            }
        }
        val upi = pd["upi_number"] ?: ""
        if (upi.isNotEmpty()) {
            val x = if (bank.isEmpty()) M else M + cw + 14
            p.rect(x, y, cw, ch, fill = BG, stroke = LINE, radius = 10.0)
            p.gradient(x, y, cw, 30.0, brand, SKY, radius = 10.0)
            p.text(x + 14, y + 19.5, "UPI PAYMENT", 9.5, true, "#FFFFFF", spacing = 0.8)
            p.text(x + 14, y + 48, "UPI payment number", 7.5, false, MUTED)
            p.text(x + 14, y + 66, upi, 17.0, true, NAVY, spacing = 1.2)
            val apps = (pd["upi_apps"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (apps.isNotEmpty()) {
                p.text(x + 14, y + 86, "Pay with", 7.5, false, MUTED)
                var ax = x + 14; var ay = y + 94
                for (a in apps) {
                    val aw = PdfCanvas.textWidth(a, 8.5, true) + 18
                    if (ax + aw > x + cw - 10) { ax = x + 14; ay += 22 }
                    p.rect(ax, ay, aw, 16.0, fill = "#FFFFFF", stroke = "#C9D5EE", radius = 8.0)
                    p.text(ax + 9, ay + 11, a, 8.5, true, NAVY2)
                    ax += aw + 6
                }
            }
        }
        if (bank.isNotEmpty() || upi.isNotEmpty()) {
            y += ch + 16
            notice("Please verify the beneficiary details before confirming payment. After paying, kindly share your payment " +
                "confirmation (screenshot or UTR number) with us.", brand, "#EAF0FF")
        } else {
            notice("Payment details are not set up yet. Please contact us for payment instructions.", "#C27A06", "#FDF3DF")
        }
        if (td.pending > 0) {
            p.text(W / 2, y + 4, "Thank you for trusting ${td.businessName.uppercase()}!", 10.0, true, brand, Align.CENTER)
            y += 22
        }
    }

    fun build(withPayment: Boolean): ByteArray {
        header(td.docTitle, if (td.tripLabel.isEmpty()) td.ref else "${td.ref}  |  ${td.tripLabel}")
        summaryCards()
        when {
            td.isCancelled -> notice("This booking has been ${td.status}. It is not valid for travel.", "#D33447", "#FDE9EC")
            !td.isConfirmed -> notice("Not confirmed yet - this itinerary is for reference only and is not valid for travel.", "#C27A06", "#FDF3DF")
            !td.isTicketed -> notice(if (td.passengers.any { it.ticketNumber.isNotEmpty() })
                "Booking confirmed. E-ticket number(s) recorded for this booking are listed below."
            else "Booking confirmed. E-ticket numbers will be shared once the ticket is issued.", ROYAL, "#EAF0FF")
        }
        if (td.journeys.isEmpty()) {
            notice("No flight segments are recorded for this booking.", MUTED, "#EEF2F9")
        } else {
            section("Flight itinerary")
            for (j in td.journeys) journey(j)
        }
        passengersTable()
        goodToKnow()
        if (withPayment) paymentPage()
        footer()
        return p.finish()
    }
}
