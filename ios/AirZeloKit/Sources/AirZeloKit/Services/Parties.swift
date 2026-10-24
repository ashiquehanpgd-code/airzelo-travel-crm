import Foundation

// Port of the customer, supplier, account, tax profile and expense category services (core/server/services.py).

let CUSTOMER_FIELDS = ["name", "phone", "alt_phone", "email", "address", "city", "state", "gstin", "passport_no",
                       "notes", "follow_up_date", "follow_up_note"]
let SUPPLIER_FIELDS = ["name", "kind", "contact_person", "phone", "email", "gstin", "notes"]
public let SUPPLIER_KINDS = ["b2b_portal", "airline", "consolidator", "hotel", "visa", "other"]
/// account_type -> the reporting kind stored in accounts.kind
public let ACCOUNT_TYPES: [String: String] = ["cash": "cash", "bank": "bank", "current": "bank", "savings": "bank", "upi": "wallet",
                                              "credit_card": "bank", "wallet": "wallet", "gateway": "gateway"]
let ACCOUNT_TYPE_ORDER = ["cash", "bank", "current", "savings", "upi", "credit_card", "wallet", "gateway"]

extension Agency {
    // MARK: customers

    func customerPayload(_ data: Fields) throws -> [String: String?] {
        [
            "name": try V.s(data, "name", 150, required: true, label: "Customer name"),
            "phone": try V.phone(data, "phone"),
            "alt_phone": try V.phone(data, "alt_phone"),
            "email": try V.email(data, "email"),
            "address": try V.s(data, "address", 500),
            "city": try V.s(data, "city", 80),
            "state": try V.s(data, "state", 80),
            "gstin": V.normCode(J(try V.s(data, "gstin", 15)), 15),
            "passport_no": try passportValue(data),
            "notes": try V.s(data, "notes", 4000),
            "follow_up_date": try V.d(data, "follow_up_date"),
            "follow_up_note": try V.s(data, "follow_up_note", 500),
        ]
    }

    /// Passport number from a payload. A value still sealed (encrypted on another device) is kept exactly as stored.
    func passportValue(_ data: Fields) throws -> String? {
        if let s = data.get("passport_no").stringValue, FieldCrypto.isSealed(s) { return s }
        return V.normCode(J(try V.s(data, "passport_no", 20)), 20)
    }

    /// The payload as stored: the passport number sealed with the device key.
    private func sealedCustomer(_ p: [String: String?]) throws -> [String: String?] {
        var s = p
        s["passport_no"] = try crypto.seal(p["passport_no"] ?? nil, column: "passport_no")
        return s
    }

    private func auditJSON(_ p: [String: String?]) -> JSONValue {
        .object(p.mapValues { J($0) })
    }

    /// A customer row with the passport number readable again.
    func openCustomer(_ r: Row) -> [String: JSONValue] {
        var o = r.json
        o["passport_no"] = J(crypto.open(r.optStr("passport_no"), column: "passport_no"))
        return o
    }

    @discardableResult
    public func createCustomer(_ data: Fields) throws -> Int {
        let p = try customerPayload(data)
        if let phone = p["phone"] ?? nil, !V.b(data, "force_duplicate") {
            if let dup = try db.one("SELECT id, name FROM customers WHERE phone = ? AND active = 1", [phone]) {
                throw AZError.conflict("A customer with this phone number already exists: \(dup.str("name"))", "duplicate_customer",
                                       ["id": .int(dup.int("id")), "name": .string(dup.str("name"))])
            }
        }
        let stored = try sealedCustomer(p)
        let ts = Dates.nowISO()
        return try tx {
            let cid = try db.run("INSERT INTO customers(\(CUSTOMER_FIELDS.joined(separator: ", ")), created_at, updated_at) VALUES ("
                                 + Array(repeating: "?", count: CUSTOMER_FIELDS.count).joined(separator: ", ") + ", ?, ?)",
                                 CUSTOMER_FIELDS.map { stored[$0] ?? nil } + [ts, ts])
            try audit("customer", cid, "create", nil, auditJSON(stored))
            return cid
        }
    }

    @discardableResult
    public func updateCustomer(_ cid: Int, _ data: Fields) throws -> Int {
        try tx {
            guard let beforeRow = try db.one("SELECT * FROM customers WHERE id = ?", [cid]) else { throw AZError.notFound("Customer not found") }
            var merged = openCustomer(beforeRow)
            for (k, v) in data where CUSTOMER_FIELDS.contains(k) { merged[k] = v }
            let p = try customerPayload(merged)
            var active = beforeRow.int("active")
            if data.contains("active") { active = V.isOff(data.get("active")) ? 0 : 1 }
            let stored = try sealedCustomer(p)
            try db.run("UPDATE customers SET " + CUSTOMER_FIELDS.map { "\($0) = ?" }.joined(separator: ", ") + ", active = ?, updated_at = ? WHERE id = ?",
                       CUSTOMER_FIELDS.map { stored[$0] ?? nil } + [active, Dates.nowISO(), cid])
            var after = auditJSON(stored).objectValue!
            after["active"] = .int(active)
            try audit("customer", cid, "update", .object(beforeRow.json), .object(after))
        }
        return cid
    }

    /// Deletes a customer with no history, otherwise deactivates them. Returns "deleted" or "deactivated".
    @discardableResult
    public func deleteCustomer(_ cid: Int) throws -> String {
        try tx {
            guard let c = try db.one("SELECT * FROM customers WHERE id = ?", [cid]) else { throw AZError.notFound("Customer not found") }
            let used = try db.scalarInt("SELECT (SELECT COUNT(*) FROM bookings WHERE customer_id = ?) + "
                                        + "(SELECT COUNT(*) FROM transactions WHERE customer_id = ?)", [cid, cid])
            if used > 0 {
                try db.run("UPDATE customers SET active = 0, updated_at = ? WHERE id = ?", [Dates.nowISO(), cid])
                try audit("customer", cid, "deactivate", .object(c.json), ["active": 0])
                return "deactivated"
            }
            try db.run("DELETE FROM customers WHERE id = ?", [cid])
            try audit("customer", cid, "delete", .object(c.json), nil)
            return "deleted"
        }
    }

    /// Customers with their balances (list_customers).
    public func listCustomers(query: String? = nil, includeInactive: Bool = false, ledger: Ledger? = nil) throws -> [[String: JSONValue]] {
        let L = try ledger ?? Ledger(self)
        let bal = L.customerBalances()
        let ql = (query ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        var out: [[String: JSONValue]] = []
        let sorted = L.customerOrder.map { L.customers[$0]! }.sorted { $0.str("name").lowercased() < $1.str("name").lowercased() }
        for c in sorted {
            if !includeInactive && !c.bool("active") { continue }
            if !ql.isEmpty && !(c.str("name").lowercased().contains(ql) || c.str("phone").contains(ql) || c.str("email").lowercased().contains(ql)) {
                continue
            }
            var r = openCustomer(c)
            let b = bal[c.int("id")]!
            for (k, v) in ["billed": b.billed, "received": b.received, "outstanding": b.outstanding, "unallocated": b.unallocated,
                           "bookings": b.bookings, "credits": b.credits, "refunded": b.refunded] { r[k] = .int(v) }
            out.append(r)
        }
        return out
    }

    public struct CustomerDetail {
        public let customer: [String: JSONValue]
        public let balance: Ledger.CustomerBalance
        public let bookings: [[String: JSONValue]]
        public let transactions: [[String: JSONValue]]
        public let tickets: [[String: JSONValue]]
    }

    public func getCustomer(_ cid: Int) throws -> CustomerDetail {
        let L = try Ledger(self)
        guard let c = L.customers[cid] else { throw AZError.notFound("Customer not found") }
        let bookings = L.bookingList.filter { $0.customerId == cid }.map { L.bookingRow($0) }
            .sorted { ($0["booking_date"]!.pyStr, $0["id"]!.intValue!) > ($1["booking_date"]!.pyStr, $1["id"]!.intValue!) }
        let txns = L.transactions.filter { $0.customerId == cid }.map { L.txnRow($0) }
            .sorted { ($0["txn_date"]!.pyStr, $0["id"]!.intValue!) > ($1["txn_date"]!.pyStr, $1["id"]!.intValue!) }
        var tickets: [[String: JSONValue]] = []
        for b in L.bookingList where b.customerId == cid {
            for p in L.passengers[b.id] ?? [] {
                tickets.append(["booking_id": .int(b.id), "ref": .string(b.ref), "pnr": J(b.pnr), "name": .string(p.str("name")),
                                "ticket_number": J(p.optStr("ticket_number")), "status": .string(b.status)])
            }
        }
        return CustomerDetail(customer: openCustomer(c), balance: L.customerBalances()[cid]!, bookings: bookings, transactions: txns, tickets: tickets)
    }

    func resolveCustomer(_ data: Fields) throws -> Int {
        if let cid = try V.i(data, "customer_id") {
            guard try db.one("SELECT id, active FROM customers WHERE id = ?", [cid]) != nil else {
                throw AZError.validation("Selected customer does not exist", "customer_id")
            }
            return cid
        }
        if let nc = data.get("new_customer").objectValue, !(nc.get("name").stringValue ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            if nc.get("phone").truthy {
                if let dup = try db.one("SELECT id FROM customers WHERE phone = ? AND active = 1",
                                        [nc.get("phone").pyStr.trimmingCharacters(in: .whitespacesAndNewlines)]) {
                    return dup.int("id")
                }
            }
            return try createCustomer(nc)
        }
        throw AZError.validation("Select a customer or enter a new customer name", "customer_id")
    }

    // MARK: suppliers

    func supplierPayload(_ data: Fields) throws -> [String: String?] {
        [
            "name": try V.s(data, "name", 150, required: true, label: "Supplier name"),
            "kind": try V.choice(data, "kind", SUPPLIER_KINDS, "b2b_portal", "Supplier type"),
            "contact_person": try V.s(data, "contact_person", 120),
            "phone": try V.phone(data, "phone"),
            "email": try V.email(data, "email"),
            "gstin": V.normCode(J(try V.s(data, "gstin", 15)), 15),
            "notes": try V.s(data, "notes", 4000),
        ]
    }

    @discardableResult
    public func createSupplier(_ data: Fields) throws -> Int {
        let p = try supplierPayload(data)
        let ts = Dates.nowISO()
        return try tx {
            if try db.one("SELECT 1 FROM suppliers WHERE name = ? COLLATE NOCASE", [p["name"]!]) != nil {
                throw AZError.conflict("A supplier named '\(p["name"]!!)' already exists", "duplicate_supplier")
            }
            let sid = try db.run("INSERT INTO suppliers(\(SUPPLIER_FIELDS.joined(separator: ", ")), created_at, updated_at) VALUES ("
                                 + Array(repeating: "?", count: SUPPLIER_FIELDS.count).joined(separator: ", ") + ", ?, ?)",
                                 SUPPLIER_FIELDS.map { p[$0] ?? nil } + [ts, ts])
            try audit("supplier", sid, "create", nil, auditJSON(p))
            return sid
        }
    }

    @discardableResult
    public func updateSupplier(_ sid: Int, _ data: Fields) throws -> Int {
        try tx {
            guard let before = try db.one("SELECT * FROM suppliers WHERE id = ?", [sid]) else { throw AZError.notFound("Supplier not found") }
            var merged = before.json
            for (k, v) in data where SUPPLIER_FIELDS.contains(k) { merged[k] = v }
            let p = try supplierPayload(merged)
            if try db.one("SELECT id FROM suppliers WHERE name = ? COLLATE NOCASE AND id <> ?", [p["name"]!, sid]) != nil {
                throw AZError.conflict("A supplier named '\(p["name"]!!)' already exists", "duplicate_supplier")
            }
            var active = before.int("active")
            if data.contains("active") { active = V.isOff(data.get("active")) ? 0 : 1 }
            try db.run("UPDATE suppliers SET " + SUPPLIER_FIELDS.map { "\($0) = ?" }.joined(separator: ", ") + ", active = ?, updated_at = ? WHERE id = ?",
                       SUPPLIER_FIELDS.map { p[$0] ?? nil } + [active, Dates.nowISO(), sid])
            var after = auditJSON(p).objectValue!
            after["active"] = .int(active)
            try audit("supplier", sid, "update", .object(before.json), .object(after))
        }
        return sid
    }

    @discardableResult
    public func deleteSupplier(_ sid: Int) throws -> String {
        try tx {
            guard let s = try db.one("SELECT * FROM suppliers WHERE id = ?", [sid]) else { throw AZError.notFound("Supplier not found") }
            let used = try db.scalarInt("SELECT (SELECT COUNT(*) FROM bookings WHERE supplier_id = ?) + "
                                        + "(SELECT COUNT(*) FROM transactions WHERE supplier_id = ?)", [sid, sid])
            if used > 0 {
                try db.run("UPDATE suppliers SET active = 0, updated_at = ? WHERE id = ?", [Dates.nowISO(), sid])
                try audit("supplier", sid, "deactivate", .object(s.json), ["active": 0])
                return "deactivated"
            }
            try db.run("DELETE FROM suppliers WHERE id = ?", [sid])
            try audit("supplier", sid, "delete", .object(s.json), nil)
            return "deleted"
        }
    }

    public func listSuppliers(includeInactive: Bool = false, ledger: Ledger? = nil) throws -> [[String: JSONValue]] {
        let L = try ledger ?? Ledger(self)
        let bal = L.supplierBalances()
        var out: [[String: JSONValue]] = []
        for s in L.supplierOrder.map({ L.suppliers[$0]! }).sorted(by: { $0.str("name").lowercased() < $1.str("name").lowercased() }) {
            if !includeInactive && !s.bool("active") { continue }
            var r = s.json
            let b = bal[s.int("id")]!
            for (k, v) in ["payable": b.payable, "credits": b.credits, "paid": b.paid, "refunds": b.refunds, "outstanding": b.outstanding,
                           "unallocated": b.unallocated, "bookings": b.bookings] { r[k] = .int(v) }
            out.append(r)
        }
        return out
    }

    // MARK: accounts

    public func listAccounts(ledger: Ledger? = nil) throws -> [[String: JSONValue]] {
        let L = try ledger ?? Ledger(self)
        let bal = L.accountBalances()
        let sorted = L.accountOrder.map { L.accounts[$0]! }.sorted {
            let a = (!$0.bool("active") ? 1 : 0, $0.str("kind"), $0.str("name").lowercased())
            let b = (!$1.bool("active") ? 1 : 0, $1.str("kind"), $1.str("name").lowercased())
            return a < b
        }
        return sorted.map { a in
            var r = a.json
            let b = bal[a.int("id")]!
            r["inflow"] = .int(b.inflow); r["outflow"] = .int(b.outflow); r["balance"] = .int(b.balance)
            r["reconciled_balance"] = .int(b.reconciledBalance)
            r["txn_count"] = .int(L.transactions.filter { $0.accountId == a.int("id") || $0.toAccountId == a.int("id") }.count)
            return r
        }
    }

    /// Keep only the last 4 digits of an account or card number; the full number is never stored.
    func last4(_ raw: JSONValue) throws -> String? {
        if raw.isNull || raw.pyStr.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
        let digits = raw.pyStr.filter { $0.isASCII && $0.isNumber }
        if digits.count < 4 { throw AZError.validation("Enter at least the last 4 digits of the account number", "account_last4") }
        return String(digits.suffix(4))
    }

    @discardableResult
    public func saveAccount(_ data: Fields, id aid: Int? = nil) throws -> Int {
        let name = try V.s(data, "name", 100, required: true, label: "Account name")!
        let accType: String = data.get("account_type").truthy ? data.get("account_type").pyStr : (data.get("kind").truthy ? data.get("kind").pyStr : "bank")
        guard let kind = ACCOUNT_TYPES[accType] else {
            throw AZError.validation("Account type must be one of: \(ACCOUNT_TYPE_ORDER.joined(separator: ", "))", "account_type")
        }
        let cur = try V.normCode(data.get("currency"), 3) ?? (setting("base_currency", "INR") ?? "INR")
        guard try db.one("SELECT 1 FROM currencies WHERE code = ?", [cur]) != nil else { throw AZError.validation("Unknown currency", "currency") }
        let opening = try parseAmount(data.get("opening_balance"), "Opening balance", allowNegative: true)
        let openingDate = try V.d(data, "opening_date")
        let details = try V.s(data, "details", 500)
        let bankName = try V.s(data, "bank_name", 100)
        let l4 = try last4(data.get("account_last4"))
        let active = V.isOff(data.get("active")) ? 0 : 1
        let ts = Dates.nowISO()
        return try tx {
            if try db.one("SELECT id FROM accounts WHERE name = ? COLLATE NOCASE AND id <> ?", [name, aid ?? 0]) != nil {
                throw AZError.conflict("An account with this name already exists", "duplicate_account")
            }
            let summary: JSONValue = ["name": .string(name), "kind": .string(kind), "account_type": .string(accType), "bank_name": J(bankName),
                                      "currency": .string(cur), "opening_balance": .int(opening), "opening_date": J(openingDate), "active": .int(active)]
            if let aid {
                guard let before = try db.one("SELECT * FROM accounts WHERE id = ?", [aid]) else { throw AZError.notFound("Account not found") }
                let hasTxn = try db.one("SELECT 1 FROM transactions WHERE account_id = ? OR to_account_id = ? LIMIT 1", [aid, aid]) != nil
                if hasTxn && cur != before.str("currency") {
                    throw AZError.validation("Currency cannot be changed after transactions are recorded", "currency")
                }
                if before.int("opening_balance") != opening && hasTxn && !V.b(data, "confirm_opening_change") {
                    throw AZError.conflict("Changing the opening balance of an account with transactions changes every running balance. Confirm to proceed.",
                                           "confirm_opening_change")
                }
                try db.run("UPDATE accounts SET name=?, kind=?, account_type=?, bank_name=?, account_last4=?, currency=?, opening_balance=?, "
                           + "opening_date=?, details=?, active=?, updated_at=? WHERE id=?",
                           [name, kind, accType, bankName, l4, cur, opening, openingDate, details, active, ts, aid])
                try audit("account", aid, "update", .object(before.json), summary)
                return aid
            }
            let id = try db.run("INSERT INTO accounts(name, kind, account_type, bank_name, account_last4, currency, opening_balance, opening_date, "
                                + "details, active, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                                [name, kind, accType, bankName, l4, cur, opening, openingDate, details, active, ts, ts])
            try audit("account", id, "create", nil, summary)
            return id
        }
    }

    public func setAccountActive(_ aid: Int, _ active: Bool) throws {
        try tx {
            guard let before = try db.one("SELECT id, name, active FROM accounts WHERE id = ?", [aid]) else { throw AZError.notFound("Account not found") }
            if !active, try db.scalarInt("SELECT COUNT(*) FROM accounts WHERE active = 1 AND id <> ?", [aid]) == 0 {
                throw AZError.validation("Keep at least one active account")
            }
            try db.run("UPDATE accounts SET active = ?, updated_at = ? WHERE id = ?", [active ? 1 : 0, Dates.nowISO(), aid])
            try audit("account", aid, active ? "activate" : "deactivate", ["active": .int(before.int("active"))], ["active": .int(active ? 1 : 0)])
        }
    }

    // MARK: tax profiles

    public func listTaxProfiles() throws -> [Row] { try db.query("SELECT * FROM tax_profiles ORDER BY active DESC, id") }

    @discardableResult
    public func saveTaxProfile(_ data: Fields, id pid: Int? = nil) throws -> Int {
        let p: [String: SQLValue] = [
            "name": .text(try V.s(data, "name", 120, required: true, label: "Tax profile name")!),
            "rate": .text(try parsePercent(data.get("rate"), "Rate")),
            "basis": .text(try V.choice(data, "basis", ["none", "service_charge", "markup", "service_and_markup", "selling_price", "custom"], "none", "Taxable basis")),
            "split": .text(try V.choice(data, "split", ["none", "cgst_sgst", "igst"], "none", "Tax split")),
            "sac_code": try V.s(data, "sac_code", 12).sqlValue,
            "notes": try V.s(data, "notes", 1000).sqlValue,
            "active": .int(V.isOff(data.get("active")) ? 0 : 1),
        ]
        let keys = ["name", "rate", "basis", "split", "sac_code", "notes", "active"]
        let pj: JSONValue = .object(p.mapValues { JSONValue($0) })
        return try tx {
            if try db.one("SELECT id FROM tax_profiles WHERE name = ? AND id <> ?", [p["name"]!, pid ?? 0]) != nil {
                throw AZError.conflict("A tax profile with this name already exists")
            }
            if let pid {
                guard let before = try db.one("SELECT * FROM tax_profiles WHERE id = ?", [pid]) else { throw AZError.notFound("Tax profile not found") }
                try db.run("UPDATE tax_profiles SET name=?, rate=?, basis=?, split=?, sac_code=?, notes=?, active=? WHERE id=?", keys.map { p[$0]! } + [pid])
                try audit("tax_profile", pid, "update", .object(before.json), pj)
                return pid
            }
            let id = try db.run("INSERT INTO tax_profiles(name, rate, basis, split, sac_code, notes, active) VALUES (?,?,?,?,?,?,?)", keys.map { p[$0]! })
            try audit("tax_profile", id, "create", nil, pj)
            return id
        }
    }

    /// Tax for a booking: from its tax profile, or entered manually (tax_override).
    func computeTax(_ data: Fields, _ amounts: [String: Int]) throws -> [String: SQLValue] {
        let pid = try V.i(data, "tax_profile_id")
        func blank(_ k: String) -> Bool { data.get(k).isNull || data.get(k).pyStr.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard let pid, pid != 0 else {
            if V.b(data, "tax_override") {
                let taxable = try parseAmount(data.get("taxable_value"), "Taxable value")
                let rate = try parsePercent(data.get("tax_rate"), "Tax rate")
                let tax = !blank("tax_amount") ? try parseAmount(data.get("tax_amount"), "Tax amount") : percentOf(taxable, rate)
                return ["tax_profile_id": .null, "tax_override": .int(1), "taxable_value": .int(Int64(taxable)), "tax_rate": .text(rate),
                        "tax_amount": .int(Int64(tax)), "tax_split": .text(try V.choice(data, "tax_split", ["none", "cgst_sgst", "igst"], "none"))]
            }
            return ["tax_profile_id": .null, "tax_override": .int(0), "taxable_value": .int(0), "tax_rate": .text("0"), "tax_amount": .int(0),
                    "tax_split": .text("none")]
        }
        guard let prof = try db.one("SELECT * FROM tax_profiles WHERE id = ?", [pid]) else {
            throw AZError.validation("Selected tax profile does not exist", "tax_profile_id")
        }
        let markup = max(amounts["selling_price"]! - amounts["purchase_cost"]!, 0)
        let taxable: Int, rate: String, tax: Int, override: Int
        if V.b(data, "tax_override") {
            taxable = try parseAmount(data.get("taxable_value"), "Taxable value")
            rate = try parsePercent(!blank("tax_rate") ? data.get("tax_rate") : .string(prof.str("rate")), "Tax rate")
            tax = !blank("tax_amount") ? try parseAmount(data.get("tax_amount"), "Tax amount") : percentOf(taxable, rate)
            override = 1
        } else {
            switch prof.str("basis") {
            case "none": taxable = 0
            case "service_charge": taxable = amounts["service_charge"]!
            case "markup": taxable = markup
            case "service_and_markup": taxable = amounts["service_charge"]! + markup
            case "selling_price": taxable = amounts["selling_price"]!
            default: taxable = try parseAmount(data.get("taxable_value"), "Taxable value")
            }
            rate = prof.str("rate")
            tax = percentOf(taxable, rate)
            override = 0
        }
        return ["tax_profile_id": .int(Int64(pid)), "tax_override": .int(Int64(override)), "taxable_value": .int(Int64(taxable)),
                "tax_rate": .text(rate), "tax_amount": .int(Int64(tax)), "tax_split": .text(prof.str("split"))]
    }

    // MARK: expense categories

    public func listExpenseCategories() throws -> [Row] { try db.query("SELECT * FROM expense_categories ORDER BY name") }

    @discardableResult
    public func saveExpenseCategory(_ data: Fields) throws -> String {
        let name = try V.s(data, "name", 80, required: true, label: "Category name")!
        let active = V.isOff(data.get("active")) ? 0 : 1
        try tx {
            try db.run("INSERT INTO expense_categories(name, active) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET active = excluded.active", [name, active])
            try audit("expense_category", name, "save", nil, ["name": .string(name), "active": .int(active)])
        }
        return name
    }
}
