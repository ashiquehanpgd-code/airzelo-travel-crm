import Foundation

/// One agency's books on this device: its SQLite database (the desktop schema, v4) plus the services that
/// change it. Port of core/server/db.py and the shared parts of core/server/services.py.
///
/// All work on an Agency happens on one thread (the app uses it from the main actor). Every write goes
/// through `tx`, so it commits completely or not at all, exactly like the desktop app.
public final class Agency {
    public let db: SQLiteDB
    public let url: URL
    public let crypto: FieldCrypto
    private var txDepth = 0

    public static let schemaVersion = GeneratedSchema.version

    /// Open (and if new, create) an agency database.
    /// - `protection`: iOS Data Protection class for the database files (see `FileSecurity`).
    public init(url: URL, crypto: FieldCrypto, protect: Bool = true) throws {
        self.url = url
        self.crypto = crypto
        let fresh = !FileManager.default.fileExists(atPath: url.path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        db = try SQLiteDB(path: url.path)
        try db.exec("PRAGMA foreign_keys = ON")
        try db.exec("PRAGMA journal_mode = WAL")
        try db.exec("PRAGMA synchronous = FULL")
        try db.exec("PRAGMA secure_delete = ON")  // freed pages are overwritten, so deleted records do not linger in the file
        let existing = try currentVersion()
        if fresh || existing == 0 {
            try createSchema()
        }
        let v = try currentVersion()
        if v > Agency.schemaVersion {
            db.close()
            throw AZError.validation("These books were saved by a newer AirZelo (data version \(v)). Update the app to open them.")
        }
        if v < Agency.schemaVersion {
            db.close()
            throw AZError.validation("These books use an older data version (\(v)). Open them once in the AirZelo desktop app "
                                     + "to upgrade them, then make a new backup and import that.")
        }
        try seed()
        if protect { FileSecurity.protectDatabaseFiles(at: url) }
    }

    public func close() { db.close() }

    // MARK: schema

    func currentVersion() throws -> Int {
        if try db.one("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'") == nil { return 0 }
        return try db.scalarInt("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")
    }

    private func createSchema() throws {
        try db.exec("BEGIN IMMEDIATE")
        do {
            if try db.one("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'") != nil {
                try db.exec("DROP TABLE schema_migrations")  // an empty, half-created file
            }
            for s in GeneratedSchema.statements { try db.exec(s) }
            // rows written by the desktop migrations themselves, so a new iPhone agency starts exactly like a new desktop one
            for row in try JSONValue.parse(GeneratedSchema.migrationDataJSON).arrayValue ?? [] {
                let r = row.arrayValue ?? []
                let cols = (r[1].arrayValue ?? []).compactMap { $0.stringValue }
                let vals: [SQLBindable] = (r[2].arrayValue ?? []).map { v -> SQLBindable in
                    switch v { case .int(let i): return i; case .string(let t): return t; case .double(let d): return d; default: return SQLValue.null }
                }
                try db.run("INSERT OR IGNORE INTO \(r[0].stringValue!)(" + cols.joined(separator: ", ") + ") VALUES ("
                           + Array(repeating: "?", count: cols.count).joined(separator: ", ") + ")", vals)
            }
            let ts = Dates.nowISO()
            for (v, name) in GeneratedSchema.migrations {
                try db.run("INSERT INTO schema_migrations(version, name, applied_at) VALUES (?, ?, ?)", [v, name, ts])
            }
            try db.exec("COMMIT")
        } catch {
            try? db.exec("ROLLBACK")
            throw error
        }
    }

    private func seed() throws {
        let seed = try JSONValue.parse(GeneratedSchema.seedJSON)
        try tx {
            for pair in seed["settings"].arrayValue ?? [] {
                let kv = pair.arrayValue ?? []
                try db.run("INSERT OR IGNORE INTO settings(key, value) VALUES (?, ?)", [kv[0].stringValue, kv[1].stringValue ?? ""])
            }
            for c in seed["currencies"].arrayValue ?? [] {
                let a = c.arrayValue ?? []
                try db.run("INSERT OR IGNORE INTO currencies(code, name, symbol) VALUES (?, ?, ?)",
                           [a[0].stringValue, a[1].stringValue, a[2].stringValue])
            }
            if try db.scalarInt("SELECT COUNT(*) FROM expense_categories") == 0 {
                for c in seed["expense_categories"].arrayValue ?? [] {
                    try db.run("INSERT INTO expense_categories(name) VALUES (?)", [c.stringValue])
                }
            }
            if try db.scalarInt("SELECT COUNT(*) FROM tax_profiles") == 0 {
                for p in seed["tax_profiles"].arrayValue ?? [] {
                    let a = (p.arrayValue ?? []).map { $0.stringValue }
                    try db.run("INSERT INTO tax_profiles(name, rate, basis, split, sac_code, notes) VALUES (?,?,?,?,?,?)",
                               [a[0], a[1], a[2], a[3], a[4], a[5]])
                }
            }
            if try db.scalarInt("SELECT COUNT(*) FROM accounts") == 0 {
                let ts = Dates.nowISO()
                try db.run("INSERT INTO accounts(name, kind, account_type, currency, opening_balance, opening_date, details, created_at, updated_at) "
                           + "VALUES ('Cash in hand', 'cash', 'cash', 'INR', 0, NULL, 'Set the opening balance in Accounts.', ?, ?)", [ts, ts])
            }
        }
    }

    // MARK: transactions

    /// Atomic write transaction. Nested calls join the outer transaction (db.tx in the desktop app).
    @discardableResult
    public func tx<T>(_ body: () throws -> T) throws -> T {
        if txDepth > 0 || db.inTransaction {
            txDepth += 1
            defer { txDepth -= 1 }
            return try body()
        }
        try db.exec("BEGIN IMMEDIATE")
        txDepth = 1
        do {
            let r = try body()
            txDepth = 0
            try db.exec("COMMIT")
            return r
        } catch {
            txDepth = 0
            try? db.exec("ROLLBACK")
            throw Agency.translate(error)
        }
    }

    /// SQLite trigger refusals ("Posted transactions cannot be deleted...") reach the user as validation messages.
    static func translate(_ error: Error) -> Error {
        if let e = error as? SQLiteError, e.code == 19 /* SQLITE_CONSTRAINT */ {
            let msg = e.message.components(separatedBy: " in: ").first ?? e.message
            return AZError.validation(msg)
        }
        return error
    }

    // MARK: shared helpers

    public func setting(_ key: String, _ dflt: String? = nil) throws -> String? {
        try db.one("SELECT value FROM settings WHERE key = ?", [key])?.optStr("value") ?? dflt
    }

    public func allSettings() throws -> [String: String] {
        var out: [String: String] = [:]
        for r in try db.query("SELECT key, value FROM settings") { out[r.str("key")] = r.str("value") }
        return out
    }

    public var baseCurrency: String { (try? setting("base_currency", "INR")) ?? "INR" }

    /// Must be called inside a write transaction.
    func nextCounter(_ name: String) throws -> Int {
        guard let r = try db.one("SELECT value FROM counters WHERE name = ?", [name]) else {
            try db.run("INSERT INTO counters(name, value) VALUES (?, 1)", [name])
            return 1
        }
        let v = r.int("value") + 1
        try db.run("UPDATE counters SET value = ? WHERE name = ?", [v, name])
        return v
    }

    func audit(_ entity: String, _ entityId: CustomStringConvertible?, _ action: String, _ before: JSONValue? = nil, _ after: JSONValue? = nil) throws {
        try db.run("INSERT INTO audit_log(at, entity, entity_id, action, before_json, after_json) VALUES (?,?,?,?,?,?)",
                   [Dates.nowISO(), entity, entityId.map { $0.description }, action,
                    before.map { JSONValue.serialize($0) }, after.map { JSONValue.serialize($0) }])
    }

    func history(_ bookingId: Int, _ action: String, _ detail: String = "") throws {
        try db.run("INSERT INTO booking_history(booking_id, at, action, detail) VALUES (?,?,?,?)", [bookingId, Dates.nowISO(), action, detail])
    }

    func rowJSON(_ sql: String, _ args: [SQLBindable]) throws -> JSONValue? {
        try db.one(sql, args).map { .object($0.json) }
    }

    public func listAudit(entity: String? = nil, entityId: String? = nil, limit: Int = 300) throws -> [Row] {
        var q = "SELECT * FROM audit_log"
        var args: [SQLBindable] = []
        var cond: [String] = []
        if let entity { cond.append("entity = ?"); args.append(entity) }
        if let entityId { cond.append("entity_id = ?"); args.append(entityId) }
        if !cond.isEmpty { q += " WHERE " + cond.joined(separator: " AND ") }
        q += " ORDER BY id DESC LIMIT ?"
        args.append(min(limit, 2000))
        return try db.query(q, args)
    }

    /// PRAGMA integrity_check + foreign_key_check: "ok" when the file is sound.
    public func integrityCheck() throws -> String {
        let r = try db.one("PRAGMA integrity_check")?.optStr("integrity_check") ?? "unknown"
        if r != "ok" { return r }
        if try !db.query("PRAGMA foreign_key_check").isEmpty { return "foreign key check failed" }
        return "ok"
    }
}

// MARK: - settings and currencies (services.py)

extension Agency {
    static let editableSettings = ["business_name", "business_tagline", "business_address", "business_phone", "business_email",
                                   "business_gstin", "business_state", "invoice_prefix", "credit_note_prefix", "invoice_terms",
                                   "invoice_footer", "auto_backup_hours", "backup_keep", "session_hours",
                                   "pay_account_name", "pay_account_number", "pay_ifsc", "pay_branch", "pay_bank_name",
                                   "pay_upi_number", "pay_upi_apps",
                                   "legal_name", "business_country", "business_website", "business_registration", "receipt_footer",
                                   "brand_color", "booking_prefix", "receipt_prefix", "date_format", "currency_display",
                                   "report_default_period", "setup_completed"]
    static let choiceSettings: [String: [String]] = [
        "date_format": ["DD/MM/YYYY", "DD MMM YYYY", "MM/DD/YYYY", "YYYY-MM-DD"],
        "currency_display": ["symbol", "code"],
        "report_default_period": ["month", "fy", "year", "week", "today"],
        "setup_completed": ["0", "1"]]
    static let prefixSettings = ["invoice_prefix", "credit_note_prefix", "booking_prefix", "receipt_prefix"]
    static let formatRules: [String: (String, String)] = [
        "business_email": (#"^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$"#, "Enter a valid email address"),
        "business_phone": (#"^[+0-9][0-9 ()\-]{5,24}$"#, "Enter a valid phone number (digits, spaces, + ( ) -)"),
        "business_website": (#"^(https?://)?[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+(/\S*)?$"#, "Enter a website such as www.example.com"),
        "brand_color": (#"^#[0-9A-Fa-f]{6}$"#, "Brand colour must be a hex colour such as #1B3FCC"),
        "business_gstin": (#"^[0-9]{2}[A-Z0-9]{10}[0-9A-Z]{3}$"#, "GSTIN must be 15 characters, e.g. 32ABCDE1234F1Z5")]
    static let paymentFormats: [String: (String, String)] = [
        "pay_account_number": (#"^\d{9,18}$"#, "Account number must be 9 to 18 digits"),
        "pay_ifsc": (#"^[A-Z]{4}0[A-Z0-9]{6}$"#, "IFSC code must look like ABCD0123456 (11 characters)"),
        "pay_upi_number": (#"^\d{10}$"#, "UPI payment number must be a 10-digit mobile number")]

    @discardableResult
    public func updateSettings(_ data: Fields) throws -> [String: String] {
        func matches(_ s: String, _ p: String) -> Bool { s.range(of: p, options: .regularExpression) != nil }
        try tx {
            let before = try allSettings()
            var changes: Fields = [:]
            for k in Agency.editableSettings where data.contains(k) {
                var val = data.get(k).isNull ? "" : data.get(k).pyStr.trimmingCharacters(in: .whitespacesAndNewlines)
                if val.unicodeScalars.count > 2000 { throw AZError.validation("\(k) is too long", k) }
                if ["auto_backup_hours", "backup_keep", "session_hours"].contains(k) {
                    guard let n = Int(val) else { throw AZError.validation("\(k) must be a whole number", k) }
                    let lim = ["auto_backup_hours": (0, 720), "backup_keep": (3, 1000), "session_hours": (1, 72)][k]!
                    if !(lim.0...lim.1).contains(n) { throw AZError.validation("\(k) must be between \(lim.0) and \(lim.1)", k) }
                    val = String(n)
                }
                if ["pay_ifsc", "pay_account_name", "business_gstin"].contains(k) { val = val.uppercased() }
                if k == "business_name" && val.isEmpty { throw AZError.validation("The display name cannot be empty", k) }
                if let opts = Agency.choiceSettings[k], !opts.contains(val) {
                    throw AZError.validation("\(V.capitalize(k.replacingOccurrences(of: "_", with: " "))) must be one of: \(opts.joined(separator: ", "))", k)
                }
                if Agency.prefixSettings.contains(k) && !matches(val, #"^[A-Za-z0-9][A-Za-z0-9/_\-]{0,11}$"#) {
                    throw AZError.validation("Prefix must be 1-12 letters, digits, / _ or -, e.g. INV/", k)
                }
                if let (p, msg) = Agency.formatRules[k], !val.isEmpty, !matches(val, p) { throw AZError.validation(msg, k) }
                if ["pay_account_number", "pay_upi_number"].contains(k) { val = val.replacingOccurrences(of: " ", with: "") }
                if let (p, msg) = Agency.paymentFormats[k], !val.isEmpty, !matches(val, p) { throw AZError.validation(msg, k) }
                if before[k] != val {
                    try db.run("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", [k, val])
                    changes[k] = .string(val)
                }
            }
            if !changes.isEmpty {
                var b: Fields = [:]
                for k in changes.keys { b[k] = J(before[k]) }
                try audit("settings", nil, "update", .object(b), .object(changes))
            }
        }
        return try allSettings()
    }

    public func listCurrencies() throws -> [Row] {
        try db.query("SELECT * FROM currencies ORDER BY CASE code WHEN 'INR' THEN 0 WHEN 'SAR' THEN 1 ELSE 2 END, code")
    }

    @discardableResult
    public func saveCurrency(_ data: Fields) throws -> Row? {
        let code = V.normCode(data.get("code"), 3)
        guard let code, code.count == 3, code.allSatisfy({ $0.isLetter }) else {
            throw AZError.validation("Currency code must be 3 letters (e.g. SAR)", "code")
        }
        let name = try V.s(data, "name", 60, required: true)!
        let symbol = try V.s(data, "symbol", 8) ?? code
        let active = V.isOff(data.get("active")) ? 0 : 1
        try tx {
            let before = try rowJSON("SELECT * FROM currencies WHERE code = ?", [code])
            let baseCode = try setting("base_currency")
            if before != nil, code == baseCode, active == 0 {
                throw AZError.validation("The reporting currency cannot be deactivated")
            }
            try db.run("INSERT INTO currencies(code, name, symbol, active) VALUES (?,?,?,?) "
                       + "ON CONFLICT(code) DO UPDATE SET name=excluded.name, symbol=excluded.symbol, active=excluded.active",
                       [code, name, symbol, active])
            try audit("currency", code, before != nil ? "update" : "create", before,
                      ["code": .string(code), "name": .string(name), "symbol": .string(symbol), "active": .int(active)])
        }
        return try db.one("SELECT * FROM currencies WHERE code = ?", [code])
    }

    /// (currency, rate as text) for a payload; the reporting currency always has rate "1".
    func currencyAndRate(_ data: Fields, curKey: String = "currency", rateKey: String = "exchange_rate") throws -> (String, String) {
        let base = try setting("base_currency", "INR") ?? "INR"
        let cur = V.normCode(data.get(curKey), 3) ?? base
        guard try db.one("SELECT * FROM currencies WHERE code = ?", [cur]) != nil else {
            throw AZError.validation("Unknown currency \(cur) - add it in Settings first", curKey)
        }
        if cur == base { return (cur, "1") }
        return (cur, try parseRate(data.get(rateKey), "Exchange rate to \(base)"))
    }
}
