import Foundation

// Port of core/server/finance.py. Every dashboard figure, report and balance is produced here from the raw
// database records, so all totals reconcile with one another - and with the desktop app, which computes
// them with the same definitions:
//
//   Markup                = Selling price - Purchase cost
//   Gross booking profit  = Markup + Commission + Service charge + Other income
//                           - Direct booking expenses (+/- adjustments)
//   Net profit            = Gross profit + Other (non-booking) income - Operating expenses
//   Customer outstanding  = Customer payable - Credits - Allocated receipts + Refunds paid
//   Supplier outstanding  = Supplier payable - Supplier credits (incl. commission)
//                           - Allocated payments + Supplier refunds/receipts
//   Cash flow             = Actual cash received - Actual cash paid (transfers excluded)
//
// Tax collected is a liability, never revenue or profit. A booking creates payables and revenue only
// once it is confirmed or ticketed ("committed").

public let INFLOW_KINDS: Set<String> = ["customer_receipt", "supplier_receipt", "other_income"]
public let OUTFLOW_KINDS: Set<String> = ["supplier_payment", "customer_refund", "expense"]
public let FINANCING_IN_KINDS: Set<String> = ["loan_received"]
public let FINANCING_OUT_KINDS: Set<String> = ["loan_repayment"]
public let KIND_LABELS: [String: String] = [
    "customer_receipt": "Customer receipt",
    "supplier_payment": "Supplier payment",
    "customer_refund": "Refund to customer",
    "supplier_receipt": "Supplier refund / receipt",
    "expense": "Expense",
    "other_income": "Other income",
    "transfer": "Transfer",
    "loan_received": "Loan received (financing)",
    "loan_repayment": "Loan repayment (principal)",
]
public let KIND_ORDER = ["customer_receipt", "supplier_payment", "customer_refund", "supplier_receipt", "expense",
                         "other_income", "transfer", "loan_received", "loan_repayment"]

let ADJ_FIELDS = ["customer_credit", "customer_credit_tax", "supplier_credit", "commission_reversal",
                  "extra_customer_charge", "extra_supplier_charge"]

func inRange(_ d: String?, _ frm: String?, _ to: String?) -> Bool {
    guard let d0 = d, !d0.isEmpty else { return false }
    let d = String(d0.prefix(10))
    if let frm, !frm.isEmpty, d < frm { return false }
    if let to, !to.isEmpty, d > to { return false }
    return true
}

/// A posted money movement (one row of `transactions`).
public struct Txn {
    public let id: Int, txnNo: String, uid: String, txnDate: String, kind: String, sign: Int, amount: Int
    public let currency: String, exchangeRate: String, rateDate: String?, baseAmount: Int
    public let accountId: Int, toAccountId: Int?, toAmount: Int?
    public let method: String, reference: String?, category: String
    public let customerId: Int?, supplierId: Int?, bookingId: Int?, isDirect: Bool
    public let payee: String?, notes: String?, reversalOf: Int?, reversedBy: Int?
    public let reconciled: Bool, reconciledOn: String?, createdAt: String, debtId: Int?

    init(_ r: Row) {
        id = r.int("id"); txnNo = r.str("txn_no"); uid = r.str("uid"); txnDate = r.str("txn_date"); kind = r.str("kind")
        sign = r.int("sign"); amount = r.int("amount"); currency = r.str("currency"); exchangeRate = r.str("exchange_rate")
        rateDate = r.optStr("rate_date"); baseAmount = r.int("base_amount"); accountId = r.int("account_id")
        toAccountId = r.optInt("to_account_id"); toAmount = r.optInt("to_amount"); method = r.str("method")
        reference = r.optStr("reference"); category = r.str("category"); customerId = r.optInt("customer_id")
        supplierId = r.optInt("supplier_id"); bookingId = r.optInt("booking_id"); isDirect = r.bool("is_direct")
        payee = r.optStr("payee"); notes = r.optStr("notes"); reversalOf = r.optInt("reversal_of"); reversedBy = r.optInt("reversed_by")
        reconciled = r.bool("reconciled"); reconciledOn = r.optStr("reconciled_on"); createdAt = r.str("created_at")
        debtId = r.has("debt_id") ? r.optInt("debt_id") : nil
    }
}

/// A booking adjustment (credit, refund, reissue charge ...).
public struct Adjustment {
    public let id: Int, adjNo: String, bookingId: Int, adjDate: String, kind: String, sign: Int, exchangeRate: String
    public let values: [String: Int]
    public let notes: String?, reversalOf: Int?, reversedBy: Int?, createdAt: String
    public let row: Row

    init(_ r: Row) {
        row = r
        id = r.int("id"); adjNo = r.str("adj_no"); bookingId = r.int("booking_id"); adjDate = r.str("adj_date")
        kind = r.str("kind"); sign = r.int("sign"); exchangeRate = r.str("exchange_rate")
        var v: [String: Int] = [:]
        for f in ADJ_FIELDS { v[f] = r.int(f) }
        values = v
        notes = r.optStr("notes"); reversalOf = r.optInt("reversal_of"); reversedBy = r.optInt("reversed_by"); createdAt = r.str("created_at")
    }
    public subscript(_ f: String) -> Int { values[f] ?? 0 }
}

public struct BookingBase {
    public var purchase_cost = 0, selling_price = 0, commission = 0, service_charge = 0, other_income = 0, tax_amount = 0
    var json: JSONValue {
        ["purchase_cost": .int(purchase_cost), "selling_price": .int(selling_price), "commission": .int(commission),
         "service_charge": .int(service_charge), "other_income": .int(other_income), "tax_amount": .int(tax_amount)]
    }
}

/// The figures computed for one booking (finance.Ledger._compute_booking).
public struct BookingCalc {
    public var base = BookingBase()
    public var markup = 0, markupOriginal = 0
    public var customerPayable = 0, customerCredits = 0, received = 0, refunded = 0, customerOutstanding = 0
    public var supplierPayable = 0, supplierCredits = 0, paidSupplier = 0, supplierRefunds = 0, supplierOutstanding = 0
    public var revenue = 0, cost = 0, commissionNet = 0, taxNet = 0, directExpenses = 0, grossProfit = 0
    public var paymentStatus = ""

    public var json: JSONValue {
        ["base": base.json, "markup": .int(markup), "markup_original": .int(markupOriginal),
         "customer_payable": .int(customerPayable), "customer_credits": .int(customerCredits), "received": .int(received),
         "refunded": .int(refunded), "customer_outstanding": .int(customerOutstanding), "supplier_payable": .int(supplierPayable),
         "supplier_credits": .int(supplierCredits), "paid_supplier": .int(paidSupplier), "supplier_refunds": .int(supplierRefunds),
         "supplier_outstanding": .int(supplierOutstanding), "revenue": .int(revenue), "cost": .int(cost),
         "commission_net": .int(commissionNet), "tax_net": .int(taxNet), "direct_expenses": .int(directExpenses),
         "gross_profit": .int(grossProfit), "payment_status": .string(paymentStatus)]
    }
}

public final class LedgerBooking {
    public let row: Row
    public var calc = BookingCalc()
    public var airlines: [String] = []
    public var route = ""
    public var paxCount = 0
    public var leadPax = ""
    public var customerName = ""
    public var customerPhone: String?
    public var supplierName = ""

    init(_ r: Row) { row = r }
    public var id: Int { row.int("id") }
    public var ref: String { row.str("ref") }
    public var pnr: String? { row.optStr("pnr") }
    public var status: String { row.str("status") }
    public var committed: Bool { row.bool("committed") }
    public var customerId: Int { row.int("customer_id") }
    public var supplierId: Int? { row.optInt("supplier_id") }
    public var bookingDate: String { row.str("booking_date") }
    public var travelDate: String? { row.optStr("travel_date") }
    public var currency: String { row.str("currency") }
    public var serviceType: String { row.str("service_type") }
}

/// A dated profit event, so period reports are exact.
public struct ProfitEvent {
    public var date: String, bookingId: Int, type: String, adjustmentId: Int? = nil
    public var bookings = 0, selling = 0, purchase = 0, sales = 0, billed = 0, cost = 0, markup = 0, commission = 0
    public var service = 0, otherBookingIncome = 0, tax = 0, directExpenses = 0, customerCredits = 0, supplierCredits = 0
    public var commissionReversed = 0, extraCustomerCharges = 0, extraSupplierCharges = 0
}

/// Profit totals for a period (finance._zero plus derived figures).
public struct ProfitTotals {
    public var bookings = 0, sales = 0, billed = 0, selling = 0, purchase = 0, cost = 0, markup = 0, commission = 0
    public var service = 0, otherBookingIncome = 0, tax = 0, directExpenses = 0, grossProfit = 0, customerCredits = 0
    public var supplierCredits = 0, commissionReversed = 0, extraCustomerCharges = 0, extraSupplierCharges = 0
    public var operatingExpenses = 0, otherIncome = 0, netProfit = 0
    public var cash = CashTotals()

    mutating func add(_ e: ProfitEvent) {
        bookings += e.bookings; sales += e.sales; billed += e.billed; selling += e.selling; purchase += e.purchase
        cost += e.cost; markup += e.markup; commission += e.commission; service += e.service
        otherBookingIncome += e.otherBookingIncome; tax += e.tax; directExpenses += e.directExpenses
        customerCredits += e.customerCredits; supplierCredits += e.supplierCredits; commissionReversed += e.commissionReversed
        extraCustomerCharges += e.extraCustomerCharges; extraSupplierCharges += e.extraSupplierCharges
    }

    /// profit_totals() keys only
    public var profitJSON: [String: JSONValue] {
        ["bookings": .int(bookings), "sales": .int(sales), "billed": .int(billed), "selling": .int(selling), "purchase": .int(purchase),
         "cost": .int(cost), "markup": .int(markup), "commission": .int(commission), "service": .int(service),
         "other_booking_income": .int(otherBookingIncome), "tax": .int(tax), "direct_expenses": .int(directExpenses),
         "gross_profit": .int(grossProfit), "customer_credits": .int(customerCredits), "supplier_credits": .int(supplierCredits),
         "commission_reversed": .int(commissionReversed), "extra_customer_charges": .int(extraCustomerCharges),
         "extra_supplier_charges": .int(extraSupplierCharges)]
    }

    /// period_summary() result
    public var summaryJSON: JSONValue {
        var o = profitJSON
        o["operating_expenses"] = .int(operatingExpenses)
        o["other_income"] = .int(otherIncome)
        o["net_profit"] = .int(netProfit)
        o["cash"] = cash.json
        return .object(o)
    }
}

public struct CashTotals {
    public var customerReceipts = 0, supplierPayments = 0, customerRefunds = 0, supplierReceipts = 0, expenses = 0
    public var operatingExpenses = 0, directExpensesPaid = 0, otherIncome = 0, cashIn = 0, cashOut = 0, netCashFlow = 0
    public var loanReceived = 0, loanRepaid = 0, netFinancing = 0
    public var json: JSONValue {
        ["customer_receipts": .int(customerReceipts), "supplier_payments": .int(supplierPayments), "customer_refunds": .int(customerRefunds),
         "supplier_receipts": .int(supplierReceipts), "expenses": .int(expenses), "operating_expenses": .int(operatingExpenses),
         "direct_expenses_paid": .int(directExpensesPaid), "other_income": .int(otherIncome), "cash_in": .int(cashIn),
         "cash_out": .int(cashOut), "net_cash_flow": .int(netCashFlow), "loan_received": .int(loanReceived),
         "loan_repaid": .int(loanRepaid), "net_financing": .int(netFinancing)]
    }
}

/// Filter keys shared by reports (customer_id, supplier_id, currency, status, airline, service_type, account_id).
public struct LedgerFilter {
    public var customerId: String?, supplierId: String?, currency: String?, status: String?, serviceType: String?
    public var airline: String?, accountId: String?
    public init(customerId: String? = nil, supplierId: String? = nil, currency: String? = nil, status: String? = nil,
                serviceType: String? = nil, airline: String? = nil, accountId: String? = nil) {
        func clean(_ s: String?) -> String? { (s?.isEmpty ?? true) ? nil : s }
        self.customerId = clean(customerId); self.supplierId = clean(supplierId); self.currency = clean(currency)
        self.status = clean(status); self.serviceType = clean(serviceType); self.airline = clean(airline); self.accountId = clean(accountId)
    }
    var isEmpty: Bool { [customerId, supplierId, currency, status, serviceType, airline, accountId].allSatisfy { $0 == nil } }
    /// Filters that narrow to specific bookings (operating expenses and other income then do not apply).
    var bookingScoped: Bool { [airline, status, serviceType, supplierId, customerId].contains { $0 != nil } }
}

public final class Ledger {
    public let base: String
    public private(set) var customers: [Int: Row] = [:]
    public private(set) var customerOrder: [Int] = []
    public private(set) var suppliers: [Int: Row] = [:]
    public private(set) var supplierOrder: [Int] = []
    public private(set) var accounts: [Int: Row] = [:]
    public private(set) var accountOrder: [Int] = []
    public private(set) var bookings: [Int: LedgerBooking] = [:]
    public private(set) var bookingOrder: [Int] = []
    public private(set) var segments: [Int: [Row]] = [:]
    public private(set) var passengers: [Int: [Row]] = [:]
    public private(set) var adjustments: [Int: [Adjustment]] = [:]
    public private(set) var allAdjustments: [Adjustment] = []
    public private(set) var transactions: [Txn] = []
    public private(set) var txnById: [Int: Txn] = [:]
    public private(set) var allocations: [Int: [(bookingId: Int, amount: Int, id: Int)]] = [:]
    public private(set) var allocByBooking: [Int: [(txnId: Int, amount: Int)]] = [:]
    public private(set) var directByBooking: [Int: [Txn]] = [:]
    public private(set) var debts: [Int: Row] = [:]
    public private(set) var events: [ProfitEvent] = []

    public init(_ agency: Agency) throws {
        let db = agency.db
        base = try agency.setting("base_currency", "INR") ?? "INR"
        for r in try db.query("SELECT * FROM customers") { customers[r.int("id")] = r; customerOrder.append(r.int("id")) }
        for r in try db.query("SELECT * FROM suppliers") { suppliers[r.int("id")] = r; supplierOrder.append(r.int("id")) }
        for r in try db.query("SELECT * FROM accounts") { accounts[r.int("id")] = r; accountOrder.append(r.int("id")) }
        for r in try db.query("SELECT * FROM bookings ORDER BY booking_date, id") {
            bookings[r.int("id")] = LedgerBooking(r)
            bookingOrder.append(r.int("id"))
        }
        for r in try db.query("SELECT * FROM booking_segments ORDER BY booking_id, seq, id") { segments[r.int("booking_id"), default: []].append(r) }
        for r in try db.query("SELECT * FROM booking_passengers ORDER BY booking_id, seq, id") { passengers[r.int("booking_id"), default: []].append(r) }
        for r in try db.query("SELECT * FROM booking_adjustments ORDER BY adj_date, id") {
            let a = Adjustment(r)
            adjustments[a.bookingId, default: []].append(a)
            allAdjustments.append(a)
        }
        transactions = try db.query("SELECT * FROM transactions ORDER BY txn_date, id").map(Txn.init)
        if try db.one("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'debts'") != nil {
            for r in try db.query("SELECT id, debt_no, creditor FROM debts") { debts[r.int("id")] = r }
        }
        for t in transactions { txnById[t.id] = t }
        for r in try db.query("SELECT * FROM allocations ORDER BY id") {
            allocations[r.int("txn_id"), default: []].append((r.int("booking_id"), r.int("amount"), r.int("id")))
            allocByBooking[r.int("booking_id"), default: []].append((r.int("txn_id"), r.int("amount")))
        }
        for t in transactions where t.kind == "expense" && t.isDirect && t.bookingId != nil {
            directByBooking[t.bookingId!, default: []].append(t)
        }
        for id in bookingOrder { compute(bookings[id]!) }
    }

    public var bookingList: [LedgerBooking] { bookingOrder.map { bookings[$0]! } }

    // MARK: bookings

    private func compute(_ b: LedgerBooking) {
        let r = b.row
        let rate = r.str("exchange_rate")
        var base = BookingBase()
        base.purchase_cost = convert(r.int("purchase_cost"), rate)
        base.selling_price = convert(r.int("selling_price"), rate)
        base.commission = convert(r.int("commission"), rate)
        base.service_charge = convert(r.int("service_charge"), rate)
        base.other_income = convert(r.int("other_income"), rate)
        base.tax_amount = convert(r.int("tax_amount"), rate)
        let committed = b.committed
        let k = committed ? 1 : 0
        var adj: [String: Int] = [:]
        for f in ADJ_FIELDS { adj[f] = 0 }
        if committed {
            for a in adjustments[b.id] ?? [] {
                for f in ADJ_FIELDS { adj[f]! += a.sign * convert(a[f], a.exchangeRate) }
            }
        }
        var alloc: [String: Int] = [:]
        for a in allocByBooking[b.id] ?? [] {
            let t = txnById[a.txnId]!
            alloc[t.kind, default: 0] += t.sign * a.amount
        }
        var direct = 0
        for t in directByBooking[b.id] ?? [] { direct += t.sign * t.baseAmount }

        let incomeCore = base.selling_price + base.service_charge + base.other_income
        let customerPayable = k * (incomeCore + base.tax_amount) + adj["extra_customer_charge"]!
        let customerCredits = adj["customer_credit"]!
        let supplierPayable = k * base.purchase_cost + adj["extra_supplier_charge"]!
        let supplierCredits = k * base.commission - adj["commission_reversal"]! + adj["supplier_credit"]!
        let received = alloc["customer_receipt", default: 0]
        let refunded = alloc["customer_refund", default: 0]
        let paidSupplier = alloc["supplier_payment", default: 0]
        let supplierRefunds = alloc["supplier_receipt", default: 0]

        let revenue = k * incomeCore + adj["extra_customer_charge"]! - (adj["customer_credit"]! - adj["customer_credit_tax"]!)
        let cost = k * base.purchase_cost + adj["extra_supplier_charge"]! - adj["supplier_credit"]!
        let commissionNet = k * base.commission - adj["commission_reversal"]!
        let taxNet = k * base.tax_amount - adj["customer_credit_tax"]!
        let gross = revenue - cost + commissionNet - direct

        let custOut = customerPayable - customerCredits - received + refunded
        let supOut = supplierPayable - supplierCredits - paidSupplier + supplierRefunds

        let payStatus: String
        if !committed {
            payStatus = received - refunded > 0 ? "advance" : "not_billed"
        } else if custOut <= 0 && customerPayable - customerCredits > 0 {
            payStatus = custOut == 0 ? "paid" : "overpaid"
        } else if custOut <= 0 {
            payStatus = custOut == 0 ? "settled" : "refund_due"
        } else if received > 0 {
            payStatus = "partial"
        } else {
            payStatus = "unpaid"
        }

        let segs = segments[b.id] ?? []
        var airlines: [String] = []
        for s in segs { if let a = s.optStr("airline"), !a.isEmpty, !airlines.contains(a) { airlines.append(a) } }
        var route = ""
        if !segs.isEmpty {
            var pts: [String] = []
            for s in segs {
                for p in [s.optStr("origin"), s.optStr("destination")] {
                    if let p, !p.isEmpty, pts.last != p { pts.append(p) }
                }
            }
            route = pts.joined(separator: "-")
        }
        let pax = passengers[b.id] ?? []
        var c = BookingCalc()
        c.base = base
        c.markup = k * (base.selling_price - base.purchase_cost)
        c.markupOriginal = r.int("selling_price") - r.int("purchase_cost")
        c.customerPayable = customerPayable; c.customerCredits = customerCredits; c.received = received; c.refunded = refunded
        c.customerOutstanding = custOut; c.supplierPayable = supplierPayable; c.supplierCredits = supplierCredits
        c.paidSupplier = paidSupplier; c.supplierRefunds = supplierRefunds; c.supplierOutstanding = supOut
        c.revenue = revenue; c.cost = cost; c.commissionNet = commissionNet; c.taxNet = taxNet; c.directExpenses = direct
        c.grossProfit = gross; c.paymentStatus = payStatus
        b.calc = c
        b.airlines = airlines
        b.route = route
        b.paxCount = pax.count
        b.leadPax = pax.first?.str("name") ?? ""
        b.customerName = customers[b.customerId]?.str("name") ?? ""
        b.customerPhone = customers[b.customerId]?.optStr("phone")
        b.supplierName = b.supplierId.flatMap { suppliers[$0]?.str("name") } ?? ""

        if committed {
            var e = ProfitEvent(date: b.bookingDate, bookingId: b.id, type: "booking")
            e.bookings = 1; e.selling = base.selling_price; e.purchase = base.purchase_cost
            e.sales = incomeCore; e.billed = incomeCore + base.tax_amount; e.cost = base.purchase_cost
            e.markup = base.selling_price - base.purchase_cost; e.commission = base.commission
            e.service = base.service_charge; e.otherBookingIncome = base.other_income; e.tax = base.tax_amount
            events.append(e)
            for a in adjustments[b.id] ?? [] {
                var cv: [String: Int] = [:]
                for f in ADJ_FIELDS { cv[f] = a.sign * convert(a[f], a.exchangeRate) }
                var x = ProfitEvent(date: a.adjDate, bookingId: b.id, type: "adjustment", adjustmentId: a.id)
                x.sales = cv["extra_customer_charge"]! - (cv["customer_credit"]! - cv["customer_credit_tax"]!)
                x.billed = cv["extra_customer_charge"]! - cv["customer_credit"]!
                x.cost = cv["extra_supplier_charge"]! - cv["supplier_credit"]!
                x.commission = -cv["commission_reversal"]!
                x.tax = -cv["customer_credit_tax"]!
                x.customerCredits = cv["customer_credit"]!
                x.supplierCredits = cv["supplier_credit"]!
                x.commissionReversed = cv["commission_reversal"]!
                x.extraCustomerCharges = cv["extra_customer_charge"]!
                x.extraSupplierCharges = cv["extra_supplier_charge"]!
                events.append(x)
            }
        }
        for t in directByBooking[b.id] ?? [] {
            var x = ProfitEvent(date: t.txnDate, bookingId: b.id, type: "direct_expense")
            x.directExpenses = t.sign * t.baseAmount
            events.append(x)
        }
    }

    // MARK: filters

    public func bookingMatches(_ b: LedgerBooking, _ f: LedgerFilter?) -> Bool {
        guard let f, !f.isEmpty else { return true }
        if let c = f.customerId, String(b.customerId) != c { return false }
        if let s = f.supplierId, (b.supplierId.map(String.init) ?? "") != s { return false }
        if let c = f.currency, b.currency != c { return false }
        if let s = f.status, b.status != s { return false }
        if let s = f.serviceType, b.serviceType != s { return false }
        if let a = f.airline {
            let needle = a.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if !b.airlines.contains(where: { $0.lowercased().contains(needle) || needle.isEmpty }) { return false }
        }
        return true
    }

    public func txnMatches(_ t: Txn, _ f: LedgerFilter?) -> Bool {
        guard let f, !f.isEmpty else { return true }
        if let c = f.customerId, (t.customerId.map(String.init) ?? "") != c { return false }
        if let s = f.supplierId, (t.supplierId.map(String.init) ?? "") != s { return false }
        if let c = f.currency, t.currency != c { return false }
        if let a = f.accountId, String(t.accountId) != a, (t.toAccountId.map(String.init) ?? "") != a { return false }
        if f.airline != nil || f.status != nil || f.serviceType != nil {
            guard let bid = t.bookingId, let b = bookings[bid] else { return false }
            if !bookingMatches(b, LedgerFilter(status: f.status, serviceType: f.serviceType, airline: f.airline)) { return false }
        }
        return true
    }

    // MARK: summaries

    public func profitTotals(_ frm: String? = nil, _ to: String? = nil, _ f: LedgerFilter? = nil) -> ProfitTotals {
        var tot = ProfitTotals()
        for e in events {
            if !inRange(e.date, frm, to) { continue }
            if !bookingMatches(bookings[e.bookingId]!, f) { continue }
            tot.add(e)
        }
        tot.grossProfit = tot.sales - tot.cost + tot.commission - tot.directExpenses
        return tot
    }

    public func cashTotals(_ frm: String? = nil, _ to: String? = nil, _ f: LedgerFilter? = nil) -> CashTotals {
        var o = CashTotals()
        for t in transactions {
            if !inRange(t.txnDate, frm, to) || !txnMatches(t, f) { continue }
            let v = t.sign * t.baseAmount
            switch t.kind {
            case "customer_receipt": o.customerReceipts += v
            case "supplier_payment": o.supplierPayments += v
            case "customer_refund": o.customerRefunds += v
            case "supplier_receipt": o.supplierReceipts += v
            case "expense":
                o.expenses += v
                if t.isDirect && t.bookingId != nil { o.directExpensesPaid += v } else { o.operatingExpenses += v }
            case "other_income": o.otherIncome += v
            case "loan_received": o.loanReceived += v
            case "loan_repayment": o.loanRepaid += v
            default: break
            }
            if INFLOW_KINDS.contains(t.kind) { o.cashIn += v } else if OUTFLOW_KINDS.contains(t.kind) { o.cashOut += v }
        }
        o.netCashFlow = o.cashIn - o.cashOut
        o.netFinancing = o.loanReceived - o.loanRepaid
        return o
    }

    public func periodSummary(_ frm: String? = nil, _ to: String? = nil, _ f: LedgerFilter? = nil) -> ProfitTotals {
        var p = profitTotals(frm, to, f)
        let cash = cashTotals(frm, to, f)
        let scoped = f?.bookingScoped ?? false
        p.operatingExpenses = scoped ? 0 : cash.operatingExpenses
        p.otherIncome = scoped ? 0 : cash.otherIncome
        p.netProfit = p.grossProfit + p.otherIncome - p.operatingExpenses
        p.cash = cash
        return p
    }

    // MARK: balances

    public struct CustomerBalance {
        public var customerId: Int, name: String, phone: String?
        public var billed = 0, credits = 0, received = 0, refunded = 0, allocatedReceived = 0, allocatedRefunded = 0
        public var outstanding = 0, unallocated = 0, bookings = 0
        public var json: JSONValue {
            ["customer_id": .int(customerId), "name": .string(name), "phone": J(phone), "billed": .int(billed), "credits": .int(credits),
             "received": .int(received), "refunded": .int(refunded), "allocated_received": .int(allocatedReceived),
             "allocated_refunded": .int(allocatedRefunded), "outstanding": .int(outstanding), "unallocated": .int(unallocated),
             "bookings": .int(bookings)]
        }
    }

    public func customerBalances() -> [Int: CustomerBalance] {
        var res: [Int: CustomerBalance] = [:]
        for cid in customerOrder {
            let c = customers[cid]!
            res[cid] = CustomerBalance(customerId: cid, name: c.str("name"), phone: c.optStr("phone"))
        }
        for b in bookingList {
            res[b.customerId]!.bookings += 1
            res[b.customerId]!.billed += b.calc.customerPayable
            res[b.customerId]!.credits += b.calc.customerCredits
            res[b.customerId]!.allocatedReceived += b.calc.received
            res[b.customerId]!.allocatedRefunded += b.calc.refunded
        }
        for t in transactions where t.customerId != nil && (t.kind == "customer_receipt" || t.kind == "customer_refund") {
            let v = t.sign * t.baseAmount
            if t.kind == "customer_receipt" { res[t.customerId!]!.received += v } else { res[t.customerId!]!.refunded += v }
        }
        for k in res.keys {
            var r = res[k]!
            r.outstanding = r.billed - r.credits - r.received + r.refunded
            r.unallocated = (r.received - r.allocatedReceived) - (r.refunded - r.allocatedRefunded)
            res[k] = r
        }
        return res
    }

    public struct SupplierBalance {
        public var supplierId: Int, name: String, kind: String
        public var payable = 0, credits = 0, paid = 0, refunds = 0, allocatedPaid = 0, allocatedRefunds = 0
        public var outstanding = 0, unallocated = 0, bookings = 0
        public var json: JSONValue {
            ["supplier_id": .int(supplierId), "name": .string(name), "kind": .string(kind), "payable": .int(payable),
             "credits": .int(credits), "paid": .int(paid), "refunds": .int(refunds), "allocated_paid": .int(allocatedPaid),
             "allocated_refunds": .int(allocatedRefunds), "outstanding": .int(outstanding), "unallocated": .int(unallocated),
             "bookings": .int(bookings)]
        }
    }

    public func supplierBalances() -> [Int: SupplierBalance] {
        var res: [Int: SupplierBalance] = [:]
        for sid in supplierOrder {
            let s = suppliers[sid]!
            res[sid] = SupplierBalance(supplierId: sid, name: s.str("name"), kind: s.str("kind"))
        }
        for b in bookingList {
            guard let sid = b.supplierId else { continue }
            res[sid]!.bookings += 1
            res[sid]!.payable += b.calc.supplierPayable
            res[sid]!.credits += b.calc.supplierCredits
            res[sid]!.allocatedPaid += b.calc.paidSupplier
            res[sid]!.allocatedRefunds += b.calc.supplierRefunds
        }
        for t in transactions where t.supplierId != nil && (t.kind == "supplier_payment" || t.kind == "supplier_receipt") {
            let v = t.sign * t.baseAmount
            if t.kind == "supplier_payment" { res[t.supplierId!]!.paid += v } else { res[t.supplierId!]!.refunds += v }
        }
        for k in res.keys {
            var r = res[k]!
            r.outstanding = r.payable - r.credits - r.paid + r.refunds
            r.unallocated = (r.paid - r.allocatedPaid) - (r.refunds - r.allocatedRefunds)
            res[k] = r
        }
        return res
    }

    /// (account id, signed amount in the account's currency) for a transaction.
    public func accountEffects(_ t: Txn) -> [(Int, Int)] {
        let s = t.sign
        if INFLOW_KINDS.contains(t.kind) || FINANCING_IN_KINDS.contains(t.kind) { return [(t.accountId, s * t.amount)] }
        if OUTFLOW_KINDS.contains(t.kind) || FINANCING_OUT_KINDS.contains(t.kind) { return [(t.accountId, -s * t.amount)] }
        if t.kind == "transfer", let to = t.toAccountId {
            return [(t.accountId, -s * t.amount), (to, s * (t.toAmount ?? t.amount))]
        }
        return []
    }

    public struct AccountBalance {
        public var accountId: Int, name: String, kind: String, currency: String, opening: Int
        public var inflow = 0, outflow = 0, balance = 0, reconciledBalance = 0, active: Bool
        public var json: JSONValue {
            ["account_id": .int(accountId), "name": .string(name), "kind": .string(kind), "currency": .string(currency),
             "opening": .int(opening), "inflow": .int(inflow), "outflow": .int(outflow), "balance": .int(balance),
             "reconciled_balance": .int(reconciledBalance), "active": .int(active ? 1 : 0)]
        }
    }

    public func accountBalances(asOf: String? = nil) -> [Int: AccountBalance] {
        var res: [Int: AccountBalance] = [:]
        for aid in accountOrder {
            let a = accounts[aid]!
            res[aid] = AccountBalance(accountId: aid, name: a.str("name"), kind: a.str("kind"), currency: a.str("currency"),
                                      opening: a.int("opening_balance"), balance: a.int("opening_balance"),
                                      reconciledBalance: a.int("opening_balance"), active: a.bool("active"))
        }
        for t in transactions {
            if let asOf, t.txnDate > asOf { continue }
            for (aid, v) in accountEffects(t) {
                guard res[aid] != nil else { continue }
                if v >= 0 { res[aid]!.inflow += v } else { res[aid]!.outflow += -v }
                res[aid]!.balance += v
                if t.reconciled { res[aid]!.reconciledBalance += v }
            }
        }
        return res
    }

    // MARK: rows for lists

    public func bookingRow(_ b: LedgerBooking) -> [String: JSONValue] {
        let r = b.row, c = b.calc
        return ["id": .int(b.id), "ref": .string(b.ref), "pnr": J(b.pnr), "status": .string(b.status), "service_type": .string(b.serviceType),
                "booking_date": .string(b.bookingDate), "travel_date": J(b.travelDate), "return_date": J(r.optStr("return_date")),
                "customer_id": .int(b.customerId), "customer_name": .string(b.customerName), "customer_phone": J(b.customerPhone),
                "supplier_id": J(b.supplierId), "supplier_name": .string(b.supplierName),
                "airlines": .array(b.airlines.map { .string($0) }), "route": .string(b.route), "pax_count": .int(b.paxCount),
                "lead_pax": .string(b.leadPax), "currency": .string(b.currency), "exchange_rate": .string(r.str("exchange_rate")),
                "committed": .int(b.committed ? 1 : 0), "selling_price": .int(r.int("selling_price")), "purchase_cost": .int(r.int("purchase_cost")),
                "customer_payable": .int(c.customerPayable), "customer_outstanding": .int(c.customerOutstanding),
                "supplier_outstanding": .int(c.supplierOutstanding), "gross_profit": .int(c.grossProfit), "revenue": .int(c.revenue),
                "cost": .int(c.cost), "commission_net": .int(c.commissionNet), "tax_net": .int(c.taxNet),
                "direct_expenses": .int(c.directExpenses), "markup": .int(c.markup), "received": .int(c.received),
                "payment_status": .string(c.paymentStatus)]
    }

    public func txnRow(_ t: Txn) -> [String: JSONValue] {
        let acc = accounts[t.accountId]
        let toAcc = t.toAccountId.flatMap { accounts[$0] }
        let b = t.bookingId.flatMap { bookings[$0] }
        var allocs: [JSONValue] = []
        var allocated = 0
        for a in allocations[t.id] ?? [] {
            allocs.append(["booking_id": .int(a.bookingId), "ref": .string(bookings[a.bookingId]?.ref ?? ""), "amount": .int(a.amount)])
            allocated += a.amount
        }
        let debt = t.debtId.flatMap { debts[$0] }
        let partyKinds: Set<String> = ["customer_receipt", "customer_refund", "supplier_payment", "supplier_receipt"]
        return ["id": .int(t.id), "txn_no": .string(t.txnNo), "uid": .string(t.uid), "txn_date": .string(t.txnDate), "kind": .string(t.kind),
                "kind_label": .string(KIND_LABELS[t.kind] ?? t.kind), "sign": .int(t.sign), "amount": .int(t.amount),
                "currency": .string(t.currency), "exchange_rate": .string(t.exchangeRate), "rate_date": J(t.rateDate),
                "base_amount": .int(t.baseAmount), "signed_base": .int(t.sign * t.baseAmount), "account_id": .int(t.accountId),
                "account_name": .string(acc?.str("name") ?? ""), "to_account_id": J(t.toAccountId),
                "to_account_name": .string(toAcc?.str("name") ?? ""), "to_amount": J(t.toAmount), "method": .string(t.method),
                "reference": J(t.reference), "category": .string(t.category), "customer_id": J(t.customerId),
                "customer_name": .string(t.customerId.flatMap { customers[$0]?.str("name") } ?? ""),
                "supplier_id": J(t.supplierId), "supplier_name": .string(t.supplierId.flatMap { suppliers[$0]?.str("name") } ?? ""),
                "booking_id": J(t.bookingId), "booking_ref": .string(b?.ref ?? ""), "is_direct": .int(t.isDirect ? 1 : 0),
                "payee": J(t.payee), "notes": J(t.notes), "reversal_of": J(t.reversalOf), "reversed_by": J(t.reversedBy),
                "reconciled": .int(t.reconciled ? 1 : 0), "reconciled_on": J(t.reconciledOn), "created_at": .string(t.createdAt),
                "debt_id": J(t.debtId), "debt_name": .string(debt?.str("creditor") ?? ""), "debt_no": .string(debt?.str("debt_no") ?? ""),
                "allocations": .array(allocs), "allocated": .int(allocated),
                "unallocated": .int(partyKinds.contains(t.kind) ? t.baseAmount - allocated : 0)]
    }
}
