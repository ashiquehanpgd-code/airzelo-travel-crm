import Foundation

// Port of the invoice, credit note, reconciliation and search services (core/server/services.py).

let SNAPSHOT_KEYS = ["business_name", "business_tagline", "business_address", "business_phone", "business_email", "business_gstin",
                     "business_state", "invoice_terms", "invoice_footer", "legal_name", "business_country", "business_website",
                     "business_registration", "logo_file", "brand_color", "date_format"]
let SERVICE_LABELS = ["flight": "Air ticket", "visa": "Visa service", "hotel": "Hotel booking", "package": "Tour package",
                      "umrah": "Umrah package", "other": "Travel service"]

/// Indian financial year of a date: "2026-27" for 2026-04-01 ... 2027-03-31.
func financialYear(_ d: String) -> String {
    let y = Int(d.prefix(4)) ?? 0, m = Int(d.dropFirst(5).prefix(2)) ?? 1
    let start = m >= 4 ? y : y - 1
    return "\(start)-" + String(format: "%02d", (start + 1) % 100)
}

func taxLines(_ split: String, _ rate: String, _ taxAmount: Int, _ taxable: Int) -> [JSONValue] {
    if taxAmount == 0 { return [] }
    if split == "cgst_sgst" {
        let (a, b) = splitHalf(taxAmount)
        let half = halfRate(rate)
        return [["label": .string("CGST @ \(half)%"), "amount": .int(a), "taxable": .int(taxable)],
                ["label": .string("SGST @ \(half)%"), "amount": .int(b), "taxable": .int(taxable)]]
    }
    if split == "igst" { return [["label": .string("IGST @ \(rate)%"), "amount": .int(taxAmount), "taxable": .int(taxable)]] }
    return [["label": .string("Tax @ \(rate)%"), "amount": .int(taxAmount), "taxable": .int(taxable)]]
}

extension Agency {
    /// Company details frozen into an issued document, so later profile edits never change it.
    func businessSnapshot() throws -> JSONValue {
        let st = try allSettings()
        var o: [String: JSONValue] = [:]
        for k in SNAPSHOT_KEYS { o[k] = .string(st[k] ?? "") }
        return .object(o)
    }

    private func customerSnapshot(_ cid: Int) throws -> JSONValue {
        let c = try db.one("SELECT * FROM customers WHERE id = ?", [cid])!
        var o: [String: JSONValue] = [:]
        for k in ["name", "phone", "email", "address", "city", "state", "gstin"] { o[k] = c[k].json }
        return .object(o)
    }

    @discardableResult
    public func createInvoice(_ bid: Int, _ data: Fields = [:]) throws -> Int {
        try tx {
            guard let b = try db.one("SELECT * FROM bookings WHERE id = ?", [bid]) else { throw AZError.notFound("Booking not found") }
            if !b.bool("committed") { throw AZError.validation("Confirm or ticket the booking before issuing an invoice") }
            if try db.one("SELECT 1 FROM invoices WHERE booking_id = ? AND doc_type = 'invoice' AND status = 'issued'", [bid]) != nil {
                throw AZError.conflict("An invoice is already issued for this booking", "invoice_exists")
            }
            let issueDate = try V.d(data, "issue_date") ?? Dates.today()
            let fy = financialYear(issueDate)
            let n = try nextCounter("invoice-" + fy)
            let number = (try setting("invoice_prefix", "INV/") ?? "INV/") + fy + "/" + String(format: "%04d", n)
            let pax = try db.query("SELECT name, pax_type, ticket_number FROM booking_passengers WHERE booking_id = ? ORDER BY seq", [bid])
            let segs = try db.query("SELECT * FROM booking_segments WHERE booking_id = ? ORDER BY seq", [bid])
            let prof = try b.optInt("tax_profile_id").flatMap { try db.one("SELECT * FROM tax_profiles WHERE id = ?", [$0]) }
            let route = segs.map { s in
                "\(s.str("airline")) \(s.str("flight_no")) \(s.str("origin"))-\(s.str("destination")) \(String(s.str("dep_at").prefix(10)))"
            }.joined(separator: " / ").trimmingCharacters(in: .whitespacesAndNewlines)
            var desc = SERVICE_LABELS[b.str("service_type")]!
            if let pnr = b.optStr("pnr"), !pnr.isEmpty { desc += " | PNR " + pnr }
            if !route.isEmpty { desc += " | " + route }
            var lines: [JSONValue] = []
            if b.int("selling_price") != 0 {
                let detail = pax.map { p in p.str("name") + (p.optStr("ticket_number").map { $0.isEmpty ? "" : " (" + $0 + ")" } ?? "") }
                    .joined(separator: ", ")
                lines.append(["description": .string(desc), "detail": .string(detail), "amount": .int(b.int("selling_price")), "sac": ""])
            }
            if b.int("service_charge") != 0 {
                lines.append(["description": "Service charge", "detail": "", "amount": .int(b.int("service_charge")),
                              "sac": .string(prof?.optStr("sac_code") ?? "")])
            }
            if b.int("other_income") != 0 {
                lines.append(["description": "Other charges", "detail": "", "amount": .int(b.int("other_income")), "sac": ""])
            }
            if lines.isEmpty { throw AZError.validation("This booking has no amounts to invoice") }
            let subtotal = lines.reduce(0) { $0 + ($1["amount"].intValue ?? 0) }
            let taxes = taxLines(b.str("tax_split"), b.str("tax_rate"), b.int("tax_amount"), b.int("taxable_value"))
            let taxTotal = taxes.reduce(0) { $0 + ($1["amount"].intValue ?? 0) }
            let doc: JSONValue = [
                "lines": .array(lines), "customer": try customerSnapshot(b.int("customer_id")), "business": try businessSnapshot(),
                "booking": ["ref": .string(b.str("ref")), "pnr": b["pnr"].json, "travel_date": b["travel_date"].json,
                            "place_of_supply": b["place_of_supply"].json,
                            "passengers": .array(pax.map { .object($0.json) }), "segments": .array(segs.map { .object($0.json) })]]
            let iid = try db.run("INSERT INTO invoices(number, doc_type, booking_id, customer_id, issue_date, currency, lines_json, tax_json, subtotal, tax_total, total, notes, created_at) "
                                 + "VALUES (?, 'invoice', ?,?,?,?,?,?,?,?,?,?,?)",
                                 [number, bid, b.int("customer_id"), issueDate, b.str("currency"), JSONValue.serialize(doc, sortKeys: false),
                                  JSONValue.serialize(.array(taxes), sortKeys: false), subtotal, taxTotal, subtotal + taxTotal,
                                  try V.s(data, "notes", 1000), Dates.nowISO()])
            try history(bid, "invoice", "Invoice \(number) issued")
            try audit("invoice", iid, "create", nil, ["number": .string(number), "total": .int(subtotal + taxTotal)])
            return iid
        }
    }

    @discardableResult
    public func createCreditNote(_ aid: Int, _ data: Fields = [:]) throws -> Int {
        try tx {
            guard let a = try db.one("SELECT * FROM booking_adjustments WHERE id = ?", [aid]) else { throw AZError.notFound("Adjustment not found") }
            if a.int("sign") != 1 || a.optInt("reversed_by") != nil { throw AZError.validation("This adjustment has been reversed") }
            if a.int("customer_credit") == 0 { throw AZError.validation("This adjustment has no customer credit") }
            if try db.one("SELECT 1 FROM invoices WHERE adjustment_id = ? AND status = 'issued'", [aid]) != nil {
                throw AZError.conflict("A credit note already exists for this adjustment")
            }
            let b = try db.one("SELECT * FROM bookings WHERE id = ?", [a.int("booking_id")])!
            let inv = try db.one("SELECT id, number FROM invoices WHERE booking_id = ? AND doc_type = 'invoice' AND status = 'issued'", [b.int("id")])
            let issueDate = try V.d(data, "issue_date") ?? Dates.today()
            let fy = financialYear(issueDate)
            let n = try nextCounter("credit-" + fy)
            let number = (try setting("credit_note_prefix", "CN/") ?? "CN/") + fy + "/" + String(format: "%04d", n)
            let sub = a.int("customer_credit") - a.int("customer_credit_tax")
            let pnrPart = b.optStr("pnr").map { $0.isEmpty ? "" : " | PNR " + $0 } ?? ""
            let lines: [JSONValue] = [["description": .string("\(V.capitalize(a.str("kind").replacingOccurrences(of: "_", with: " "))) credit - booking \(b.str("ref"))\(pnrPart)"),
                                       "detail": .string(a.optStr("notes") ?? ""), "amount": .int(sub), "sac": ""]]
            let taxes = a.int("customer_credit_tax") != 0 ? taxLines(b.str("tax_split"), b.str("tax_rate"), a.int("customer_credit_tax"), 0) : []
            let doc: JSONValue = [
                "lines": .array(lines), "customer": try customerSnapshot(b.int("customer_id")), "business": try businessSnapshot(),
                "against": J(inv?.str("number")),
                "booking": ["ref": .string(b.str("ref")), "pnr": b["pnr"].json, "travel_date": b["travel_date"].json,
                            "place_of_supply": b["place_of_supply"].json]]
            let iid = try db.run("INSERT INTO invoices(number, doc_type, booking_id, customer_id, adjustment_id, related_invoice_id, issue_date, currency, lines_json, tax_json, subtotal, tax_total, total, notes, created_at) "
                                 + "VALUES (?, 'credit_note', ?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                 [number, b.int("id"), b.int("customer_id"), aid, inv?.optInt("id"), issueDate, b.str("currency"),
                                  JSONValue.serialize(doc, sortKeys: false), JSONValue.serialize(.array(taxes), sortKeys: false), sub,
                                  a.int("customer_credit_tax"), a.int("customer_credit"), try V.s(data, "notes", 1000), Dates.nowISO()])
            try history(b.int("id"), "credit_note", "Credit note \(number) issued")
            try audit("invoice", iid, "create", nil, ["number": .string(number), "total": .int(a.int("customer_credit")), "type": "credit_note"])
            return iid
        }
    }

    @discardableResult
    public func cancelInvoice(_ iid: Int, _ data: Fields) throws -> Int {
        try tx {
            guard let inv = try db.one("SELECT * FROM invoices WHERE id = ?", [iid]) else { throw AZError.notFound("Invoice not found") }
            if inv.str("status") == "cancelled" { throw AZError.validation("Invoice is already cancelled") }
            let reason = try V.s(data, "reason", 500, required: true, label: "Reason")!
            try db.run("UPDATE invoices SET status = 'cancelled', cancel_reason = ? WHERE id = ?", [reason, iid])
            try history(inv.int("booking_id"), "invoice", "\(inv.str("doc_type") == "invoice" ? "Invoice" : "Credit note") \(inv.str("number")) cancelled: \(reason)")
            try audit("invoice", iid, "cancel", ["status": "issued"], ["status": "cancelled", "reason": .string(reason)])
            return iid
        }
    }

    public struct InvoiceDoc {
        public let row: Row
        public let doc: JSONValue
        public let taxes: [JSONValue]
        public let received: Int?
        public let outstanding: Int?
        public let base: String
    }

    public func getInvoice(_ iid: Int) throws -> InvoiceDoc {
        guard let inv = try db.one("SELECT * FROM invoices WHERE id = ?", [iid]) else { throw AZError.notFound("Invoice not found") }
        let L = try Ledger(self)
        let b = L.bookings[inv.int("booking_id")]
        return InvoiceDoc(row: inv, doc: try JSONValue.parse(inv.str("lines_json")), taxes: try JSONValue.parse(inv.str("tax_json")).arrayValue ?? [],
                          received: b?.calc.received, outstanding: b?.calc.customerOutstanding, base: L.base)
    }

    public func listInvoices(_ params: [String: String] = [:]) throws -> [Row] {
        var q = "SELECT i.id, i.number, i.doc_type, i.issue_date, i.currency, i.subtotal, i.tax_total, i.total, i.status, "
            + "i.booking_id, b.ref AS booking_ref, b.pnr, c.name AS customer_name, i.customer_id FROM invoices i "
            + "JOIN bookings b ON b.id = i.booking_id JOIN customers c ON c.id = i.customer_id WHERE 1=1"
        var args: [SQLBindable] = []
        if let v = params["from"], !v.isEmpty { q += " AND i.issue_date >= ?"; args.append(v) }
        if let v = params["to"], !v.isEmpty { q += " AND i.issue_date <= ?"; args.append(v) }
        if let v = params["doc_type"], !v.isEmpty { q += " AND i.doc_type = ?"; args.append(v) }
        if let v = params["q"], !v.isEmpty {
            q += " AND (i.number LIKE ? OR b.ref LIKE ? OR b.pnr LIKE ? OR c.name LIKE ?)"
            let like = "%" + v + "%"
            args += [like, like, like, like]
        }
        q += " ORDER BY i.issue_date DESC, i.id DESC"
        return try db.query(q, args)
    }

    // MARK: reconciliation

    public struct ReconciliationView {
        public let account: Row
        public let items: [[String: JSONValue]]
        public let balance: Int
        public let clearedBalance: Int
        public let history: [Row]
    }

    public func reconciliationView(_ aid: Int, asOf: String? = nil) throws -> ReconciliationView {
        let L = try Ledger(self)
        guard let acc = L.accounts[aid] else { throw AZError.notFound("Account not found") }
        var items: [[String: JSONValue]] = []
        for t in L.transactions {
            if let asOf, t.txnDate > asOf { continue }
            for (accId, eff) in L.accountEffects(t) where accId == aid {
                var r = L.txnRow(t)
                r["effect"] = .int(eff)
                items.append(r)
            }
        }
        items.sort { ($0["txn_date"]!.pyStr, $0["id"]!.intValue!) < ($1["txn_date"]!.pyStr, $1["id"]!.intValue!) }
        let bal = L.accountBalances(asOf: asOf)[aid]!
        return ReconciliationView(account: acc, items: items, balance: bal.balance, clearedBalance: bal.reconciledBalance,
                                  history: try db.query("SELECT * FROM reconciliations WHERE account_id = ? ORDER BY id DESC LIMIT 20", [aid]))
    }

    @discardableResult
    public func saveReconciliation(_ aid: Int, _ data: Fields) throws -> (id: Int, clearedBalance: Int, difference: Int) {
        try tx {
            guard try db.one("SELECT * FROM accounts WHERE id = ?", [aid]) != nil else { throw AZError.notFound("Account not found") }
            let stmtDate = try V.d(data, "statement_date", required: true, label: "Statement date")!
            let stmtBal = try parseAmount(data.get("statement_balance"), "Statement balance", required: true, allowNegative: true)
            let raw = data.get("cleared_ids")
            if !raw.isNull, raw.arrayValue == nil, raw.truthy { throw AZError.validation("cleared_ids must be a list") }
            let cleared = Set((raw.arrayValue ?? []).compactMap { $0.intValue })
            let ts = Dates.nowISO()
            for t in try db.query("SELECT id, reconciled, txn_date FROM transactions WHERE (account_id = ? OR to_account_id = ?) AND txn_date <= ?",
                                  [aid, aid, stmtDate]) {
                let want = cleared.contains(t.int("id")) ? 1 : 0
                if want != t.int("reconciled") {
                    try db.run("UPDATE transactions SET reconciled = ?, reconciled_on = ?, updated_at = ? WHERE id = ?",
                               [want, want == 1 ? stmtDate : nil, ts, t.int("id")])
                }
            }
            let clearedBal = try Ledger(self).accountBalances(asOf: stmtDate)[aid]!.reconciledBalance
            let diff = stmtBal - clearedBal
            let rid = try db.run("INSERT INTO reconciliations(account_id, statement_date, statement_balance, cleared_balance, difference, notes, created_at) "
                                 + "VALUES (?,?,?,?,?,?,?)", [aid, stmtDate, stmtBal, clearedBal, diff, try V.s(data, "notes", 500), ts])
            try audit("reconciliation", rid, "save", nil, ["account_id": .int(aid), "statement_date": .string(stmtDate),
                                                          "statement_balance": .int(stmtBal), "cleared_balance": .int(clearedBal),
                                                          "difference": .int(diff), "cleared_count": .int(cleared.count)])
            return (rid, clearedBal, diff)
        }
    }

    // MARK: search

    public struct SearchResults {
        public var bookings: [[String: JSONValue]] = []
        public var customers: [Row] = []
        public var suppliers: [Row] = []
        public var invoices: [Row] = []
        public var transactions: [Row] = []
    }

    public func globalSearch(_ query: String) throws -> SearchResults {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        var r = SearchResults()
        if q.count < 2 { return r }
        r.bookings = Array(try searchBookings(["q": q]).prefix(8))
        let like = "%" + q + "%"
        let digits = q.filter { $0.isNumber }
        r.customers = try db.query("SELECT id, name, phone, email FROM customers WHERE name LIKE ? OR email LIKE ? OR (? <> '' AND "
                                   + "replace(replace(replace(phone,' ',''),'-',''),'+','') LIKE ?) ORDER BY name LIMIT 8",
                                   [like, like, digits, "%" + digits + "%"])
        r.suppliers = try db.query("SELECT id, name, kind FROM suppliers WHERE name LIKE ? ORDER BY name LIMIT 5", [like])
        r.invoices = try db.query("SELECT i.id, i.number, i.doc_type, i.total, i.currency, b.ref FROM invoices i JOIN bookings b ON b.id = i.booking_id "
                                  + "WHERE i.number LIKE ? ORDER BY i.id DESC LIMIT 5", [like])
        r.transactions = try db.query("SELECT id, txn_no, kind, amount, currency, txn_date, reference FROM transactions WHERE txn_no LIKE ? OR reference LIKE ? "
                                      + "ORDER BY id DESC LIMIT 6", [like, like])
        return r
    }
}
