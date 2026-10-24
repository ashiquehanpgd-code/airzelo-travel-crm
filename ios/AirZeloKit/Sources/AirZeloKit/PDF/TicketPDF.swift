import Foundation
import CoreGraphics
import ImageIO

// Port of core/server/ticket.py: ticketData() is the single source for everything shown on a ticket
// (the PDF and the WhatsApp text). Only saved booking data is used - missing details are left out, never guessed.

public let STATUS_LABELS = ["enquiry": "Enquiry - not confirmed", "on_hold": "On hold - not confirmed", "confirmed": "Confirmed",
                            "ticketed": "Ticketed", "cancelled": "Cancelled", "refunded": "Refunded"]
let DOC_TITLES = ["ticketed": "E-TICKET", "confirmed": "BOOKING CONFIRMATION", "enquiry": "ITINERARY (NOT CONFIRMED)",
                  "on_hold": "ITINERARY (ON HOLD)", "cancelled": "CANCELLED BOOKING", "refunded": "CANCELLED BOOKING"]
let PAX_TYPES = ["ADT": "Adult", "CHD": "Child", "INF": "Infant"]
let TRIP_TYPES = ["one_way": "One way", "round_trip": "Return", "multi_city": "Multi-city"]
let DIRECTIONS = ["outbound": "Onward journey", "return": "Return journey", "other": "Journey"]
let AIRPORTS: [String: String] = [
    "COK": "Kochi", "TRV": "Thiruvananthapuram", "CCJ": "Kozhikode", "CNN": "Kannur", "BLR": "Bengaluru", "MAA": "Chennai",
    "HYD": "Hyderabad", "BOM": "Mumbai", "DEL": "Delhi", "CCU": "Kolkata", "AMD": "Ahmedabad", "GOI": "Goa", "GOX": "Goa (Mopa)",
    "PNQ": "Pune", "IXE": "Mangaluru", "CJB": "Coimbatore", "TRZ": "Tiruchirappalli", "IXM": "Madurai", "LKO": "Lucknow",
    "JAI": "Jaipur", "ATQ": "Amritsar", "VNS": "Varanasi", "GAU": "Guwahati", "BBI": "Bhubaneswar", "IXZ": "Port Blair",
    "JED": "Jeddah", "RUH": "Riyadh", "DMM": "Dammam", "MED": "Madinah", "TIF": "Taif", "AHB": "Abha", "TUU": "Tabuk",
    "GIZ": "Jizan", "ELQ": "Qassim", "HAS": "Hail", "YNB": "Yanbu", "DXB": "Dubai", "DWC": "Dubai World Central",
    "AUH": "Abu Dhabi", "SHJ": "Sharjah", "RKT": "Ras Al Khaimah", "DOH": "Doha", "MCT": "Muscat", "SLL": "Salalah",
    "KWI": "Kuwait", "BAH": "Bahrain", "CMB": "Colombo", "MLE": "Male", "KTM": "Kathmandu", "DAC": "Dhaka",
    "SIN": "Singapore", "KUL": "Kuala Lumpur", "BKK": "Bangkok", "HKG": "Hong Kong", "IST": "Istanbul", "SAW": "Istanbul (Sabiha)",
    "CAI": "Cairo", "AMM": "Amman", "LHR": "London Heathrow", "LGW": "London Gatwick", "MAN": "Manchester",
    "CDG": "Paris", "FRA": "Frankfurt", "AMS": "Amsterdam", "JFK": "New York", "YYZ": "Toronto", "SYD": "Sydney",
    "MEL": "Melbourne", "CHC": "Christchurch", "AKL": "Auckland", "NBO": "Nairobi", "ADD": "Addis Ababa"]

public struct TicketData {
    public struct Leg {
        public var airline, flightNo, airlinePnr, origin, originCity, destination, destinationCity: String
        public var depAt, depDate, depTime, arrAt, arrDate, arrTime, cabin, baggage: String
        public var layover: (airport: String, city: String, duration: String)?
    }
    public struct Journey { public var direction, label: String; public var legs: [Leg]; public var stops: Int }
    public struct Passenger { public var title, name, displayName, type, ticketNumber: String }

    public var businessName, tagline, phone, email, address, website, gstin, brandColor: String
    public var logo: CGImage?
    public var bookingId: Int, ref, pnr, status, statusLabel, docTitle, tripLabel, bookingDateLabel, ticketedOn: String
    public var isTicketed, isConfirmed, isCancelled: Bool
    public var customerName, customerPhone: String
    public var passengers: [Passenger]
    public var journeys: [Journey]
    public var currency: String
    public var total, received, pending, credit: Int
    public var payStatus, payLabel: String
    public var committed: Bool
    public var payment: [String: String]  // account_name, account_number, ifsc, branch, bank_name, upi_number, upi_apps
}

enum TicketDates {
    static func parse(_ s: String?) -> Date? {
        guard let s, !s.isEmpty else { return nil }
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.timeZone = TimeZone(identifier: "UTC")
        for f in ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"] {
            df.dateFormat = f
            if let d = df.date(from: String(s.prefix(19))) { return d }
        }
        return nil
    }
    static func fmt(_ d: Date, _ f: String) -> String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.timeZone = TimeZone(identifier: "UTC")
        df.dateFormat = f
        return df.string(from: d)
    }
    static func dateLabel(_ s: String?) -> String { parse(s).map { fmt($0, "EEE, dd MMM yyyy") } ?? "" }
    static func timeLabel(_ s: String?) -> String {
        guard let s, s.count > 10, let d = parse(s) else { return "" }
        return fmt(d, "HH:mm")
    }
    static func minutes(_ a: String?, _ b: String?) -> Int? {
        guard let a, let b, a.count > 10, b.count > 10, let da = parse(a), let db = parse(b) else { return nil }
        let m = Int(db.timeIntervalSince(da) / 60)
        return m > 0 && m < 7 * 24 * 60 ? m : nil
    }
    static func duration(_ m: Int) -> String { m >= 60 ? String(format: "%dh %02dm", m / 60, m % 60) : "\(m)m" }
}

func paxDisplayName(title: String, name: String) -> String {
    let t = title.trimmingCharacters(in: .whitespaces)
    if t.isEmpty { return name }
    let bare = NSRegularExpression.escapedPattern(for: t.hasSuffix(".") ? String(t.dropLast()) : t)
    let pattern = "^(MR|MRS|MS|MISS|MSTR|MASTER|DR|\(bare))\\b\\.?\\s"
    if (name.trimmingCharacters(in: .whitespaces) + " ").range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil { return name }
    return t + " " + name
}

extension Agency {
    func logoImage(_ name: String) -> CGImage? {
        guard name.hasPrefix("logo-"), !name.contains("/"), !name.contains("\\") else { return nil }
        let url = self.url.deletingLastPathComponent().appendingPathComponent("branding").appendingPathComponent(name)
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(src, 0, nil)
    }

    public func ticketData(_ bid: Int) throws -> TicketData {
        let L = try Ledger(self)
        guard let b = L.bookings[bid] else { throw AZError.notFound("Booking not found") }
        let s = try allSettings()
        let calc = b.calc
        let cust = L.customers[b.customerId]
        let segs = L.segments[bid] ?? []
        var journeys: [TicketData.Journey] = []
        for direction in ["outbound", "return", "other"] {
            let group = segs.filter { $0.str("direction") == direction }
            if group.isEmpty { continue }
            var legs: [TicketData.Leg] = []
            for (i, x) in group.enumerated() {
                var leg = TicketData.Leg(
                    airline: x.str("airline"), flightNo: x.str("flight_no"), airlinePnr: x.str("airline_pnr"),
                    origin: x.str("origin").uppercased(), originCity: AIRPORTS[x.str("origin").uppercased().trimmingCharacters(in: .whitespaces)] ?? "",
                    destination: x.str("destination").uppercased(),
                    destinationCity: AIRPORTS[x.str("destination").uppercased().trimmingCharacters(in: .whitespaces)] ?? "",
                    depAt: x.str("dep_at"), depDate: TicketDates.dateLabel(x.optStr("dep_at")), depTime: TicketDates.timeLabel(x.optStr("dep_at")),
                    arrAt: x.str("arr_at"), arrDate: TicketDates.dateLabel(x.optStr("arr_at")), arrTime: TicketDates.timeLabel(x.optStr("arr_at")),
                    cabin: x.optStr("cabin").flatMap { $0.isEmpty ? nil : $0 } ?? b.row.str("cabin"),
                    baggage: x.optStr("baggage").flatMap { $0.isEmpty ? nil : $0 } ?? b.row.str("baggage"), layover: nil)
                if i + 1 < group.count {
                    let nxt = group[i + 1]
                    if !x.str("destination").isEmpty && x.str("destination").uppercased() == nxt.str("origin").uppercased() {
                        let m = TicketDates.minutes(x.optStr("arr_at"), nxt.optStr("dep_at"))
                        leg.layover = (leg.destination, leg.destinationCity, m.map(TicketDates.duration) ?? "")
                    }
                }
                legs.append(leg)
            }
            journeys.append(TicketData.Journey(direction: direction, label: DIRECTIONS[direction]!, legs: legs, stops: legs.count - 1))
        }
        var ticketedOn = ""
        for h in try db.query("SELECT at, detail FROM booking_history WHERE booking_id = ? AND action IN ('status','created') ORDER BY id", [bid]) {
            if h.str("detail").range(of: "(-> ticketed|status ticketed)$", options: .regularExpression) != nil { ticketedOn = String(h.str("at").prefix(10)) }
        }
        let total = calc.customerPayable - calc.customerCredits
        let received = calc.received - calc.refunded
        let pending = calc.customerOutstanding
        let (ps, pl): (String, String) = !b.committed ? ("not_billed", "Not billed yet") : pending <= 0 ? ("paid", "Fully paid")
            : received > 0 ? ("partial", "Partly paid") : ("unpaid", "Payment pending")
        let st = b.status
        var pay: [String: String] = [:]
        for k in ["account_name", "account_number", "ifsc", "branch", "bank_name", "upi_number", "upi_apps"] {
            pay[k] = (s["pay_" + k] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        func nz(_ k: String) -> String { s[k] ?? "" }
        return TicketData(
            businessName: nz("business_name").isEmpty ? "Travel Agency" : nz("business_name"), tagline: nz("business_tagline"),
            phone: nz("business_phone"), email: nz("business_email"), address: nz("business_address"), website: nz("business_website"),
            gstin: nz("business_gstin"), brandColor: nz("brand_color"), logo: logoImage(nz("logo_file")),
            bookingId: bid, ref: b.ref, pnr: b.pnr ?? "", status: st, statusLabel: STATUS_LABELS[st]!, docTitle: DOC_TITLES[st]!,
            tripLabel: TRIP_TYPES[b.row.str("trip_type")] ?? "", bookingDateLabel: TicketDates.dateLabel(b.bookingDate),
            ticketedOn: TicketDates.dateLabel(ticketedOn), isTicketed: st == "ticketed", isConfirmed: ["confirmed", "ticketed"].contains(st),
            isCancelled: ["cancelled", "refunded"].contains(st), customerName: cust?.str("name") ?? "", customerPhone: cust?.str("phone") ?? "",
            passengers: (L.passengers[bid] ?? []).map { p in
                TicketData.Passenger(title: p.str("title"), name: p.str("name"), displayName: paxDisplayName(title: p.str("title"), name: p.str("name")),
                                     type: PAX_TYPES[p.str("pax_type")] ?? p.str("pax_type"), ticketNumber: p.str("ticket_number"))
            },
            journeys: journeys, currency: L.base, total: total, received: received, pending: max(pending, 0), credit: max(-pending, 0),
            payStatus: ps, payLabel: pl, committed: b.committed, payment: pay)
    }

    /// The ticket (and optionally the payment page) as PDF, generated on the device.
    public func ticketPDF(_ bid: Int, withPayment: Bool = false) throws -> (data: Data, fileName: String) {
        let td = try ticketData(bid)
        let lead = td.passengers.first?.name ?? td.customerName
        let parts = ["Booking", td.ref, td.pnr, lead, withPayment ? "Ticket+Payment" : "Ticket"].filter { !$0.isEmpty }.map { p -> String in
            let cleaned = p.replacingOccurrences(of: "[^A-Za-z0-9+-]+", with: "-", options: .regularExpression)
                .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
            return String(cleaned.prefix(40))
        }
        return (TicketLayout(td).build(withPayment: withPayment), parts.filter { !$0.isEmpty }.joined(separator: "_") + ".pdf")
    }
}

// MARK: - layout

private let NAVY = "#0A1A3F", NAVY2 = "#0F2457", ROYAL = "#2350E6", SKY = "#35C9EA", SKY_LIGHT = "#E6F7FD"
private let INK = "#0D1833", MUTED = "#64738F", LINE = "#E2E8F2", BG = "#F5F8FD"
private let STATUS_COLORS = ["ticketed": ("#0E9F6B", "#E3F6EE"), "confirmed": ("#2350E6", "#EAF0FF"), "enquiry": ("#C27A06", "#FDF3DF"),
                             "on_hold": ("#C27A06", "#FDF3DF"), "cancelled": ("#D33447", "#FDE9EC"), "refunded": ("#D33447", "#FDE9EC")]
private let PAY_COLORS = ["paid": ("#0E9F6B", "#E3F6EE"), "partial": ("#C27A06", "#FDF3DF"), "unpaid": ("#D33447", "#FDE9EC"),
                          "not_billed": ("#64738F", "#EEF2F9")]

final class TicketLayout {
    let td: TicketData
    let p: PDFCanvas
    let M: CGFloat = 34
    var y: CGFloat = 0
    let brand: String
    var W: CGFloat { p.width }
    var H: CGFloat { p.height }

    init(_ td: TicketData) {
        self.td = td
        p = PDFCanvas(title: "\(td.docTitle) \(td.ref)", author: td.businessName)
        brand = td.brandColor.range(of: "^#[0-9A-Fa-f]{6}$", options: .regularExpression) != nil ? td.brandColor : ROYAL
    }

    func header(_ title: String, _ sub: String, compact: Bool = false) {
        p.newPage()
        let h: CGFloat = compact ? 74 : 104
        p.gradient(0, 0, W, h, NAVY, brand)
        var x = M
        if let logo = td.logo {
            p.rect(M, 20, 44, 44, fill: "#FFFFFF", radius: 10)
            p.image(logo, M + 4, 24, 36, 36)
            x += 56
        } else {
            p.rect(M, 20, 44, 44, fill: "#FFFFFF", radius: 10)
            p.plane(M + 22, 42, size: 26, color: brand)
            x += 56
        }
        p.text(x, 40, td.businessName.uppercased(), 17, .bold, "#FFFFFF", maxWidth: W - x - 200)
        if !td.tagline.isEmpty { p.text(x, 56, td.tagline, 8.5, .regular, "#CFE3FF", maxWidth: W - x - 200) }
        p.text(W - M, 38, title, compact ? 13 : 15, .bold, "#FFFFFF", align: .right, spacing: 0.6)
        p.text(W - M, 54, sub, 8.5, .regular, "#CFE3FF", align: .right)
        p.rect(0, h, W, 4, fill: SKY)
        y = h + 22
    }

    func footer() {
        let contact = [td.phone, td.email, td.website].filter { !$0.isEmpty }.joined(separator: "  |  ")
        p.line(M, H - 40, W - M, H - 40, color: LINE)
        p.text(M, H - 26, td.businessName.uppercased(), 8, .bold, NAVY)
        if !contact.isEmpty { p.text(M, H - 15, contact, 7.5, .regular, MUTED, maxWidth: W - 2 * M - 120) }
        p.text(W - M, H - 26, "Generated \(TicketDates.fmt(Dates.now(), "dd MMM yyyy")) with AirZelo", 7.5, .regular, MUTED, align: .right)
        p.text(W - M, H - 15, "Page \(p.pageCount)", 7.5, .regular, MUTED, align: .right)
    }

    /// Starts a new page when fewer than h points are left.
    func need(_ h: CGFloat, _ contTitle: String) {
        if y + h > H - 56 {
            footer()
            header(contTitle, "\(td.ref)  |  PNR \(td.pnr.isEmpty ? "-" : td.pnr)", compact: true)
        }
    }

    func section(_ label: String) {
        need(40, td.docTitle + " (CONTINUED)")
        p.rect(M, y - 9, 3, 13, fill: brand)
        p.text(M + 10, y + 2, label.uppercased(), 9, .bold, NAVY, spacing: 0.6)
        y += 16
    }

    func notice(_ text: String, _ fg: String, _ bg: String) {
        let lines = PDFCanvas.wrap(text, 8.8, W - 2 * M - 30)
        let h = CGFloat(lines.count) * 13 + 14
        need(h + 12, td.docTitle + " (CONTINUED)")
        p.rect(M, y, W - 2 * M, h, fill: bg, radius: 8)
        p.rect(M, y, 3.5, h, fill: fg, radius: 1.5)
        var yy = y + 16
        for l in lines { p.text(M + 16, yy, l, 8.8, .bold, fg); yy += 13 }
        y += h + 12
    }

    func summaryCards() {
        let cw = (W - 2 * M - 3 * 10) / 4, h: CGFloat = 56
        let (sfg, sbg) = STATUS_COLORS[td.status]!
        let cards: [(String, String, String?, String?)] = [
            ("PNR / BOOKING REFERENCE", td.pnr.isEmpty ? "-" : td.pnr, NAVY, nil), ("BOOKING REF", td.ref, nil, nil),
            ("STATUS", td.statusLabel, sfg, sbg),
            (td.isTicketed ? "TICKET ISSUED" : "BOOKED ON", td.isTicketed && !td.ticketedOn.isEmpty ? td.ticketedOn : td.bookingDateLabel, nil, nil)]
        for (i, c) in cards.enumerated() {
            let x = M + CGFloat(i) * (cw + 10)
            if i == 0 {
                p.rect(x, y, cw, h, fill: NAVY, radius: 10)
                p.text(x + 12, y + 18, c.0, 6.8, .bold, SKY, spacing: 0.4)
                p.text(x + 12, y + 41, c.1, 17, .bold, "#FFFFFF", spacing: 1, maxWidth: cw - 20)
            } else {
                p.rect(x, y, cw, h, fill: BG, stroke: LINE, radius: 10)
                p.text(x + 12, y + 18, c.0, 6.8, .bold, MUTED, spacing: 0.4)
                if let fg = c.2, let bg = c.3 {
                    let tw = PDFCanvas.textWidth(c.1, 9, .bold) + 16
                    p.rect(x + 12, y + 28, min(tw, cw - 20), 18, fill: bg, radius: 9)
                    p.text(x + 20, y + 41, c.1, 9, .bold, fg, maxWidth: cw - 36)
                } else {
                    p.text(x + 12, y + 41, c.1, 11, .bold, INK, maxWidth: cw - 20)
                }
            }
        }
        y += h + 18
    }

    func journey(_ j: TicketData.Journey) {
        let first = j.legs.first!, last = j.legs.last!
        let stops = j.stops == 0 ? "Non-stop" : "\(j.stops) stop\(j.stops > 1 ? "s" : "")"
        let legH: CGFloat = 86
        let h = 74 + CGFloat(j.legs.count) * legH + CGFloat(j.legs.filter { $0.layover != nil }.count) * 22
        need(min(h, 400), td.docTitle + " (CONTINUED)")
        p.rect(M, y, W - 2 * M, 28, fill: SKY_LIGHT, radius: 10)
        p.text(M + 14, y + 18, j.label.uppercased(), 8.5, .bold, NAVY, spacing: 0.5)
        p.text(W - M - 14, y + 18, stops + (first.depDate.isEmpty ? "" : "  |  " + first.depDate), 8.5, .bold, brand, align: .right)
        y += 40
        p.text(M + 14, y + 18, first.origin.isEmpty ? "-" : first.origin, 24, .bold, NAVY)
        p.text(M + 14, y + 32, first.originCity, 8, .regular, MUTED)
        p.text(W - M - 14, y + 18, last.destination.isEmpty ? "-" : last.destination, 24, .bold, NAVY, align: .right)
        p.text(W - M - 14, y + 32, last.destinationCity, 8, .regular, MUTED, align: .right)
        p.line(M + 110, y + 10, W - M - 110, y + 10, color: SKY, width: 1.2, dash: [4, 3])
        p.circle(W / 2, y + 10, 11, fill: "#FFFFFF", stroke: SKY, lineWidth: 1.2)
        p.plane(W / 2, y + 10, size: 13, color: brand)
        y += 44
        for leg in j.legs {
            need(legH + 10, td.docTitle + " (CONTINUED)")
            p.line(M + 14, y, W - M - 14, y, color: LINE)
            let name = [leg.airline, leg.flightNo].filter { !$0.isEmpty }.joined(separator: " ")
            p.text(M + 14, y + 16, name.isEmpty ? "Flight" : name, 10, .bold, INK)
            let right = [leg.cabin, leg.airlinePnr.isEmpty ? "" : "Airline PNR " + leg.airlinePnr].filter { !$0.isEmpty }.joined(separator: "  |  ")
            p.text(W - M - 14, y + 16, right, 8, .regular, MUTED, align: .right)
            let colW = (W - 2 * M - 28) / 3
            let cols: [(String, String, String, String)] = [
                ("DEPARTURE", [leg.depTime, leg.origin].filter { !$0.isEmpty }.joined(separator: "  "), leg.depDate, leg.originCity),
                ("ARRIVAL", [leg.arrTime, leg.destination].filter { !$0.isEmpty }.joined(separator: "  "), leg.arrDate, leg.destinationCity),
                ("BAGGAGE", leg.baggage.isEmpty ? "As per airline" : leg.baggage, "", "")]
            for (i, c) in cols.enumerated() {
                let x = M + 14 + CGFloat(i) * colW
                p.text(x, y + 34, c.0, 6.8, .bold, MUTED, spacing: 0.4)
                p.text(x, y + 50, c.1, i == 2 ? 9.5 : 13, .bold, NAVY, maxWidth: colW - 8)
                if !c.2.isEmpty { p.text(x, y + 63, c.2, 8, .regular, INK) }
                if !c.3.isEmpty { p.text(x, y + 74, c.3, 7.5, .regular, MUTED) }
            }
            y += legH
            if let l = leg.layover {
                let text = "Layover at \(l.airport)\(l.city.isEmpty ? "" : " (\(l.city))")\(l.duration.isEmpty ? "" : "  |  " + l.duration)"
                let tw = PDFCanvas.textWidth(text, 8, .bold) + 20
                p.rect(M + 14, y - 6, tw, 16, fill: "#FDF3DF", radius: 8)
                p.text(M + 24, y + 5, text, 8, .bold, "#C27A06")
                y += 22
            }
        }
        y += 12
    }

    func passengersTable() {
        section("Passengers (\(td.passengers.count))")
        let cols: [(String, CGFloat)] = [("#", 24), ("PASSENGER NAME", 250), ("TYPE", 80), ("E-TICKET NUMBER", W - 2 * M - 354)]
        need(26 + CGFloat(td.passengers.count) * 22, td.docTitle + " (CONTINUED)")
        p.rect(M, y, W - 2 * M, 22, fill: NAVY, radius: 6)
        var x = M + 10
        for c in cols { p.text(x, y + 14.5, c.0, 7.5, .bold, "#FFFFFF", spacing: 0.4); x += c.1 }
        y += 22
        for (i, px) in td.passengers.enumerated() {
            need(24, td.docTitle + " (CONTINUED)")
            if i % 2 == 1 { p.rect(M, y, W - 2 * M, 22, fill: BG) }
            x = M + 10
            let vals = [String(i + 1), px.displayName.uppercased(), px.type, px.ticketNumber.isEmpty ? "Not issued yet" : px.ticketNumber]
            for (k, c) in cols.enumerated() {
                let isTicket = k == 3 && !px.ticketNumber.isEmpty
                p.text(x, y + 14.5, vals[k], 9, k == 1 || isTicket ? .bold : .regular, isTicket ? brand : (k == 3 ? MUTED : INK), maxWidth: c.1 - 8)
                x += c.1
            }
            y += 22
        }
        y += 16
    }

    func goodToKnow() {
        var tips = ["Carry a valid passport or government photo ID. Names must match the ticket exactly.",
                    "Reach the airport at least 3 hours before international and 2 hours before domestic departures.",
                    "All times are local airport times. Please reconfirm timings with the airline before travel.",
                    "Baggage, cancellation and change rules are as per the airline fare conditions."]
        if !td.isTicketed && !td.isCancelled && !td.passengers.contains(where: { !$0.ticketNumber.isEmpty }) {
            tips.insert("This is not an e-ticket yet. Travel is possible only after the ticket is issued.", at: 0)
        }
        let h = 26 + 13 * CGFloat(tips.reduce(0) { $0 + PDFCanvas.wrap($1, 8.3, W - 2 * M - 40).count })
        need(h + 30, td.docTitle + " (CONTINUED)")
        p.rect(M, y, W - 2 * M, h, fill: SKY_LIGHT, radius: 10)
        p.text(M + 14, y + 16, "GOOD TO KNOW", 8.5, .bold, NAVY, spacing: 0.6)
        var yy = y + 31
        for t in tips {
            p.circle(M + 18, yy - 3, 1.8, fill: brand)
            yy = p.paragraph(M + 26, yy, t, 8.3, width: W - 2 * M - 40, .regular, INK, leading: 13)
        }
        y += h + 16
        p.text(W / 2, y + 4, "We wish you a safe, comfortable and wonderful journey!", 10, .bold, brand, align: .center)
        y += 22
    }

    func paymentPage() {
        footer()
        header("PAYMENT DETAILS", "\(td.ref)  |  PNR \(td.pnr.isEmpty ? "-" : td.pnr)", compact: true)
        let cur = td.currency
        let (fg, bg) = PAY_COLORS[td.payStatus]!
        let h: CGFloat = 118
        p.rect(M, y, W - 2 * M, h, fill: "#FFFFFF", stroke: LINE, radius: 10)
        p.text(M + 16, y + 20, "PAYMENT SUMMARY", 9, .bold, NAVY, spacing: 0.6)
        let tw = PDFCanvas.textWidth(td.payLabel, 9, .bold) + 18
        p.rect(W - M - 16 - tw, y + 9, tw, 17, fill: bg, radius: 8.5)
        p.text(W - M - 16 - tw + 9, y + 20.5, td.payLabel, 9, .bold, fg)
        let rows = [("Booking reference", "\(td.ref)  |  PNR \(td.pnr.isEmpty ? "-" : td.pnr)"),
                    ("Total amount", td.committed ? money(td.total, cur) : "Not billed yet"), ("Amount received", money(td.received, cur))]
        var yy = y + 44
        for (k, v) in rows {
            p.text(M + 16, yy, k, 9, .regular, MUTED)
            p.text(W - M - 16, yy, v, 9.5, .bold, INK, align: .right)
            p.line(M + 16, yy + 6, W - M - 16, yy + 6, color: LINE, width: 0.5)
            yy += 19
        }
        p.text(M + 16, yy + 4, "Balance pending", 11, .bold, NAVY)
        p.text(W - M - 16, yy + 5, money(td.pending, cur), 15, .bold, td.pending > 0 ? "#D33447" : "#0E9F6B", align: .right)
        y += h + 16
        if td.credit > 0 { notice("Customer credit of \(money(td.credit, cur)) is held for this booking (paid more than billed).", "#6B4CE6", "#EFEBFE") }
        if td.payStatus == "paid" { notice("Paid in full - thank you! No payment is due for this booking.", "#0E9F6B", "#E3F6EE"); return }
        if !td.committed {
            notice("The amount is not billed yet. We will share the payable amount once the booking is confirmed.", MUTED, "#EEF2F9")
            return
        }
        let pd = td.payment
        let bank = [("Account name", pd["account_name"]!), ("Account number", pd["account_number"]!), ("IFSC code", pd["ifsc"]!),
                    ("Bank", pd["bank_name"]!), ("Branch", pd["branch"]!)].filter { !$0.1.isEmpty }
        let cw = (W - 2 * M - 14) / 2
        let ch = 52 + 28 * CGFloat(max(bank.count, 3))
        if !bank.isEmpty {
            p.rect(M, y, cw, ch, fill: BG, stroke: LINE, radius: 10)
            p.gradient(M, y, cw, 30, NAVY, brand, radius: 10)
            p.text(M + 14, y + 19.5, "BANK TRANSFER", 9.5, .bold, "#FFFFFF", spacing: 0.8)
            var by = y + 48
            for (k, v) in bank {
                p.text(M + 14, by, k, 7.5, .regular, MUTED)
                p.text(M + 14, by + 12.5, v, 10.5, .bold, INK, spacing: 0.4, maxWidth: cw - 24)
                by += 28
            }
        }
        if let upi = pd["upi_number"], !upi.isEmpty {
            let x = bank.isEmpty ? M : M + cw + 14
            p.rect(x, y, cw, ch, fill: BG, stroke: LINE, radius: 10)
            p.gradient(x, y, cw, 30, brand, SKY, radius: 10)
            p.text(x + 14, y + 19.5, "UPI PAYMENT", 9.5, .bold, "#FFFFFF", spacing: 0.8)
            p.text(x + 14, y + 48, "UPI payment number", 7.5, .regular, MUTED)
            p.text(x + 14, y + 66, upi, 17, .bold, NAVY, spacing: 1.2)
            let apps = (pd["upi_apps"] ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            if !apps.isEmpty {
                p.text(x + 14, y + 86, "Pay with", 7.5, .regular, MUTED)
                var ax = x + 14, ay = y + 94
                for a in apps {
                    let tw = PDFCanvas.textWidth(a, 8.5, .bold) + 18
                    if ax + tw > x + cw - 10 { ax = x + 14; ay += 22 }
                    p.rect(ax, ay, tw, 16, fill: "#FFFFFF", stroke: "#C9D5EE", radius: 8)
                    p.text(ax + 9, ay + 11, a, 8.5, .bold, NAVY2)
                    ax += tw + 6
                }
            }
        }
        if !bank.isEmpty || !(pd["upi_number"] ?? "").isEmpty {
            y += ch + 16
            notice("Please verify the beneficiary details before confirming payment. After paying, kindly share your payment "
                   + "confirmation (screenshot or UTR number) with us.", brand, "#EAF0FF")
        } else {
            notice("Payment details are not set up yet. Please contact us for payment instructions.", "#C27A06", "#FDF3DF")
        }
        if td.pending > 0 {
            p.text(W / 2, y + 4, "Thank you for trusting \(td.businessName.uppercased())!", 10, .bold, brand, align: .center)
            y += 22
        }
    }

    func build(withPayment: Bool) -> Data {
        header(td.docTitle, td.tripLabel.isEmpty ? td.ref : "\(td.ref)  |  \(td.tripLabel)")
        summaryCards()
        if td.isCancelled {
            notice("This booking has been \(td.status). It is not valid for travel.", "#D33447", "#FDE9EC")
        } else if !td.isConfirmed {
            notice("Not confirmed yet - this itinerary is for reference only and is not valid for travel.", "#C27A06", "#FDF3DF")
        } else if !td.isTicketed {
            notice(td.passengers.contains { !$0.ticketNumber.isEmpty }
                   ? "Booking confirmed. E-ticket number(s) recorded for this booking are listed below."
                   : "Booking confirmed. E-ticket numbers will be shared once the ticket is issued.", ROYAL, "#EAF0FF")
        }
        if td.journeys.isEmpty {
            notice("No flight segments are recorded for this booking.", MUTED, "#EEF2F9")
        } else {
            section("Flight itinerary")
            for j in td.journeys { journey(j) }
        }
        passengersTable()
        goodToKnow()
        if withPayment { paymentPage() }
        footer()
        return p.finish()
    }
}
