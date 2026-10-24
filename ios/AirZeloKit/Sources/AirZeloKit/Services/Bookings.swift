import Foundation

// Port of the booking services (core/server/services.py).

public let BOOKING_STATUSES = ["enquiry", "on_hold", "confirmed", "ticketed", "cancelled", "refunded"]
let COMMIT_STATUSES: Set<String> = ["confirmed", "ticketed"]
public let SERVICE_TYPES = ["flight", "visa", "hotel", "package", "umrah", "other"]
let PRICING_FIELDS = ["purchase_cost", "selling_price", "commission", "service_charge", "other_income",
                      "currency", "exchange_rate", "tax_amount", "taxable_value", "tax_rate", "tax_profile_id"]

extension Agency {
    func normTicket(_ t: JSONValue) throws -> String? {
        if t.isNull { return nil }
        let s = t.pyStr.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().replacingOccurrences(of: " ", with: "")
        if s.isEmpty { return nil }
        if s.unicodeScalars.count > 30 { throw AZError.validation("Ticket number is too long", "passengers") }
        return s
    }

    struct Pax { var seq: Int; var title: String?; var name: String; var paxType: String; var ticket: String?; var passport: String?; var notes: String? }

    func passengersPayload(_ data: Fields) throws -> [Pax] {
        let raw = data.get("passengers")
        let list: [JSONValue]
        switch raw {
        case .null: list = []
        case .array(let a): list = a
        default:
            if !raw.truthy { list = [] } else { throw AZError.validation("Passengers must be a list", "passengers") }
        }
        var out: [Pax] = []
        for (idx, pv) in list.enumerated() {
            guard let p = pv.objectValue else { continue }
            let name = (p.get("name").truthy ? p.get("name").pyStr : "").trimmingCharacters(in: .whitespacesAndNewlines)
            if name.isEmpty && !p.get("ticket_number").truthy { continue }
            if name.isEmpty { throw AZError.validation("Passenger \(idx + 1) needs a name", "passengers") }
            if name.unicodeScalars.count > 150 { throw AZError.validation("Passenger name is too long", "passengers") }
            let pt = p.get("pax_type").truthy ? p.get("pax_type") : .string("ADT")
            guard case .string(let ptype) = pt, ["ADT", "CHD", "INF"].contains(ptype) else {
                throw AZError.validation("Passenger type must be ADT, CHD or INF", "passengers")
            }
            out.append(Pax(seq: out.count, title: try V.s(p, "title", 10), name: name, paxType: ptype,
                           ticket: try normTicket(p.get("ticket_number")),
                           passport: try passportValue(p), notes: try V.s(p, "notes", 500)))
        }
        if out.isEmpty { throw AZError.validation("Add at least one passenger", "passengers") }
        let tickets = out.compactMap { $0.ticket }
        if tickets.count != Set(tickets).count { throw AZError.validation("The same ticket number is entered twice in this booking", "passengers") }
        return out
    }

    struct Segment {
        var seq: Int; var direction: String; var airline, flightNo, origin, destination, depAt, arrAt, cabin, baggage, airlinePnr: String?
        var json: JSONValue {
            ["seq": .int(seq), "direction": .string(direction), "airline": J(airline), "flight_no": J(flightNo), "origin": J(origin),
             "destination": J(destination), "dep_at": J(depAt), "arr_at": J(arrAt), "cabin": J(cabin), "baggage": J(baggage),
             "airline_pnr": J(airlinePnr)]
        }
    }

    func segmentsPayload(_ data: Fields) throws -> [Segment] {
        let raw = data.get("segments")
        let list: [JSONValue]
        switch raw {
        case .null: list = []
        case .array(let a): list = a
        default:
            if !raw.truthy { list = [] } else { throw AZError.validation("Flight segments must be a list", "segments") }
        }
        var out: [Segment] = []
        for (idx, sv) in list.enumerated() {
            guard let sg = sv.objectValue else { continue }
            let hasAny = ["airline", "flight_no", "origin", "destination", "dep_at"].contains {
                sg.get($0).truthy && !sg.get($0).pyStr.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            if !hasAny { continue }
            let s = Segment(seq: out.count,
                            direction: try V.choice(sg, "direction", ["outbound", "return", "other"], "outbound"),
                            airline: try V.s(sg, "airline", 80),
                            flightNo: V.normCode(J(try V.s(sg, "flight_no", 12)), 12),
                            origin: V.normCode(J(try V.s(sg, "origin", 40)), 40),
                            destination: V.normCode(J(try V.s(sg, "destination", 40)), 40),
                            depAt: try V.dt(sg, "dep_at", label: "Departure (segment \(idx + 1))"),
                            arrAt: try V.dt(sg, "arr_at", label: "Arrival (segment \(idx + 1))"),
                            cabin: try V.s(sg, "cabin", 30), baggage: try V.s(sg, "baggage", 60),
                            airlinePnr: V.normCode(J(try V.s(sg, "airline_pnr", 20)), 20))
            if let d = s.depAt, let a = s.arrAt, a < d { throw AZError.validation("Segment \(idx + 1) arrives before it departs", "segments") }
            out.append(s)
        }
        return out
    }

    func hasFinancialActivity(_ bid: Int) throws -> Bool {
        try db.scalarInt("SELECT (SELECT COUNT(*) FROM allocations WHERE booking_id = ?) + "
                         + "(SELECT COUNT(*) FROM booking_adjustments WHERE booking_id = ?) + "
                         + "(SELECT COUNT(*) FROM invoices WHERE booking_id = ? AND status = 'issued') + "
                         + "(SELECT COUNT(*) FROM transactions WHERE booking_id = ?)", [bid, bid, bid, bid]) > 0
    }

    public func findDuplicatePNR(_ pnr: String?, excluding: Int? = nil) throws -> [Row] {
        guard let pnr, !pnr.isEmpty else { return [] }
        return try db.query("SELECT b.id, b.ref, b.status, b.booking_date, c.name AS customer_name FROM bookings b "
                            + "JOIN customers c ON c.id = b.customer_id WHERE b.pnr = ? COLLATE NOCASE AND b.id <> ?", [pnr, excluding ?? 0])
    }

    /// Python str() of a stored value, for "did this field change" comparisons.
    private func pyText(_ v: SQLValue) -> String {
        switch v {
        case .null: return "None"
        case .int(let i): return String(i)
        case .real(let d): return JSONValue.pyFloat(d)
        case .text(let s): return s
        case .blob: return ""
        }
    }

    /// Create (id == nil) or update a booking with its passengers and flight segments.
    @discardableResult
    public func saveBooking(_ data: Fields, id: Int? = nil) throws -> Int {
        var bid = id
        return try tx {
            var before: Row?
            if let bid {
                guard let b = try db.one("SELECT * FROM bookings WHERE id = ?", [bid]) else { throw AZError.notFound("Booking not found") }
                before = b
            }
            let customerId = try resolveCustomer(data)
            let supplierId = try V.i(data, "supplier_id")
            if let supplierId, try db.one("SELECT 1 FROM suppliers WHERE id = ?", [supplierId]) == nil {
                throw AZError.validation("Selected supplier does not exist", "supplier_id")
            }
            let pnr = V.normCode(J(try V.s(data, "pnr", 20, label: "PNR")), 20)
            let status = try V.choice(data, "status", BOOKING_STATUSES, "confirmed", "Booking status")
            let serviceType = try V.choice(data, "service_type", SERVICE_TYPES, "flight", "Service type")
            let tripType = try V.choice(data, "trip_type", ["one_way", "round_trip", "multi_city"], "one_way", "Trip type")
            let bookingDate = try V.d(data, "booking_date", label: "Booking date") ?? Dates.today()
            let (currency, rate) = try currencyAndRate(data)
            let amounts: [String: Int] = [
                "purchase_cost": try parseAmount(data.get("purchase_cost"), "Purchase cost"),
                "selling_price": try parseAmount(data.get("selling_price"), "Selling price"),
                "commission": try parseAmount(data.get("commission"), "Commission"),
                "service_charge": try parseAmount(data.get("service_charge"), "Service charge"),
                "other_income": try parseAmount(data.get("other_income"), "Other income"),
            ]
            let tax = try computeTax(data, amounts)
            let pax = try passengersPayload(data)
            let segs = try segmentsPayload(data)
            var travelDate = try V.d(data, "travel_date", label: "Travel date")
            var returnDate = try V.d(data, "return_date", label: "Return date")
            if travelDate == nil, let first = segs.first?.depAt { travelDate = String(first.prefix(10)) }
            if returnDate == nil {
                for sg in segs where sg.direction == "return" && sg.depAt != nil { returnDate = String(sg.depAt!.prefix(10)); break }
            }
            if let t = travelDate, let r = returnDate, r < t { throw AZError.validation("Return date is before the travel date", "return_date") }

            if let pnr, !V.b(data, "force_duplicate") {
                let dups = try findDuplicatePNR(pnr, excluding: bid)
                if !dups.isEmpty {
                    throw AZError.conflict("PNR \(pnr) already exists in \(dups.map { $0.str("ref") }.joined(separator: ", ")). "
                                           + "Open the existing booking, or confirm to save a separate booking.", "duplicate_pnr",
                                           .array(dups.map { .object($0.json) }))
                }
            }
            for p in pax {
                guard let t = p.ticket else { continue }
                if let hit = try db.one("SELECT b.ref FROM booking_passengers p JOIN bookings b ON b.id = p.booking_id "
                                        + "WHERE p.ticket_number = ? AND p.booking_id <> ?", [t, bid ?? 0]) {
                    throw AZError.conflict("Ticket number \(t) is already recorded in booking \(hit.str("ref"))", "duplicate_ticket")
                }
            }

            var committed = COMMIT_STATUSES.contains(status) ? 1 : 0
            if let before, let bid {
                let wasCommitted = before.bool("committed")
                if wasCommitted && (status == "enquiry" || status == "on_hold") {
                    if try hasFinancialActivity(bid) {
                        throw AZError.validation("This booking already has payments, adjustments or an invoice. "
                                                 + "Cancel it with an adjustment instead of moving it back to \(status).", "status")
                    }
                    committed = 0
                } else if wasCommitted {
                    committed = 1
                }
                if wasCommitted && (status == "cancelled" || status == "refunded") && !["cancelled", "refunded"].contains(before.str("status")) {
                    throw AZError.validation("Use 'Cancel / Refund' on the booking so customer and supplier credits are recorded.", "status")
                }
                var newVals: [String: SQLValue] = tax
                for (k, v) in amounts { newVals[k] = .int(Int64(v)) }
                newVals["currency"] = .text(currency)
                newVals["exchange_rate"] = .text(rate)
                let changed = PRICING_FIELDS.filter { pyText(before[$0]) != pyText(newVals[$0] ?? .null) }
                if !changed.isEmpty,
                   try db.one("SELECT 1 FROM invoices WHERE booking_id = ? AND doc_type = 'invoice' AND status = 'issued'", [bid]) != nil {
                    throw AZError.validation("An invoice has been issued for this booking. Cancel that invoice before changing prices, "
                                             + "or record a reissue/adjustment.", "selling_price")
                }
                if wasCommitted && !changed.isEmpty && currency != before.str("currency"), try hasFinancialActivity(bid) {
                    throw AZError.validation("Currency cannot be changed after payments are recorded against this booking", "currency")
                }
            } else if status == "cancelled" || status == "refunded" {
                committed = 0
            }

            let ts = Dates.nowISO()
            let base = try setting("base_currency")
            var fields: [(String, SQLValue)] = [
                ("pnr", pnr.sqlValue), ("customer_id", .int(Int64(customerId))), ("supplier_id", supplierId.sqlValue),
                ("service_type", .text(serviceType)), ("booking_date", .text(bookingDate)), ("travel_date", travelDate.sqlValue),
                ("return_date", returnDate.sqlValue), ("trip_type", .text(tripType)), ("status", .text(status)),
                ("committed", .int(Int64(committed))), ("cabin", try V.s(data, "cabin", 30).sqlValue),
                ("baggage", try V.s(data, "baggage", 60).sqlValue), ("notes", try V.s(data, "notes", 8000).sqlValue),
                ("currency", .text(currency)), ("exchange_rate", .text(rate)),
                ("rate_date", (try V.d(data, "rate_date", label: "Rate date") ?? (currency != base ? bookingDate : nil)).sqlValue),
                ("place_of_supply", try V.s(data, "place_of_supply", 60).sqlValue),
            ]
            for k in ["purchase_cost", "selling_price", "commission", "service_charge", "other_income"] { fields.append((k, .int(Int64(amounts[k]!)))) }
            for k in ["tax_profile_id", "tax_override", "taxable_value", "tax_rate", "tax_amount", "tax_split"] { fields.append((k, tax[k]!)) }

            var ref: String
            if let before, let id = bid {
                ref = before.str("ref")
                try db.run("UPDATE bookings SET " + fields.map { "\($0.0) = ?" }.joined(separator: ", ") + ", updated_at = ? WHERE id = ?",
                           fields.map { $0.1 } + [.text(ts), .int(Int64(id))])
                try db.run("DELETE FROM booking_passengers WHERE booking_id = ?", [id])
                try db.run("DELETE FROM booking_segments WHERE booking_id = ?", [id])
            } else {
                let yy = String(bookingDate.dropFirst(2).prefix(2))
                let n = try nextCounter("booking-" + yy)
                let prefix = (try setting("booking_prefix", "BK-")).flatMap { $0.isEmpty ? nil : $0 } ?? "BK-"
                ref = prefix + yy + "-" + String(format: "%05d", n)
                fields.append(("ref", .text(ref)))
                bid = try db.run("INSERT INTO bookings(" + fields.map { $0.0 }.joined(separator: ", ") + ", created_at, updated_at) VALUES ("
                                 + Array(repeating: "?", count: fields.count).joined(separator: ", ") + ", ?, ?)",
                                 fields.map { $0.1 } + [.text(ts), .text(ts)])
            }
            let id = bid!
            for p in pax {
                try db.run("INSERT INTO booking_passengers(booking_id, seq, title, name, pax_type, ticket_number, passport_no, notes) VALUES (?,?,?,?,?,?,?,?)",
                           [id, p.seq, p.title, p.name, p.paxType, p.ticket, try crypto.seal(p.passport, column: "passport_no"), p.notes])
            }
            for s in segs {
                try db.run("INSERT INTO booking_segments(booking_id, seq, direction, airline, flight_no, origin, destination, dep_at, arr_at, cabin, baggage, airline_pnr) "
                           + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                           [id, s.seq, s.direction, s.airline, s.flightNo, s.origin, s.destination, s.depAt, s.arrAt, s.cabin, s.baggage, s.airlinePnr])
            }
            var after: [String: JSONValue] = [:]
            for (k, v) in fields { after[k] = JSONValue(v) }
            after["passengers"] = .array(pax.map { p in
                ["seq": .int(p.seq), "title": J(p.title), "name": .string(p.name), "pax_type": .string(p.paxType),
                 "ticket_number": J(p.ticket), "passport_no": p.passport == nil ? .null : "(encrypted)", "notes": J(p.notes)]
            })
            after["segments"] = .array(segs.map { $0.json })
            if let before {
                let changes = fields.filter { pyText(before[$0.0]) != pyText($0.1) }.map { $0.0 }
                try history(id, "updated", changes.isEmpty ? "Details updated" : "Changed: " + changes.joined(separator: ", "))
                if before.str("status") != status { try history(id, "status", "\(before.str("status")) -> \(status)") }
                try audit("booking", id, "update", .object(before.json), .object(after))
            } else {
                try history(id, "created", "Booking \(ref) created with status \(status)")
                try audit("booking", id, "create", nil, .object(after))
            }
            return id
        }
    }

    /// The complete save payload of an existing booking: every field saveBooking accepts, as stored. An edit screen
    /// starts from this and changes only what the user edits, so fields it does not show (cabin, place of supply,
    /// manual tax, passenger notes ...) are kept and the prices are re-sent exactly.
    public func bookingEditFields(_ bid: Int) throws -> Fields {
        guard let b = try db.one("SELECT * FROM bookings WHERE id = ?", [bid]) else { throw AZError.notFound("Booking not found") }
        func money(_ k: String) -> JSONValue { .string(fmt(b.int(k))) }
        var f: Fields = ["customer_id": .int(b.int("customer_id")), "supplier_id": b["supplier_id"].json, "pnr": b["pnr"].json,
                         "status": .string(b.str("status")), "service_type": .string(b.str("service_type")), "trip_type": .string(b.str("trip_type")),
                         "booking_date": .string(b.str("booking_date")), "travel_date": b["travel_date"].json, "return_date": b["return_date"].json,
                         "cabin": b["cabin"].json, "baggage": b["baggage"].json, "notes": b["notes"].json, "currency": .string(b.str("currency")),
                         "exchange_rate": .string(b.str("exchange_rate")), "rate_date": b["rate_date"].json, "place_of_supply": b["place_of_supply"].json,
                         "purchase_cost": money("purchase_cost"), "selling_price": money("selling_price"), "commission": money("commission"),
                         "service_charge": money("service_charge"), "other_income": money("other_income"),
                         "tax_profile_id": b["tax_profile_id"].json, "taxable_value": money("taxable_value")]
        if b.bool("tax_override") {
            f["tax_override"] = true
            f["tax_rate"] = .string(b.str("tax_rate"))
            f["tax_amount"] = money("tax_amount")
            f["tax_split"] = .string(b.str("tax_split"))
        }
        f["passengers"] = .array(try db.query("SELECT * FROM booking_passengers WHERE booking_id = ? ORDER BY seq, id", [bid]).map { p in
            ["title": p["title"].json, "name": .string(p.str("name")), "pax_type": .string(p.str("pax_type")), "ticket_number": p["ticket_number"].json,
             "passport_no": J(crypto.open(p.optStr("passport_no"), column: "passport_no")), "notes": p["notes"].json]
        })
        f["segments"] = .array(try db.query("SELECT * FROM booking_segments WHERE booking_id = ? ORDER BY seq, id", [bid]).map { s in
            var o: Fields = [:]
            for k in ["direction", "airline", "flight_no", "origin", "destination", "dep_at", "arr_at", "cabin", "baggage", "airline_pnr"] { o[k] = s[k].json }
            return .object(o)
        })
        return f
    }

    @discardableResult
    public func setBookingStatus(_ bid: Int, _ status: String) throws -> Int {
        guard BOOKING_STATUSES.contains(status) else { throw AZError.validation("Invalid status", "status") }
        return try tx {
            guard let b = try db.one("SELECT * FROM bookings WHERE id = ?", [bid]) else { throw AZError.notFound("Booking not found") }
            if b.str("status") == status { return bid }
            var committed = b.int("committed")
            if COMMIT_STATUSES.contains(status) {
                committed = 1
            } else if status == "enquiry" || status == "on_hold" {
                if b.bool("committed"), try hasFinancialActivity(bid) {
                    throw AZError.validation("This booking has financial activity; cancel it with an adjustment instead.", "status")
                }
                committed = 0
            } else if (status == "cancelled" || status == "refunded") && b.bool("committed") && !["cancelled", "refunded"].contains(b.str("status")) {
                throw AZError.validation("Use 'Cancel / Refund' so credits and charges are recorded.", "status")
            }
            try db.run("UPDATE bookings SET status = ?, committed = ?, updated_at = ? WHERE id = ?", [status, committed, Dates.nowISO(), bid])
            try history(bid, "status", "\(b.str("status")) -> \(status)")
            try audit("booking", bid, "status", ["status": .string(b.str("status")), "committed": .int(b.int("committed"))],
                      ["status": .string(status), "committed": .int(committed)])
            return bid
        }
    }

    /// Delete a booking with no money, adjustments, invoices or documents. Bookings with history are kept: cancel / refund them instead.
    public func deleteBooking(_ bid: Int) throws -> String {
        try tx {
            guard let b = try db.one("SELECT * FROM bookings WHERE id = ?", [bid]) else { throw AZError.notFound("Booking not found") }
            if try hasFinancialActivity(bid) || db.one("SELECT 1 FROM invoices WHERE booking_id = ?", [bid]) != nil {
                throw AZError.validation("\(b.str("ref")) has payments, refunds, adjustments or invoices, so it is kept for your accounts. "
                                         + "Use 'Refund / cancel' to cancel it instead.")
            }
            if try db.one("SELECT 1 FROM documents WHERE booking_id = ? AND deleted = 0", [bid]) != nil {
                throw AZError.validation("Remove the documents attached to \(b.str("ref")) first.")
            }
            let pax = try db.query("SELECT name, ticket_number FROM booking_passengers WHERE booking_id = ?", [bid]).map { JSONValue.object($0.json) }
            try db.run("DELETE FROM booking_history WHERE booking_id = ?", [bid])
            try db.run("DELETE FROM documents WHERE booking_id = ? AND deleted = 1", [bid])
            try db.run("DELETE FROM bookings WHERE id = ?", [bid])  // passengers and segments cascade
            try audit("booking", bid, "delete", ["ref": .string(b.str("ref")), "pnr": J(b.optStr("pnr")), "status": .string(b.str("status")),
                                                 "customer_id": .int(b.int("customer_id")), "selling_price": .int(b.int("selling_price")),
                                                 "purchase_cost": .int(b.int("purchase_cost")), "passengers": .array(pax)], nil)
            return b.str("ref")
        }
    }

    public struct BookingDetail {
        public let booking: LedgerBooking
        public let passengers: [[String: JSONValue]]
        public let segments: [Row]
        public let adjustments: [Adjustment]
        public let transactions: [[String: JSONValue]]
        public let invoices: [Row]
        public let history: [Row]
        public let customer: [String: JSONValue]?
        public let supplier: Row?
        public let taxProfile: Row?
        public let duplicates: [Row]
        public let base: String
    }

    public func getBooking(_ bid: Int) throws -> BookingDetail {
        let L = try Ledger(self)
        guard let b = L.bookings[bid] else { throw AZError.notFound("Booking not found") }
        var txns: [[String: JSONValue]] = []
        for t in L.transactions {
            let allocs = (L.allocations[t.id] ?? []).filter { $0.bookingId == bid }
            if !allocs.isEmpty || t.bookingId == bid {
                var r = L.txnRow(t)
                r["allocated_here"] = .int(allocs.reduce(0) { $0 + $1.amount })
                txns.append(r)
            }
        }
        txns.sort { ($0["txn_date"]!.pyStr, $0["id"]!.intValue!) < ($1["txn_date"]!.pyStr, $1["id"]!.intValue!) }
        let pax: [[String: JSONValue]] = (L.passengers[bid] ?? []).map { p in
            var o = p.json
            o["passport_no"] = J(crypto.open(p.optStr("passport_no"), column: "passport_no"))
            return o
        }
        return BookingDetail(
            booking: b, passengers: pax, segments: L.segments[bid] ?? [], adjustments: L.adjustments[bid] ?? [], transactions: txns,
            invoices: try db.query("SELECT id, number, doc_type, issue_date, total, currency, status, adjustment_id FROM invoices WHERE booking_id = ? ORDER BY id", [bid]),
            history: try db.query("SELECT * FROM booking_history WHERE booking_id = ? ORDER BY id DESC", [bid]),
            customer: L.customers[b.customerId].map { openCustomer($0) },
            supplier: b.supplierId.flatMap { L.suppliers[$0] },
            taxProfile: try b.row.optInt("tax_profile_id").flatMap { try db.one("SELECT * FROM tax_profiles WHERE id = ?", [$0]) },
            duplicates: try findDuplicatePNR(b.pnr, excluding: bid), base: L.base)
    }

    /// Saved-booking search (PNR, name, ticket number, phone ...). Searches this device's records only: no live airline / GDS lookup.
    public func searchBookings(_ params: [String: String], ledger: Ledger? = nil) throws -> [[String: JSONValue]] {
        let L = try ledger ?? Ledger(self)
        func p(_ k: String) -> String { (params[k] ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        let q = p("q").lowercased(), airline = p("airline").lowercased(), origin = p("origin").uppercased()
        let dest = p("destination").uppercased(), tdate = p("travel_date")
        let frm = params["from"].flatMap { $0.isEmpty ? nil : $0 }, to = params["to"].flatMap { $0.isEmpty ? nil : $0 }
        let status = params["status"].flatMap { $0.isEmpty ? nil : $0 }, payment = params["payment_status"].flatMap { $0.isEmpty ? nil : $0 }
        let customerId = params["customer_id"].flatMap { $0.isEmpty ? nil : $0 }, supplierId = params["supplier_id"].flatMap { $0.isEmpty ? nil : $0 }
        var out: [[String: JSONValue]] = []
        for b in L.bookingList {
            let pax = L.passengers[b.id] ?? [], segs = L.segments[b.id] ?? []
            if !q.isEmpty {
                let qd = q.filter { $0.isNumber }
                var hay = [b.ref.lowercased(), (b.pnr ?? "").lowercased(), b.customerName.lowercased()]
                hay += pax.map { $0.str("name").lowercased() }
                hay += pax.map { $0.str("ticket_number").lowercased() }
                hay += segs.map { $0.str("airline_pnr").lowercased() }
                var match = hay.contains { !$0.isEmpty && $0.contains(q) }
                if !match && qd.count >= 4 {
                    let phone = (b.customerPhone ?? "").filter { $0.isNumber }
                    match = phone.contains(qd)
                }
                if !match { continue }
            }
            if !airline.isEmpty && !segs.contains(where: { $0.str("airline").lowercased().contains(airline) || $0.str("flight_no").lowercased().contains(airline) }) { continue }
            if !origin.isEmpty && !segs.contains(where: { $0.str("origin") == origin }) { continue }
            if !dest.isEmpty && !segs.contains(where: { $0.str("destination") == dest }) { continue }
            if !tdate.isEmpty && !(b.travelDate == tdate || segs.contains { String($0.str("dep_at").prefix(10)) == tdate }) { continue }
            if let frm, b.bookingDate < frm { continue }
            if let to, b.bookingDate > to { continue }
            if let status, b.status != status { continue }
            if let payment, b.calc.paymentStatus != payment { continue }
            if let customerId, String(b.customerId) != customerId { continue }
            if let supplierId, (b.supplierId.map(String.init) ?? "") != supplierId { continue }
            var r = L.bookingRow(b)
            r["passenger_names"] = .array(pax.map { .string($0.str("name")) })
            r["tickets"] = .array(pax.compactMap { $0.optStr("ticket_number") }.filter { !$0.isEmpty }.map { .string($0) })
            r["exact_pnr"] = .bool(!q.isEmpty && (b.pnr ?? "").lowercased() == q)
            out.append(r)
        }
        out.sort { a, b in
            let ea = a["exact_pnr"] == .bool(true) ? 0 : 1, eb = b["exact_pnr"] == .bool(true) ? 0 : 1
            if ea != eb { return ea < eb }
            return a["id"]!.intValue! > b["id"]!.intValue!
        }
        return out
    }
}
