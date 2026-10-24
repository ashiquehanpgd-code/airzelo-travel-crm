import Foundation

// Port of core/server/reports.py and finance.dashboard. Each report returns {title, columns, rows, totals}
// built from the same Ledger used by the dashboard, so the figures always agree (and match the desktop app).

public struct ReportColumn: Equatable {
    public let key: String, label: String, type: String
    public var isMoney: Bool { type == "money" || type == "money_raw" }
    var json: JSONValue { ["key": .string(key), "label": .string(label), "type": .string(type)] }
}

private func col(_ key: String, _ label: String, _ type: String = "text") -> ReportColumn { ReportColumn(key: key, label: label, type: type) }
private let M = "money"

public struct Report {
    public let name: String
    public let title: String
    public let columns: [ReportColumn]
    public let rows: [[String: JSONValue]]
    public let totals: [String: JSONValue]
    public let summary: [JSONValue]?
    public let note: String?
    public let base: String

    /// The report as the desktop API returns it (used by parity tests and CSV export).
    public var json: JSONValue {
        var o: [String: JSONValue] = ["title": .string(title), "columns": .array(columns.map { $0.json }),
                                      "rows": .array(rows.map { .object($0) }), "totals": .object(totals)]
        if let summary { o["summary"] = .array(summary) }
        if let note { o["note"] = .string(note) }
        if name == "cashbook" { o["note"] = J(note) }  // the desktop always returns note here, null when there is none
        return .object(o)
    }

    /// CSV text: header, rows, then the totals row (reports.to_csv).
    public var csv: String {
        func cell(_ v: JSONValue?, _ c: ReportColumn) -> String {
            guard let v, !v.isNull else { return "" }
            if c.isMoney, let i = v.intValue { return fmt(i) }
            if case .array(let a) = v { return a.map { $0.pyStr }.joined(separator: ", ") }
            return v.pyStr
        }
        func esc(_ s: String) -> String {
            s.contains(where: { ",\"\n\r".contains($0) }) ? "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : s
        }
        var lines = [columns.map { esc($0.label) }.joined(separator: ",")]
        for r in rows { lines.append(columns.map { esc(cell(r[$0.key], $0)) }.joined(separator: ",")) }
        if !totals.isEmpty {
            lines.append(columns.enumerated().map { i, c in i == 0 ? "Total" : esc(cell(totals[c.key], c)) }.joined(separator: ","))
        }
        return lines.joined(separator: "\r\n") + "\r\n"
    }
}

public struct ReportParams {
    public var values: [String: String]
    public init(_ values: [String: String] = [:]) { self.values = values.filter { !$0.value.isEmpty } }
    public subscript(_ k: String) -> String? { values[k] }
    var filter: LedgerFilter {
        LedgerFilter(customerId: values["customer_id"], supplierId: values["supplier_id"], currency: values["currency"],
                     status: values["status"], serviceType: values["service_type"], airline: values["airline"])
    }
}

public let REPORT_NAMES = ["sales", "bookings", "profit", "customer_outstanding", "supplier_outstanding", "expenses", "cashbook",
                           "refunds", "commission", "monthly", "yearly", "airline", "supplier_purchases"]

private func totalsOf(_ rows: [[String: JSONValue]], _ cols: [ReportColumn]) -> [String: JSONValue] {
    var t: [String: JSONValue] = [:]
    for c in cols where c.type == M { t[c.key] = .int(rows.reduce(0) { $0 + ($1[c.key]?.intValue ?? 0) }) }
    return t
}

private func I(_ v: Int) -> JSONValue { .int(v) }
private func S(_ s: String) -> JSONValue { .string(s) }

private func sortRows(_ rows: inout [[String: JSONValue]], by keys: [String]) {
    // stable sort by string/int keys ascending, like Python's sort with a tuple key
    let indexed = rows.enumerated().map { ($0.offset, $0.element) }
    rows = indexed.sorted { a, b in
        for k in keys {
            let x = a.1[k] ?? .null, y = b.1[k] ?? .null
            if let xi = x.intValue, let yi = y.intValue, case .int = x, case .int = y {
                if xi != yi { return xi < yi }
            } else if x.pyStr != y.pyStr {
                return x.pyStr < y.pyStr
            }
        }
        return a.0 < b.0
    }.map { $0.1 }
}

private func monthLabel(_ y: Int, _ m: Int, short: Bool) -> String {
    Dates.monthAbbr[m - 1] + " " + (short ? String(format: "%02d", y % 100) : String(y))
}

extension Ledger {
    public func report(_ name: String, _ p: ReportParams) throws -> Report {
        let frm = p["from"], to = p["to"], f = p.filter
        func make(_ title: String, _ cols: [ReportColumn], _ rows: [[String: JSONValue]], _ totals: [String: JSONValue]? = nil,
                  summary: [JSONValue]? = nil, note: String? = nil) -> Report {
            Report(name: name, title: title, columns: cols, rows: rows, totals: totals ?? totalsOf(rows, cols), summary: summary, note: note, base: base)
        }
        switch name {
        case "sales":
            var out: [[String: JSONValue]] = []
            for b in bookingList where b.committed && inRange(b.bookingDate, frm, to) && bookingMatches(b, f) {
                let x = b.calc.base
                out.append(["booking_date": S(b.bookingDate), "ref": S(b.ref), "pnr": S(b.pnr ?? ""), "customer": S(b.customerName),
                            "supplier": S(b.supplierName), "airline": S(b.airlines.joined(separator: ", ")), "route": S(b.route),
                            "status": S(b.status), "currency": S(b.currency), "rate": S(b.row.str("exchange_rate")),
                            "selling": I(x.selling_price), "service": I(x.service_charge), "other": I(x.other_income), "tax": I(x.tax_amount),
                            "billed": I(x.selling_price + x.service_charge + x.other_income + x.tax_amount), "_booking_id": I(b.id)])
            }
            sortRows(&out, by: ["booking_date", "ref"])
            let cols = [col("booking_date", "Date", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("customer", "Customer"),
                        col("supplier", "Supplier"), col("airline", "Airline"), col("route", "Route"), col("status", "Status", "status"),
                        col("currency", "Cur"), col("selling", "Ticket fare", M), col("service", "Service charge", M),
                        col("other", "Other income", M), col("tax", "Tax", M), col("billed", "Total billed", M)]
            return make("Sales report", cols, out,
                        note: "Gross bookings recorded in the period (credits for cancellations appear in the refunds report and in profit).")

        case "bookings":
            var out: [[String: JSONValue]] = []
            for b in bookingList where inRange(b.bookingDate, frm, to) && bookingMatches(b, f) {
                out.append(["booking_date": S(b.bookingDate), "ref": S(b.ref), "pnr": S(b.pnr ?? ""), "customer": S(b.customerName),
                            "lead_pax": S(b.leadPax), "pax": I(b.paxCount), "airline": S(b.airlines.joined(separator: ", ")), "route": S(b.route),
                            "travel_date": S(b.travelDate ?? ""), "supplier": S(b.supplierName), "status": S(b.status),
                            "payment": S(b.calc.paymentStatus), "payable": I(b.calc.customerPayable - b.calc.customerCredits),
                            "outstanding": I(b.calc.customerOutstanding), "_booking_id": I(b.id)])
            }
            sortRows(&out, by: ["booking_date", "ref"])
            let cols = [col("booking_date", "Booked", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("customer", "Customer"),
                        col("lead_pax", "Lead passenger"), col("pax", "Pax", "number"), col("airline", "Airline"), col("route", "Route"),
                        col("travel_date", "Travel", "date"), col("supplier", "Supplier"), col("status", "Status", "status"),
                        col("payment", "Payment", "status"), col("payable", "Net payable", M), col("outstanding", "Outstanding", M)]
            var t = totalsOf(out, cols)
            t["pax"] = I(out.reduce(0) { $0 + ($1["pax"]?.intValue ?? 0) })
            return make("Booking report", cols, out, t)

        case "profit":
            var agg: [Int: [String: JSONValue]] = [:], order: [Int] = []
            for e in events where inRange(e.date, frm, to) {
                let b = bookings[e.bookingId]!
                if !bookingMatches(b, f) { continue }
                if agg[b.id] == nil {
                    order.append(b.id)
                    agg[b.id] = ["booking_date": S(b.bookingDate), "ref": S(b.ref), "pnr": S(b.pnr ?? ""), "customer": S(b.customerName),
                                 "airline": S(b.airlines.joined(separator: ", ")), "supplier": S(b.supplierName), "status": S(b.status),
                                 "sales": 0, "cost": 0, "markup": 0, "commission": 0, "direct": 0, "gross_profit": 0, "_booking_id": I(b.id)]
                }
                for (k, v) in [("sales", e.sales), ("cost", e.cost), ("markup", e.markup), ("commission", e.commission), ("direct", e.directExpenses)] {
                    agg[b.id]![k] = I(agg[b.id]![k]!.intValue! + v)
                }
            }
            var out = order.map { agg[$0]! }
            for i in out.indices {
                let s = out[i]["sales"]!.intValue!, gp = s - out[i]["cost"]!.intValue! + out[i]["commission"]!.intValue! - out[i]["direct"]!.intValue!
                out[i]["gross_profit"] = I(gp)
                out[i]["margin"] = S(s != 0 ? String(format: "%.1f%%", 100.0 * Double(gp) / Double(s)) : "")
            }
            sortRows(&out, by: ["booking_date", "ref"])
            let cols = [col("booking_date", "Date", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("customer", "Customer"),
                        col("airline", "Airline"), col("supplier", "Supplier"), col("status", "Status", "status"),
                        col("sales", "Net sales (ex tax)", M), col("cost", "Net cost", M), col("markup", "Markup", M),
                        col("commission", "Commission", M), col("direct", "Direct expenses", M), col("gross_profit", "Gross profit", M),
                        col("margin", "Margin")]
            let s = periodSummary(frm, to, f)
            let summary: [JSONValue] = [
                ["label": "Net sales (ex tax)", "value": I(s.sales)], ["label": "Net purchase cost", "value": I(s.cost)],
                ["label": "Commission (net)", "value": I(s.commission)], ["label": "Direct booking expenses", "value": I(s.directExpenses)],
                ["label": "Gross profit", "value": I(s.grossProfit), "strong": true],
                ["label": "Other income", "value": I(s.otherIncome)], ["label": "Operating expenses", "value": I(s.operatingExpenses)],
                ["label": "Net profit", "value": I(s.netProfit), "strong": true],
                ["label": "Tax collected (liability, excluded)", "value": I(s.tax)]]
            return make("Profit report", cols, out, summary: summary, note: "Net sales are after credits; tax is excluded from revenue and profit.")

        case "customer_outstanding":
            let bal = customerBalances()
            var out: [[String: JSONValue]] = []
            for cid in customerOrder {
                let r = bal[cid]!
                if let c = p["customer_id"], String(r.customerId) != c { continue }
                if p["show"] != "all" && r.outstanding == 0 { continue }
                out.append(["customer": S(r.name), "phone": S(r.phone ?? ""), "bookings": I(r.bookings), "billed": I(r.billed), "credits": I(r.credits),
                            "received": I(r.received), "refunded": I(r.refunded), "unallocated": I(r.unallocated), "outstanding": I(r.outstanding),
                            "_customer_id": I(r.customerId)])
            }
            out = out.enumerated().sorted { (-$0.element["outstanding"]!.intValue!, $0.offset) < (-$1.element["outstanding"]!.intValue!, $1.offset) }.map { $0.element }
            let cols = [col("customer", "Customer"), col("phone", "Phone"), col("bookings", "Bookings", "number"),
                        col("billed", "Billed", M), col("credits", "Credits", M), col("received", "Received", M),
                        col("refunded", "Refunded", M), col("unallocated", "Unallocated advance", M), col("outstanding", "Outstanding", M)]
            return make("Customer outstanding", cols, out, note: "Positive = customer owes you. Negative = you owe the customer (advance or refund due).")

        case "supplier_outstanding":
            let bal = supplierBalances()
            var out: [[String: JSONValue]] = []
            for sid in supplierOrder {
                let r = bal[sid]!
                if let s = p["supplier_id"], String(r.supplierId) != s { continue }
                if p["show"] != "all" && r.outstanding == 0 { continue }
                out.append(["supplier": S(r.name), "kind": S(r.kind), "bookings": I(r.bookings), "payable": I(r.payable), "credits": I(r.credits),
                            "paid": I(r.paid), "refunds": I(r.refunds), "unallocated": I(r.unallocated), "outstanding": I(r.outstanding),
                            "_supplier_id": I(r.supplierId)])
            }
            out = out.enumerated().sorted { (-$0.element["outstanding"]!.intValue!, $0.offset) < (-$1.element["outstanding"]!.intValue!, $1.offset) }.map { $0.element }
            let cols = [col("supplier", "Supplier"), col("kind", "Type"), col("bookings", "Bookings", "number"), col("payable", "Payable", M),
                        col("credits", "Credits & commission", M), col("paid", "Paid", M), col("refunds", "Refunds received", M),
                        col("unallocated", "Unallocated", M), col("outstanding", "Outstanding", M)]
            return make("Supplier outstanding", cols, out, note: "Positive = you owe the supplier. Negative = the supplier owes you (credit/refund due).")

        case "expenses":
            var out: [[String: JSONValue]] = []
            var byCat: [String: Int] = [:], catOrder: [String] = []
            for t in transactions where t.kind == "expense" && inRange(t.txnDate, frm, to) {
                if let c = p["currency"], t.currency != c { continue }
                if let c = p["category"], t.category != c { continue }
                let r = txnRow(t)
                let v = t.sign * t.baseAmount
                let supplierName = r["supplier_name"]!.pyStr
                out.append(["txn_date": S(t.txnDate), "txn_no": S(t.txnNo), "category": S(t.category),
                            "type": S(t.isDirect ? "Direct (booking \(r["booking_ref"]!.pyStr))" : "Operating"),
                            "payee": S((t.payee?.isEmpty == false ? t.payee : nil) ?? (supplierName.isEmpty ? "" : supplierName)),
                            "account": r["account_name"]!, "method": S(t.method), "reference": S(t.reference ?? ""), "currency": S(t.currency),
                            "amount": I(t.sign * t.amount), "base": I(v), "notes": S(t.notes ?? ""), "_txn_id": I(t.id)])
                if byCat[t.category] == nil { catOrder.append(t.category) }
                byCat[t.category, default: 0] += v
            }
            sortRows(&out, by: ["txn_date", "txn_no"])
            let cols = [col("txn_date", "Date", "date"), col("txn_no", "No."), col("category", "Category"), col("type", "Type"),
                        col("payee", "Payee"), col("account", "Paid from"), col("method", "Method"), col("reference", "Reference"),
                        col("currency", "Cur"), col("amount", "Amount", "money_raw"), col("base", "Amount (" + base + ")", M),
                        col("notes", "Notes")]
            let summary = catOrder.enumerated().sorted { (-byCat[$0.element]!, $0.offset) < (-byCat[$1.element]!, $1.offset) }
                .map { JSONValue.object(["label": S($0.element), "value": I(byCat[$0.element]!)]) }
            return make("Expense report", cols, out, summary: summary)

        case "cashbook":
            return try cashbook(p)

        case "refunds":
            var out: [[String: JSONValue]] = []
            for a in allAdjustments {
                let b = bookings[a.bookingId]!
                if !inRange(a.adjDate, frm, to) || !bookingMatches(b, f) { continue }
                let s = a.sign
                func cv(_ k: String) -> JSONValue { I(s * convert(a[k], a.exchangeRate)) }
                out.append(["date": S(a.adjDate), "no": S(a.adjNo), "type": S(a.kind.replacingOccurrences(of: "_", with: " ") + (s < 0 ? " (reversal)" : "")),
                            "booking": S(b.ref), "pnr": S(b.pnr ?? ""), "customer": S(b.customerName), "supplier": S(b.supplierName),
                            "customer_credit": cv("customer_credit"), "supplier_credit": cv("supplier_credit"),
                            "commission_reversal": cv("commission_reversal"), "charges": cv("extra_customer_charge"),
                            "supplier_charges": cv("extra_supplier_charge"), "cash": 0, "notes": S(a.notes ?? ""), "_booking_id": I(b.id)])
            }
            for t in transactions where (t.kind == "customer_refund" || t.kind == "supplier_receipt") && inRange(t.txnDate, frm, to) {
                if !txnMatches(t, f) { continue }
                let r = txnRow(t)
                out.append(["date": S(t.txnDate), "no": S(t.txnNo), "type": S(KIND_LABELS[t.kind]! + (t.sign < 0 ? " (reversal)" : "")),
                            "booking": r["booking_ref"]!, "pnr": "", "customer": r["customer_name"]!, "supplier": r["supplier_name"]!,
                            "customer_credit": 0, "supplier_credit": 0, "commission_reversal": 0, "charges": 0, "supplier_charges": 0,
                            "cash": I((t.kind == "customer_refund" ? -1 : 1) * t.sign * t.baseAmount), "notes": S(t.notes ?? ""), "_txn_id": I(t.id)])
            }
            sortRows(&out, by: ["date", "no"])
            let cols = [col("date", "Date", "date"), col("no", "No."), col("type", "Type"), col("booking", "Booking"), col("pnr", "PNR"),
                        col("customer", "Customer"), col("supplier", "Supplier"), col("customer_credit", "Customer credit", M),
                        col("supplier_credit", "Supplier credit", M), col("commission_reversal", "Commission reversed", M),
                        col("charges", "Charges to customer", M), col("supplier_charges", "Supplier charges", M),
                        col("cash", "Cash in(+)/out(-)", M), col("notes", "Notes")]
            return make("Refunds & cancellations", cols, out)

        case "commission":
            var agg: [Int: [String: JSONValue]] = [:], order: [Int] = []
            for e in events where e.commission != 0 && inRange(e.date, frm, to) {
                let b = bookings[e.bookingId]!
                if !bookingMatches(b, f) { continue }
                if agg[b.id] == nil {
                    order.append(b.id)
                    agg[b.id] = ["booking_date": S(b.bookingDate), "ref": S(b.ref), "pnr": S(b.pnr ?? ""), "supplier": S(b.supplierName),
                                 "airline": S(b.airlines.joined(separator: ", ")), "earned": 0, "reversed": 0, "net": 0, "_booking_id": I(b.id)]
                }
                if e.commission > 0 { agg[b.id]!["earned"] = I(agg[b.id]!["earned"]!.intValue! + e.commission) }
                else { agg[b.id]!["reversed"] = I(agg[b.id]!["reversed"]!.intValue! - e.commission) }
                agg[b.id]!["net"] = I(agg[b.id]!["earned"]!.intValue! - agg[b.id]!["reversed"]!.intValue!)
            }
            var out = order.map { agg[$0]! }
            sortRows(&out, by: ["booking_date", "ref"])
            let cols = [col("booking_date", "Date", "date"), col("ref", "Booking"), col("pnr", "PNR"), col("supplier", "Supplier"),
                        col("airline", "Airline"), col("earned", "Earned", M), col("reversed", "Reversed", M), col("net", "Net commission", M)]
            return make("Commission income", cols, out)

        case "monthly":
            let today = Dates.today()
            guard let year = Int(p["year"] ?? String(today.prefix(4))) else { throw AZError.validation("Year is invalid") }
            var periods: [(String, String, String)] = []
            for m in 1...12 {
                periods.append((monthLabel(year, m, short: false), String(format: "%04d-%02d-01", year, m),
                                String(format: "%04d-%02d-%02d", year, m, Dates.daysInMonth(year, m))))
            }
            let out = periodRows(periods)
            var t = totalsOf(out, PERIOD_COLS)
            t["bookings"] = I(out.reduce(0) { $0 + ($1["bookings"]?.intValue ?? 0) })
            return make("Monthly summary \(year)", PERIOD_COLS, out, t)

        case "yearly":
            var years = Set<Int>()
            for e in events { years.insert(Int(e.date.prefix(4)) ?? 0) }
            for t in transactions { years.insert(Int(t.txnDate.prefix(4)) ?? 0) }
            if years.isEmpty { years = [Int(Dates.today().prefix(4))!] }
            let fy = p["basis"] == "fy"
            var all = years
            if fy { all.insert(years.min()! - 1) }
            var periods: [(String, String, String)] = []
            for y in all.sorted() {
                if fy { periods.append(("FY \(y)-" + String(format: "%02d", (y + 1) % 100), "\(y)-04-01", "\(y + 1)-03-31")) }
                else { periods.append((String(y), "\(y)-01-01", "\(y)-12-31")) }
            }
            let out = periodRows(periods).filter { r in ["bookings", "cash_in", "cash_out", "op_expenses", "sales"].contains { (r[$0]?.intValue ?? 0) != 0 } }
            var t = totalsOf(out, PERIOD_COLS)
            t["bookings"] = I(out.reduce(0) { $0 + ($1["bookings"]?.intValue ?? 0) })
            return make("Yearly summary" + (fy ? " (Indian financial year)" : ""), PERIOD_COLS, out, t)

        case "airline", "supplier_purchases":
            let isAirline = name == "airline"
            var agg: [String: [String: Int]] = [:], order: [String] = []
            for e in events where inRange(e.date, frm, to) {
                let b = bookings[e.bookingId]!
                if !bookingMatches(b, f) { continue }
                let key = isAirline ? (b.airlines.first ?? "(no airline)") : (b.supplierName.isEmpty ? "(no supplier)" : b.supplierName)
                if agg[key] == nil { order.append(key); agg[key] = [:] }
                for (k, v) in [("bookings", e.bookings), ("sales", e.sales), ("cost", e.cost), ("commission", e.commission), ("direct", e.directExpenses)] {
                    agg[key]![k, default: 0] += v
                }
            }
            var out: [[String: JSONValue]] = order.map { k in
                let a = agg[k]!
                var r: [String: JSONValue] = [isAirline ? "airline" : "supplier": S(k)]
                for f in ["bookings", "sales", "cost", "commission", "direct"] { r[f] = I(a[f] ?? 0) }
                r["gross_profit"] = I((a["sales"] ?? 0) - (a["cost"] ?? 0) + (a["commission"] ?? 0) - (a["direct"] ?? 0))
                return r
            }
            let sortKey = isAirline ? "sales" : "cost"
            out = out.enumerated().sorted { (-$0.element[sortKey]!.intValue!, $0.offset) < (-$1.element[sortKey]!.intValue!, $1.offset) }.map { $0.element }
            let cols = isAirline
                ? [col("airline", "Airline (first segment)"), col("bookings", "Bookings", "number"), col("sales", "Net sales", M),
                   col("cost", "Net cost", M), col("commission", "Commission", M), col("direct", "Direct exp.", M), col("gross_profit", "Gross profit", M)]
                : [col("supplier", "Supplier / portal"), col("bookings", "Bookings", "number"), col("cost", "Net purchases", M),
                   col("commission", "Commission", M), col("sales", "Net sales", M), col("gross_profit", "Gross profit", M)]
            var t = totalsOf(out, cols)
            t["bookings"] = I(out.reduce(0) { $0 + ($1["bookings"]?.intValue ?? 0) })
            return make(isAirline ? "Airline-wise sales" : "Supplier-wise purchases", cols, out, t)

        default:
            throw AZError.notFound("Unknown report")
        }
    }

    var PERIOD_COLS: [ReportColumn] {
        [col("period", "Period"), col("bookings", "Bookings", "number"), col("sales", "Net sales", M), col("cost", "Net cost", M),
         col("commission", "Commission", M), col("direct", "Direct exp.", M), col("gross_profit", "Gross profit", M),
         col("other_income", "Other income", M), col("op_expenses", "Operating exp.", M), col("net_profit", "Net profit", M),
         col("tax", "Tax collected", M), col("cash_in", "Cash in", M), col("cash_out", "Cash out", M), col("net_cash", "Net cash flow", M)]
    }

    private func periodRows(_ periods: [(String, String, String)]) -> [[String: JSONValue]] {
        periods.map { label, frm, to in
            let s = periodSummary(frm, to), c = s.cash
            return ["period": S(label), "bookings": I(s.bookings), "sales": I(s.sales), "cost": I(s.cost), "commission": I(s.commission),
                    "direct": I(s.directExpenses), "gross_profit": I(s.grossProfit), "other_income": I(s.otherIncome),
                    "op_expenses": I(s.operatingExpenses), "net_profit": I(s.netProfit), "tax": I(s.tax),
                    "cash_in": I(c.cashIn), "cash_out": I(c.cashOut), "net_cash": I(c.netCashFlow)]
        }
    }

    private func cashbook(_ p: ReportParams) throws -> Report {
        let frm = p["from"], to = p["to"], accId = p["account_id"]
        let accs = accountOrder.compactMap { accounts[$0] }.filter { accId == nil || String($0.int("id")) == accId }
        if accId != nil && accs.isEmpty { throw AZError.notFound("Account not found") }
        let single = accs.count == 1
        var out: [[String: JSONValue]] = []
        var opening = single ? accs[0].int("opening_balance") : 0
        let ids = Set(accs.map { $0.int("id") })
        for t in transactions {
            for (aid, eff) in accountEffects(t) where ids.contains(aid) {
                if single, let frm, t.txnDate < frm { opening += eff; continue }
                if !inRange(t.txnDate, frm, to) { continue }
                let r = txnRow(t)
                var party = [r["customer_name"]!.pyStr, r["supplier_name"]!.pyStr, t.payee ?? ""].first { !$0.isEmpty } ?? ""
                if party.isEmpty && t.kind == "transfer" { party = r["to_account_name"]!.pyStr }
                out.append(["txn_date": S(t.txnDate), "txn_no": S(t.txnNo), "account": S(accounts[aid]!.str("name")),
                            "type": S(KIND_LABELS[t.kind]! + (t.sign < 0 ? " (reversal)" : "")), "party": S(party),
                            "booking": r["booking_ref"]!, "reference": S(t.reference ?? ""), "method": S(t.method),
                            "currency": S(accounts[aid]!.str("currency")), "in": I(eff > 0 ? eff : 0), "out": I(eff < 0 ? -eff : 0),
                            "balance": 0, "_txn_id": I(t.id), "_sup": J(t.supplierId), "_kind": S(t.kind)])
            }
        }
        sortRows(&out, by: ["txn_date", "txn_no"])
        if single {
            var bal = opening
            for i in out.indices { bal += out[i]["in"]!.intValue! - out[i]["out"]!.intValue!; out[i]["balance"] = I(bal) }
        }
        let closing = opening + out.reduce(0) { $0 + $1["in"]!.intValue! - $1["out"]!.intValue! }
        let supId = p["supplier_id"]
        let kinds = Set((p["kind"] ?? "").split(separator: ",").map(String.init).filter { !$0.isEmpty })
        let filtered = supId != nil || !kinds.isEmpty
        if let supId { out = out.filter { ($0["_sup"]!.isNull ? "" : $0["_sup"]!.pyStr) == supId } }
        if !kinds.isEmpty { out = out.filter { kinds.contains($0["_kind"]!.pyStr) } }
        var cols = [col("txn_date", "Date", "date"), col("txn_no", "No."), col("account", "Account"), col("type", "Type"),
                    col("party", "Party"), col("booking", "Booking"), col("reference", "Reference"), col("method", "Method"),
                    col("currency", "Cur"), col("in", "Money in", "money_raw"), col("out", "Money out", "money_raw")]
        if single { cols.append(col("balance", "Balance", "money_raw")) }
        var t: [String: JSONValue] = [:]
        if Set(out.map { $0["currency"]!.pyStr }).count <= 1 {
            t = ["in": I(out.reduce(0) { $0 + $1["in"]!.intValue! }), "out": I(out.reduce(0) { $0 + $1["out"]!.intValue! })]
        }
        var summary: [JSONValue] = []
        if single {
            summary = [["label": "Opening balance", "value": I(opening), "raw": true],
                       ["label": "Money in", "value": I(out.reduce(0) { $0 + $1["in"]!.intValue! }), "raw": true],
                       ["label": "Money out", "value": I(out.reduce(0) { $0 + $1["out"]!.intValue! }), "raw": true],
                       ["label": "Closing balance", "value": I(closing), "raw": true, "strong": true]]
        }
        var note: String? = single ? nil : "Choose a single account to see running balances. Amounts are in each account's currency."
        if filtered {
            note = (note.map { $0 + " " } ?? "") + "Filtered view: money in / out add up the rows shown; the balance column and closing balance "
                + "are the account's full running balance."
        }
        return Report(name: "cashbook", title: "Cashbook" + (single ? " - " + accs[0].str("name") : ""), columns: cols, rows: out, totals: t,
                      summary: summary, note: note, base: base)
    }

    // MARK: statements

    public struct Statement {
        public let party: Row
        public let kind: String
        public let opening: Int, closing: Int, totalDebit: Int, totalCredit: Int
        public let lines: [[String: JSONValue]]
        public let from: String?, to: String?
        public var json: JSONValue {
            ["kind": .string(kind), "opening": .int(opening), "closing": .int(closing), "total_debit": .int(totalDebit),
             "total_credit": .int(totalCredit), "lines": .array(lines.map { .object($0) }), "from": J(from), "to": J(to)]
        }
    }

    public func customerStatement(_ cid: Int, from frm: String? = nil, to: String? = nil) throws -> Statement {
        guard let c = customers[cid] else { throw AZError.notFound("Customer not found") }
        var lines: [[String: JSONValue]] = []
        for b in bookingList where b.customerId == cid && b.committed {
            let x = b.calc.base
            let amt = x.selling_price + x.service_charge + x.other_income + x.tax_amount
            if amt != 0 {
                lines.append(["date": S(b.bookingDate), "type": "Booking", "ref": S(b.ref),
                              "description": S("\(V.capitalize(b.serviceType))\(b.pnr.map { $0.isEmpty ? "" : " PNR " + $0 } ?? "") \(b.route)"),
                              "debit": I(amt), "credit": 0, "booking_id": I(b.id)])
            }
            for a in adjustments[b.id] ?? [] {
                let ch = a.sign * convert(a["extra_customer_charge"], a.exchangeRate)
                let cr = a.sign * convert(a["customer_credit"], a.exchangeRate)
                let kindText = V.capitalize(a.kind.replacingOccurrences(of: "_", with: " "))
                if ch != 0 {
                    lines.append(["date": S(a.adjDate), "type": "Charge", "ref": S(a.adjNo), "description": S("\(kindText) charges - \(b.ref)"),
                                  "debit": I(ch), "credit": 0, "booking_id": I(b.id)])
                }
                if cr != 0 {
                    lines.append(["date": S(a.adjDate), "type": "Credit", "ref": S(a.adjNo),
                                  "description": S("\(kindText) credit - \(b.ref)\(a.sign < 0 ? " (reversal)" : "")"),
                                  "debit": 0, "credit": I(cr), "booking_id": I(b.id)])
                }
            }
        }
        for t in transactions where t.customerId == cid && (t.kind == "customer_receipt" || t.kind == "customer_refund") {
            let v = t.sign * t.baseAmount
            let ref = bookings[t.bookingId ?? -1]?.ref ?? ""
            var desc = KIND_LABELS[t.kind]! + (ref.isEmpty ? "" : " - " + ref)
                + " (" + t.method.replacingOccurrences(of: "_", with: " ") + (t.reference.map { $0.isEmpty ? "" : " " + $0 } ?? "") + ")"
            if t.sign < 0 { desc += " - reversal" }
            if t.kind == "customer_receipt" {
                lines.append(["date": S(t.txnDate), "type": "Receipt", "ref": S(t.txnNo), "description": S(desc), "debit": 0, "credit": I(v), "txn_id": I(t.id)])
            } else {
                lines.append(["date": S(t.txnDate), "type": "Refund", "ref": S(t.txnNo), "description": S(desc), "debit": I(v), "credit": 0, "txn_id": I(t.id)])
            }
        }
        return running(lines, frm, to, party: c, kind: "customer")
    }

    public func supplierStatement(_ sid: Int, from frm: String? = nil, to: String? = nil) throws -> Statement {
        guard let s = suppliers[sid] else { throw AZError.notFound("Supplier not found") }
        var lines: [[String: JSONValue]] = []
        for b in bookingList where b.supplierId == sid && b.committed {
            let x = b.calc.base
            if x.purchase_cost != 0 {
                lines.append(["date": S(b.bookingDate), "type": "Purchase", "ref": S(b.ref),
                              "description": S("Purchase\(b.pnr.map { $0.isEmpty ? "" : " PNR " + $0 } ?? "") \(b.route)"),
                              "debit": I(x.purchase_cost), "credit": 0, "booking_id": I(b.id)])
            }
            if x.commission != 0 {
                lines.append(["date": S(b.bookingDate), "type": "Commission", "ref": S(b.ref), "description": "Commission earned",
                              "debit": 0, "credit": I(x.commission), "booking_id": I(b.id)])
            }
            for a in adjustments[b.id] ?? [] {
                func cv(_ k: String) -> Int { a.sign * convert(a[k], a.exchangeRate) }
                if a["extra_supplier_charge"] != 0 {
                    lines.append(["date": S(a.adjDate), "type": "Charge", "ref": S(a.adjNo), "description": S("Supplier charges - " + b.ref),
                                  "debit": I(cv("extra_supplier_charge")), "credit": 0, "booking_id": I(b.id)])
                }
                if a["supplier_credit"] != 0 {
                    lines.append(["date": S(a.adjDate), "type": "Credit", "ref": S(a.adjNo), "description": S("Supplier credit - " + b.ref),
                                  "debit": 0, "credit": I(cv("supplier_credit")), "booking_id": I(b.id)])
                }
                if a["commission_reversal"] != 0 {
                    lines.append(["date": S(a.adjDate), "type": "Commission reversed", "ref": S(a.adjNo), "description": S("Commission reversed - " + b.ref),
                                  "debit": I(cv("commission_reversal")), "credit": 0, "booking_id": I(b.id)])
                }
            }
        }
        for t in transactions where t.supplierId == sid && (t.kind == "supplier_payment" || t.kind == "supplier_receipt") {
            let v = t.sign * t.baseAmount
            let ref = bookings[t.bookingId ?? -1]?.ref ?? ""
            let desc = KIND_LABELS[t.kind]! + (ref.isEmpty ? "" : " - " + ref) + " (" + t.method.replacingOccurrences(of: "_", with: " ")
                + (t.reference.map { $0.isEmpty ? "" : " " + $0 } ?? "") + ")" + (t.sign < 0 ? " - reversal" : "")
            if t.kind == "supplier_payment" {
                lines.append(["date": S(t.txnDate), "type": "Payment", "ref": S(t.txnNo), "description": S(desc), "debit": 0, "credit": I(v), "txn_id": I(t.id)])
            } else {
                lines.append(["date": S(t.txnDate), "type": "Refund received", "ref": S(t.txnNo), "description": S(desc), "debit": I(v), "credit": 0, "txn_id": I(t.id)])
            }
        }
        return running(lines, frm, to, party: s, kind: "supplier")
    }

    private func running(_ input: [[String: JSONValue]], _ frm: String?, _ to: String?, party: Row, kind: String) -> Statement {
        let order = ["Booking": 0, "Purchase": 0, "Commission": 1, "Charge": 2, "Credit": 3, "Commission reversed": 4]
        let lines = input.enumerated().sorted { a, b in
            let x = (a.element["date"]!.pyStr, order[a.element["type"]!.pyStr] ?? 5, a.element["ref"]!.pyStr)
            let y = (b.element["date"]!.pyStr, order[b.element["type"]!.pyStr] ?? 5, b.element["ref"]!.pyStr)
            return x != y ? x < y : a.offset < b.offset
        }.map { $0.element }
        var opening = 0
        var out: [[String: JSONValue]] = []
        for l in lines {
            let d = l["date"]!.pyStr
            if let frm, d < frm { opening += l["debit"]!.intValue! - l["credit"]!.intValue!; continue }
            if let to, d > to { continue }
            out.append(l)
        }
        var bal = opening
        for i in out.indices { bal += out[i]["debit"]!.intValue! - out[i]["credit"]!.intValue!; out[i]["balance"] = I(bal) }
        return Statement(party: party, kind: kind, opening: opening, closing: bal,
                         totalDebit: out.reduce(0) { $0 + $1["debit"]!.intValue! }, totalCredit: out.reduce(0) { $0 + $1["credit"]!.intValue! },
                         lines: out, from: frm, to: to)
    }

    // MARK: dashboard

    public struct Dashboard {
        public let summary: ProfitTotals
        public let today: ProfitTotals, month: ProfitTotals, year: ProfitTotals
        public let customerOutstanding: Int, customerCreditBalances: Int, supplierOutstanding: Int, supplierCreditBalances: Int
        public let pending: [[String: JSONValue]], pendingCount: Int, cancellationsCount: Int
        public let accounts: [AccountBalance]
        public let chart: [[String: JSONValue]]
        public let recentBookings: [[String: JSONValue]], recentTransactions: [[String: JSONValue]]
        public let upcoming: [[String: JSONValue]], followUps: [[String: JSONValue]]

        public var json: JSONValue {
            func pick(_ t: ProfitTotals) -> JSONValue { ["bookings": .int(t.bookings), "sales": .int(t.sales), "gross_profit": .int(t.grossProfit)] }
            return ["summary": summary.summaryJSON, "today": pick(today), "month": pick(month), "year": pick(year),
                    "customer_outstanding": .int(customerOutstanding), "customer_credit_balances": .int(customerCreditBalances),
                    "supplier_outstanding": .int(supplierOutstanding), "supplier_credit_balances": .int(supplierCreditBalances),
                    "pending_count": .int(pendingCount), "pending": .array(pending.map { .object($0) }), "cancellations_count": .int(cancellationsCount),
                    "accounts": .array(accounts.map { $0.json }), "chart": .array(chart.map { .object($0) }),
                    "upcoming": .array(upcoming.map { .object($0) }), "follow_ups": .array(followUps.map { .object($0) })]
        }
    }

    public func dashboard(from frm: String?, to: String?, today: String? = nil) -> Dashboard {
        let today = today ?? Dates.today()
        let s = periodSummary(frm, to)
        let monthStart = String(today.prefix(8)) + "01"
        let yearStart = String(today.prefix(5)) + "01-01"
        let cb = customerBalances(), sb = supplierBalances()
        var pending = bookingList.filter { $0.committed && $0.calc.customerOutstanding > 0 }.map { bookingRow($0) }
        pending = pending.enumerated().sorted { a, b in
            let x = (a.element["travel_date"]!.isNull ? "9999" : a.element["travel_date"]!.pyStr, a.element["ref"]!.pyStr)
            let y = (b.element["travel_date"]!.isNull ? "9999" : b.element["travel_date"]!.pyStr, b.element["ref"]!.pyStr)
            return x != y ? x < y : a.offset < b.offset
        }.map { $0.element }
        let cancellations = allAdjustments.filter { inRange($0.adjDate, frm, to) && ["cancellation", "refund"].contains($0.kind) && $0.sign == 1 && $0.reversedBy == nil }
        let accts = accountBalances().values.sorted { ($0.kind, $0.name) < ($1.kind, $1.name) }

        // chart: daily when the range is 62 days or less, otherwise monthly
        var buckets: [String: [String: JSONValue]] = [:], bucketOrder: [String] = []
        let d0 = frm.flatMap { Dates.parse($0) != nil ? $0 : nil }, d1 = to.flatMap { Dates.parse($0) != nil ? $0 : nil }
        let daily: Bool
        if let d0, let d1, let n = Dates.daysBetween(d0, d1), n <= 62 {
            daily = true
            var d = d0
            while d <= d1 {
                let day = Int(d.dropFirst(8))!, m = Int(d.dropFirst(5).prefix(2))!
                buckets[d] = ["label": S(String(format: "%02d ", day) + Dates.monthAbbr[m - 1]), "key": S(d), "sales": 0, "gross_profit": 0, "cost": 0]
                bucketOrder.append(d)
                d = Dates.addDays(d, 1)
            }
        } else {
            daily = false
            let end = d1 ?? Dates.today()
            let start = d0 ?? Dates.addDays(String(end.prefix(8)) + "01", -334)
            var y = Int(start.prefix(4))!, m = Int(start.dropFirst(5).prefix(2))!
            let ey = Int(end.prefix(4))!, em = Int(end.dropFirst(5).prefix(2))!
            while (y, m) <= (ey, em) {
                let k = String(format: "%04d-%02d", y, m)
                buckets[k] = ["label": S(monthLabel(y, m, short: true)), "key": S(k), "sales": 0, "gross_profit": 0, "cost": 0]
                bucketOrder.append(k)
                m += 1
                if m > 12 { y += 1; m = 1 }
            }
        }
        for e in events {
            let k = daily ? String(e.date.prefix(10)) : String(e.date.prefix(7))
            guard buckets[k] != nil, inRange(e.date, frm, to) else { continue }
            buckets[k]!["sales"] = I(buckets[k]!["sales"]!.intValue! + e.sales)
            buckets[k]!["cost"] = I(buckets[k]!["cost"]!.intValue! + e.cost)
            buckets[k]!["gross_profit"] = I(buckets[k]!["gross_profit"]!.intValue! + e.sales - e.cost + e.commission - e.directExpenses)
        }

        let recentB = bookingList.sorted { ($0.row.str("created_at"), $0.id) > ($1.row.str("created_at"), $1.id) }.prefix(8).map { bookingRow($0) }
        let recentT = transactions.sorted { ($0.createdAt, $0.id) > ($1.createdAt, $1.id) }.prefix(8).map { txnRow($0) }

        let horizon = Dates.addDays(today, 14)
        var upcoming: [[String: JSONValue]] = []
        for b in bookingList where !["cancelled", "refunded", "enquiry"].contains(b.status) {
            let segs = segments[b.id] ?? []
            for sg in segs {
                let dep = String(sg.str("dep_at").prefix(10))
                if !dep.isEmpty && today <= dep && dep <= horizon {
                    upcoming.append(["booking_id": I(b.id), "ref": S(b.ref), "pnr": J(b.pnr), "dep_at": sg["dep_at"].json, "airline": sg["airline"].json,
                                     "flight_no": sg["flight_no"].json, "origin": sg["origin"].json, "destination": sg["destination"].json,
                                     "lead_pax": S(b.leadPax), "pax_count": I(b.paxCount), "customer_name": S(b.customerName),
                                     "outstanding": I(b.calc.customerOutstanding)])
                }
            }
            if segs.isEmpty, let td = b.travelDate, !td.isEmpty, today <= td, td <= horizon {
                upcoming.append(["booking_id": I(b.id), "ref": S(b.ref), "pnr": J(b.pnr), "dep_at": S(td), "airline": "", "flight_no": "",
                                 "origin": "", "destination": "", "lead_pax": S(b.leadPax), "pax_count": I(b.paxCount),
                                 "customer_name": S(b.customerName), "outstanding": I(b.calc.customerOutstanding)])
            }
        }
        upcoming = upcoming.enumerated().sorted { a, b in
            let x = a.element["dep_at"]!.isNull ? "" : a.element["dep_at"]!.pyStr, y = b.element["dep_at"]!.isNull ? "" : b.element["dep_at"]!.pyStr
            return x != y ? x < y : a.offset < b.offset
        }.map { $0.element }
        var follow: [[String: JSONValue]] = []
        for cid in customerOrder {
            let c = customers[cid]!
            guard let fd = c.optStr("follow_up_date"), !fd.isEmpty, fd <= horizon, c.bool("active") else { continue }
            follow.append(["customer_id": I(cid), "name": S(c.str("name")), "phone": c["phone"].json, "follow_up_date": S(fd),
                           "follow_up_note": c["follow_up_note"].json, "overdue": .bool(fd < today), "outstanding": I(cb[cid]!.outstanding)])
        }
        follow = follow.enumerated().sorted { ($0.element["follow_up_date"]!.pyStr, $0.offset) < ($1.element["follow_up_date"]!.pyStr, $1.offset) }.map { $0.element }

        return Dashboard(summary: s, today: profitTotals(today, today), month: profitTotals(monthStart, today), year: profitTotals(yearStart, today),
                         customerOutstanding: cb.values.filter { $0.outstanding > 0 }.reduce(0) { $0 + $1.outstanding },
                         customerCreditBalances: cb.values.filter { $0.outstanding < 0 }.reduce(0) { $0 - $1.outstanding },
                         supplierOutstanding: sb.values.filter { $0.outstanding > 0 }.reduce(0) { $0 + $1.outstanding },
                         supplierCreditBalances: sb.values.filter { $0.outstanding < 0 }.reduce(0) { $0 - $1.outstanding },
                         pending: Array(pending.prefix(8)), pendingCount: pending.count, cancellationsCount: cancellations.count,
                         accounts: accts, chart: bucketOrder.map { buckets[$0]! }, recentBookings: Array(recentB), recentTransactions: Array(recentT),
                         upcoming: Array(upcoming.prefix(12)), followUps: Array(follow.prefix(12)))
    }
}
