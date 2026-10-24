package com.airzelo.engine

import java.util.UUID

// Port of the adjustment, transaction, refund and allocation services (core/server/services.py) and of the debt
// balance guard (debts.after_change). Posted money is never edited or deleted: a correction is a reversal plus a
// new entry, inside one database transaction.

val METHODS = listOf("cash", "bank_transfer", "upi", "card", "cheque", "payment_gateway", "portal_wallet", "adjustment", "other")
val ADJ_KINDS = listOf("cancellation", "refund", "reissue", "date_change", "other")
internal val PARTY_KINDS = mapOf("customer_receipt" to "customer", "customer_refund" to "customer", "supplier_payment" to "supplier", "supplier_receipt" to "supplier")
internal val TXN_PREFIX = mapOf("customer_receipt" to "RCT", "supplier_payment" to "PAY", "customer_refund" to "CRF", "supplier_receipt" to "SRC",
    "expense" to "EXP", "other_income" to "INC", "transfer" to "TRF", "loan_received" to "LNR", "loan_repayment" to "LNP")
internal val LOAN_KINDS = setOf("loan_received", "loan_repayment")
internal val DEFAULT_CATEGORY = mapOf("customer_receipt" to "Customer receipt", "supplier_payment" to "Supplier payment",
    "customer_refund" to "Customer refund", "supplier_receipt" to "Supplier refund", "transfer" to "Transfer",
    "loan_received" to "Loan received", "loan_repayment" to "Loan repayment")
internal val META_FIELDS = listOf("reference", "notes", "payee", "method", "category")

private fun js(v: SqlValue) = JValue.fromSql(v)

// ---- adjustments

fun Agency.createAdjustment(bid: Long, data: Fields): Long {
    val idem = V.s(data, "idem_key", 80)
    return tx {
        if (idem != null) db.one("SELECT id FROM booking_adjustments WHERE idem_key = ?", listOf(idem))?.let { return@tx it.long("id") }
        val b = db.one("SELECT * FROM bookings WHERE id = ?", listOf(bid)) ?: throw AZError.notFound("Booking not found")
        if (!b.bool("committed")) throw AZError.validation("This booking was never confirmed, so there is nothing to credit. Change its status instead.")
        val kind = V.choice(data, "kind", ADJ_KINDS, null, "Adjustment type")
        val adjDate = V.d(data, "adj_date", label = "Adjustment date") ?: Dates.today()
        val p = ADJ_FIELDS.associateWith { parseAmount(data.arg(it), V.capitalize(it.replace("_", " "))) }
        if (p.values.none { it != 0L }) throw AZError.validation("Enter at least one amount for the adjustment")
        if (p["customer_credit_tax"]!! > p["customer_credit"]!!) throw AZError.validation("Tax portion cannot exceed the customer credit", "customer_credit_tax")
        val calc = Ledger(this).bookings[bid]!!.calc
        val rate = b.str("exchange_rate")
        val custNet = calc.customerPayable - calc.customerCredits
        if (convert(p["customer_credit"]!!, rate) > custNet + convert(p["extra_customer_charge"]!!, rate))
            throw AZError.validation("Customer credit is more than the amount billed to the customer", "customer_credit")
        val supNet = calc.supplierPayable - (calc.supplierCredits - calc.commissionNet)
        if (convert(p["supplier_credit"]!!, rate) > supNet + convert(p["extra_supplier_charge"]!!, rate))
            throw AZError.validation("Supplier credit is more than the supplier cost", "supplier_credit")
        if (convert(p["commission_reversal"]!!, rate) > calc.commissionNet)
            throw AZError.validation("Commission reversal is more than the commission earned", "commission_reversal")
        val newStatus = data.arg("new_status")
        if (newStatus.truthy && newStatus.py !in BOOKING_STATUSES) throw AZError.validation("Invalid status", "new_status")
        val adjNo = String.format("ADJ-%05d", nextCounter("adjustment"))
        val ts = Dates.nowISO()
        val aid = db.run("INSERT INTO booking_adjustments(adj_no, booking_id, adj_date, kind, sign, exchange_rate, customer_credit, customer_credit_tax, " +
            "supplier_credit, commission_reversal, extra_customer_charge, extra_supplier_charge, notes, idem_key, created_at) VALUES (?,?,?,?,1,?,?,?,?,?,?,?,?,?,?)",
            listOf(adjNo, bid, adjDate, kind, rate, p["customer_credit"], p["customer_credit_tax"], p["supplier_credit"], p["commission_reversal"],
                p["extra_customer_charge"], p["extra_supplier_charge"], V.s(data, "notes", 2000), idem, ts))
        history(bid, "adjustment", "$adjNo ${kind.replace("_", " ")} recorded")
        audit("adjustment", aid, "create", null, JValue.Obj(p.mapValues { J(it.value) } + mapOf("kind" to J(kind), "adj_date" to J(adjDate), "booking_id" to J(bid))))
        if (newStatus.truthy && newStatus.py != b.str("status")) {
            db.run("UPDATE bookings SET status = ?, updated_at = ? WHERE id = ?", listOf(newStatus.py, ts, bid))
            history(bid, "status", "${b.str("status")} -> ${newStatus.py}")
            audit("booking", bid, "status", JValue.obj("status" to b.str("status")), JValue.obj("status" to newStatus))
        }
        aid
    }
}

fun Agency.reverseAdjustment(aid: Long, data: Fields): Long = tx {
    val a = db.one("SELECT * FROM booking_adjustments WHERE id = ?", listOf(aid)) ?: throw AZError.notFound("Adjustment not found")
    if (a.optLong("reversal_of") != null || a.long("sign") == -1L) throw AZError.validation("A reversal cannot itself be reversed")
    if (a.optLong("reversed_by") != null) throw AZError.validation("This adjustment has already been reversed")
    if (db.one("SELECT 1 FROM invoices WHERE adjustment_id = ? AND status = 'issued'", listOf(aid)) != null) throw AZError.validation("Cancel the credit note issued for this adjustment first")
    val n = nextCounter("adjustment")
    val ts = Dates.nowISO()
    val reason = V.s(data, "reason", 500) ?: "Reversal"
    val rid = db.run("INSERT INTO booking_adjustments(adj_no, booking_id, adj_date, kind, sign, exchange_rate, customer_credit, customer_credit_tax, " +
        "supplier_credit, commission_reversal, extra_customer_charge, extra_supplier_charge, notes, reversal_of, created_at) VALUES (?,?,?,?,-1,?,?,?,?,?,?,?,?,?,?)",
        listOf(String.format("ADJ-%05d", n), a.long("booking_id"), V.d(data, "date") ?: Dates.today(), a.str("kind"), a.str("exchange_rate"),
            a.long("customer_credit"), a.long("customer_credit_tax"), a.long("supplier_credit"), a.long("commission_reversal"),
            a.long("extra_customer_charge"), a.long("extra_supplier_charge"), "Reversal of ${a.str("adj_no")}: $reason", aid, ts))
    db.run("UPDATE booking_adjustments SET reversed_by = ? WHERE id = ?", listOf(rid, aid))
    history(a.long("booking_id"), "adjustment", "${a.str("adj_no")} reversed ($reason)")
    audit("adjustment", aid, "reverse", JValue.Obj(a.json), JValue.obj("reversal_id" to rid, "reason" to reason))
    rid
}

fun Agency.correctAdjustment(aid: Long, data: Fields): Long = tx {
    val a = db.one("SELECT * FROM booking_adjustments WHERE id = ?", listOf(aid)) ?: throw AZError.notFound("Adjustment not found")
    if (a.optLong("reversed_by") != null || a.long("sign") < 0) throw AZError.validation("This adjustment has already been reversed, so it cannot be edited")
    reverseAdjustment(aid, fieldsOf("reason" to (V.s(data, "reason", 300) ?: "Edited"), "date" to a.str("adj_date")))
    val d = LinkedHashMap(data); d["kind"] = if (data.arg("kind").truthy) data.arg("kind") else J(a.str("kind"))
    val nid = createAdjustment(a.long("booking_id"), d)
    audit("adjustment", aid, "edit", JValue.obj("adj_no" to a.str("adj_no")), JValue.obj("replaced_by" to nid))
    nid
}

// ---- transactions

internal data class Alloc(val bookingId: Long, val amount: Long)

internal fun Agency.validateAllocations(kind: String, partyId: Long?, baseAmount: Long, allocs: JValue, txnId: Long? = null): List<Alloc> {
    if (!allocs.truthy) return emptyList()
    val list = allocs.array ?: throw AZError.validation("Allocations must be a list", "allocations")
    val partyCol = if (PARTY_KINDS[kind] == "customer") "customer_id" else "supplier_id"
    val out = ArrayList<Alloc>(); val seen = HashSet<Long>(); var total = 0L
    for (av in list) {
        val a = av.obj ?: continue
        val bid = V.i(a, "booking_id", required = true, label = "Booking")!!
        val amt = parseAmount(a.arg("amount"), "Allocated amount")
        if (amt == 0L) continue
        if (!seen.add(bid)) throw AZError.validation("A booking is allocated twice", "allocations")
        val b = db.one("SELECT id, ref, $partyCol AS party FROM bookings WHERE id = ?", listOf(bid)) ?: throw AZError.validation("Allocated booking does not exist", "allocations")
        if (b.optLong("party") != partyId) throw AZError.validation("Booking ${b.str("ref")} belongs to a different ${PARTY_KINDS[kind]}", "allocations")
        total += amt
        out.add(Alloc(bid, amt))
    }
    if (total > baseAmount) throw AZError.validation("Allocated total ${fmt(total)} is more than the payment amount ${fmt(baseAmount)}", "allocations")
    checkAllocationCaps(kind, out, txnId)
    return out
}

/** Money paid back to a customer (or received back from a supplier) must match an amount actually due back. */
internal fun Agency.checkRefundBacked(kind: String, partyId: Long, baseAmount: Long, allocs: List<Alloc>) {
    if (kind != "customer_refund" && kind != "supplier_receipt") return
    val L = Ledger(this)
    val customer = kind == "customer_refund"
    val unallocated = if (customer) (L.customerBalances()[partyId] ?: return).unallocated else (L.supplierBalances()[partyId] ?: return).unallocated
    val free = baseAmount - allocs.sumOf { it.amount }
    val available = maxOf(unallocated, 0L)
    if (free > available) {
        val who = if (customer) "the customer" else "you by the supplier"
        throw AZError.conflict("Nothing is due back to $who for this amount (unused advance: ${L.base} ${fmt(available)}). To refund a booking, open it and use " +
            "'Refund / cancel' - that records the refund credit, the airline / supplier refund and any fee you keep, " +
            "so the booking profit is adjusted, and then pays the refund.", "refund_not_due", JValue.obj("due_back" to available))
    }
    for (a in allocs) {
        val b = L.bookings[a.bookingId] ?: continue
        val due = -(if (customer) b.calc.customerOutstanding else b.calc.supplierOutstanding)
        if (a.amount > maxOf(due, 0L)) throw AZError.conflict("Booking ${b.ref} has only ${L.base} ${fmt(maxOf(due, 0L))} due back. Use 'Refund / cancel' on the booking to record the refund first.",
            "refund_not_due", JValue.obj("booking_id" to b.id, "due_back" to maxOf(due, 0L)))
    }
}

/** A receipt or payment may not be allocated beyond a confirmed booking's outstanding balance. */
internal fun Agency.checkAllocationCaps(kind: String, allocs: List<Alloc>, txnId: Long? = null) {
    if ((kind != "customer_receipt" && kind != "supplier_payment") || allocs.isEmpty()) return
    val L = Ledger(this)
    for (a in allocs) {
        val b = L.bookings[a.bookingId] ?: continue
        if (!b.committed) continue
        var due = if (kind == "customer_receipt") b.calc.customerOutstanding else b.calc.supplierOutstanding
        if (txnId != null) due += L.allocations[txnId].orEmpty().filter { it.first == a.bookingId }.sumOf { it.second }
        if (a.amount > maxOf(due, 0L)) {
            val dueTxt = "${L.base} ${fmt(maxOf(due, 0L))}"
            throw AZError.validation("Booking ${b.ref} has only $dueTxt outstanding${if (kind == "supplier_payment") " to the supplier" else ""}. " +
                "Reduce the allocation to $dueTxt or less; any extra stays unallocated.", "allocations")
        }
    }
}

internal fun Agency.txnPrefix(kind: String): String =
    if (kind == "customer_receipt") (setting("receipt_prefix", "").takeUnless { it.isNullOrEmpty() } ?: TXN_PREFIX[kind]!!).trimEnd('-') else TXN_PREFIX[kind]!!

/** Post a receipt, payment, refund, expense, other income or transfer. Returns (id, replayed). */
fun Agency.createTransaction(data: Fields): Pair<Long, Boolean> {
    val kind = V.choice(data, "kind", KIND_ORDER, null, "Transaction type")
    val idem = V.s(data, "idem_key", 80)
    if (idem != null) db.one("SELECT id FROM transactions WHERE idem_key = ?", listOf(idem))?.let { return it.long("id") to true }
    return tx {
        if (idem != null) db.one("SELECT id FROM transactions WHERE idem_key = ?", listOf(idem))?.let { return@tx it.long("id") to true }
        val txnDate = V.d(data, "txn_date", label = "Date") ?: Dates.today()
        val accountId = V.i(data, "account_id", required = true, label = "Account")!!
        val acc = db.one("SELECT * FROM accounts WHERE id = ?", listOf(accountId)) ?: throw AZError.validation("Selected account does not exist", "account_id")
        if (!acc.bool("active")) throw AZError.validation("Selected account is inactive", "account_id")
        val amount = parseAmount(data.arg("amount"), "Amount", required = true, allowZero = false)
        val currency = V.normCode(data.arg("currency"), 3) ?: acc.str("currency")
        if (currency != acc.str("currency")) throw AZError.validation("Account ${acc.str("name")} holds ${acc.str("currency")}. Record this in ${acc.str("currency")} or use an account in $currency.", "currency")
        val (_, rate) = currencyAndRate(LinkedHashMap(data).also { it["currency"] = J(currency) })
        val baseAmount = convert(amount, rate)
        val method = V.choice(data, "method", METHODS, "cash", "Payment method")
        val reference = V.s(data, "reference", 120)
        var category = V.s(data, "category", 80) ?: DEFAULT_CATEGORY[kind]
        if (category == null) throw AZError.validation("Category is required", "category")
        val notes = V.s(data, "notes", 2000)
        val payee = V.s(data, "payee", 150)
        var customerId = V.i(data, "customer_id"); var supplierId = V.i(data, "supplier_id"); var bookingId = V.i(data, "booking_id")
        var toAccountId: Long? = null; var toAmount: Long? = null; var isDirect = 0L
        val booking = bookingId?.let { db.one("SELECT * FROM bookings WHERE id = ?", listOf(it)) ?: throw AZError.validation("Linked booking does not exist", "booking_id") }
        val party = PARTY_KINDS[kind]
        when {
            party == "customer" -> {
                if (customerId == null && booking != null) customerId = booking.long("customer_id")
                if (customerId == null || customerId == 0L) throw AZError.validation("Select the customer", "customer_id")
                db.one("SELECT 1 FROM customers WHERE id = ?", listOf(customerId)) ?: throw AZError.validation("Selected customer does not exist", "customer_id")
                supplierId = null
            }
            party == "supplier" -> {
                if (supplierId == null && booking != null) supplierId = booking.optLong("supplier_id")
                if (supplierId == null || supplierId == 0L) throw AZError.validation("Select the supplier", "supplier_id")
                db.one("SELECT 1 FROM suppliers WHERE id = ?", listOf(supplierId)) ?: throw AZError.validation("Selected supplier does not exist", "supplier_id")
                customerId = null
            }
            kind == "transfer" -> {
                toAccountId = V.i(data, "to_account_id", required = true, label = "Destination account")
                if (toAccountId == accountId) throw AZError.validation("Choose two different accounts for a transfer", "to_account_id")
                val toAcc = db.one("SELECT * FROM accounts WHERE id = ?", listOf(toAccountId)) ?: throw AZError.validation("Destination account does not exist", "to_account_id")
                toAmount = if (toAcc.str("currency") == acc.str("currency")) amount
                    else parseAmount(data.arg("to_amount"), "Amount received in ${toAcc.str("currency")}", required = true, allowZero = false)
                customerId = null; supplierId = null; bookingId = null; category = "Transfer"
            }
            kind == "expense" -> {
                if (booking != null) isDirect = 1
                if (supplierId != null && db.one("SELECT 1 FROM suppliers WHERE id = ?", listOf(supplierId)) == null) throw AZError.validation("Selected supplier does not exist", "supplier_id")
                customerId = null
            }
            kind == "other_income" -> { customerId = null; supplierId = null; bookingId = null }
            kind in LOAN_KINDS -> { customerId = null; supplierId = null; bookingId = null }
        }
        val debtId = if (kind in LOAN_KINDS || kind == "expense") V.i(data, "debt_id") else null
        if (kind in LOAN_KINDS && debtId == null) throw AZError.validation("Select the loan / debt", "debt_id")
        if (debtId != null) {
            val debt = db.one("SELECT * FROM debts WHERE id = ?", listOf(debtId)) ?: throw AZError.validation("Selected debt does not exist", "debt_id")
            if (debt.str("status") != "open" && !data.arg("_replacing").truthy) throw AZError.validation("This debt is closed. Reopen it to record more entries.", "debt_id")
        }
        var allocs: List<Alloc> = emptyList()
        if (party != null) {
            val partyId = if (party == "customer") customerId else supplierId
            if (booking != null && (if (party == "customer") booking.optLong("customer_id") else booking.optLong("supplier_id")) != partyId)
                throw AZError.validation("Linked booking belongs to a different $party", "booking_id")
            if (data.containsKey("allocations") && data.arg("allocations").truthy) allocs = validateAllocations(kind, partyId, baseAmount, data.arg("allocations"))
            else if (booking != null) allocs = listOf(Alloc(bookingId!!, baseAmount))
            if (allocs.size == 1 && bookingId == null) bookingId = allocs[0].bookingId
            checkRefundBacked(kind, partyId!!, baseAmount, allocs)
        }
        if (!V.b(data, "force_duplicate")) {
            val q = "SELECT txn_no FROM transactions WHERE kind = ? AND txn_date = ? AND amount = ? AND account_id = ? AND sign = 1 " +
                "AND reversed_by IS NULL AND COALESCE(reference,'') = COALESCE(?, '') AND COALESCE(booking_id,0) = COALESCE(?,0) " +
                "AND COALESCE(customer_id,0) = ? AND COALESCE(supplier_id,0) = ?"
            db.one(q, listOf(kind, txnDate, amount, accountId, reference, bookingId, customerId ?: 0, supplierId ?: 0))?.let {
                throw AZError.conflict("This looks like a duplicate of ${it.str("txn_no")} (same type, date, amount, account and reference). " +
                    "Confirm to record it anyway.", "possible_duplicate", JValue.obj("txn_no" to it.str("txn_no")))
            }
        }
        val n = nextCounter("txn-" + TXN_PREFIX[kind])
        val txnNo = txnPrefix(kind) + "-" + String.format("%06d", n)
        val ts = Dates.nowISO()
        val rateDate = V.d(data, "rate_date") ?: if (rate != "1") txnDate else null
        val tid = db.run("INSERT INTO transactions(txn_no, uid, idem_key, txn_date, kind, sign, amount, currency, exchange_rate, rate_date, base_amount, " +
            "account_id, to_account_id, to_amount, method, reference, category, customer_id, supplier_id, booking_id, is_direct, payee, notes, " +
            "debt_id, created_at, updated_at) VALUES (?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(txnNo, UUID.randomUUID().toString(), idem, txnDate, kind, amount, currency, rate, rateDate, baseAmount, accountId, toAccountId, toAmount,
                method, reference, category, customerId, supplierId, bookingId, isDirect, payee, notes, debtId, ts, ts))
        if (debtId != null) debtAfterChange(debtId)
        for (a in allocs) {
            db.run("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", listOf(tid, a.bookingId, a.amount, ts))
            history(a.bookingId, "payment", "$txnNo ${KIND_LABELS[kind]!!.lowercase()} ${fmt(a.amount)} allocated")
        }
        if (kind == "expense" && isDirect == 1L) history(bookingId!!, "expense", "$txnNo direct expense recorded")
        audit("transaction", tid, "create", null, JValue.obj("txn_no" to txnNo, "kind" to kind, "date" to txnDate, "amount" to amount, "currency" to currency,
            "rate" to rate, "base_amount" to baseAmount, "account_id" to accountId, "method" to method, "reference" to reference, "customer_id" to customerId,
            "supplier_id" to supplierId, "booking_id" to bookingId, "debt_id" to debtId,
            "allocations" to allocs.map { mapOf("booking_id" to it.bookingId, "amount" to it.amount) }))
        tid to false
    }
}

fun Agency.reverseTransaction(tid: Long, data: Fields): Long = tx {
    val t = db.one("SELECT * FROM transactions WHERE id = ?", listOf(tid)) ?: throw AZError.notFound("Transaction not found")
    if (t.optLong("reversal_of") != null || t.long("sign") == -1L) throw AZError.validation("A reversal cannot itself be reversed")
    if (t.optLong("reversed_by") != null) throw AZError.validation("This transaction has already been reversed")
    val reason = V.s(data, "reason", 500, required = true, label = "Reason")!!
    val rdate = V.d(data, "date") ?: Dates.today()
    val kind = t.str("kind")
    val n = nextCounter("txn-" + TXN_PREFIX[kind])
    val txnNo = txnPrefix(kind) + "-" + String.format("%06d", n)
    val ts = Dates.nowISO()
    val debtId = if (t.has("debt_id")) t.optLong("debt_id") else null
    val rid = db.run("INSERT INTO transactions(txn_no, uid, idem_key, txn_date, kind, sign, amount, currency, exchange_rate, rate_date, base_amount, " +
        "account_id, to_account_id, to_amount, method, reference, category, customer_id, supplier_id, booking_id, is_direct, payee, notes, " +
        "debt_id, reversal_of, created_at, updated_at) VALUES (?,?,NULL,?,?,-1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        listOf(txnNo, UUID.randomUUID().toString(), rdate, kind, t.long("amount"), t.str("currency"), t.str("exchange_rate"), t["rate_date"],
            t.long("base_amount"), t.long("account_id"), t["to_account_id"], t["to_amount"], t.str("method"), t["reference"], t.str("category"),
            t["customer_id"], t["supplier_id"], t["booking_id"], t.long("is_direct"), t["payee"], "Reversal of ${t.str("txn_no")}: $reason", debtId, tid, ts, ts))
    for (a in db.query("SELECT * FROM allocations WHERE txn_id = ?", listOf(tid))) {
        db.run("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", listOf(rid, a.long("booking_id"), a.long("amount"), ts))
        history(a.long("booking_id"), "payment", "${t.str("txn_no")} reversed by $txnNo ($reason)")
    }
    db.run("UPDATE transactions SET reversed_by = ?, updated_at = ? WHERE id = ?", listOf(rid, ts, tid))
    if (debtId != null && !data.arg("_defer_debt_check").truthy) debtAfterChange(debtId)
    audit("transaction", tid, "reverse", JValue.obj("txn_no" to t.str("txn_no"), "amount" to t.long("amount")), JValue.obj("reversal" to txnNo, "reason" to reason))
    rid
}

/** Edit a posted transaction: descriptive fields in place; any money change is a reversal plus a corrected entry. */
fun Agency.correctTransaction(tid: Long, data: Fields): Pair<Long, String> {
    val idem = V.s(data, "idem_key", 80)
    if (idem != null) db.one("SELECT id FROM transactions WHERE idem_key = ?", listOf(idem))?.let { return it.long("id") to "replay" }
    return tx {
        val t = db.one("SELECT * FROM transactions WHERE id = ?", listOf(tid)) ?: throw AZError.notFound("Transaction not found")
        if (t.optLong("reversed_by") != null || t.long("sign") < 0) throw AZError.validation("This entry has already been deleted / reversed, so it cannot be edited")
        if (data.arg("kind").truthy && data.arg("kind").py != t.str("kind")) throw AZError.validation("The type of an entry cannot be changed. Delete it and record a new one.")
        val new = LinkedHashMap(data); new["kind"] = J(t.str("kind"))
        val oldAllocs = db.query("SELECT booking_id, amount FROM allocations WHERE txn_id = ?", listOf(tid)).map { listOf(it.long("booking_id"), it.long("amount")) }
            .sortedWith(compareBy({ it[0] }, { it[1] }))
        val acc = db.one("SELECT * FROM accounts WHERE id = ?", listOf(V.i(new, "account_id") ?: t.long("account_id")))
        val amount = parseAmount(new.arg("amount"), "Amount", required = true, allowZero = false)
        val (_, rate) = currencyAndRate(LinkedHashMap(new).also { it["currency"] = J(acc?.str("currency") ?: t.str("currency")) })
        val base = convert(amount, rate)
        var newAllocs: List<List<Long>>? = null
        if (new.containsKey("allocations")) {
            newAllocs = (new.arg("allocations").array ?: emptyList()).mapNotNull { av ->
                val a = av.obj ?: return@mapNotNull null
                val amt = parseAmount(a.arg("amount"), "Allocated amount")
                if (amt != 0L) listOf(a.arg("booking_id").long ?: 0L, amt) else null
            }.sortedWith(compareBy({ it[0] }, { it[1] }))
        }
        val tDebt = if (t.has("debt_id")) t.optLong("debt_id") else null
        val newDate = V.d(new, "txn_date") ?: t.str("txn_date")
        val newAccount = V.i(new, "account_id") ?: t.long("account_id")
        val newTo = V.i(new, "to_account_id") ?: t.optLong("to_account_id")
        var same = amount == t.long("amount") && newDate == t.str("txn_date") && newAccount == t.long("account_id") && base == t.long("base_amount") && newTo == t.optLong("to_account_id")
        if (same && t.str("kind") == "transfer") {
            val recv = if (new.arg("to_amount").truthy) new.arg("to_amount") else new.arg("amount")
            same = parseAmount(recv, "Amount received") == (t.optLong("to_amount") ?: t.long("amount"))
        }
        if (same) {
            val c = V.i(new, "customer_id") ?: t.optLong("customer_id")
            val s = V.i(new, "supplier_id") ?: t.optLong("supplier_id")
            val d = V.i(new, "debt_id") ?: tDebt
            same = c == t.optLong("customer_id") && s == t.optLong("supplier_id") && d == tDebt && (newAllocs == null || newAllocs == oldAllocs)
        }
        if (same) {
            updateTransactionMeta(tid, META_FIELDS.filter { new.containsKey(it) }.associateWith { new.arg(it) })
            return@tx tid to "updated"
        }
        val reason = V.s(new, "reason", 300) ?: "Edited"
        val rid = reverseTransaction(tid, fieldsOf("reason" to reason, "date" to t.str("txn_date"), "_defer_debt_check" to true))
        if (tDebt != null && !new.containsKey("debt_id")) new["debt_id"] = J(tDebt)
        new["force_duplicate"] = JValue.Bool(true)
        new["_replacing"] = J(tid)
        if (!new.containsKey("booking_id")) new["booking_id"] = if (t.bool("is_direct")) js(t["booking_id"]) else JValue.Null
        val (nid, _) = createTransaction(new)
        db.run("UPDATE transactions SET notes = COALESCE(notes || char(10), '') || ? WHERE id = ?", listOf("Corrected from ${t.str("txn_no")}", nid))
        audit("transaction", tid, "edit", JValue.obj("txn_no" to t.str("txn_no"), "amount" to t.long("amount"), "date" to t.str("txn_date")),
            JValue.obj("replaced_by" to nid, "reversal" to rid, "reason" to reason))
        nid to "replaced"
    }
}

/** Only descriptive fields can be edited; amounts require a reversal. */
fun Agency.updateTransactionMeta(tid: Long, data: Fields): Long = tx {
    val t = db.one("SELECT * FROM transactions WHERE id = ?", listOf(tid)) ?: throw AZError.notFound("Transaction not found")
    val upd = LinkedHashMap<String, Any?>()
    if (data.containsKey("reference")) upd["reference"] = V.s(data, "reference", 120)
    if (data.containsKey("notes")) upd["notes"] = V.s(data, "notes", 2000)
    if (data.containsKey("payee")) upd["payee"] = V.s(data, "payee", 150)
    if (data.containsKey("method")) upd["method"] = V.choice(data, "method", METHODS, t.str("method"))
    if (data.containsKey("category") && t.str("kind") in listOf("expense", "other_income")) upd["category"] = V.s(data, "category", 80, required = true, label = "Category")
    val forbidden = listOf("amount", "txn_date", "account_id", "currency", "exchange_rate", "kind").filter { data.containsKey(it) && data.arg(it).py != (t.optStr(it) ?: "None") }
    if (forbidden.isNotEmpty()) throw AZError.validation("Amount, date, account and currency cannot be edited on a posted transaction. Reverse it and record it again.")
    if (upd.isEmpty()) return@tx tid
    val before = upd.keys.associateWith { js(t[it]) }
    val after = upd.mapValues { JValue.wrap(it.value) }
    upd["updated_at"] = Dates.nowISO()
    db.run("UPDATE transactions SET ${upd.keys.joinToString(", ") { "$it = ?" }} WHERE id = ?", upd.values.toList() + tid)
    audit("transaction", tid, "edit", JValue.Obj(before), JValue.Obj(after))
    tid
}

fun Agency.setAllocations(tid: Long, data: Fields): Long = tx {
    val t = db.one("SELECT * FROM transactions WHERE id = ?", listOf(tid)) ?: throw AZError.notFound("Transaction not found")
    val party = PARTY_KINDS[t.str("kind")] ?: throw AZError.validation("Only receipts, payments and refunds can be allocated")
    if (t.optLong("reversed_by") != null || t.optLong("reversal_of") != null) throw AZError.validation("Reversed transactions cannot be re-allocated")
    val partyId = if (party == "customer") t.optLong("customer_id") else t.optLong("supplier_id")
    val allocs = validateAllocations(t.str("kind"), partyId, t.long("base_amount"), if (data.arg("allocations").truthy) data.arg("allocations") else JValue.Arr(emptyList()), tid)
    val before = db.query("SELECT booking_id, amount FROM allocations WHERE txn_id = ?", listOf(tid))
    db.run("DELETE FROM allocations WHERE txn_id = ?", listOf(tid))
    val ts = Dates.nowISO()
    for (a in allocs) db.run("INSERT INTO allocations(txn_id, booking_id, amount, created_at) VALUES (?,?,?,?)", listOf(tid, a.bookingId, a.amount, ts))
    val changed = (before.map { it.long("booking_id") } + allocs.map { it.bookingId }).toSortedSet()
    for (bid in changed) history(bid, "allocation", "Allocation of ${t.str("txn_no")} updated")
    audit("allocation", tid, "update", JValue.Arr(before.map { JValue.Obj(it.json) }), JValue.Arr(allocs.map { JValue.obj("booking_id" to it.bookingId, "amount" to it.amount) }))
    tid
}

// ---- refunds

class RefundResult(val adjustmentId: Long, val transactions: Map<String, Long>, val profitBefore: Long?, val grossProfit: Long,
                   val customerOutstanding: Long, val supplierOutstanding: Long, val paymentStatus: String, val replayed: Boolean)

/** One-step refund / cancellation: credit adjustment, then the cash refund and any supplier refund, all in one transaction. */
fun Agency.refundBooking(bid: Long, data: Fields): RefundResult {
    val idem = V.s(data, "idem_key", 80)
    return tx {
        if (idem != null) db.one("SELECT id FROM booking_adjustments WHERE idem_key = ?", listOf(idem))?.let { hit ->
            val c = Ledger(this).bookings[bid]!!.calc
            return@tx RefundResult(hit.long("id"), emptyMap(), null, c.grossProfit, c.customerOutstanding, c.supplierOutstanding, c.paymentStatus, true)
        }
        val b = db.one("SELECT * FROM bookings WHERE id = ?", listOf(bid)) ?: throw AZError.notFound("Booking not found")
        val before = Ledger(this).bookings[bid]!!.calc
        val cust = parseAmount(data.arg("customer_refund"), "Refund to customer")
        parseAmount(data.arg("customer_refund_tax"), "Tax part of the refund")
        val sup = parseAmount(data.arg("supplier_refund"), "Refund from supplier / airline")
        val comm = parseAmount(data.arg("commission_reversal"), "Commission given back")
        if (cust == 0L && sup == 0L && comm == 0L) throw AZError.validation("Enter the refund to the customer and / or the refund from the supplier", "customer_refund")
        val date = V.d(data, "date", label = "Refund date") ?: Dates.today()
        val status = if (data.arg("new_status").truthy) data.arg("new_status").py else ""
        val kind = if (status == "cancelled" || status == "refunded") "cancellation" else "refund"
        val aid = createAdjustment(bid, fieldsOf("kind" to kind, "adj_date" to date, "customer_credit" to data.arg("customer_refund"),
            "customer_credit_tax" to data.arg("customer_refund_tax"), "supplier_credit" to data.arg("supplier_refund"),
            "commission_reversal" to data.arg("commission_reversal"), "new_status" to status, "notes" to V.s(data, "notes", 2000), "idem_key" to idem))
        val txns = LinkedHashMap<String, Long>()
        val baseCur = setting("base_currency", "INR") ?: "INR"
        for (key in listOf("pay_customer", "supplier_received")) {
            val sub = data.arg(key).obj ?: emptyMap()
            if (sub.arg("account_id").truthy) {
                val acc = db.one("SELECT name, currency FROM accounts WHERE id = ?", listOf(V.i(sub, "account_id")))
                if (acc != null && acc.str("currency") != baseCur) throw AZError.validation("Use a $baseCur account here, or record this refund separately from the Refunds page.", key)
            }
        }
        val pay = data.arg("pay_customer").obj ?: emptyMap()
        if (pay.arg("account_id").truthy && parseAmount(pay.arg("amount"), "Refund paid now") != 0L) {
            txns["customer_refund"] = createTransaction(fieldsOf("kind" to "customer_refund", "txn_date" to date, "account_id" to pay.arg("account_id"),
                "amount" to pay.arg("amount"), "exchange_rate" to pay.arg("exchange_rate"), "method" to (if (pay.arg("method").truthy) pay.arg("method") else J("bank_transfer")),
                "reference" to pay.arg("reference"), "customer_id" to b.long("customer_id"), "force_duplicate" to true, "idem_key" to idem?.let { "$it:customer" },
                "allocations" to listOf(mapOf("booking_id" to bid, "amount" to pay.arg("amount"))), "notes" to "Refund for ${b.str("ref")}")).first
        }
        val got = data.arg("supplier_received").obj ?: emptyMap()
        if (got.arg("account_id").truthy && parseAmount(got.arg("amount"), "Supplier refund received") != 0L) {
            if (b.optLong("supplier_id") == null) throw AZError.validation("This booking has no supplier", "supplier_received")
            txns["supplier_receipt"] = createTransaction(fieldsOf("kind" to "supplier_receipt", "txn_date" to date, "account_id" to got.arg("account_id"),
                "amount" to got.arg("amount"), "exchange_rate" to got.arg("exchange_rate"), "method" to (if (got.arg("method").truthy) got.arg("method") else J("portal_wallet")),
                "reference" to got.arg("reference"), "supplier_id" to b.long("supplier_id"), "force_duplicate" to true, "idem_key" to idem?.let { "$it:supplier" },
                "allocations" to listOf(mapOf("booking_id" to bid, "amount" to got.arg("amount"))), "notes" to "Supplier refund for ${b.str("ref")}")).first
        }
        audit("booking", bid, "refund", JValue.obj("gross_profit" to before.grossProfit), JValue.obj("adjustment_id" to aid, "customer_refund" to cust,
            "supplier_refund" to sup, "commission_reversal" to comm, "transactions" to txns))
        val c = Ledger(this).bookings[bid]!!.calc
        RefundResult(aid, txns, before.grossProfit, c.grossProfit, c.customerOutstanding, c.supplierOutstanding, c.paymentStatus, false)
    }
}

fun Agency.listTransactions(params: Map<String, String>, ledger: Ledger? = null): List<LinkedHashMap<String, JValue>> {
    val L = ledger ?: Ledger(this)
    fun p(k: String) = params[k]?.takeIf { it.isNotEmpty() }
    val kinds = p("kind")?.split(",")?.toSet()
    val q = (params["q"] ?: "").trim().lowercase()
    val f = LedgerFilter(customerId = p("customer_id"), supplierId = p("supplier_id"), currency = p("currency"), accountId = p("account_id"))
    val out = ArrayList<LinkedHashMap<String, JValue>>()
    for (t in L.transactions) {
        if (kinds != null && t.kind !in kinds) continue
        val frm = p("from"); val to = p("to")
        if (frm != null && t.txnDate < frm) continue
        if (to != null && t.txnDate > to) continue
        if (p("category") != null && t.category != p("category")) continue
        if (p("method") != null && t.method != p("method")) continue
        if (params["direct"] == "0" && t.isDirect) continue
        if (!L.txnMatches(t, f)) continue
        val r = L.txnRow(t)
        if (q.isNotEmpty() && listOf("txn_no", "reference", "notes", "customer_name", "supplier_name", "booking_ref", "category", "payee")
                .none { k -> r[k]!!.let { if (it.isNull) "" else it.py }.lowercase().contains(q) }) continue
        out.add(r)
    }
    return out.sortedWith(compareByDescending<Map<String, JValue>> { it["txn_date"]!!.py }.thenByDescending { it["id"]!!.long!! })
}

/** Debt balance guard only (debts.after_change); the debt register itself is managed in the desktop app for now. */
internal fun Agency.debtAfterChange(did: Long) {
    val d = db.one("SELECT * FROM debts WHERE id = ?", listOf(did)) ?: throw AZError.notFound("Debt not found")
    var loanReceived = 0L; var principalRepaid = 0L; var interestPaid = 0L; var interestCharged = 0L
    for (t in db.query("SELECT kind, sign, base_amount FROM transactions WHERE debt_id = ?", listOf(did))) {
        val v = t.long("sign") * t.long("base_amount")
        when (t.str("kind")) { "loan_received" -> loanReceived += v; "loan_repayment" -> principalRepaid += v; "expense" -> interestPaid += v }
    }
    for (e in db.query("SELECT sign, amount FROM debt_entries WHERE debt_id = ?", listOf(did))) interestCharged += e.long("sign") * e.long("amount")
    val principalOutstanding = d.long("opening_amount") + loanReceived - principalRepaid
    val totalOutstanding = principalOutstanding + maxOf(0L, interestCharged - interestPaid)
    if (principalOutstanding < 0) throw AZError.validation("This would make the principal repaid ${fmt(-principalOutstanding)} more than the amount borrowed. " +
        "Check the amounts, or reverse the later repayments first.", "amount")
    if (d.str("status") == "closed" && totalOutstanding != 0L) {
        db.run("UPDATE debts SET status = 'open', updated_at = ? WHERE id = ?", listOf(Dates.nowISO(), did))
        audit("debt", did, "reopen", JValue.obj("status" to "closed"), JValue.obj("status" to "open", "reason" to "balance changed"))
    }
}
