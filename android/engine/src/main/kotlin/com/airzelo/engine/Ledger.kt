package com.airzelo.engine

/*
 * Port of core/server/finance.py (via the iPhone engine). Every figure is produced here from the raw records,
 * so all totals reconcile - and match the desktop app, which uses the same definitions:
 *   Gross booking profit = Markup + Commission + Service charge + Other income - Direct expenses (+/- adjustments)
 *   Net profit           = Gross profit + Other (non-booking) income - Operating expenses
 *   Customer outstanding = Customer payable - Credits - Allocated receipts + Refunds paid
 *   Supplier outstanding = Supplier payable - Supplier credits (incl. commission) - Allocated payments + Supplier refunds
 * Tax collected is a liability, never revenue or profit. A booking creates payables / revenue only once committed.
 */

val INFLOW_KINDS = setOf("customer_receipt", "supplier_receipt", "other_income")
val OUTFLOW_KINDS = setOf("supplier_payment", "customer_refund", "expense")
val FINANCING_IN_KINDS = setOf("loan_received")
val FINANCING_OUT_KINDS = setOf("loan_repayment")
val KIND_LABELS = linkedMapOf(
    "customer_receipt" to "Customer receipt", "supplier_payment" to "Supplier payment", "customer_refund" to "Refund to customer",
    "supplier_receipt" to "Supplier refund / receipt", "expense" to "Expense", "other_income" to "Other income", "transfer" to "Transfer",
    "loan_received" to "Loan received (financing)", "loan_repayment" to "Loan repayment (principal)")
val KIND_ORDER = KIND_LABELS.keys.toList()
val ADJ_FIELDS = listOf("customer_credit", "customer_credit_tax", "supplier_credit", "commission_reversal", "extra_customer_charge", "extra_supplier_charge")

internal fun inRange(d: String?, frm: String?, to: String?): Boolean {
    if (d.isNullOrEmpty()) return false
    val x = d.take(10)
    if (!frm.isNullOrEmpty() && x < frm) return false
    if (!to.isNullOrEmpty() && x > to) return false
    return true
}

class Txn(r: Row) {
    val id = r.long("id"); val txnNo = r.str("txn_no"); val uid = r.str("uid"); val txnDate = r.str("txn_date"); val kind = r.str("kind")
    val sign = r.long("sign"); val amount = r.long("amount"); val currency = r.str("currency"); val exchangeRate = r.str("exchange_rate")
    val rateDate = r.optStr("rate_date"); val baseAmount = r.long("base_amount"); val accountId = r.long("account_id")
    val toAccountId = r.optLong("to_account_id"); val toAmount = r.optLong("to_amount"); val method = r.str("method")
    val reference = r.optStr("reference"); val category = r.str("category"); val customerId = r.optLong("customer_id")
    val supplierId = r.optLong("supplier_id"); val bookingId = r.optLong("booking_id"); val isDirect = r.bool("is_direct")
    val payee = r.optStr("payee"); val notes = r.optStr("notes"); val reversalOf = r.optLong("reversal_of"); val reversedBy = r.optLong("reversed_by")
    val reconciled = r.bool("reconciled"); val reconciledOn = r.optStr("reconciled_on"); val createdAt = r.str("created_at")
    val debtId = if (r.has("debt_id")) r.optLong("debt_id") else null
}

class Adjustment(val row: Row) {
    val id = row.long("id"); val adjNo = row.str("adj_no"); val bookingId = row.long("booking_id"); val adjDate = row.str("adj_date")
    val kind = row.str("kind"); val sign = row.long("sign"); val exchangeRate = row.str("exchange_rate")
    val values: Map<String, Long> = ADJ_FIELDS.associateWith { row.long(it) }
    val notes = row.optStr("notes"); val reversalOf = row.optLong("reversal_of"); val reversedBy = row.optLong("reversed_by")
    operator fun get(f: String): Long = values[f] ?: 0
}

data class BookingBase(var purchase_cost: Long = 0, var selling_price: Long = 0, var commission: Long = 0, var service_charge: Long = 0,
                       var other_income: Long = 0, var tax_amount: Long = 0) {
    val json: JValue get() = JValue.obj("purchase_cost" to purchase_cost, "selling_price" to selling_price, "commission" to commission,
        "service_charge" to service_charge, "other_income" to other_income, "tax_amount" to tax_amount)
}

data class BookingCalc(
    var base: BookingBase = BookingBase(), var markup: Long = 0, var markupOriginal: Long = 0,
    var customerPayable: Long = 0, var customerCredits: Long = 0, var received: Long = 0, var refunded: Long = 0, var customerOutstanding: Long = 0,
    var supplierPayable: Long = 0, var supplierCredits: Long = 0, var paidSupplier: Long = 0, var supplierRefunds: Long = 0, var supplierOutstanding: Long = 0,
    var revenue: Long = 0, var cost: Long = 0, var commissionNet: Long = 0, var taxNet: Long = 0, var directExpenses: Long = 0, var grossProfit: Long = 0,
    var paymentStatus: String = "") {
    val json: JValue get() = JValue.obj("base" to base.json, "markup" to markup, "markup_original" to markupOriginal,
        "customer_payable" to customerPayable, "customer_credits" to customerCredits, "received" to received, "refunded" to refunded,
        "customer_outstanding" to customerOutstanding, "supplier_payable" to supplierPayable, "supplier_credits" to supplierCredits,
        "paid_supplier" to paidSupplier, "supplier_refunds" to supplierRefunds, "supplier_outstanding" to supplierOutstanding,
        "revenue" to revenue, "cost" to cost, "commission_net" to commissionNet, "tax_net" to taxNet, "direct_expenses" to directExpenses,
        "gross_profit" to grossProfit, "payment_status" to paymentStatus)
}

class LedgerBooking(val row: Row) {
    var calc = BookingCalc(); var airlines: List<String> = emptyList(); var route = ""; var paxCount = 0; var leadPax = ""
    var customerName = ""; var customerPhone: String? = null; var supplierName = ""
    val id get() = row.long("id"); val ref get() = row.str("ref"); val pnr get() = row.optStr("pnr"); val status get() = row.str("status")
    val committed get() = row.bool("committed"); val customerId get() = row.long("customer_id"); val supplierId get() = row.optLong("supplier_id")
    val bookingDate get() = row.str("booking_date"); val travelDate get() = row.optStr("travel_date"); val currency get() = row.str("currency")
    val serviceType get() = row.str("service_type")
}

data class ProfitEvent(val date: String, val bookingId: Long, val type: String, val adjustmentId: Long? = null) {
    var bookings = 0L; var selling = 0L; var purchase = 0L; var sales = 0L; var billed = 0L; var cost = 0L; var markup = 0L; var commission = 0L
    var service = 0L; var otherBookingIncome = 0L; var tax = 0L; var directExpenses = 0L; var customerCredits = 0L; var supplierCredits = 0L
    var commissionReversed = 0L; var extraCustomerCharges = 0L; var extraSupplierCharges = 0L
}

class CashTotals {
    var customerReceipts = 0L; var supplierPayments = 0L; var customerRefunds = 0L; var supplierReceipts = 0L; var expenses = 0L
    var operatingExpenses = 0L; var directExpensesPaid = 0L; var otherIncome = 0L; var cashIn = 0L; var cashOut = 0L; var netCashFlow = 0L
    var loanReceived = 0L; var loanRepaid = 0L; var netFinancing = 0L
    val json: JValue get() = JValue.obj("customer_receipts" to customerReceipts, "supplier_payments" to supplierPayments, "customer_refunds" to customerRefunds,
        "supplier_receipts" to supplierReceipts, "expenses" to expenses, "operating_expenses" to operatingExpenses, "direct_expenses_paid" to directExpensesPaid,
        "other_income" to otherIncome, "cash_in" to cashIn, "cash_out" to cashOut, "net_cash_flow" to netCashFlow, "loan_received" to loanReceived,
        "loan_repaid" to loanRepaid, "net_financing" to netFinancing)
}

class ProfitTotals {
    var bookings = 0L; var sales = 0L; var billed = 0L; var selling = 0L; var purchase = 0L; var cost = 0L; var markup = 0L; var commission = 0L
    var service = 0L; var otherBookingIncome = 0L; var tax = 0L; var directExpenses = 0L; var grossProfit = 0L; var customerCredits = 0L
    var supplierCredits = 0L; var commissionReversed = 0L; var extraCustomerCharges = 0L; var extraSupplierCharges = 0L
    var operatingExpenses = 0L; var otherIncome = 0L; var netProfit = 0L
    var cash = CashTotals()

    fun add(e: ProfitEvent) {
        bookings += e.bookings; sales += e.sales; billed += e.billed; selling += e.selling; purchase += e.purchase; cost += e.cost
        markup += e.markup; commission += e.commission; service += e.service; otherBookingIncome += e.otherBookingIncome; tax += e.tax
        directExpenses += e.directExpenses; customerCredits += e.customerCredits; supplierCredits += e.supplierCredits
        commissionReversed += e.commissionReversed; extraCustomerCharges += e.extraCustomerCharges; extraSupplierCharges += e.extraSupplierCharges
    }

    val profitJSON: Map<String, JValue> get() = (JValue.obj("bookings" to bookings, "sales" to sales, "billed" to billed, "selling" to selling,
        "purchase" to purchase, "cost" to cost, "markup" to markup, "commission" to commission, "service" to service,
        "other_booking_income" to otherBookingIncome, "tax" to tax, "direct_expenses" to directExpenses, "gross_profit" to grossProfit,
        "customer_credits" to customerCredits, "supplier_credits" to supplierCredits, "commission_reversed" to commissionReversed,
        "extra_customer_charges" to extraCustomerCharges, "extra_supplier_charges" to extraSupplierCharges) as JValue.Obj).v

    val summaryJSON: JValue get() = JValue.Obj(LinkedHashMap(profitJSON).apply {
        put("operating_expenses", J(operatingExpenses)); put("other_income", J(otherIncome)); put("net_profit", J(netProfit)); put("cash", cash.json)
    })
}

/** Report / ledger filter keys (customer_id, supplier_id, currency, status, airline, service_type, account_id). */
data class LedgerFilter(val customerId: String? = null, val supplierId: String? = null, val currency: String? = null, val status: String? = null,
                        val serviceType: String? = null, val airline: String? = null, val accountId: String? = null) {
    private fun all() = listOf(customerId, supplierId, currency, status, serviceType, airline, accountId)
    val isEmpty get() = all().all { it.isNullOrEmpty() }
    val bookingScoped get() = listOf(airline, status, serviceType, supplierId, customerId).any { !it.isNullOrEmpty() }
}

class Ledger(agency: Agency) {
    val base: String = agency.setting("base_currency", "INR") ?: "INR"
    val customers = LinkedHashMap<Long, Row>()
    val suppliers = LinkedHashMap<Long, Row>()
    val accounts = LinkedHashMap<Long, Row>()
    val bookings = LinkedHashMap<Long, LedgerBooking>()
    val segments = HashMap<Long, MutableList<Row>>()
    val passengers = HashMap<Long, MutableList<Row>>()
    val adjustments = HashMap<Long, MutableList<Adjustment>>()
    val allAdjustments = ArrayList<Adjustment>()
    val transactions: List<Txn>
    val txnById: Map<Long, Txn>
    val allocations = HashMap<Long, MutableList<Triple<Long, Long, Long>>>()      // txn id -> (booking id, amount, id)
    private val allocByBooking = HashMap<Long, MutableList<Pair<Long, Long>>>()   // booking id -> (txn id, amount)
    private val directByBooking = HashMap<Long, MutableList<Txn>>()
    val debts = HashMap<Long, Row>()
    val events = ArrayList<ProfitEvent>()

    init {
        val db = agency.db
        db.query("SELECT * FROM customers").forEach { customers[it.long("id")] = it }
        db.query("SELECT * FROM suppliers").forEach { suppliers[it.long("id")] = it }
        db.query("SELECT * FROM accounts").forEach { accounts[it.long("id")] = it }
        db.query("SELECT * FROM bookings ORDER BY booking_date, id").forEach { bookings[it.long("id")] = LedgerBooking(it) }
        db.query("SELECT * FROM booking_segments ORDER BY booking_id, seq, id").forEach { segments.getOrPut(it.long("booking_id")) { ArrayList() }.add(it) }
        db.query("SELECT * FROM booking_passengers ORDER BY booking_id, seq, id").forEach { passengers.getOrPut(it.long("booking_id")) { ArrayList() }.add(it) }
        db.query("SELECT * FROM booking_adjustments ORDER BY adj_date, id").forEach {
            val a = Adjustment(it); adjustments.getOrPut(a.bookingId) { ArrayList() }.add(a); allAdjustments.add(a)
        }
        transactions = db.query("SELECT * FROM transactions ORDER BY txn_date, id").map { Txn(it) }
        if (db.one("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'debts'") != null) {
            db.query("SELECT id, debt_no, creditor FROM debts").forEach { debts[it.long("id")] = it }
        }
        txnById = transactions.associateBy { it.id }
        db.query("SELECT * FROM allocations ORDER BY id").forEach {
            allocations.getOrPut(it.long("txn_id")) { ArrayList() }.add(Triple(it.long("booking_id"), it.long("amount"), it.long("id")))
            allocByBooking.getOrPut(it.long("booking_id")) { ArrayList() }.add(it.long("txn_id") to it.long("amount"))
        }
        for (t in transactions) if (t.kind == "expense" && t.isDirect && t.bookingId != null) directByBooking.getOrPut(t.bookingId) { ArrayList() }.add(t)
        for (b in bookings.values) compute(b)
    }

    val bookingList: Collection<LedgerBooking> get() = bookings.values

    private fun compute(b: LedgerBooking) {
        val r = b.row
        val rate = r.str("exchange_rate")
        val base = BookingBase(convert(r.long("purchase_cost"), rate), convert(r.long("selling_price"), rate), convert(r.long("commission"), rate),
            convert(r.long("service_charge"), rate), convert(r.long("other_income"), rate), convert(r.long("tax_amount"), rate))
        val committed = b.committed
        val k = if (committed) 1L else 0L
        val adj = ADJ_FIELDS.associateWith { 0L }.toMutableMap()
        if (committed) for (a in adjustments[b.id].orEmpty()) for (f in ADJ_FIELDS) adj[f] = adj[f]!! + a.sign * convert(a[f], a.exchangeRate)
        val alloc = HashMap<String, Long>()
        for ((tid, amt) in allocByBooking[b.id].orEmpty()) { val t = txnById[tid]!!; alloc[t.kind] = (alloc[t.kind] ?: 0) + t.sign * amt }
        var direct = 0L
        for (t in directByBooking[b.id].orEmpty()) direct += t.sign * t.baseAmount

        val incomeCore = base.selling_price + base.service_charge + base.other_income
        val customerPayable = k * (incomeCore + base.tax_amount) + adj["extra_customer_charge"]!!
        val customerCredits = adj["customer_credit"]!!
        val supplierPayable = k * base.purchase_cost + adj["extra_supplier_charge"]!!
        val supplierCredits = k * base.commission - adj["commission_reversal"]!! + adj["supplier_credit"]!!
        val received = alloc["customer_receipt"] ?: 0; val refunded = alloc["customer_refund"] ?: 0
        val paidSupplier = alloc["supplier_payment"] ?: 0; val supplierRefunds = alloc["supplier_receipt"] ?: 0
        val revenue = k * incomeCore + adj["extra_customer_charge"]!! - (adj["customer_credit"]!! - adj["customer_credit_tax"]!!)
        val cost = k * base.purchase_cost + adj["extra_supplier_charge"]!! - adj["supplier_credit"]!!
        val commissionNet = k * base.commission - adj["commission_reversal"]!!
        val taxNet = k * base.tax_amount - adj["customer_credit_tax"]!!
        val gross = revenue - cost + commissionNet - direct
        val custOut = customerPayable - customerCredits - received + refunded
        val supOut = supplierPayable - supplierCredits - paidSupplier + supplierRefunds
        val pay = when {
            !committed -> if (received - refunded > 0) "advance" else "not_billed"
            custOut <= 0 && customerPayable - customerCredits > 0 -> if (custOut == 0L) "paid" else "overpaid"
            custOut <= 0 -> if (custOut == 0L) "settled" else "refund_due"
            received > 0 -> "partial"
            else -> "unpaid"
        }
        val segs = segments[b.id].orEmpty()
        val airlines = ArrayList<String>()
        for (s in segs) { val a = s.optStr("airline"); if (!a.isNullOrEmpty() && a !in airlines) airlines.add(a) }
        var route = ""
        if (segs.isNotEmpty()) {
            val pts = ArrayList<String>()
            for (s in segs) for (p in listOf(s.optStr("origin"), s.optStr("destination"))) if (!p.isNullOrEmpty() && pts.lastOrNull() != p) pts.add(p)
            route = pts.joinToString("-")
        }
        val pax = passengers[b.id].orEmpty()
        b.calc = BookingCalc(base, k * (base.selling_price - base.purchase_cost), r.long("selling_price") - r.long("purchase_cost"),
            customerPayable, customerCredits, received, refunded, custOut, supplierPayable, supplierCredits, paidSupplier, supplierRefunds, supOut,
            revenue, cost, commissionNet, taxNet, direct, gross, pay)
        b.airlines = airlines; b.route = route; b.paxCount = pax.size; b.leadPax = pax.firstOrNull()?.str("name") ?: ""
        b.customerName = customers[b.customerId]?.str("name") ?: ""
        b.customerPhone = customers[b.customerId]?.optStr("phone")
        b.supplierName = b.supplierId?.let { suppliers[it]?.str("name") } ?: ""

        if (committed) {
            events.add(ProfitEvent(b.bookingDate, b.id, "booking").apply {
                bookings = 1; selling = base.selling_price; purchase = base.purchase_cost; sales = incomeCore; billed = incomeCore + base.tax_amount
                this.cost = base.purchase_cost; markup = base.selling_price - base.purchase_cost; commission = base.commission
                service = base.service_charge; otherBookingIncome = base.other_income; tax = base.tax_amount
            })
            for (a in adjustments[b.id].orEmpty()) {
                val cv = ADJ_FIELDS.associateWith { a.sign * convert(a[it], a.exchangeRate) }
                events.add(ProfitEvent(a.adjDate, b.id, "adjustment", a.id).apply {
                    sales = cv["extra_customer_charge"]!! - (cv["customer_credit"]!! - cv["customer_credit_tax"]!!)
                    billed = cv["extra_customer_charge"]!! - cv["customer_credit"]!!
                    this.cost = cv["extra_supplier_charge"]!! - cv["supplier_credit"]!!
                    commission = -cv["commission_reversal"]!!; tax = -cv["customer_credit_tax"]!!
                    this.customerCredits = cv["customer_credit"]!!; this.supplierCredits = cv["supplier_credit"]!!
                    commissionReversed = cv["commission_reversal"]!!; extraCustomerCharges = cv["extra_customer_charge"]!!
                    extraSupplierCharges = cv["extra_supplier_charge"]!!
                })
            }
        }
        for (t in directByBooking[b.id].orEmpty()) events.add(ProfitEvent(t.txnDate, b.id, "direct_expense").apply { directExpenses = t.sign * t.baseAmount })
    }

    // ---- filters

    fun bookingMatches(b: LedgerBooking, f: LedgerFilter?): Boolean {
        if (f == null || f.isEmpty) return true
        if (!f.customerId.isNullOrEmpty() && b.customerId.toString() != f.customerId) return false
        if (!f.supplierId.isNullOrEmpty() && (b.supplierId?.toString() ?: "") != f.supplierId) return false
        if (!f.currency.isNullOrEmpty() && b.currency != f.currency) return false
        if (!f.status.isNullOrEmpty() && b.status != f.status) return false
        if (!f.serviceType.isNullOrEmpty() && b.serviceType != f.serviceType) return false
        if (!f.airline.isNullOrEmpty()) {
            val needle = f.airline.trim().lowercase()
            if (b.airlines.none { it.lowercase().contains(needle) }) return false
        }
        return true
    }

    fun txnMatches(t: Txn, f: LedgerFilter?): Boolean {
        if (f == null || f.isEmpty) return true
        if (!f.customerId.isNullOrEmpty() && (t.customerId?.toString() ?: "") != f.customerId) return false
        if (!f.supplierId.isNullOrEmpty() && (t.supplierId?.toString() ?: "") != f.supplierId) return false
        if (!f.currency.isNullOrEmpty() && t.currency != f.currency) return false
        if (!f.accountId.isNullOrEmpty() && t.accountId.toString() != f.accountId && (t.toAccountId?.toString() ?: "") != f.accountId) return false
        if (!f.airline.isNullOrEmpty() || !f.status.isNullOrEmpty() || !f.serviceType.isNullOrEmpty()) {
            val b = t.bookingId?.let { bookings[it] } ?: return false
            if (!bookingMatches(b, LedgerFilter(status = f.status, serviceType = f.serviceType, airline = f.airline))) return false
        }
        return true
    }

    // ---- summaries

    fun profitTotals(frm: String? = null, to: String? = null, f: LedgerFilter? = null): ProfitTotals {
        val tot = ProfitTotals()
        for (e in events) if (inRange(e.date, frm, to) && bookingMatches(bookings[e.bookingId]!!, f)) tot.add(e)
        tot.grossProfit = tot.sales - tot.cost + tot.commission - tot.directExpenses
        return tot
    }

    fun cashTotals(frm: String? = null, to: String? = null, f: LedgerFilter? = null): CashTotals {
        val o = CashTotals()
        for (t in transactions) {
            if (!inRange(t.txnDate, frm, to) || !txnMatches(t, f)) continue
            val v = t.sign * t.baseAmount
            when (t.kind) {
                "customer_receipt" -> o.customerReceipts += v
                "supplier_payment" -> o.supplierPayments += v
                "customer_refund" -> o.customerRefunds += v
                "supplier_receipt" -> o.supplierReceipts += v
                "expense" -> { o.expenses += v; if (t.isDirect && t.bookingId != null) o.directExpensesPaid += v else o.operatingExpenses += v }
                "other_income" -> o.otherIncome += v
                "loan_received" -> o.loanReceived += v
                "loan_repayment" -> o.loanRepaid += v
            }
            if (t.kind in INFLOW_KINDS) o.cashIn += v else if (t.kind in OUTFLOW_KINDS) o.cashOut += v
        }
        o.netCashFlow = o.cashIn - o.cashOut
        o.netFinancing = o.loanReceived - o.loanRepaid
        return o
    }

    fun periodSummary(frm: String? = null, to: String? = null, f: LedgerFilter? = null): ProfitTotals {
        val p = profitTotals(frm, to, f)
        val cash = cashTotals(frm, to, f)
        val scoped = f?.bookingScoped ?: false
        p.operatingExpenses = if (scoped) 0 else cash.operatingExpenses
        p.otherIncome = if (scoped) 0 else cash.otherIncome
        p.netProfit = p.grossProfit + p.otherIncome - p.operatingExpenses
        p.cash = cash
        return p
    }

    // ---- balances

    class CustomerBalance(val customerId: Long, val name: String, val phone: String?) {
        var billed = 0L; var credits = 0L; var received = 0L; var refunded = 0L; var allocatedReceived = 0L; var allocatedRefunded = 0L
        var outstanding = 0L; var unallocated = 0L; var bookings = 0L
        val json: JValue get() = JValue.obj("customer_id" to customerId, "name" to name, "phone" to phone, "billed" to billed, "credits" to credits,
            "received" to received, "refunded" to refunded, "allocated_received" to allocatedReceived, "allocated_refunded" to allocatedRefunded,
            "outstanding" to outstanding, "unallocated" to unallocated, "bookings" to bookings)
    }

    fun customerBalances(): LinkedHashMap<Long, CustomerBalance> {
        val res = LinkedHashMap<Long, CustomerBalance>()
        for ((cid, c) in customers) res[cid] = CustomerBalance(cid, c.str("name"), c.optStr("phone"))
        for (b in bookingList) res[b.customerId]!!.apply {
            bookings += 1; billed += b.calc.customerPayable; credits += b.calc.customerCredits
            allocatedReceived += b.calc.received; allocatedRefunded += b.calc.refunded
        }
        for (t in transactions) if (t.customerId != null && (t.kind == "customer_receipt" || t.kind == "customer_refund")) {
            val r = res[t.customerId]!!; val v = t.sign * t.baseAmount
            if (t.kind == "customer_receipt") r.received += v else r.refunded += v
        }
        for (r in res.values) { r.outstanding = r.billed - r.credits - r.received + r.refunded
            r.unallocated = (r.received - r.allocatedReceived) - (r.refunded - r.allocatedRefunded) }
        return res
    }

    class SupplierBalance(val supplierId: Long, val name: String, val kind: String) {
        var payable = 0L; var credits = 0L; var paid = 0L; var refunds = 0L; var allocatedPaid = 0L; var allocatedRefunds = 0L
        var outstanding = 0L; var unallocated = 0L; var bookings = 0L
        val json: JValue get() = JValue.obj("supplier_id" to supplierId, "name" to name, "kind" to kind, "payable" to payable, "credits" to credits,
            "paid" to paid, "refunds" to refunds, "allocated_paid" to allocatedPaid, "allocated_refunds" to allocatedRefunds,
            "outstanding" to outstanding, "unallocated" to unallocated, "bookings" to bookings)
    }

    fun supplierBalances(): LinkedHashMap<Long, SupplierBalance> {
        val res = LinkedHashMap<Long, SupplierBalance>()
        for ((sid, s) in suppliers) res[sid] = SupplierBalance(sid, s.str("name"), s.str("kind"))
        for (b in bookingList) { val sid = b.supplierId ?: continue
            res[sid]!!.apply { bookings += 1; payable += b.calc.supplierPayable; credits += b.calc.supplierCredits
                allocatedPaid += b.calc.paidSupplier; allocatedRefunds += b.calc.supplierRefunds } }
        for (t in transactions) if (t.supplierId != null && (t.kind == "supplier_payment" || t.kind == "supplier_receipt")) {
            val r = res[t.supplierId]!!; val v = t.sign * t.baseAmount
            if (t.kind == "supplier_payment") r.paid += v else r.refunds += v
        }
        for (r in res.values) { r.outstanding = r.payable - r.credits - r.paid + r.refunds
            r.unallocated = (r.paid - r.allocatedPaid) - (r.refunds - r.allocatedRefunds) }
        return res
    }

    /** (account id, signed amount in the account's currency) for a transaction. */
    fun accountEffects(t: Txn): List<Pair<Long, Long>> {
        val s = t.sign
        return when {
            t.kind in INFLOW_KINDS || t.kind in FINANCING_IN_KINDS -> listOf(t.accountId to s * t.amount)
            t.kind in OUTFLOW_KINDS || t.kind in FINANCING_OUT_KINDS -> listOf(t.accountId to -s * t.amount)
            t.kind == "transfer" && t.toAccountId != null -> listOf(t.accountId to -s * t.amount, t.toAccountId to s * (t.toAmount ?: t.amount))
            else -> emptyList()
        }
    }

    class AccountBalance(val accountId: Long, val name: String, val kind: String, val currency: String, val opening: Long, val active: Boolean) {
        var inflow = 0L; var outflow = 0L; var balance = opening; var reconciledBalance = opening
        val json: JValue get() = JValue.obj("account_id" to accountId, "name" to name, "kind" to kind, "currency" to currency, "opening" to opening,
            "inflow" to inflow, "outflow" to outflow, "balance" to balance, "reconciled_balance" to reconciledBalance, "active" to (if (active) 1 else 0))
    }

    fun accountBalances(asOf: String? = null): LinkedHashMap<Long, AccountBalance> {
        val res = LinkedHashMap<Long, AccountBalance>()
        for ((aid, a) in accounts) res[aid] = AccountBalance(aid, a.str("name"), a.str("kind"), a.str("currency"), a.long("opening_balance"), a.bool("active"))
        for (t in transactions) {
            if (asOf != null && t.txnDate > asOf) continue
            for ((aid, v) in accountEffects(t)) {
                val r = res[aid] ?: continue
                if (v >= 0) r.inflow += v else r.outflow += -v
                r.balance += v
                if (t.reconciled) r.reconciledBalance += v
            }
        }
        return res
    }

    // ---- rows for lists

    fun bookingRow(b: LedgerBooking): LinkedHashMap<String, JValue> {
        val r = b.row; val c = b.calc
        return (JValue.obj("id" to b.id, "ref" to b.ref, "pnr" to b.pnr, "status" to b.status, "service_type" to b.serviceType,
            "booking_date" to b.bookingDate, "travel_date" to b.travelDate, "return_date" to r.optStr("return_date"), "customer_id" to b.customerId,
            "customer_name" to b.customerName, "customer_phone" to b.customerPhone, "supplier_id" to b.supplierId, "supplier_name" to b.supplierName,
            "airlines" to b.airlines, "route" to b.route, "pax_count" to b.paxCount, "lead_pax" to b.leadPax, "currency" to b.currency,
            "exchange_rate" to r.str("exchange_rate"), "committed" to (if (b.committed) 1 else 0), "selling_price" to r.long("selling_price"),
            "purchase_cost" to r.long("purchase_cost"), "customer_payable" to c.customerPayable, "customer_outstanding" to c.customerOutstanding,
            "supplier_outstanding" to c.supplierOutstanding, "gross_profit" to c.grossProfit, "revenue" to c.revenue, "cost" to c.cost,
            "commission_net" to c.commissionNet, "tax_net" to c.taxNet, "direct_expenses" to c.directExpenses, "markup" to c.markup,
            "received" to c.received, "payment_status" to c.paymentStatus) as JValue.Obj).v.let { LinkedHashMap(it) }
    }

    fun txnRow(t: Txn): LinkedHashMap<String, JValue> {
        val acc = accounts[t.accountId]; val toAcc = t.toAccountId?.let { accounts[it] }; val b = t.bookingId?.let { bookings[it] }
        val allocs = allocations[t.id].orEmpty().map { (bid, amt, _) -> JValue.obj("booking_id" to bid, "ref" to (bookings[bid]?.ref ?: ""), "amount" to amt) }
        val allocated = allocations[t.id].orEmpty().sumOf { it.second }
        val debt = t.debtId?.let { debts[it] }
        val party = setOf("customer_receipt", "customer_refund", "supplier_payment", "supplier_receipt")
        return LinkedHashMap((JValue.obj("id" to t.id, "txn_no" to t.txnNo, "uid" to t.uid, "txn_date" to t.txnDate, "kind" to t.kind,
            "kind_label" to (KIND_LABELS[t.kind] ?: t.kind), "sign" to t.sign, "amount" to t.amount, "currency" to t.currency,
            "exchange_rate" to t.exchangeRate, "rate_date" to t.rateDate, "base_amount" to t.baseAmount, "signed_base" to t.sign * t.baseAmount,
            "account_id" to t.accountId, "account_name" to (acc?.str("name") ?: ""), "to_account_id" to t.toAccountId,
            "to_account_name" to (toAcc?.str("name") ?: ""), "to_amount" to t.toAmount, "method" to t.method, "reference" to t.reference,
            "category" to t.category, "customer_id" to t.customerId, "customer_name" to (t.customerId?.let { customers[it]?.str("name") } ?: ""),
            "supplier_id" to t.supplierId, "supplier_name" to (t.supplierId?.let { suppliers[it]?.str("name") } ?: ""),
            "booking_id" to t.bookingId, "booking_ref" to (b?.ref ?: ""), "is_direct" to (if (t.isDirect) 1 else 0), "payee" to t.payee,
            "notes" to t.notes, "reversal_of" to t.reversalOf, "reversed_by" to t.reversedBy, "reconciled" to (if (t.reconciled) 1 else 0),
            "reconciled_on" to t.reconciledOn, "created_at" to t.createdAt, "debt_id" to t.debtId, "debt_name" to (debt?.str("creditor") ?: ""),
            "debt_no" to (debt?.str("debt_no") ?: ""), "allocations" to allocs, "allocated" to allocated,
            "unallocated" to (if (t.kind in party) t.baseAmount - allocated else 0L)) as JValue.Obj).v)
    }
}
