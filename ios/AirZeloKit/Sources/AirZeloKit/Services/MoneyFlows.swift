import Foundation

// Port of the adjustment, transaction, refund and allocation services (core/server/services.py) and of the
// debt balance guard (core/server/debts.py after_change). Posted money is never edited or deleted: a
// correction is a reversal plus a new entry, inside one database transaction.

public let METHODS = ["cash", "bank_transfer", "upi", "card", "cheque", "payment_gateway", "portal_wallet", "adjustment", "other"]
public let ADJ_KINDS = ["cancellation", "refund", "reissue", "date_change", "other"]
let PARTY_KINDS: [String: String] = ["customer_receipt": "customer", "customer_refund": "customer",
                                     "supplier_payment": "supplier", "supplier_receipt": "supplier"]
let TXN_PREFIX: [String: String] = ["customer_receipt": "RCT", "supplier_payment": "PAY", "customer_refund": "CRF",
                                    "supplier_receipt": "SRC", "expense": "EXP", "other_income": "INC", "transfer": "TRF",
                                    "loan_received": "LNR", "loan_repayment": "LNP"]
let LOAN_KINDS: Set<String> = ["loan_received", "loan_repayment"]
let DEFAULT_CATEGORY: [String: String] = ["customer_receipt": "Customer receipt", "supplier_payment": "Supplier payment",
                                          "customer_refund": "Customer refund", "supplier_receipt": "Supplier refund",
                                          "transfer": "Transfer", "loan_received": "Loan received", "loan_repayment": "Loan repayment"]
let META_FIELDS = ["reference", "notes", "payee", "method", "category"]

extension Agency {
    // MARK: adjustments

    @discardableResult
    public func createAdjustment(_ bid: Int, _ data: Fields) throws -> Int {
        let idem = try V.s(data, "idem_key", 80)
        return try tx {
            if let idem, let hit = try db.one("SELECT id FROM booking_adjustments WHERE idem_key = ?", [idem]) { return hit.int("id") }
            guard let b = try db.one("SELECT * FROM bookings WHERE id = ?", [bid]) else { throw AZError.notFound("Booking not found") }
            if !b.bool("committed") {
                throw AZError.validation("This booking was never confirmed, so there is nothing to credit. Change its status instead.")
            }
            let kind = try V.choice(data, "kind", ADJ_KINDS, nil, "Adjustment type")
            let adjDate = try V.d(data, "adj_date", label: "Adjustment date") ?? Dates.today()
            var p: [String: Int] = [:]
            for f in ADJ_FIELDS { p[f] = try parseAmount(data.get(f), V.capitalize(f.replacingOccurrences(of: "_", with: " "))) }
            if !p.values.contains(where: { $0 != 0 }) { throw AZError.validation("Enter at least one amount for the adjustment") }
            if p["customer_credit_tax"]! > p["customer_credit"]! {
                throw AZError.validation("Tax portion cannot exceed the customer credit", "customer_credit_tax")
            }
            let L = try Ledger(self)
            let calc = L.bookings[bid]!.calc
            let rate = b.str("exchange_rate")
            let custNet = calc.customerPayable - calc.customerCredits
            if convert(p["customer_credit"]!, rate) > custNet + convert(p["extra_customer_charge"]!, rate) {
                throw AZError.validation("Customer credit is more than the amount billed to the customer", "customer_credit")
            }
            let supNet = calc.supplierPayable - (calc.supplierCredits - calc.commissionNet)
            if convert(p["supplier_credit"]!, rate) > supNet + convert(p["extra_supplier_charge"]!, rate) {
                throw AZError.validation("Supplier credit is more than the supplier cost", "supplier_credit")
            }
            if convert(p["commission_reversal"]!, rate) > calc.commissionNet {
                throw AZError.validation("Commission reversal is more than the commission earned", "commission_reversal")
            }
            let newStatus = data.get("new_status")
            if newStatus.truthy, !BOOKING_STATUSES.contains(newStatus.pyStr) { throw AZError.validation("Invalid status", "new_status") }
            let n = try nextCounter("adjustment")
            let adjNo = String(format: "ADJ-%05d", n)
            let ts = Dates.nowISO()
            let aid = try db.run("INSERT INTO booking_adjustments(adj_no, booking_id, adj_date, kind, sign, exchange_rate, customer_credit, customer_credit_tax, "
                                 + "supplier_credit, commission_reversal, extra_customer_charge, extra_supplier_charge, notes, idem_key, created_at) "
                                 + "VALUES (?,?,?,?,1,?,?,?,?,?,?,?,?,?,?)",
                                 [adjNo, bid, adjDate, kind, rate, p["customer_credit"]!, p["customer_credit_tax"]!, p["supplier_credit"]!,
                                  p["commission_reversal"]!, p["extra_customer_charge"]!, p["extra_supplier_charge"]!,
                                  try V.s(data, "notes", 2000), idem, ts])
            try history(bid, "adjustment", "\(adjNo) \(kind.replacingOccurrences(of: "_", with: " ")) recorded")
            var a: [String: JSONValue] = p.mapValues { .int($0) }
            a["kind"] = .string(kind); a["adj_date"] = .string(adjDate); a["booking_id"] = .int(bid)
            try audit("adjustment", aid, "create", nil, .object(a))
            if newStatus.truthy, newStatus.pyStr != b.str("status") {
                try db.run("UPDATE bookings SET status = ?, updated_at = ? WHERE id = ?", [newStatus.pyStr, ts, bid])
                try history(bid, "status", "\(b.str("status")) -> \(newStatus.pyStr)")
                try audit("booking", bid, "status", ["status": .string(b.str("status"))], ["status": newStatus])
            }
            return aid
        }
    }

    @discardableResult
    public func reverseAdjustment(_ aid: Int, _ data: Fields) throws -> Int {
        try tx {
            guard let a = try db.one("SELECT * FROM booking_adjustments WHERE id = ?", [aid]) else { throw AZError.notFound("Adjustment not found") }
            if a.optInt("reversal_of") != nil || a.int("sign") == -1 { throw AZError.validation("A reversal cannot itself be reversed") }
            if a.optInt("reversed_by") != nil { throw AZError.validation("This adjustment has already been reversed") }
            if try db.one("SELECT 1 FROM invoices WHERE adjustment_id = ? AND status = 'issued'", [aid]) != nil {
                throw AZError.validation("Cancel the credit note issued for this adjustment first")
            }
            let n = try nextCounter("adjustment")
            let ts = Dates.nowISO()
            let reason = try V.s(data, "reason", 500) ?? "Reversal"
            let rid = try db.run("INSERT INTO booking_adjustments(adj_no, booking_id, adj_date, kind, sign, exchange_rate, customer_credit, customer_credit_tax, "
                                 + "supplier_credit, commission_reversal, extra_customer_charge, extra_supplier_charge, notes, reversal_of, created_at) "
                                 + "VALUES (?,?,?,?,-1,?,?,?,?,?,?,?,?,?,?)",
                                 [String(format: "ADJ-%05d", n), a.int("booking_id"), try V.d(data, "date") ?? Dates.today(), a.str("kind"),
                                  a.str("exchange_rate"), a.int("customer_credit"), a.int("customer_credit_tax"), a.int("supplier_credit"),
                                  a.int("commission_reversal"), a.int("extra_customer_charge"), a.int("extra_supplier_charge"),
                                  "Reversal of \(a.str("adj_no")): \(reason)", aid, ts])
            try db.run("UPDATE booking_adjustments SET reversed_by = ? WHERE id = ?", [rid, aid])
            try history(a.int("booking_id"), "adjustment", "\(a.str("adj_no")) reversed (\(reason))")
            try audit("adjustment", aid, "reverse", .object(a.json), ["reversal_id": .int(rid), "reason": .string(reason)])
            return rid
        }
    }

    /// Edit an adjustment: reverse it and post the corrected one in a single transaction.
    @discardableResult
    public func correctAdjustment(_ aid: Int, _ data: Fields) throws -> Int {
        try tx {
            guard let a = try db.one("SELECT * FROM booking_adjustments WHERE id = ?", [aid]) else { throw AZError.notFound("Adjustment not found") }
            if a.optInt("reversed_by") != nil || a.int("sign") < 0 {
                throw AZError.validation("This adjustment has already been reversed, so it cannot be edited")
            }
            try reverseAdjustment(aid, ["reason": .string(try V.s(data, "reason", 300) ?? "Edited"), "date": .string(a.str("adj_date"))])
            var d = data
            d["kind"] = data.get("kind").truthy ? data.get("kind") : .string(a.str("kind"))
            let nid = try createAdjustment(a.int("booking_id"), d)
            try audit("adjustment", aid, "edit", ["adj_no": .string(a.str("adj_no"))], ["replaced_by": .int(nid)])
            return nid
        }
    }

    // MARK: transactions

    struct Alloc { var bookingId: Int; var amount: Int }

    func validateAllocations(_ kind: String, _ partyId: Int?, _ baseAmount: Int, _ allocs: JSONValue, txnId: Int? = nil) throws -> [Alloc] {
        if !allocs.truthy { return [] }
        guard let list = allocs.arrayValue else { throw AZError.validation("Allocations must be a list", "allocations") }
        let partyCol = PARTY_KINDS[kind] == "customer" ? "customer_id" : "supplier_id"
        var out: [Alloc] = []
        var seen = Set<Int>()
        var total = 0
        for av in list {
            guard let a = av.objectValue else { continue }
            let bid = try V.i(a, "booking_id", required: true, label: "Booking")!
            let amt = try parseAmount(a.get("amount"), "Allocated amount")
            if amt == 0 { continue }
            if seen.contains(bid) { throw AZError.validation("A booking is allocated twice", "allocations") }
            seen.insert(bid)
            guard let b = try db.one("SELECT id, ref, \(partyCol) AS party FROM bookings WHERE id = ?", [bid]) else {
                throw AZError.validation("Allocated booking does not exist", "allocations")
            }
            if b.optInt("party") != partyId {
                throw AZError.validation("Booking \(b.str("ref")) belongs to a different \(PARTY_KINDS[kind]!)", "allocations")
            }
            total += amt
            out.append(Alloc(bookingId: bid, amount: amt))
        }
        if total > baseAmount {
            throw AZError.validation("Allocated total \(fmt(total)) is more than the payment amount \(fmt(baseAmount))", "allocations")
        }
        try checkAllocationCaps(kind, out, txnId: txnId)
        return out
    }

    /// Money paid back to a customer (or received back from a supplier) must match an amount actually due back.
    func checkRefundBacked(_ kind: String, _ partyId: Int, _ baseAmount: Int, _ allocs: [Alloc]) throws {
        guard kind == "customer_refund" || kind == "supplier_receipt" else { return }
        let L = try Ledger(self)
        let customer = kind == "customer_refund"
        let unallocated: Int
        if customer { guard let bal = L.customerBalances()[partyId] else { return }; unallocated = bal.unallocated }
        else { guard let bal = L.supplierBalances()[partyId] else { return }; unallocated = bal.unallocated }
        let free = baseAmount - allocs.reduce(0) { $0 + $1.amount }
        let available = max(unallocated, 0)
        if free > available {
            let who = customer ? "the customer" : "you by the supplier"
            throw AZError.conflict("Nothing is due back to \(who) for this amount (unused advance: \(L.base) \(fmt(available))). To refund a booking, open it and use "
                                   + "'Refund / cancel' - that records the refund credit, the airline / supplier refund and any fee you keep, "
                                   + "so the booking profit is adjusted, and then pays the refund.", "refund_not_due", ["due_back": .int(available)])
        }
        for a in allocs {
            guard let b = L.bookings[a.bookingId] else { continue }
            let due = -(customer ? b.calc.customerOutstanding : b.calc.supplierOutstanding)
            if a.amount > max(due, 0) {
                throw AZError.conflict("Booking \(b.ref) has only \(L.base) \(fmt(max(due, 0))) due back. Use 'Refund / cancel' on the booking to record the refund first.",
                                       "refund_not_due", ["booking_id": .int(b.id), "due_back": .int(max(due, 0))])
            }
        }
    }

    /// A receipt or payment may not be allocated beyond a confirmed booking's outstanding balance.
    func checkAllocationCaps(_ kind: String, _ allocs: [Alloc], txnId: Int? = nil) throws {
        guard kind == "customer_receipt" || kind == "supplier_payment", !allocs.isEmpty else { return }
        let L = try Ledger(self)
        for a in allocs {
            guard let b = L.bookings[a.bookingId], b.committed else { continue }
            var due = kind == "customer_receipt" ? b.calc.customerOutstanding : b.calc.supplierOutstanding
            if let txnId { due += (L.allocations[txnId] ?? []).filter { $0.bookingId == a.bookingId }.reduce(0) { $0 + $1.amount } }
            if a.amount > max(due, 0) {
                let dueTxt = "\(L.base) \(fmt(max(due, 0)))"
                throw AZError.validation("Booking \(b.ref) has only \(dueTxt) outstanding\(kind == "supplier_payment" ? " to the supplier" : ""). "
                                         + "Reduce the allocation to \(dueTxt) or less; any extra stays unallocated.", "allocations")
            }
        }
    }

    func txnPrefix(_ kind: String) throws -> String {
        if kind == "customer_receipt" {
            var p = (try setting("receipt_prefix", "")).flatMap { $0.isEmpty ? nil : $0 } ?? TXN_PREFIX[kind]!
            while p.hasSuffix("-") { p.removeLast() }
            return p
        }
        return TXN_PREFIX[kind]!
    }

    /// Post a receipt, payment, refund, expense, other income or transfer. Returns (id, replayed).
    @discardableResult
    public func createTransaction(_ data: Fields) throws -> (id: Int, replayed: Bool) {
        let kind = try V.choice(data, "kind", KIND_ORDER, nil, "Transaction type")
        let idem = try V.s(data, "idem_key", 80)
        if let idem, let hit = try db.one("SELECT id FROM transactions WHERE idem_key = ?", [idem]) { return (hit.int("id"), true) }
        return try tx {
            if let idem, let hit = try db.one("SELECT id FROM transactions WHERE idem_key = ?", [idem]) { return (hit.int("id"), true) }
            let txnDate = try V.d(data, "txn_date", label: "Date") ?? Dates.today()
            let accountId = try V.i(data, "account_id", required: true, label: "Account")!
            guard let acc = try db.one("SELECT * FROM accounts WHERE id = ?", [accountId]) else {
                throw AZError.validation("Selected account does not exist", "account_id")
            }
            if !acc.bool("active") { throw AZError.validation("Selected account is inactive", "account_id") }
            let amount = try parseAmount(data.get("amount"), "Amount", required: true, allowZero: false)
            let currency = V.normCode(data.get("currency"), 3) ?? acc.str("currency")
            if currency != acc.str("currency") {
                throw AZError.validation("Account \(acc.str("name")) holds \(acc.str("currency")). Record this in \(acc.str("currency")) or use an account in \(currency).", "currency")
            }
            var withCur = data
            withCur["currency"] = .string(currency)
            let (_, rate) = try currencyAndRate(withCur)
            let baseAmount = convert(amount, rate)
            let method = try V.choice(data, "method", METHODS, "cash", "Payment method")
            let reference = try V.s(data, "reference", 120)
            var category = try V.s(data, "category", 80) ?? DEFAULT_CATEGORY[kind]
            if category == nil { throw AZError.validation("Category is required", "category") }
            let notes = try V.s(data, "notes", 2000)
            let payee = try V.s(data, "payee", 150)
            var customerId = try V.i(data, "customer_id")
            var supplierId = try V.i(data, "supplier_id")
            var bookingId = try V.i(data, "booking_id")
            var toAccountId: Int?
            var toAmount: Int?
            var isDirect = 0
            var booking: Row?
            if let bookingId {
                booking = try db.one("SELECT * FROM bookings WHERE id = ?", [bookingId])
                if booking == nil { throw AZError.validation("Linked booking does not exist", "booking_id") }
            }
            let party = PARTY_KINDS[kind]
            if party == "customer" {
                if customerId == nil, let booking { customerId = booking.int("customer_id") }
                guard let cid = customerId, cid != 0 else { throw AZError.validation("Select the customer", "customer_id") }
                if try db.one("SELECT 1 FROM customers WHERE id = ?", [cid]) == nil { throw AZError.validation("Selected customer does not exist", "customer_id") }
                supplierId = nil
            } else if party == "supplier" {
                if supplierId == nil, let booking { supplierId = booking.optInt("supplier_id") }
                guard let sid = supplierId, sid != 0 else { throw AZError.validation("Select the supplier", "supplier_id") }
                if try db.one("SELECT 1 FROM suppliers WHERE id = ?", [sid]) == nil { throw AZError.validation("Selected supplier does not exist", "supplier_id") }
                customerId = nil
            } else if kind == "transfer" {
                toAccountId = try V.i(data, "to_account_id", required: true, label: "Destination account")
                if toAccountId == accountId { throw AZError.validation("Choose two different accounts for a transfer", "to_account_id") }
                guard let toAcc = try db.one("SELECT * FROM accounts WHERE id = ?", [toAccountId!]) else {
                    throw AZError.validation("Destination account does not exist", "to_account_id")
                }
                if toAcc.str("currency") == acc.str("currency") {
                    toAmount = amount
                } else {
                    toAmount = try parseAmount(data.get("to_amount"), "Amount received in \(toAcc.str("currency"))", required: true, allowZero: false)
                }
                customerId = nil; supplierId = nil; bookingId = nil
                category = "Transfer"
            } else if kind == "expense" {
                if booking != nil { isDirect = 1 }
                if let sid = supplierId, try db.one("SELECT 1 FROM suppliers WHERE id = ?", [sid]) == nil {
                    throw AZError.validation("Selected supplier does not exist", "supplier_id")
                }
                customerId = nil
            } else if kind == "other_income" {
                customerId = nil; supplierId = nil; bookingId = nil
            } else if LOAN_KINDS.contains(kind) {
                customerId = nil; supplierId = nil; bookingId = nil
            }
            let debtId = (LOAN_KINDS.contains(kind) || kind == "expense") ? try V.i(data, "debt_id") : nil
            if LOAN_KINDS.contains(kind) && debtId == nil { throw AZError.validation("Select the loan / debt", "debt_id") }
            if let debtId {
                guard let debt = try db.one("SELECT * FROM debts WHERE id = ?", [debtId]) else { throw AZError.validation("Selected debt does not exist", "debt_id") }
                if debt.str("status") != "open" && !data.get("_replacing").truthy {
                    throw AZError.validation("This debt is closed. Reopen it to record more entries.", "debt_id")
                }
            }

            var allocs: [Alloc] = []
            if let party {
                let partyId = party == "customer" ? customerId : supplierId
                if let booking, (party == "customer" ? booking.optInt("customer_id") : booking.optInt("supplier_id")) != partyId {
                    throw AZError.validation("Linked booking belongs to a different \(party)", "booking_id")
                }
                if data.contains("allocations") && data.get("allocations").truthy {
                    allocs = try validateAllocations(kind, partyId, baseAmount, data.get("allocations"))
                } else if booking != nil {
                    allocs = [Alloc(bookingId: bookingId!, amount: baseAmount)]
                }
                if allocs.count == 1 && bookingId == nil { bookingId = allocs[0].bookingId }
                try checkRefundBacked(kind, partyId!, baseAmount, allocs)
            }

            if !V.b(data, "force_duplicate") {
                let q = "SELECT txn_no FROM transactions WHERE kind = ? AND txn_date = ? AND amount = ? AND account_id = ? AND sign = 1 "
                    + "AND reversed_by IS NULL AND COALESCE(reference,'') = COALESCE(?, '') AND COALESCE(booking_id,0) = COALESCE(?,0) "
                    + "AND COALESCE(customer_id,0) = ? AND COALESCE(supplier_id,0) = ?"
                if let dup = try db.one(q, [kind, txnDate, amount, accountId, reference, bookingId, customerId ?? 0, supplierId ?? 0]) {
                    throw AZError.conflict("This looks like a duplicate of \(dup.str("txn_no")) (same type, date, amount, account and reference). "
                                           + "Confirm to record it anyway.", "possible_duplicate", ["txn_no": .string(dup.str("txn_no"))])
                }
            }

            let n = try nextCounter("txn-" + TXN_PREFIX[kind]!)
            let txnNo = try txnPrefix(kind) + "-" + String(format: "%06d", n)
            let ts = Dates.nowISO()
            let rateDate = try V.d(data, "rate_date") ?? (rate != "1" ? txnDate : nil)
            let tid = try db.run("INSERT INTO transactions(txn_no, uid, idem_key, txn_date, kind, sign, amount, currency, exchange_rate, rate_date, base_amount, "
                                 + "account_id, to_account_id, to_amount, method, reference, category, customer_id, supplier_id, booking_id, is_direct, payee, notes, "
                                 + "debt_id, created_at, updated_at) VALUES (?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                 [txnNo, UUID().uuidString.lowercased(), idem, txnDate, kind, amount, currency, rate, rateDate, baseAmount,
                                  accountId, toAccountId, toAmount, method, reference, category, customerId, supplierId, bookingId, isDirect,
                                  payee, notes, debtId, ts, ts])
            if let debtId { try debtAfterChange(debtId) }
            for a in allocs {
                try db.run("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", [tid, a.bookingId, a.amount, ts])
                try history(a.bookingId, "payment", "\(txnNo) \(KIND_LABELS[kind]!.lowercased()) \(fmt(a.amount)) allocated")
            }
            if kind == "expense" && isDirect == 1 { try history(bookingId!, "expense", "\(txnNo) direct expense recorded") }
            try audit("transaction", tid, "create", nil, [
                "txn_no": .string(txnNo), "kind": .string(kind), "date": .string(txnDate), "amount": .int(amount), "currency": .string(currency),
                "rate": .string(rate), "base_amount": .int(baseAmount), "account_id": .int(accountId), "method": .string(method),
                "reference": J(reference), "customer_id": J(customerId), "supplier_id": J(supplierId), "booking_id": J(bookingId),
                "debt_id": J(debtId), "allocations": .array(allocs.map { ["booking_id": .int($0.bookingId), "amount": .int($0.amount)] })])
            return (tid, false)
        }
    }

    @discardableResult
    public func reverseTransaction(_ tid: Int, _ data: Fields) throws -> Int {
        try tx {
            guard let t = try db.one("SELECT * FROM transactions WHERE id = ?", [tid]) else { throw AZError.notFound("Transaction not found") }
            if t.optInt("reversal_of") != nil || t.int("sign") == -1 { throw AZError.validation("A reversal cannot itself be reversed") }
            if t.optInt("reversed_by") != nil { throw AZError.validation("This transaction has already been reversed") }
            let reason = try V.s(data, "reason", 500, required: true, label: "Reason")!
            let rdate = try V.d(data, "date") ?? Dates.today()
            let kind = t.str("kind")
            let n = try nextCounter("txn-" + TXN_PREFIX[kind]!)
            let txnNo = try txnPrefix(kind) + "-" + String(format: "%06d", n)
            let ts = Dates.nowISO()
            let debtId = t.has("debt_id") ? t.optInt("debt_id") : nil
            let rid = try db.run("INSERT INTO transactions(txn_no, uid, idem_key, txn_date, kind, sign, amount, currency, exchange_rate, rate_date, base_amount, "
                                 + "account_id, to_account_id, to_amount, method, reference, category, customer_id, supplier_id, booking_id, is_direct, payee, notes, "
                                 + "debt_id, reversal_of, created_at, updated_at) VALUES (?,?,NULL,?,?,-1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                 [txnNo, UUID().uuidString.lowercased(), rdate, kind, t.int("amount"), t.str("currency"), t.str("exchange_rate"),
                                  t["rate_date"], t.int("base_amount"), t.int("account_id"), t["to_account_id"], t["to_amount"], t.str("method"),
                                  t["reference"], t.str("category"), t["customer_id"], t["supplier_id"], t["booking_id"], t.int("is_direct"),
                                  t["payee"], "Reversal of \(t.str("txn_no")): \(reason)", debtId, tid, ts, ts])
            for a in try db.query("SELECT * FROM allocations WHERE txn_id = ?", [tid]) {
                try db.run("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", [rid, a.int("booking_id"), a.int("amount"), ts])
                try history(a.int("booking_id"), "payment", "\(t.str("txn_no")) reversed by \(txnNo) (\(reason))")
            }
            try db.run("UPDATE transactions SET reversed_by = ?, updated_at = ? WHERE id = ?", [rid, ts, tid])
            if let debtId, !data.get("_defer_debt_check").truthy { try debtAfterChange(debtId) }
            try audit("transaction", tid, "reverse", ["txn_no": .string(t.str("txn_no")), "amount": .int(t.int("amount"))],
                      ["reversal": .string(txnNo), "reason": .string(reason)])
            return rid
        }
    }

    /// Edit a posted transaction: descriptive fields in place; any money change is a reversal plus a corrected entry.
    @discardableResult
    public func correctTransaction(_ tid: Int, _ data: Fields) throws -> (id: Int, outcome: String) {
        let idem = try V.s(data, "idem_key", 80)
        if let idem, let hit = try db.one("SELECT id FROM transactions WHERE idem_key = ?", [idem]) { return (hit.int("id"), "replay") }
        return try tx {
            guard let t = try db.one("SELECT * FROM transactions WHERE id = ?", [tid]) else { throw AZError.notFound("Transaction not found") }
            if t.optInt("reversed_by") != nil || t.int("sign") < 0 {
                throw AZError.validation("This entry has already been deleted / reversed, so it cannot be edited")
            }
            if data.get("kind").truthy && data.get("kind").pyStr != t.str("kind") {
                throw AZError.validation("The type of an entry cannot be changed. Delete it and record a new one.")
            }
            var new = data
            new["kind"] = .string(t.str("kind"))
            let oldAllocs = try db.query("SELECT booking_id, amount FROM allocations WHERE txn_id = ?", [tid])
                .map { [$0.int("booking_id"), $0.int("amount")] }.sorted { $0.lexicographicallyPrecedes($1) }
            let acc = try db.one("SELECT * FROM accounts WHERE id = ?", [try V.i(new, "account_id") ?? t.int("account_id")])
            let amount = try parseAmount(new.get("amount"), "Amount", required: true, allowZero: false)
            var withCur = new
            withCur["currency"] = .string(acc?.str("currency") ?? t.str("currency"))
            let (_, rate) = try currencyAndRate(withCur)
            let base = convert(amount, rate)
            var newAllocs: [[Int]]?
            if new.contains("allocations") {
                var list: [[Int]] = []
                for av in new.get("allocations").arrayValue ?? [] {
                    guard let a = av.objectValue else { continue }
                    let amt = try parseAmount(a.get("amount"), "Allocated amount")
                    if amt != 0 { list.append([a.get("booking_id").intValue ?? 0, amt]) }
                }
                newAllocs = list.sorted { $0.lexicographicallyPrecedes($1) }
            }
            let tDebt = t.has("debt_id") ? t.optInt("debt_id") : nil
            // the same checks, in the same order, as the desktop's same_money expression
            let newDate = try V.d(new, "txn_date") ?? t.str("txn_date")
            let newAccount = try V.i(new, "account_id") ?? t.int("account_id")
            let newTo = try V.i(new, "to_account_id") ?? t.optInt("to_account_id")
            var sameMoney = amount == t.int("amount") && newDate == t.str("txn_date") && newAccount == t.int("account_id")
                && base == t.int("base_amount") && newTo == t.optInt("to_account_id")
            if sameMoney && t.str("kind") == "transfer" {
                let recv = new.get("to_amount").truthy ? new.get("to_amount") : new.get("amount")
                sameMoney = try parseAmount(recv, "Amount received") == (t.optInt("to_amount") ?? t.int("amount"))
            }
            if sameMoney {
                let c = try V.i(new, "customer_id") ?? t.optInt("customer_id")
                let s = try V.i(new, "supplier_id") ?? t.optInt("supplier_id")
                let d = try V.i(new, "debt_id") ?? tDebt
                sameMoney = c == t.optInt("customer_id") && s == t.optInt("supplier_id") && d == tDebt
                    && (newAllocs == nil || newAllocs! == oldAllocs)
            }
            if sameMoney {
                var meta: Fields = [:]
                for k in META_FIELDS where new.contains(k) { meta[k] = new[k] }
                try updateTransactionMeta(tid, meta)
                return (tid, "updated")
            }
            let reason = try V.s(new, "reason", 300) ?? "Edited"
            let rid = try reverseTransaction(tid, ["reason": .string(reason), "date": .string(t.str("txn_date")), "_defer_debt_check": true])
            if let tDebt, !new.contains("debt_id") { new["debt_id"] = .int(tDebt) }
            new["force_duplicate"] = true
            new["_replacing"] = .int(tid)
            if !new.contains("booking_id") { new["booking_id"] = t.bool("is_direct") ? t["booking_id"].json : .null }
            let (nid, _) = try createTransaction(new)
            try db.run("UPDATE transactions SET notes = COALESCE(notes || char(10), '') || ? WHERE id = ?", ["Corrected from \(t.str("txn_no"))", nid])
            try audit("transaction", tid, "edit", ["txn_no": .string(t.str("txn_no")), "amount": .int(t.int("amount")), "date": .string(t.str("txn_date"))],
                      ["replaced_by": .int(nid), "reversal": .int(rid), "reason": .string(reason)])
            return (nid, "replaced")
        }
    }

    /// Only descriptive fields can be edited; amounts require a reversal.
    @discardableResult
    public func updateTransactionMeta(_ tid: Int, _ data: Fields) throws -> Int {
        try tx {
            guard let t = try db.one("SELECT * FROM transactions WHERE id = ?", [tid]) else { throw AZError.notFound("Transaction not found") }
            var upd: [(String, SQLValue)] = []
            if data.contains("reference") { upd.append(("reference", try V.s(data, "reference", 120).sqlValue)) }
            if data.contains("notes") { upd.append(("notes", try V.s(data, "notes", 2000).sqlValue)) }
            if data.contains("payee") { upd.append(("payee", try V.s(data, "payee", 150).sqlValue)) }
            if data.contains("method") { upd.append(("method", .text(try V.choice(data, "method", METHODS, t.str("method"))))) }
            if data.contains("category") && ["expense", "other_income"].contains(t.str("kind")) {
                upd.append(("category", .text(try V.s(data, "category", 80, required: true, label: "Category")!)))
            }
            let forbidden = ["amount", "txn_date", "account_id", "currency", "exchange_rate", "kind"].filter {
                data.contains($0) && data.get($0).pyStr != (t.optStr($0) ?? "None")
            }
            if !forbidden.isEmpty {
                throw AZError.validation("Amount, date, account and currency cannot be edited on a posted transaction. Reverse it and record it again.")
            }
            if upd.isEmpty { return tid }
            var before: [String: JSONValue] = [:], after: [String: JSONValue] = [:]
            for (k, v) in upd { before[k] = t[k].json; after[k] = JSONValue(v) }
            upd.append(("updated_at", .text(Dates.nowISO())))
            try db.run("UPDATE transactions SET " + upd.map { "\($0.0) = ?" }.joined(separator: ", ") + " WHERE id = ?", upd.map { $0.1 } + [.int(Int64(tid))])
            try audit("transaction", tid, "edit", .object(before), .object(after))
            return tid
        }
    }

    @discardableResult
    public func setAllocations(_ tid: Int, _ data: Fields) throws -> Int {
        try tx {
            guard let t = try db.one("SELECT * FROM transactions WHERE id = ?", [tid]) else { throw AZError.notFound("Transaction not found") }
            guard let party = PARTY_KINDS[t.str("kind")] else { throw AZError.validation("Only receipts, payments and refunds can be allocated") }
            if t.optInt("reversed_by") != nil || t.optInt("reversal_of") != nil { throw AZError.validation("Reversed transactions cannot be re-allocated") }
            let partyId = party == "customer" ? t.optInt("customer_id") : t.optInt("supplier_id")
            let allocs = try validateAllocations(t.str("kind"), partyId, t.int("base_amount"),
                                                 data.get("allocations").truthy ? data.get("allocations") : .array([]), txnId: tid)
            let before = try db.query("SELECT booking_id, amount FROM allocations WHERE txn_id = ?", [tid])
            try db.run("DELETE FROM allocations WHERE txn_id = ?", [tid])
            let ts = Dates.nowISO()
            for a in allocs {
                try db.run("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", [tid, a.bookingId, a.amount, ts])
            }
            var changed = Set(before.map { $0.int("booking_id") })
            for a in allocs { changed.insert(a.bookingId) }
            for bid in changed.sorted() { try history(bid, "allocation", "Allocation of \(t.str("txn_no")) updated") }
            try audit("allocation", tid, "update", .array(before.map { .object($0.json) }),
                      .array(allocs.map { ["booking_id": .int($0.bookingId), "amount": .int($0.amount)] }))
            return tid
        }
    }

    // MARK: refunds

    public struct RefundResult {
        public let adjustmentId: Int
        public let transactions: [String: Int]
        public let profitBefore: Int?
        public let grossProfit: Int, customerOutstanding: Int, supplierOutstanding: Int
        public let paymentStatus: String
        public let replayed: Bool
    }

    private func refundSummary(_ bid: Int) throws -> BookingCalc { try Ledger(self).bookings[bid]!.calc }

    /// One-step refund / cancellation: credit adjustment, then the cash refund and any supplier refund, all in one transaction.
    @discardableResult
    public func refundBooking(_ bid: Int, _ data: Fields) throws -> RefundResult {
        let idem = try V.s(data, "idem_key", 80)
        return try tx {
            if let idem, let hit = try db.one("SELECT id FROM booking_adjustments WHERE idem_key = ?", [idem]) {
                let c = try refundSummary(bid)
                return RefundResult(adjustmentId: hit.int("id"), transactions: [:], profitBefore: nil, grossProfit: c.grossProfit,
                                    customerOutstanding: c.customerOutstanding, supplierOutstanding: c.supplierOutstanding,
                                    paymentStatus: c.paymentStatus, replayed: true)
            }
            guard let b = try db.one("SELECT * FROM bookings WHERE id = ?", [bid]) else { throw AZError.notFound("Booking not found") }
            let before = try refundSummary(bid)
            let cust = try parseAmount(data.get("customer_refund"), "Refund to customer")
            let custTax = try parseAmount(data.get("customer_refund_tax"), "Tax part of the refund")
            let sup = try parseAmount(data.get("supplier_refund"), "Refund from supplier / airline")
            let comm = try parseAmount(data.get("commission_reversal"), "Commission given back")
            if cust == 0 && sup == 0 && comm == 0 {
                throw AZError.validation("Enter the refund to the customer and / or the refund from the supplier", "customer_refund")
            }
            let date = try V.d(data, "date", label: "Refund date") ?? Dates.today()
            let status = data.get("new_status").truthy ? data.get("new_status").pyStr : ""
            let kind = status == "cancelled" || status == "refunded" ? "cancellation" : "refund"
            let aid = try createAdjustment(bid, ["kind": .string(kind), "adj_date": .string(date), "customer_credit": data.get("customer_refund"),
                                                 "customer_credit_tax": data.get("customer_refund_tax"), "supplier_credit": data.get("supplier_refund"),
                                                 "commission_reversal": data.get("commission_reversal"), "new_status": .string(status),
                                                 "notes": J(try V.s(data, "notes", 2000)), "idem_key": J(idem)])
            var txns: [String: Int] = [:]
            let baseCur = try setting("base_currency", "INR") ?? "INR"
            for key in ["pay_customer", "supplier_received"] {
                let sub = data.get(key).objectValue ?? [:]
                if sub.get("account_id").truthy {
                    if let acc = try db.one("SELECT name, currency FROM accounts WHERE id = ?", [try V.i(sub, "account_id")]), acc.str("currency") != baseCur {
                        throw AZError.validation("Use a \(baseCur) account here, or record this refund separately from the Refunds page.", key)
                    }
                }
            }
            let pay = data.get("pay_customer").objectValue ?? [:]
            if pay.get("account_id").truthy, try parseAmount(pay.get("amount"), "Refund paid now") != 0 {
                let (tid, _) = try createTransaction([
                    "kind": "customer_refund", "txn_date": .string(date), "account_id": pay.get("account_id"), "amount": pay.get("amount"),
                    "exchange_rate": pay.get("exchange_rate"), "method": pay.get("method").truthy ? pay.get("method") : "bank_transfer",
                    "reference": pay.get("reference"), "customer_id": .int(b.int("customer_id")), "force_duplicate": true,
                    "idem_key": idem.map { .string($0 + ":customer") } ?? .null,
                    "allocations": [["booking_id": .int(bid), "amount": pay.get("amount")]],
                    "notes": .string("Refund for \(b.str("ref"))")])
                txns["customer_refund"] = tid
            }
            let got = data.get("supplier_received").objectValue ?? [:]
            if got.get("account_id").truthy, try parseAmount(got.get("amount"), "Supplier refund received") != 0 {
                if b.optInt("supplier_id") == nil { throw AZError.validation("This booking has no supplier", "supplier_received") }
                let (tid, _) = try createTransaction([
                    "kind": "supplier_receipt", "txn_date": .string(date), "account_id": got.get("account_id"), "amount": got.get("amount"),
                    "exchange_rate": got.get("exchange_rate"), "method": got.get("method").truthy ? got.get("method") : "portal_wallet",
                    "reference": got.get("reference"), "supplier_id": .int(b.int("supplier_id")), "force_duplicate": true,
                    "idem_key": idem.map { .string($0 + ":supplier") } ?? .null,
                    "allocations": [["booking_id": .int(bid), "amount": got.get("amount")]],
                    "notes": .string("Supplier refund for \(b.str("ref"))")])
                txns["supplier_receipt"] = tid
            }
            try audit("booking", bid, "refund", ["gross_profit": .int(before.grossProfit)],
                      ["adjustment_id": .int(aid), "customer_refund": .int(cust), "supplier_refund": .int(sup), "commission_reversal": .int(comm),
                       "transactions": .object(txns.mapValues { .int($0) })])
            _ = custTax
            let c = try refundSummary(bid)
            return RefundResult(adjustmentId: aid, transactions: txns, profitBefore: before.grossProfit, grossProfit: c.grossProfit,
                                customerOutstanding: c.customerOutstanding, supplierOutstanding: c.supplierOutstanding,
                                paymentStatus: c.paymentStatus, replayed: false)
        }
    }

    // MARK: listing

    public func listTransactions(_ params: [String: String], ledger: Ledger? = nil) throws -> [[String: JSONValue]] {
        let L = try ledger ?? Ledger(self)
        func p(_ k: String) -> String? { params[k].flatMap { $0.isEmpty ? nil : $0 } }
        let kinds = p("kind").map { Set($0.split(separator: ",").map(String.init)) }
        let q = (params["q"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let f = LedgerFilter(customerId: p("customer_id"), supplierId: p("supplier_id"), currency: p("currency"), accountId: p("account_id"))
        var out: [[String: JSONValue]] = []
        for t in L.transactions {
            if let kinds, !kinds.contains(t.kind) { continue }
            if let frm = p("from"), t.txnDate < frm { continue }
            if let to = p("to"), t.txnDate > to { continue }
            if let c = p("category"), t.category != c { continue }
            if let m = p("method"), t.method != m { continue }
            if params["direct"] == "0" && t.isDirect { continue }
            if !L.txnMatches(t, f) { continue }
            let r = L.txnRow(t)
            if !q.isEmpty && !["txn_no", "reference", "notes", "customer_name", "supplier_name", "booking_ref", "category", "payee"].contains(where: {
                let v = r[$0] ?? .null
                return (v.isNull ? "" : v.pyStr).lowercased().contains(q)
            }) { continue }
            out.append(r)
        }
        out.sort { ($0["txn_date"]!.pyStr, $0["id"]!.intValue!) > ($1["txn_date"]!.pyStr, $1["id"]!.intValue!) }
        return out
    }

    // MARK: debts (balance guard only; the debt register itself is managed in the desktop app for now)

    func debtAfterChange(_ did: Int) throws {
        guard let d = try db.one("SELECT * FROM debts WHERE id = ?", [did]) else { throw AZError.notFound("Debt not found") }
        var loanReceived = 0, principalRepaid = 0, interestPaid = 0, interestCharged = 0
        for t in try db.query("SELECT kind, sign, base_amount FROM transactions WHERE debt_id = ?", [did]) {
            let v = t.int("sign") * t.int("base_amount")
            switch t.str("kind") {
            case "loan_received": loanReceived += v
            case "loan_repayment": principalRepaid += v
            case "expense": interestPaid += v
            default: break
            }
        }
        for e in try db.query("SELECT sign, amount FROM debt_entries WHERE debt_id = ?", [did]) { interestCharged += e.int("sign") * e.int("amount") }
        let principalOutstanding = d.int("opening_amount") + loanReceived - principalRepaid
        let totalOutstanding = principalOutstanding + max(0, interestCharged - interestPaid)
        if principalOutstanding < 0 {
            throw AZError.validation("This would make the principal repaid \(fmt(-principalOutstanding)) more than the amount borrowed. "
                                     + "Check the amounts, or reverse the later repayments first.", "amount")
        }
        if d.str("status") == "closed" && totalOutstanding != 0 {
            try db.run("UPDATE debts SET status = 'open', updated_at = ? WHERE id = ?", [Dates.nowISO(), did])
            try audit("debt", did, "reopen", ["status": "closed"], ["status": "open", "reason": "balance changed"])
        }
    }
}

extension SQLValue {
    var json: JSONValue { JSONValue(self) }
}
