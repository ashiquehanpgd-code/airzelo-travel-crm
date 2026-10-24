package com.airzelo.engine

// Port of the customer, supplier, account, tax profile and expense category services (core/server/services.py).

internal val CUSTOMER_FIELDS = listOf("name", "phone", "alt_phone", "email", "address", "city", "state", "gstin", "passport_no",
    "notes", "follow_up_date", "follow_up_note")
internal val SUPPLIER_FIELDS = listOf("name", "kind", "contact_person", "phone", "email", "gstin", "notes")
val SUPPLIER_KINDS = listOf("b2b_portal", "airline", "consolidator", "hotel", "visa", "other")
/** account_type -> the reporting kind stored in accounts.kind */
val ACCOUNT_TYPES = linkedMapOf("cash" to "cash", "bank" to "bank", "current" to "bank", "savings" to "bank", "upi" to "wallet",
    "credit_card" to "bank", "wallet" to "wallet", "gateway" to "gateway")

private fun jobj(m: Map<String, String?>): JValue = JValue.Obj(m.mapValues { J(it.value) })

/** Passport number from a payload. A value still sealed (encrypted on another device) is kept exactly as stored. */
internal fun passportValue(data: Fields): String? {
    val s = data.arg("passport_no").string
    if (FieldCrypto.isSealed(s)) return s
    return V.normCode(J(V.s(data, "passport_no", 20)), 20)
}

internal fun Agency.customerPayload(data: Fields): LinkedHashMap<String, String?> = linkedMapOf(
    "name" to V.s(data, "name", 150, required = true, label = "Customer name"),
    "phone" to V.phone(data, "phone"),
    "alt_phone" to V.phone(data, "alt_phone"),
    "email" to V.email(data, "email"),
    "address" to V.s(data, "address", 500),
    "city" to V.s(data, "city", 80),
    "state" to V.s(data, "state", 80),
    "gstin" to V.normCode(J(V.s(data, "gstin", 15)), 15),
    "passport_no" to passportValue(data),
    "notes" to V.s(data, "notes", 4000),
    "follow_up_date" to V.d(data, "follow_up_date"),
    "follow_up_note" to V.s(data, "follow_up_note", 500))

private fun Agency.sealedCustomer(p: Map<String, String?>) = LinkedHashMap(p).also { it["passport_no"] = crypto.seal(p["passport_no"], "passport_no") }

/** A customer row with the passport number readable again. */
fun Agency.openCustomer(r: Row): LinkedHashMap<String, JValue> = r.json.also { it["passport_no"] = J(crypto.open(r.optStr("passport_no"), "passport_no")) }

fun Agency.createCustomer(data: Fields): Long {
    val p = customerPayload(data)
    val phone = p["phone"]
    if (phone != null && !V.b(data, "force_duplicate")) {
        db.one("SELECT id, name FROM customers WHERE phone = ? AND active = 1", listOf(phone))?.let {
            throw AZError.conflict("A customer with this phone number already exists: ${it.str("name")}", "duplicate_customer",
                JValue.obj("id" to it.long("id"), "name" to it.str("name")))
        }
    }
    val stored = sealedCustomer(p)
    val ts = Dates.nowISO()
    return tx {
        val cid = db.run("INSERT INTO customers(${CUSTOMER_FIELDS.joinToString(", ")}, created_at, updated_at) VALUES (${CUSTOMER_FIELDS.joinToString(", ") { "?" }}, ?, ?)",
            CUSTOMER_FIELDS.map { stored[it] } + listOf(ts, ts))
        audit("customer", cid, "create", null, jobj(stored))
        cid
    }
}

fun Agency.updateCustomer(cid: Long, data: Fields): Long {
    tx {
        val before = db.one("SELECT * FROM customers WHERE id = ?", listOf(cid)) ?: throw AZError.notFound("Customer not found")
        val merged = openCustomer(before)
        for ((k, v) in data) if (k in CUSTOMER_FIELDS) merged[k] = v
        val p = customerPayload(merged)
        var active = before.long("active")
        if (data.containsKey("active")) active = if (V.isOff(data.arg("active"))) 0 else 1
        val stored = sealedCustomer(p)
        db.run("UPDATE customers SET ${CUSTOMER_FIELDS.joinToString(", ") { "$it = ?" }}, active = ?, updated_at = ? WHERE id = ?",
            CUSTOMER_FIELDS.map { stored[it] } + listOf(active, Dates.nowISO(), cid))
        val after = (jobj(stored) as JValue.Obj).v.toMutableMap().also { it["active"] = J(active) }
        audit("customer", cid, "update", JValue.Obj(before.json), JValue.Obj(after))
    }
    return cid
}

/** Deletes a customer with no history, otherwise deactivates them. Returns "deleted" or "deactivated". */
fun Agency.deleteCustomer(cid: Long): String = tx {
    val c = db.one("SELECT * FROM customers WHERE id = ?", listOf(cid)) ?: throw AZError.notFound("Customer not found")
    val used = db.scalarLong("SELECT (SELECT COUNT(*) FROM bookings WHERE customer_id = ?) + (SELECT COUNT(*) FROM transactions WHERE customer_id = ?)", listOf(cid, cid))
    if (used > 0) {
        db.run("UPDATE customers SET active = 0, updated_at = ? WHERE id = ?", listOf(Dates.nowISO(), cid))
        audit("customer", cid, "deactivate", JValue.Obj(c.json), JValue.obj("active" to 0))
        "deactivated"
    } else {
        db.run("DELETE FROM customers WHERE id = ?", listOf(cid))
        audit("customer", cid, "delete", JValue.Obj(c.json), null)
        "deleted"
    }
}

fun Agency.listCustomers(query: String? = null, includeInactive: Boolean = false, ledger: Ledger? = null): List<Map<String, JValue>> {
    val L = ledger ?: Ledger(this)
    val bal = L.customerBalances()
    val ql = (query ?: "").trim().lowercase()
    return L.customers.values.withIndex().sortedWith(compareBy({ it.value.str("name").lowercase() }, { it.index })).map { it.value }.filter { c ->
        (includeInactive || c.bool("active")) &&
            (ql.isEmpty() || c.str("name").lowercase().contains(ql) || c.str("phone").contains(ql) || c.str("email").lowercase().contains(ql))
    }.map { c ->
        val b = bal[c.long("id")]!!
        openCustomer(c).also { r ->
            r["billed"] = J(b.billed); r["received"] = J(b.received); r["outstanding"] = J(b.outstanding); r["unallocated"] = J(b.unallocated)
            r["bookings"] = J(b.bookings); r["credits"] = J(b.credits); r["refunded"] = J(b.refunded)
        }
    }
}

class CustomerDetail(val customer: Map<String, JValue>, val balance: Ledger.CustomerBalance, val bookings: List<Map<String, JValue>>,
                     val transactions: List<Map<String, JValue>>, val tickets: List<Map<String, JValue>>)

fun Agency.getCustomer(cid: Long): CustomerDetail {
    val L = Ledger(this)
    val c = L.customers[cid] ?: throw AZError.notFound("Customer not found")
    val bookings = L.bookingList.filter { it.customerId == cid }.map { L.bookingRow(it) }
        .sortedWith(compareByDescending<Map<String, JValue>> { it["booking_date"]!!.py }.thenByDescending { it["id"]!!.long!! })
    val txns = L.transactions.filter { it.customerId == cid }.map { L.txnRow(it) }
        .sortedWith(compareByDescending<Map<String, JValue>> { it["txn_date"]!!.py }.thenByDescending { it["id"]!!.long!! })
    val tickets = L.bookingList.filter { it.customerId == cid }.flatMap { b ->
        L.passengers[b.id].orEmpty().map { p -> mapOf("booking_id" to J(b.id), "ref" to J(b.ref), "pnr" to J(b.pnr), "name" to J(p.str("name")),
            "ticket_number" to J(p.optStr("ticket_number")), "status" to J(b.status)) }
    }
    return CustomerDetail(openCustomer(c), L.customerBalances()[cid]!!, bookings, txns, tickets)
}

internal fun Agency.resolveCustomer(data: Fields): Long {
    V.i(data, "customer_id")?.let { cid ->
        db.one("SELECT id, active FROM customers WHERE id = ?", listOf(cid)) ?: throw AZError.validation("Selected customer does not exist", "customer_id")
        return cid
    }
    val nc = data.arg("new_customer").obj
    if (nc != null && (nc.arg("name").string ?: "").isNotBlank()) {
        if (nc.arg("phone").truthy) {
            db.one("SELECT id FROM customers WHERE phone = ? AND active = 1", listOf(nc.arg("phone").py.trim()))?.let { return it.long("id") }
        }
        return createCustomer(nc)
    }
    throw AZError.validation("Select a customer or enter a new customer name", "customer_id")
}

// ---- suppliers

internal fun Agency.supplierPayload(data: Fields): LinkedHashMap<String, String?> = linkedMapOf(
    "name" to V.s(data, "name", 150, required = true, label = "Supplier name"),
    "kind" to V.choice(data, "kind", SUPPLIER_KINDS, "b2b_portal", "Supplier type"),
    "contact_person" to V.s(data, "contact_person", 120),
    "phone" to V.phone(data, "phone"),
    "email" to V.email(data, "email"),
    "gstin" to V.normCode(J(V.s(data, "gstin", 15)), 15),
    "notes" to V.s(data, "notes", 4000))

fun Agency.createSupplier(data: Fields): Long {
    val p = supplierPayload(data)
    val ts = Dates.nowISO()
    return tx {
        if (db.one("SELECT 1 FROM suppliers WHERE name = ? COLLATE NOCASE", listOf(p["name"])) != null)
            throw AZError.conflict("A supplier named '${p["name"]}' already exists", "duplicate_supplier")
        val sid = db.run("INSERT INTO suppliers(${SUPPLIER_FIELDS.joinToString(", ")}, created_at, updated_at) VALUES (${SUPPLIER_FIELDS.joinToString(", ") { "?" }}, ?, ?)",
            SUPPLIER_FIELDS.map { p[it] } + listOf(ts, ts))
        audit("supplier", sid, "create", null, jobj(p))
        sid
    }
}

fun Agency.updateSupplier(sid: Long, data: Fields): Long {
    tx {
        val before = db.one("SELECT * FROM suppliers WHERE id = ?", listOf(sid)) ?: throw AZError.notFound("Supplier not found")
        val merged = before.json
        for ((k, v) in data) if (k in SUPPLIER_FIELDS) merged[k] = v
        val p = supplierPayload(merged)
        if (db.one("SELECT id FROM suppliers WHERE name = ? COLLATE NOCASE AND id <> ?", listOf(p["name"], sid)) != null)
            throw AZError.conflict("A supplier named '${p["name"]}' already exists", "duplicate_supplier")
        var active = before.long("active")
        if (data.containsKey("active")) active = if (V.isOff(data.arg("active"))) 0 else 1
        db.run("UPDATE suppliers SET ${SUPPLIER_FIELDS.joinToString(", ") { "$it = ?" }}, active = ?, updated_at = ? WHERE id = ?",
            SUPPLIER_FIELDS.map { p[it] } + listOf(active, Dates.nowISO(), sid))
        val after = (jobj(p) as JValue.Obj).v.toMutableMap().also { it["active"] = J(active) }
        audit("supplier", sid, "update", JValue.Obj(before.json), JValue.Obj(after))
    }
    return sid
}

fun Agency.deleteSupplier(sid: Long): String = tx {
    val s = db.one("SELECT * FROM suppliers WHERE id = ?", listOf(sid)) ?: throw AZError.notFound("Supplier not found")
    val used = db.scalarLong("SELECT (SELECT COUNT(*) FROM bookings WHERE supplier_id = ?) + (SELECT COUNT(*) FROM transactions WHERE supplier_id = ?)", listOf(sid, sid))
    if (used > 0) {
        db.run("UPDATE suppliers SET active = 0, updated_at = ? WHERE id = ?", listOf(Dates.nowISO(), sid))
        audit("supplier", sid, "deactivate", JValue.Obj(s.json), JValue.obj("active" to 0)); "deactivated"
    } else {
        db.run("DELETE FROM suppliers WHERE id = ?", listOf(sid))
        audit("supplier", sid, "delete", JValue.Obj(s.json), null); "deleted"
    }
}

fun Agency.listSuppliers(includeInactive: Boolean = false, ledger: Ledger? = null): List<Map<String, JValue>> {
    val L = ledger ?: Ledger(this)
    val bal = L.supplierBalances()
    return L.suppliers.values.withIndex().sortedWith(compareBy({ it.value.str("name").lowercase() }, { it.index })).map { it.value }
        .filter { includeInactive || it.bool("active") }.map { s ->
            val b = bal[s.long("id")]!!
            s.json.also { r -> r["payable"] = J(b.payable); r["credits"] = J(b.credits); r["paid"] = J(b.paid); r["refunds"] = J(b.refunds)
                r["outstanding"] = J(b.outstanding); r["unallocated"] = J(b.unallocated); r["bookings"] = J(b.bookings) }
        }
}

// ---- accounts

fun Agency.listAccounts(ledger: Ledger? = null): List<Map<String, JValue>> {
    val L = ledger ?: Ledger(this)
    val bal = L.accountBalances()
    return L.accounts.values.sortedWith(compareBy({ if (it.bool("active")) 0 else 1 }, { it.str("kind") }, { it.str("name").lowercase() })).map { a ->
        val b = bal[a.long("id")]!!
        a.json.also { r -> r["inflow"] = J(b.inflow); r["outflow"] = J(b.outflow); r["balance"] = J(b.balance); r["reconciled_balance"] = J(b.reconciledBalance)
            r["txn_count"] = J(L.transactions.count { it.accountId == a.long("id") || it.toAccountId == a.long("id") }) }
    }
}

/** Keep only the last 4 digits of an account or card number; the full number is never stored. */
private fun last4(raw: JValue): String? {
    if (raw.isNull || raw.py.isBlank()) return null
    val digits = raw.py.filter { it in '0'..'9' }
    if (digits.length < 4) throw AZError.validation("Enter at least the last 4 digits of the account number", "account_last4")
    return digits.takeLast(4)
}

fun Agency.saveAccount(data: Fields, aid: Long? = null): Long {
    val name = V.s(data, "name", 100, required = true, label = "Account name")!!
    val accType = when { data.arg("account_type").truthy -> data.arg("account_type").py; data.arg("kind").truthy -> data.arg("kind").py; else -> "bank" }
    val kind = ACCOUNT_TYPES[accType] ?: throw AZError.validation("Account type must be one of: ${ACCOUNT_TYPES.keys.joinToString(", ")}", "account_type")
    val cur = V.normCode(data.arg("currency"), 3) ?: (setting("base_currency", "INR") ?: "INR")
    db.one("SELECT 1 FROM currencies WHERE code = ?", listOf(cur)) ?: throw AZError.validation("Unknown currency", "currency")
    val opening = parseAmount(data.arg("opening_balance"), "Opening balance", allowNegative = true)
    val openingDate = V.d(data, "opening_date")
    val details = V.s(data, "details", 500)
    val bankName = V.s(data, "bank_name", 100)
    val l4 = last4(data.arg("account_last4"))
    val active = if (V.isOff(data.arg("active"))) 0 else 1
    val ts = Dates.nowISO()
    return tx {
        if (db.one("SELECT id FROM accounts WHERE name = ? COLLATE NOCASE AND id <> ?", listOf(name, aid ?: 0)) != null)
            throw AZError.conflict("An account with this name already exists", "duplicate_account")
        val summary = JValue.obj("name" to name, "kind" to kind, "account_type" to accType, "bank_name" to bankName, "currency" to cur,
            "opening_balance" to opening, "opening_date" to openingDate, "active" to active)
        if (aid != null) {
            val before = db.one("SELECT * FROM accounts WHERE id = ?", listOf(aid)) ?: throw AZError.notFound("Account not found")
            val hasTxn = db.one("SELECT 1 FROM transactions WHERE account_id = ? OR to_account_id = ? LIMIT 1", listOf(aid, aid)) != null
            if (hasTxn && cur != before.str("currency")) throw AZError.validation("Currency cannot be changed after transactions are recorded", "currency")
            if (before.long("opening_balance") != opening && hasTxn && !V.b(data, "confirm_opening_change"))
                throw AZError.conflict("Changing the opening balance of an account with transactions changes every running balance. Confirm to proceed.", "confirm_opening_change")
            db.run("UPDATE accounts SET name=?, kind=?, account_type=?, bank_name=?, account_last4=?, currency=?, opening_balance=?, opening_date=?, details=?, active=?, updated_at=? WHERE id=?",
                listOf(name, kind, accType, bankName, l4, cur, opening, openingDate, details, active, ts, aid))
            audit("account", aid, "update", JValue.Obj(before.json), summary)
            aid
        } else {
            val id = db.run("INSERT INTO accounts(name, kind, account_type, bank_name, account_last4, currency, opening_balance, opening_date, details, active, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(name, kind, accType, bankName, l4, cur, opening, openingDate, details, active, ts, ts))
            audit("account", id, "create", null, summary)
            id
        }
    }
}

fun Agency.setAccountActive(aid: Long, active: Boolean) {
    tx {
        val before = db.one("SELECT id, name, active FROM accounts WHERE id = ?", listOf(aid)) ?: throw AZError.notFound("Account not found")
        if (!active && db.scalarLong("SELECT COUNT(*) FROM accounts WHERE active = 1 AND id <> ?", listOf(aid)) == 0L) throw AZError.validation("Keep at least one active account")
        db.run("UPDATE accounts SET active = ?, updated_at = ? WHERE id = ?", listOf(if (active) 1 else 0, Dates.nowISO(), aid))
        audit("account", aid, if (active) "activate" else "deactivate", JValue.obj("active" to before.long("active")), JValue.obj("active" to (if (active) 1 else 0)))
    }
}

// ---- tax profiles

fun Agency.listTaxProfiles(): List<Row> = db.query("SELECT * FROM tax_profiles ORDER BY active DESC, id")

fun Agency.saveTaxProfile(data: Fields, pid: Long? = null): Long {
    val p = linkedMapOf<String, Any?>(
        "name" to V.s(data, "name", 120, required = true, label = "Tax profile name")!!,
        "rate" to parsePercent(data.arg("rate"), "Rate"),
        "basis" to V.choice(data, "basis", listOf("none", "service_charge", "markup", "service_and_markup", "selling_price", "custom"), "none", "Taxable basis"),
        "split" to V.choice(data, "split", listOf("none", "cgst_sgst", "igst"), "none", "Tax split"),
        "sac_code" to V.s(data, "sac_code", 12),
        "notes" to V.s(data, "notes", 1000),
        "active" to (if (V.isOff(data.arg("active"))) 0 else 1))
    val pj = JValue.wrap(p)
    return tx {
        if (db.one("SELECT id FROM tax_profiles WHERE name = ? AND id <> ?", listOf(p["name"], pid ?: 0)) != null) throw AZError.conflict("A tax profile with this name already exists")
        if (pid != null) {
            val before = db.one("SELECT * FROM tax_profiles WHERE id = ?", listOf(pid)) ?: throw AZError.notFound("Tax profile not found")
            db.run("UPDATE tax_profiles SET name=?, rate=?, basis=?, split=?, sac_code=?, notes=?, active=? WHERE id=?", p.values.toList() + pid)
            audit("tax_profile", pid, "update", JValue.Obj(before.json), pj); pid
        } else {
            val id = db.run("INSERT INTO tax_profiles(name, rate, basis, split, sac_code, notes, active) VALUES (?,?,?,?,?,?,?)", p.values.toList())
            audit("tax_profile", id, "create", null, pj); id
        }
    }
}

/** Tax for a booking: from its tax profile, or entered manually (tax_override). */
internal fun Agency.computeTax(data: Fields, amounts: Map<String, Long>): LinkedHashMap<String, Any?> {
    val pid = V.i(data, "tax_profile_id")
    fun blank(k: String) = data.arg(k).isNull || data.arg(k).py.isBlank()
    if (pid == null || pid == 0L) {
        if (V.b(data, "tax_override")) {
            val taxable = parseAmount(data.arg("taxable_value"), "Taxable value")
            val rate = parsePercent(data.arg("tax_rate"), "Tax rate")
            val tax = if (!blank("tax_amount")) parseAmount(data.arg("tax_amount"), "Tax amount") else percentOf(taxable, rate)
            return linkedMapOf("tax_profile_id" to null, "tax_override" to 1L, "taxable_value" to taxable, "tax_rate" to rate, "tax_amount" to tax,
                "tax_split" to V.choice(data, "tax_split", listOf("none", "cgst_sgst", "igst"), "none"))
        }
        return linkedMapOf("tax_profile_id" to null, "tax_override" to 0L, "taxable_value" to 0L, "tax_rate" to "0", "tax_amount" to 0L, "tax_split" to "none")
    }
    val prof = db.one("SELECT * FROM tax_profiles WHERE id = ?", listOf(pid)) ?: throw AZError.validation("Selected tax profile does not exist", "tax_profile_id")
    val markup = maxOf(amounts["selling_price"]!! - amounts["purchase_cost"]!!, 0L)
    val taxable: Long; val rate: String; val tax: Long; val override: Long
    if (V.b(data, "tax_override")) {
        taxable = parseAmount(data.arg("taxable_value"), "Taxable value")
        rate = parsePercent(if (!blank("tax_rate")) data.arg("tax_rate") else J(prof.str("rate")), "Tax rate")
        tax = if (!blank("tax_amount")) parseAmount(data.arg("tax_amount"), "Tax amount") else percentOf(taxable, rate)
        override = 1
    } else {
        taxable = when (prof.str("basis")) {
            "none" -> 0
            "service_charge" -> amounts["service_charge"]!!
            "markup" -> markup
            "service_and_markup" -> amounts["service_charge"]!! + markup
            "selling_price" -> amounts["selling_price"]!!
            else -> parseAmount(data.arg("taxable_value"), "Taxable value")
        }
        rate = prof.str("rate"); tax = percentOf(taxable, rate); override = 0
    }
    return linkedMapOf("tax_profile_id" to pid, "tax_override" to override, "taxable_value" to taxable, "tax_rate" to rate, "tax_amount" to tax,
        "tax_split" to prof.str("split"))
}

// ---- expense categories

fun Agency.listExpenseCategories(): List<Row> = db.query("SELECT * FROM expense_categories ORDER BY name")

fun Agency.saveExpenseCategory(data: Fields): String {
    val name = V.s(data, "name", 80, required = true, label = "Category name")!!
    val active = if (V.isOff(data.arg("active"))) 0 else 1
    tx {
        db.run("INSERT INTO expense_categories(name, active) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET active = excluded.active", listOf(name, active))
        audit("expense_category", name, "save", null, JValue.obj("name" to name, "active" to active))
    }
    return name
}
