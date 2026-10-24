import Foundation
import AirZeloKit

/// Cross-checks against the desktop app (fixture from ios/tools/parity.py).
enum ParityChecks {
    static let cases: [CheckSuite.Case] = [
        ("parity: iPhone engine reads desktop data", readDesktopDatabase),
        ("parity: replayed operations match desktop", replayOperations),
    ]

    struct Fixture {
        let today: String
        let ops: [JSONValue]
        let snapshot: JSONValue
        let dbURL: URL
    }

    static func load(_ options: CheckOptions) throws -> Fixture {
        guard let dir = options.parityDir else { throw CheckSkipped(reason: "no --parity folder (run ios/tools/parity.py first)") }
        let f = try JSONValue.parse(Data(contentsOf: dir.appendingPathComponent("fixture.json")))
        return Fixture(today: f["today"].stringValue ?? "2026-10-25", ops: f["ops"].arrayValue ?? [], snapshot: f["snapshot"],
                       dbURL: dir.appendingPathComponent("desktop.db"))
    }

    /// 1. The desktop's own database, read by the iPhone engine, must give exactly the desktop's figures.
    static func readDesktopDatabase(_ options: CheckOptions, _ log: CheckLog) throws {
        let fx = try load(options)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("az-parity-read-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("agency.db")
        try FileManager.default.copyItem(at: fx.dbURL, to: url)
        pinClock(fx.today)
        defer { unpinClock() }
        let agency = try Agency(url: url, crypto: .ephemeral(), protect: false)
        defer { agency.close() }
        let snap = try snapshot(agency, today: fx.today)
        let diffs = diff(snap, fx.snapshot)
        log.note("compared \(count(fx.snapshot)) values: \(fx.snapshot["bookings"].objectValue?.count ?? 0) bookings, "
                 + "\(fx.snapshot["reports"].objectValue?.count ?? 0) report variants, \(fx.snapshot["statements"].objectValue?.count ?? 0) statements")
        try expect(diffs.isEmpty, "\(diffs.count) differences from the desktop figures:\n" + diffs.prefix(25).joined(separator: "\n"))
    }

    /// 2. The same operations through the iPhone services: same results, same refusals, same books.
    static func replayOperations(_ options: CheckOptions, _ log: CheckLog) throws {
        let fx = try load(options)
        pinClock(fx.today)
        defer { unpinClock() }
        let t = try TempAgency()
        let a = t.agency
        var mismatches: [String] = []
        for (i, op) in fx.ops.enumerated() {
            let name = op["op"].stringValue!
            let args = op["args"].arrayValue ?? []
            let got = outcome { try apply(a, name, args) }
            let want = op["outcome"]
            if got != want {
                mismatches.append("op #\(i) \(name)\(JSONValue.serialize(.array(args)).prefix(160)):\n    desktop \(JSONValue.serialize(want))\n    iPhone  \(JSONValue.serialize(got))")
                if mismatches.count >= 10 { break }
            }
        }
        log.note("\(fx.ops.count) operations replayed, \(fx.ops.filter { !$0["outcome"]["error"].isNull }.count) of them refused on both")
        try expect(mismatches.isEmpty, "\(mismatches.count) operation outcomes differ:\n" + mismatches.joined(separator: "\n"))
        let diffs = diff(try snapshot(a, today: fx.today), fx.snapshot)
        try expect(diffs.isEmpty, "\(diffs.count) differences after replay:\n" + diffs.prefix(25).joined(separator: "\n"))
    }

    static func outcome(_ body: () throws -> JSONValue) -> JSONValue {
        do { return ["ok": try body()] } catch let e as AZError { return ["error": .string(e.code), "message": .string(e.message)] }
        catch { return ["error": "exception", "message": .string("\(error)")] }
    }

    static func fields(_ v: JSONValue) -> Fields { v.objectValue ?? [:] }
    static func id(_ v: JSONValue) -> Int? { v.intValue }

    /// One desktop service call, by name, through the iPhone services; results normalised like parity.py does.
    static func apply(_ a: Agency, _ op: String, _ x: [JSONValue]) throws -> JSONValue {
        switch op {
        case "update_settings": try a.updateSettings(fields(x[0])); return "ok"
        case "save_currency": try a.saveCurrency(fields(x[0])); return "ok"
        case "save_account": return .int(try a.saveAccount(fields(x[0]), id: id(x[1])))
        case "set_account_active": try a.setAccountActive(id(x[0])!, x[1].truthy); return x[0]
        case "save_tax_profile": return .int(try a.saveTaxProfile(fields(x[0]), id: id(x[1])))
        case "save_expense_category": return .string(try a.saveExpenseCategory(fields(x[0])))
        case "create_supplier": return .int(try a.createSupplier(fields(x[0])))
        case "update_supplier": try a.updateSupplier(id(x[0])!, fields(x[1])); return "ok"
        case "delete_supplier": return .string(try a.deleteSupplier(id(x[0])!))
        case "create_customer": return .int(try a.createCustomer(fields(x[0])))
        case "update_customer": try a.updateCustomer(id(x[0])!, fields(x[1])); return "ok"
        case "delete_customer": return .string(try a.deleteCustomer(id(x[0])!))
        case "save_booking": return .int(try a.saveBooking(fields(x[0]), id: id(x[1])))
        case "set_booking_status": return .int(try a.setBookingStatus(id(x[0])!, x[1].stringValue!))
        case "delete_booking": return .string(try a.deleteBooking(id(x[0])!))
        case "create_transaction":
            let r = try a.createTransaction(fields(x[0])); return [.int(r.id), .bool(r.replayed)]
        case "reverse_transaction": return .int(try a.reverseTransaction(id(x[0])!, fields(x[1])))
        case "correct_transaction":
            let r = try a.correctTransaction(id(x[0])!, fields(x[1])); return [.int(r.id), .string(r.outcome)]
        case "update_transaction_meta": return .int(try a.updateTransactionMeta(id(x[0])!, fields(x[1])))
        case "set_allocations": return .int(try a.setAllocations(id(x[0])!, fields(x[1])))
        case "create_adjustment": return .int(try a.createAdjustment(id(x[0])!, fields(x[1])))
        case "reverse_adjustment": return .int(try a.reverseAdjustment(id(x[0])!, fields(x[1])))
        case "correct_adjustment": return .int(try a.correctAdjustment(id(x[0])!, fields(x[1])))
        case "refund_booking":
            let r = try a.refundBooking(id(x[0])!, fields(x[1]))
            return ["adjustment_id": .int(r.adjustmentId),
                    "transactions": r.replayed ? .null : .object(r.transactions.mapValues { .int($0) }),
                    "gross_profit": .int(r.grossProfit), "customer_outstanding": .int(r.customerOutstanding),
                    "supplier_outstanding": .int(r.supplierOutstanding), "payment_status": .string(r.paymentStatus),
                    "idempotent_replay": .bool(r.replayed)]
        case "create_invoice": return .int(try a.createInvoice(id(x[0])!, fields(x[1])))
        case "create_credit_note": return .int(try a.createCreditNote(id(x[0])!, fields(x[1])))
        case "cancel_invoice": return .int(try a.cancelInvoice(id(x[0])!, fields(x[1])))
        case "save_reconciliation":
            let r = try a.saveReconciliation(id(x[0])!, fields(x[1])); return [.int(r.id), .int(r.clearedBalance), .int(r.difference)]
        default: throw CheckFailure(message: "parity op not mapped: \(op)")
        }
    }

    // MARK: snapshot (mirror of parity.py snapshot())

    static let paramSets: [[String: String]] = [[:], ["from": "2026-03-01", "to": "2026-06-30"], ["currency": "SAR"], ["status": "ticketed"],
                                                ["airline": "emirates"], ["service_type": "flight", "from": "2026-07-01"]]
    static let volatile: Set<String> = ["created_at", "updated_at", "uid", "applied_at", "at"]
    static let skipTables: Set<String> = ["audit_log", "sessions", "owner", "sqlite_sequence"]

    static func key(_ p: [String: String]) -> String { JSONValue.serialize(.object(p.mapValues { .string($0) })) }

    static func snapshot(_ a: Agency, today: String) throws -> JSONValue {
        let L = try Ledger(a)
        var s: [String: JSONValue] = [:]
        var bookings: [String: JSONValue] = [:]
        for b in L.bookingList {
            bookings[String(b.id)] = ["calc": b.calc.json, "airlines": .array(b.airlines.map { .string($0) }), "route": .string(b.route),
                                      "pax_count": .int(b.paxCount), "lead_pax": .string(b.leadPax), "customer_name": .string(b.customerName),
                                      "supplier_name": .string(b.supplierName)]
        }
        s["bookings"] = .object(bookings)
        var filters = paramSets
        if let sup = try a.db.one("SELECT id FROM suppliers ORDER BY id LIMIT 1") { filters.append(["supplier_id": String(sup.int("id"))]) }
        if let cus = try a.db.one("SELECT id FROM customers ORDER BY id LIMIT 1") { filters.append(["customer_id": String(cus.int("id"))]) }
        for m in 1...12 { filters.append(["from": String(format: "2026-%02d-01", m), "to": String(format: "2026-%02d-28", m)]) }
        var periods: [String: JSONValue] = [:]
        for f in filters {
            let p = ReportParams(f)
            periods[key(f)] = L.periodSummary(f["from"], f["to"], LedgerFilter(customerId: p["customer_id"], supplierId: p["supplier_id"],
                                                                                currency: p["currency"], status: p["status"],
                                                                                serviceType: p["service_type"], airline: p["airline"])).summaryJSON
        }
        s["periods"] = .object(periods)
        s["customer_balances"] = .object(Dictionary(uniqueKeysWithValues: L.customerBalances().map { (String($0.key), $0.value.json) }))
        s["supplier_balances"] = .object(Dictionary(uniqueKeysWithValues: L.supplierBalances().map { (String($0.key), $0.value.json) }))
        s["account_balances"] = .object(Dictionary(uniqueKeysWithValues: L.accountBalances().map { (String($0.key), $0.value.json) }))
        s["account_balances_asof"] = .object(Dictionary(uniqueKeysWithValues: L.accountBalances(asOf: "2026-06-30").map { (String($0.key), $0.value.json) }))
        var reports: [String: JSONValue] = [:]
        for name in REPORT_NAMES {
            var sets = filters
            switch name {
            case "cashbook":
                sets = [[:], ["from": "2026-04-01", "to": "2026-08-31"]] + L.accountOrder.map { ["account_id": String($0), "from": "2026-04-01"] }
                    + L.accountOrder.map { ["account_id": String($0), "kind": "customer_receipt,expense"] }
            case "customer_outstanding", "supplier_outstanding": sets = [[:], ["show": "all"]]
            case "monthly": sets = [["year": "2026"], ["year": "2027"]]
            case "yearly": sets = [[:], ["basis": "fy"]]
            case "expenses": sets = [[:], ["from": "2026-03-01", "to": "2026-09-30"], ["category": "Rent"]]
            default: break
            }
            for p in sets { reports[name + "|" + key(p)] = try L.report(name, ReportParams(p)).json }
        }
        s["reports"] = .object(reports)
        var statements: [String: JSONValue] = [:]
        for cid in L.customerOrder {
            statements["c\(cid)"] = try L.customerStatement(cid).json
            statements["c\(cid):range"] = try L.customerStatement(cid, from: "2026-04-01", to: "2026-09-30").json
        }
        for sid in L.supplierOrder {
            statements["s\(sid)"] = try L.supplierStatement(sid).json
            statements["s\(sid):range"] = try L.supplierStatement(sid, from: "2026-04-01", to: "2026-09-30").json
        }
        s["statements"] = .object(statements)
        s["dashboard"] = L.dashboard(from: "2026-10-01", to: "2026-10-31", today: today).json
        s["dashboard_year"] = L.dashboard(from: "2026-01-01", to: "2026-12-31", today: today).json
        var search: [String: JSONValue] = [:]
        for q in ["ahmed", "ek", "9", "pb-26", "065", "zz"] {
            search[q] = .array(try a.searchBookings(["q": q], ledger: L).map { $0["id"]! })
        }
        s["search"] = .object(search)
        s["tables"] = try tableDump(a)
        return .object(s)
    }

    static func tableDump(_ a: Agency) throws -> JSONValue {
        var out: [String: JSONValue] = [:]
        for t in try a.db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name") {
            let name = t.str("name")
            if skipTables.contains(name) { continue }
            var rows: [JSONValue] = []
            for r in try a.db.query("SELECT * FROM \"\(name)\" ORDER BY rowid") {
                var d = r.json
                for k in volatile { d.removeValue(forKey: k) }
                for k in ["lines_json", "tax_json"] where d[k] != nil && d[k] != .null {
                    d[k] = try JSONValue.parse(d[k]!.stringValue!)
                }
                if d["passport_no"] != nil { d["passport_no"] = J(a.crypto.open(r.optStr("passport_no"), column: "passport_no")) }
                rows.append(.object(d))
            }
            if name == "booking_history" {
                // the desktop writes some history lines while iterating a Python set (hash order), so compare them unordered
                rows = rows.map { r -> JSONValue in var o = r.objectValue!; o.removeValue(forKey: "id"); return .object(o) }
                    .sorted { JSONValue.serialize($0) < JSONValue.serialize($1) }
            }
            out[name] = .array(rows)
        }
        return .object(out)
    }

    // MARK: comparison

    static func count(_ v: JSONValue) -> Int {
        switch v {
        case .array(let a): return a.reduce(0) { $0 + count($1) }
        case .object(let o): return o.values.reduce(0) { $0 + count($1) }
        default: return 1
        }
    }

    static func diff(_ got: JSONValue, _ want: JSONValue, _ path: String = "", into out: inout [String]) {
        if out.count > 200 { return }
        switch (got, want) {
        case (.object(let g), .object(let w)):
            for k in Set(g.keys).union(w.keys).sorted() {
                guard let gv = g[k] else { out.append("\(path)/\(k): missing on iPhone (desktop \(short(w[k]!)))"); continue }
                guard let wv = w[k] else { out.append("\(path)/\(k): extra on iPhone (\(short(gv)))"); continue }
                diff(gv, wv, path + "/" + k, into: &out)
            }
        case (.array(let g), .array(let w)):
            if g.count != w.count { out.append("\(path): \(g.count) items on iPhone, \(w.count) on desktop") }
            for i in 0..<min(g.count, w.count) { diff(g[i], w[i], "\(path)[\(i)]", into: &out) }
        case (.double(let g), .int(let w)) where g == Double(w): return
        case (.int(let g), .double(let w)) where Double(g) == w: return
        default:
            if got != want { out.append("\(path): iPhone \(short(got)) vs desktop \(short(want))") }
        }
    }

    static func diff(_ got: JSONValue, _ want: JSONValue) -> [String] {
        var out: [String] = []
        diff(got, want, "", into: &out)
        return out
    }

    static func short(_ v: JSONValue) -> String { String(JSONValue.serialize(v).prefix(140)) }
}
