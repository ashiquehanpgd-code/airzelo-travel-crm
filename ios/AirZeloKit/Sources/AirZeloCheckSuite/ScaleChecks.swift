import Foundation
import CryptoKit
import AirZeloKit

/// Large-dataset performance and accounting consistency.
enum ScaleChecks {
    static let cases: [CheckSuite.Case] = [
        ("scale: large agency stays fast and consistent", largeAgency),
    ]

    static func time<T>(_ label: String, _ log: CheckLog, limit: Double, _ body: () throws -> T) throws -> T {
        let start = Date()
        let r = try body()
        let s = Date().timeIntervalSince(start)
        log.note(String(format: "%-34@ %8.3f s  (limit %.0f s)", label, s, limit))
        try expect(s <= limit, "\(label) took \(String(format: "%.2f", s)) s, limit \(limit) s")
        return r
    }

    static func memoryMB() -> Double {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count) }
        }
        return kr == KERN_SUCCESS ? Double(info.resident_size) / 1_048_576 : 0
    }

    /// Bulk-loads `n` bookings (with passengers, segments, receipts, supplier payments, refunds and expenses) in
    /// one transaction, writing exactly the rows the services write, then exercises the app at that size.
    static func largeAgency(_ o: CheckOptions, _ log: CheckLog) throws {
        let n = o.scaleBookings
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("az-scale-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AgencyStore(root: root, deviceKey: SymmetricKey(size: .bits256))
        let a = try store.open()
        let x = try ServiceChecks.basics(a)
        var rng = SystemRandomNumberGenerator()
        let customers = max(50, n / 4), suppliers = 12
        let ts = Dates.nowISO()
        try time("bulk load \(n) bookings", log, limit: 600) {
            try a.tx {
                for i in 0..<customers {
                    try a.db.run("INSERT INTO customers(name, phone, passport_no, created_at, updated_at) VALUES (?,?,?,?,?)",
                                 ["Customer \(i)", "+91 9\(String(format: "%09d", i))", try a.crypto.seal("P\(String(format: "%07d", i))", column: "passport_no"), ts, ts])
                }
                for i in 0..<suppliers {
                    try a.db.run("INSERT INTO suppliers(name, kind, created_at, updated_at) VALUES (?, 'b2b_portal', ?, ?)", ["Portal \(i)", ts, ts])
                }
                let custBase = try a.db.scalarInt("SELECT MIN(id) FROM customers WHERE name LIKE 'Customer %'")
                let supBase = try a.db.scalarInt("SELECT MIN(id) FROM suppliers WHERE name LIKE 'Portal %'")
                for i in 0..<n {
                    let day = String(format: "202%d-%02d-%02d", 4 + i % 3, 1 + i % 12, 1 + i % 28)
                    let sar = i % 5 == 0
                    let rate = sar ? "22.15" : "1"
                    let cost = Int.random(in: 200_000...6_000_000, using: &rng) / (sar ? 22 : 1)
                    let sell = cost + Int.random(in: 0...300_000, using: &rng) / (sar ? 22 : 1)
                    let comm = i % 3 == 0 ? 25_000 : 0
                    let status = ["ticketed", "ticketed", "confirmed", "enquiry"][i % 4]
                    let committed = status == "enquiry" ? 0 : 1
                    let cid = custBase + i % customers, sid = supBase + i % suppliers
                    let bid = try a.db.run("INSERT INTO bookings(ref, pnr, customer_id, supplier_id, service_type, booking_date, travel_date, trip_type, status, committed, "
                                           + "currency, exchange_rate, purchase_cost, selling_price, commission, service_charge, created_at, updated_at) "
                                           + "VALUES (?,?,?,?,'flight',?,?,'one_way',?,?,?,?,?,?,?,?,?,?)",
                                           [String(format: "BK-%02d-%06d", 24 + i % 3, i), String(format: "P%05d", i), cid, sid, day, day, status, committed,
                                            sar ? "SAR" : "INR", rate, cost, sell, comm, 50_000, ts, ts])
                    try a.db.run("INSERT INTO booking_passengers(booking_id, seq, name, pax_type, ticket_number) VALUES (?,0,?,'ADT',?)",
                                 [bid, "Passenger \(i)", String(format: "065%010d", i)])
                    try a.db.run("INSERT INTO booking_segments(booking_id, seq, direction, airline, flight_no, origin, destination, dep_at) VALUES (?,0,'outbound',?,?,?,?,?)",
                                 [bid, ["IndiGo", "Emirates", "Saudia", "Air India Express"][i % 4], "XX \(100 + i % 900)", "COK", ["DXB", "JED", "DOH"][i % 3], day + "T10:00"])
                    guard committed == 1 else { continue }
                    let payable = convert(sell + 50_000, rate)
                    func post(_ kind: String, _ amount: Int, _ base: Int, _ account: Int, _ cur: String, _ r: String, cust: Int?, sup: Int?) throws {
                        let tid = try a.db.run("INSERT INTO transactions(txn_no, uid, txn_date, kind, sign, amount, currency, exchange_rate, base_amount, account_id, method, "
                                               + "category, customer_id, supplier_id, booking_id, created_at, updated_at) VALUES (?,?,?,?,1,?,?,?,?,?,'cash',?,?,?,?,?,?)",
                                               ["\(kind.prefix(3).uppercased())-\(i)", UUID().uuidString, day, kind, amount, cur, r, base, account, kind, cust, sup, bid, ts, ts])
                        try a.db.run("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", [tid, bid, base, ts])
                    }
                    let paid = i % 7 == 0 ? payable / 2 : payable
                    try post("customer_receipt", paid, paid, x.bank, "INR", "1", cust: cid, sup: nil)
                    let supDue = convert(cost, rate) - convert(comm, rate)
                    if i % 2 == 0 && supDue > 0 { try post("supplier_payment", supDue, supDue, x.bank, "INR", "1", cust: nil, sup: sid) }
                    if i % 50 == 0 {
                        try a.db.run("INSERT INTO booking_adjustments(adj_no, booking_id, adj_date, kind, sign, exchange_rate, customer_credit, supplier_credit, created_at) "
                                     + "VALUES (?,?,?,'cancellation',1,?,?,?,?)", [String(format: "ADJ-%06d", i), bid, day, rate, sell / 2, cost / 2, ts])
                    }
                    if i % 20 == 0 {
                        try a.db.run("INSERT INTO transactions(txn_no, uid, txn_date, kind, sign, amount, currency, exchange_rate, base_amount, account_id, method, category, created_at, updated_at) "
                                     + "VALUES (?,?,?,'expense',1,?,'INR','1',?,?,'cash','Office expenses',?,?)", ["EXP-\(i)", UUID().uuidString, day, 150_000, 150_000, x.cash, ts, ts])
                    }
                }
            }
        }
        let dbSize = (try FileManager.default.attributesOfItem(atPath: store.dbURL.path)[.size] as? Int) ?? 0
        log.note(String(format: "database %.1f MB, %d transactions", Double(dbSize) / 1_048_576, try a.db.scalarInt("SELECT COUNT(*) FROM transactions")))
        let memBefore = memoryMB()
        let L = try time("load ledger (all figures)", log, limit: 15) { try Ledger(a) }
        log.note(String(format: "memory: %.0f MB before, %.0f MB with the ledger loaded", memBefore, memoryMB()))
        let dash = try time("dashboard", log, limit: 10) { L.dashboard(from: "2026-01-01", to: "2026-12-31") }
        for name in ["sales", "profit", "customer_outstanding", "cashbook", "monthly", "yearly", "airline", "refunds"] {
            _ = try time("report: \(name)", log, limit: 10) { try L.report(name, ReportParams(name == "monthly" ? ["year": "2026"] : ["from": "2026-01-01", "to": "2026-12-31"])) }
        }
        _ = try time("search \"P01234\"", log, limit: 10) { try a.searchBookings(["q": "P01234"], ledger: L) }
        let custId = try a.db.scalarInt("SELECT MIN(id) FROM customers WHERE name LIKE 'Customer %'")
        let newBooking = try time("save booking (service)", log, limit: 5) {
            try a.saveBooking(["customer_id": .int(custId), "status": "ticketed", "purchase_cost": "1000", "selling_price": "1200", "passengers": [["name": "Scale Pax"]]])
        }
        _ = try time("receipt with allocation check", log, limit: 15) {
            try a.createTransaction(["kind": "customer_receipt", "account_id": .int(x.cash), "amount": "1200", "booking_id": .int(newBooking)])
        }
        _ = try time("ticket PDF", log, limit: 5) { try a.ticketPDF(newBooking, withPayment: true) }
        let backup = try time("encrypted backup (create + verify)", log, limit: 120) { try store.createBackup() }
        log.note(String(format: "backup %.1f MB", Double(backup.size) / 1_048_576))
        _ = try time("restore (verify + safety copy + swap)", log, limit: 240) { try store.restore(try store.backupData(backup)) }

        // accounting consistency at scale: independent paths must agree
        let L2 = try Ledger(try store.open())
        let all = L2.periodSummary()
        let committed = L2.bookingList.filter { $0.committed }
        try expectEqual(committed.reduce(0) { $0 + $1.calc.grossProfit }, all.grossProfit + 0, "Σ booking profit = period gross profit")
        try expectEqual(committed.reduce(0) { $0 + $1.calc.revenue }, all.sales, "Σ booking revenue = period sales")
        let profit = try L2.report("profit", ReportParams())
        try expectEqual(profit.totals["gross_profit"]?.intValue, all.grossProfit, "profit report total")
        let years = try L2.report("yearly", ReportParams())
        try expectEqual(years.totals["gross_profit"]?.intValue, all.grossProfit, "yearly report total")
        let cb = L2.customerBalances()
        try expectEqual(cb.values.reduce(0) { $0 + $1.outstanding },
                        L2.bookingList.reduce(0) { $0 + $1.calc.customerOutstanding } + cb.values.reduce(0) { $0 - $1.unallocated },
                        "customer balances = Σ booking outstanding - unallocated")
        let acc = L2.accountBalances()
        let inr = acc.values.filter { $0.currency == "INR" }
        try expectEqual(inr.reduce(0) { $0 + $1.balance - $1.opening }, all.cash.netCashFlow, "INR account movements = net cash flow (no transfers here)")
        log.note("\(committed.count) committed bookings; gross profit \(money(all.grossProfit)); dashboard pending \(dash.pendingCount)")
    }
}
