import Foundation
import AirZeloKit

/// A worked example with hand-computed figures, and the safety rules that protect posted money.
enum ServiceChecks {
    static let cases: [CheckSuite.Case] = [
        ("accounting: worked example (GST, SAR, refund)", workedExample),
        ("accounting: posted money cannot be edited", immutability),
        ("accounting: repeated submissions post once", idempotency),
        ("editing: re-saving every desktop booking changes nothing", editRoundTrip),
        ("editing: passport sealed on another device is kept", foreignSealedPassport),
    ]

    /// Every booking of the desktop parity data is re-saved from its edit payload (what the edit screen starts from):
    /// nothing may be refused, no stored field may change and no figure may move.
    static func editRoundTrip(_ o: CheckOptions, _ log: CheckLog) throws {
        let fx = try ParityChecks.load(o)
        pinClock(fx.today)
        defer { unpinClock() }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("az-edit-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("agency.db")
        try FileManager.default.copyItem(at: fx.dbURL, to: url)
        let a = try Agency(url: url, crypto: .ephemeral(), protect: false)
        defer { a.close() }
        func state() throws -> JSONValue {
            var b = try a.db.query("SELECT * FROM bookings ORDER BY id").map { r -> JSONValue in
                var o = r.json; o.removeValue(forKey: "updated_at"); return .object(o)
            }
            b += try a.db.query("SELECT booking_id, seq, title, name, pax_type, ticket_number, passport_no, notes FROM booking_passengers ORDER BY booking_id, seq").map { r in
                var o = r.json; o["passport_no"] = J(a.crypto.open(r.optStr("passport_no"), column: "passport_no")); return .object(o)
            }
            b += try a.db.query("SELECT booking_id, seq, direction, airline, flight_no, origin, destination, dep_at, arr_at, cabin, baggage, airline_pnr FROM booking_segments ORDER BY booking_id, seq").map { .object($0.json) }
            return .array(b)
        }
        let before = try state()
        let calcBefore = try Ledger(a).bookingList.map { $0.calc.json }
        let ids = try a.db.query("SELECT id FROM bookings ORDER BY id").map { $0.int("id") }
        var refused: [String] = []
        for id in ids {
            var f = try a.bookingEditFields(id)
            f["force_duplicate"] = true  // the scenario deliberately has duplicate PNRs
            do { try a.saveBooking(f, id: id) } catch let e as AZError { refused.append("booking \(id): \(e.message)") }
        }
        try expect(refused.isEmpty, "\(refused.count) re-saves refused:\n" + refused.prefix(10).joined(separator: "\n"))
        let diffs = ParityChecks.diff(try state(), before)
        try expect(diffs.isEmpty, "\(diffs.count) stored values changed:\n" + diffs.prefix(15).joined(separator: "\n"))
        try expect(try Ledger(a).bookingList.map { $0.calc.json } == calcBefore, "figures moved after re-saving")
        let manual = try a.db.scalarInt("SELECT COUNT(*) FROM bookings WHERE tax_override = 1")
        let invoiced = try a.db.scalarInt("SELECT COUNT(DISTINCT booking_id) FROM invoices WHERE status = 'issued' AND doc_type = 'invoice'")
        log.note("\(ids.count) bookings re-saved unchanged (\(manual) with manual tax, \(invoiced) with an issued invoice)")
    }

    static func foreignSealedPassport(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = try TempAgency()
        let a = t.agency
        let other = FieldCrypto.ephemeral()
        let foreign = try other.seal("X7654321", column: "passport_no")!
        let cid = try a.createCustomer(["name": "Moved Customer"])
        try a.db.run("UPDATE customers SET passport_no = ? WHERE id = ?", [foreign, cid])
        let b = try a.saveBooking(["customer_id": .int(cid), "status": "confirmed", "passengers": [["name": "Moved Pax"]]])
        try a.db.run("UPDATE booking_passengers SET passport_no = ? WHERE booking_id = ?", [foreign, b])
        try expectEqual(FieldCrypto.display(try a.getCustomer(cid).customer["passport_no"]?.stringValue), "Encrypted on another device")
        try a.updateCustomer(cid, ["city": "Kochi"])
        try a.saveBooking(try a.bookingEditFields(b), id: b)
        try expectEqual(try a.db.one("SELECT passport_no FROM customers WHERE id = ?", [cid])!.str("passport_no"), foreign, "customer value kept")
        try expectEqual(try a.db.one("SELECT passport_no FROM booking_passengers WHERE booking_id = ?", [b])!.str("passport_no"), foreign, "passenger value kept")
        try expectEqual(other.open(foreign, column: "passport_no"), "X7654321", "still readable with its own key")
    }

    /// Sets up an agency with two accounts, a customer and a supplier.
    static func basics(_ a: Agency) throws -> (cash: Int, bank: Int, sar: Int, cust: Int, sup: Int) {
        let cash = try a.db.scalarInt("SELECT id FROM accounts WHERE name = 'Cash in hand'")
        let bank = try a.saveAccount(["name": "HDFC Current", "account_type": "current", "currency": "INR"])
        let sar = try a.saveAccount(["name": "SAR Cash", "account_type": "cash", "currency": "SAR"])
        let cust = try a.createCustomer(["name": "Test Customer", "phone": "+91 90000 00001", "passport_no": "k1234567"])
        let sup = try a.createSupplier(["name": "Portal A", "kind": "b2b_portal"])
        return (cash, bank, sar, cust, sup)
    }

    static func workedExample(_ o: CheckOptions, _ log: CheckLog) throws {
        pinClock("2026-10-25")
        defer { unpinClock() }
        let t = try TempAgency()
        let a = t.agency
        let x = try basics(a)
        // INR ticket: cost 40,000, sell 43,000, commission 500, service charge 1,000 with GST 18% on service charge (CGST+SGST)
        let b1 = try a.saveBooking(["customer_id": .int(x.cust), "supplier_id": .int(x.sup), "status": "ticketed", "booking_date": "2026-10-01",
                                    "purchase_cost": "40000", "selling_price": "43000", "commission": "500", "service_charge": "1000",
                                    "tax_profile_id": 2, "pnr": "abc123",
                                    "passengers": [["name": "Test Passenger", "ticket_number": "065 1234567890", "passport_no": "z7654321"]],
                                    "segments": [["airline": "Saudia", "flight_no": "sv 756", "origin": "cok", "destination": "jed",
                                                  "dep_at": "2026-11-02T04:10", "arr_at": "2026-11-02T08:40"]]])
        var L = try Ledger(a)
        var c = L.bookings[b1]!.calc
        try expectEqual(c.base.tax_amount, 18_000, "GST 18% of 1,000.00")
        try expectEqual(c.customerPayable, 4_418_000, "43,000 + 1,000 + 180 tax")
        try expectEqual(c.grossProfit, 450_000, "3,000 markup + 500 commission + 1,000 service")
        try expectEqual(c.supplierOutstanding, 3_950_000, "40,000 - 500 commission")
        try expectEqual(c.paymentStatus, "unpaid")
        // SAR ticket at 22.15: cost SAR 1,000.00 -> INR 22,150.00; sell SAR 1,100.00 -> INR 24,365.00
        let b2 = try a.saveBooking(["customer_id": .int(x.cust), "status": "confirmed", "booking_date": "2026-10-05", "currency": "SAR",
                                    "exchange_rate": "22.15", "purchase_cost": "1000", "selling_price": "1100", "passengers": [["name": "Second Pax"]]])
        L = try Ledger(a)
        try expectEqual(L.bookings[b2]!.calc.grossProfit, 221_500, "SAR 100 margin at 22.15")
        // receipts: full payment of booking 1, part of booking 2 in SAR
        try a.createTransaction(["kind": "customer_receipt", "txn_date": "2026-10-06", "account_id": .int(x.bank), "amount": "44180", "booking_id": .int(b1), "method": "upi"])
        try a.createTransaction(["kind": "customer_receipt", "txn_date": "2026-10-07", "account_id": .int(x.sar), "amount": "500", "currency": "SAR",
                                 "exchange_rate": "22.15", "booking_id": .int(b2), "method": "cash"])
        try a.createTransaction(["kind": "supplier_payment", "txn_date": "2026-10-07", "account_id": .int(x.bank), "amount": "39500", "booking_id": .int(b1)])
        try a.createTransaction(["kind": "expense", "txn_date": "2026-10-08", "account_id": .int(x.cash), "amount": "750", "category": "Rent"])
        L = try Ledger(a)
        c = L.bookings[b1]!.calc
        try expectEqual(c.paymentStatus, "paid")
        try expectEqual(c.supplierOutstanding, 0)
        try expectEqual(L.bookings[b2]!.calc.customerOutstanding, 2_436_500 - 1_107_500, "SAR 600 still due at 22.15")
        let s = L.periodSummary("2026-10-01", "2026-10-31")
        try expectEqual(s.grossProfit, 671_500)
        try expectEqual(s.netProfit, 596_500, "gross - rent 750")
        try expectEqual(s.tax, 18_000, "tax is a liability, not profit")
        let acct = L.accountBalances()
        try expectEqual(acct[x.bank]!.balance, 4_418_000 - 3_950_000)
        try expectEqual(acct[x.sar]!.balance, 50_000, "account balances stay in the account's own currency")
        // invoice: 2 lines + CGST/SGST 9% each
        let inv = try a.createInvoice(b1, ["issue_date": "2026-10-06"])
        let doc = try a.getInvoice(inv)
        try expectEqual(doc.row.str("number"), "INV/2026-27/0001")
        try expectEqual(doc.taxes.map { $0["label"].stringValue! }, ["CGST @ 9%", "SGST @ 9%"])
        try expectEqual(doc.row.int("total"), 4_418_000)
        try expectError("validation") { try a.saveBooking(["customer_id": .int(x.cust), "selling_price": "50000", "booking_date": "2026-10-01",
                                                           "passengers": [["name": "Test Passenger"]]], id: b1) }  // invoice locks prices
        // refund booking 1: customer gets 30,000 back (incl. 0 tax), airline refunds 35,000, commission 500 reversed
        let r = try a.refundBooking(b1, ["customer_refund": "30000", "supplier_refund": "35000", "commission_reversal": "500", "date": "2026-10-20",
                                         "new_status": "cancelled", "pay_customer": ["account_id": .int(x.bank), "amount": "30000"]])
        // new profit = 4,500 - 30,000 + 35,000 - 500 = 9,000
        try expectEqual(r.grossProfit, 900_000)
        try expectEqual(r.customerOutstanding, 0)
        try expectEqual(r.supplierOutstanding, -3_450_000, "the supplier owes the 35,000 refund less the 500 commission given back")
        let cn = try a.createCreditNote(r.adjustmentId, ["issue_date": "2026-10-20"])
        try expectEqual(try a.getInvoice(cn).row.str("number"), "CN/2026-27/0001")
        // a refund with nothing due back is refused (the desktop's original refund bug)
        try expectError("refund_not_due") { try a.createTransaction(["kind": "customer_refund", "txn_date": "2026-10-21", "account_id": .int(x.bank),
                                                                     "amount": "5000", "customer_id": .int(x.cust)]) }
        // reports agree with the summary
        L = try Ledger(a)
        let profit = try L.report("profit", ReportParams(["from": "2026-10-01", "to": "2026-10-31"]))
        try expectEqual(profit.totals["gross_profit"]?.intValue, L.periodSummary("2026-10-01", "2026-10-31").grossProfit)
        let st = try L.customerStatement(x.cust)
        try expectEqual(st.closing, L.customerBalances()[x.cust]!.outstanding, "statement closing balance = outstanding")
        log.note("booking profit after refund \(fmt(r.grossProfit)), statement closing \(fmt(st.closing))")
    }

    static func immutability(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = try TempAgency()
        let a = t.agency
        let x = try basics(a)
        let (tid, _) = try a.createTransaction(["kind": "other_income", "account_id": .int(x.cash), "amount": "100", "category": "Other income"])
        try expect((try? a.tx { try a.db.run("DELETE FROM transactions WHERE id = ?", [tid]) }) == nil, "delete must be refused by the database")
        try expect((try? a.tx { try a.db.run("UPDATE transactions SET amount = 1 WHERE id = ?", [tid]) }) == nil, "amount change must be refused")
        try expectError("validation") { try a.updateTransactionMeta(tid, ["amount": "5"]) }
        let (nid, outcome) = try a.correctTransaction(tid, ["amount": "150", "reason": "typo", "account_id": .int(x.cash), "category": "Other income"])
        try expectEqual(outcome, "replaced")
        let L = try Ledger(a)
        try expectEqual(L.accountBalances()[x.cash]!.balance, 15_000, "original reversed, corrected one posted")
        try expect(L.txnById[tid]!.reversedBy != nil && L.txnById[nid]!.sign == 1, "audit trail kept")
        try expectError("validation", message: "This transaction has already been reversed") { try a.reverseTransaction(tid, ["reason": "again"]) }
    }

    static func idempotency(_ o: CheckOptions, _ log: CheckLog) throws {
        let t = try TempAgency()
        let a = t.agency
        let x = try basics(a)
        let data: Fields = ["kind": "customer_receipt", "account_id": .int(x.cash), "amount": "1000", "customer_id": .int(x.cust), "idem_key": "tap-1"]
        let first = try a.createTransaction(data)
        let second = try a.createTransaction(data)
        try expect(!first.replayed && second.replayed && first.id == second.id, "a double tap must post once")
        try expectError("possible_duplicate") {
            var d = data; d["idem_key"] = "tap-2"
            try a.createTransaction(d)
        }
    }
}
