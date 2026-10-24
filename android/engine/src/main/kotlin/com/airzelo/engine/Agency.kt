package com.airzelo.engine

import java.io.File

/**
 * One agency's books on this device: the SQLite database (the desktop schema, v4) plus the services that
 * change it. Port of core/server/db.py and the shared parts of services.py (via the iPhone engine).
 * All work on an Agency happens on one thread. Every write goes through [tx]: it commits completely or not at all.
 */
class Agency(val db: SqlDb, val crypto: FieldCrypto) {
    private var txDepth = 0
    val file: File get() = File(db.path)

    companion object {
        val schema: JValue by lazy {
            val text = Agency::class.java.getResourceAsStream("schema.json")!!.bufferedReader().readText()
            JValue.parse(text)
        }
        val schemaVersion: Int get() = schema["version"].intValue!!

        /** Opens (creating if new) an agency database. Refuses books from a newer or older data version. */
        fun open(factory: SqlDbFactory, path: File, crypto: FieldCrypto): Agency {
            path.parentFile?.mkdirs()
            val fresh = !path.exists()
            val db = factory.open(path.path, readOnly = false)
            try {
                db.exec("PRAGMA foreign_keys = ON")
                db.query("PRAGMA journal_mode = WAL")
                db.exec("PRAGMA synchronous = FULL")
                db.exec("PRAGMA secure_delete = ON")  // freed pages are overwritten: deleted records do not linger in the file
                val a = Agency(db, crypto)
                if (fresh || a.currentVersion() == 0) a.createSchema()
                val v = a.currentVersion()
                if (v > schemaVersion) throw AZError.validation("These books were saved by a newer AirZelo (data version $v). Update the app to open them.")
                if (v < schemaVersion) throw AZError.validation("These books use an older data version ($v). Open them once in the AirZelo desktop app " +
                    "to upgrade them, then make a new backup and import that.")
                a.seed()
                return a
            } catch (e: Exception) {
                db.close()
                throw e
            }
        }
    }

    fun close() = db.close()

    // ---- schema

    fun currentVersion(): Int {
        if (db.one("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'") == null) return 0
        return db.scalarLong("SELECT COALESCE(MAX(version), 0) FROM schema_migrations").toInt()
    }

    private fun createSchema() {
        db.exec("BEGIN IMMEDIATE")
        try {
            if (db.one("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'") != null) db.exec("DROP TABLE schema_migrations")
            for (s in schema["statements"].array!!) db.exec(s.string!!)
            // rows written by the desktop migrations themselves, so a new phone agency starts exactly like a desktop one
            for (row in schema["migration_data"].array!!) {
                val r = row.array!!
                val cols = r[1].array!!.map { it.string!! }
                db.run("INSERT OR IGNORE INTO ${r[0].string}(${cols.joinToString(", ")}) VALUES (${cols.joinToString(", ") { "?" }})", r[2].array!!)
            }
            val ts = Dates.nowISO()
            for (m in schema["migrations"].array!!) {
                db.run("INSERT INTO schema_migrations(version, name, applied_at) VALUES (?, ?, ?)", listOf(m.array!![0].long, m.array!![1].string, ts))
            }
            db.exec("COMMIT")
        } catch (e: Exception) {
            runCatching { db.exec("ROLLBACK") }
            throw e
        }
    }

    private fun seed() {
        val seed = schema["seed"]
        tx {
            for (pair in seed["settings"].array!!) db.run("INSERT OR IGNORE INTO settings(key, value) VALUES (?, ?)", listOf(pair.array!![0].string, pair.array!![1].string ?: ""))
            for (c in seed["currencies"].array!!) db.run("INSERT OR IGNORE INTO currencies(code, name, symbol) VALUES (?, ?, ?)", c.array!!.map { it.string })
            if (db.scalarLong("SELECT COUNT(*) FROM expense_categories") == 0L) {
                for (c in seed["expense_categories"].array!!) db.run("INSERT INTO expense_categories(name) VALUES (?)", listOf(c.string))
            }
            if (db.scalarLong("SELECT COUNT(*) FROM tax_profiles") == 0L) {
                for (p in seed["tax_profiles"].array!!) {
                    db.run("INSERT INTO tax_profiles(name, rate, basis, split, sac_code, notes) VALUES (?,?,?,?,?,?)", p.array!!.map { it.string })
                }
            }
            if (db.scalarLong("SELECT COUNT(*) FROM accounts") == 0L) {
                val ts = Dates.nowISO()
                db.run("INSERT INTO accounts(name, kind, account_type, currency, opening_balance, opening_date, details, created_at, updated_at) " +
                    "VALUES ('Cash in hand', 'cash', 'cash', 'INR', 0, NULL, 'Set the opening balance in Accounts.', ?, ?)", listOf(ts, ts))
            }
        }
    }

    // ---- transactions

    /** Atomic write transaction. Nested calls join the outer transaction (db.tx in the desktop app). */
    fun <T> tx(body: () -> T): T {
        if (txDepth > 0 || db.inTransaction) {
            txDepth++
            try { return body() } finally { txDepth-- }
        }
        db.exec("BEGIN IMMEDIATE")
        txDepth = 1
        try {
            val r = body()
            txDepth = 0
            db.exec("COMMIT")
            return r
        } catch (e: Throwable) {
            txDepth = 0
            runCatching { db.exec("ROLLBACK") }
            throw translate(e)
        }
    }

    /** Database trigger refusals ("Posted transactions cannot be deleted...") reach the user as validation messages. */
    private fun translate(e: Throwable): Throwable =
        if (e is SqlException && e.isConstraint) AZError.validation(e.message!!.substringBefore(" in: ").substringAfter("[SQLITE_CONSTRAINT_TRIGGER] ").substringBefore(" (").trim()) else e

    // ---- shared helpers

    fun setting(key: String, dflt: String? = null): String? = db.one("SELECT value FROM settings WHERE key = ?", listOf(key))?.optStr("value") ?: dflt
    fun allSettings(): Map<String, String> = db.query("SELECT key, value FROM settings").associate { it.str("key") to it.str("value") }
    val baseCurrency: String get() = setting("base_currency", "INR") ?: "INR"

    /** Must be called inside a write transaction. */
    internal fun nextCounter(name: String): Long {
        val r = db.one("SELECT value FROM counters WHERE name = ?", listOf(name))
        if (r == null) { db.run("INSERT INTO counters(name, value) VALUES (?, 1)", listOf(name)); return 1 }
        val v = r.long("value") + 1
        db.run("UPDATE counters SET value = ? WHERE name = ?", listOf(v, name))
        return v
    }

    internal fun audit(entity: String, entityId: Any?, action: String, before: JValue? = null, after: JValue? = null) {
        db.run("INSERT INTO audit_log(at, entity, entity_id, action, before_json, after_json) VALUES (?,?,?,?,?,?)",
            listOf(Dates.nowISO(), entity, entityId?.toString(), action, before?.let { JValue.serialize(it) }, after?.let { JValue.serialize(it) }))
    }

    internal fun history(bookingId: Long, action: String, detail: String = "") {
        db.run("INSERT INTO booking_history(booking_id, at, action, detail) VALUES (?,?,?,?)", listOf(bookingId, Dates.nowISO(), action, detail))
    }

    fun listAudit(entity: String? = null, entityId: String? = null, limit: Int = 300): List<Row> {
        var q = "SELECT * FROM audit_log"
        val args = ArrayList<Any?>(); val cond = ArrayList<String>()
        if (entity != null) { cond += "entity = ?"; args += entity }
        if (entityId != null) { cond += "entity_id = ?"; args += entityId }
        if (cond.isNotEmpty()) q += " WHERE " + cond.joinToString(" AND ")
        q += " ORDER BY id DESC LIMIT ?"; args += minOf(limit, 2000)
        return db.query(q, args)
    }

    /** integrity_check + foreign_key_check: "ok" when the file is sound. */
    fun integrityCheck(): String {
        val r = db.one("PRAGMA integrity_check")?.values?.firstOrNull()?.let { JValue.fromSql(it).py } ?: "unknown"
        if (r != "ok") return r
        if (db.query("PRAGMA foreign_key_check").isNotEmpty()) return "foreign key check failed"
        return "ok"
    }

    // ---- settings and currencies (services.py)

    private val editableSettings = listOf("business_name", "business_tagline", "business_address", "business_phone", "business_email",
        "business_gstin", "business_state", "invoice_prefix", "credit_note_prefix", "invoice_terms", "invoice_footer", "auto_backup_hours",
        "backup_keep", "session_hours", "pay_account_name", "pay_account_number", "pay_ifsc", "pay_branch", "pay_bank_name",
        "pay_upi_number", "pay_upi_apps", "legal_name", "business_country", "business_website", "business_registration", "receipt_footer",
        "brand_color", "booking_prefix", "receipt_prefix", "date_format", "currency_display", "report_default_period", "setup_completed")
    private val choiceSettings = mapOf("date_format" to listOf("DD/MM/YYYY", "DD MMM YYYY", "MM/DD/YYYY", "YYYY-MM-DD"),
        "currency_display" to listOf("symbol", "code"), "report_default_period" to listOf("month", "fy", "year", "week", "today"),
        "setup_completed" to listOf("0", "1"))
    private val prefixSettings = setOf("invoice_prefix", "credit_note_prefix", "booking_prefix", "receipt_prefix")
    private val formatRules = mapOf(
        "business_email" to ("""[^@\s]+@[^@\s]+\.[A-Za-z]{2,}""" to "Enter a valid email address"),
        "business_phone" to ("""[+0-9][0-9 ()\-]{5,24}""" to "Enter a valid phone number (digits, spaces, + ( ) -)"),
        "business_website" to ("""(https?://)?[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+(/\S*)?""" to "Enter a website such as www.example.com"),
        "brand_color" to ("""#[0-9A-Fa-f]{6}""" to "Brand colour must be a hex colour such as #1B3FCC"),
        "business_gstin" to ("""[0-9]{2}[A-Z0-9]{10}[0-9A-Z]{3}""" to "GSTIN must be 15 characters, e.g. 32ABCDE1234F1Z5"))
    private val paymentFormats = mapOf(
        "pay_account_number" to ("""\d{9,18}""" to "Account number must be 9 to 18 digits"),
        "pay_ifsc" to ("""[A-Z]{4}0[A-Z0-9]{6}""" to "IFSC code must look like ABCD0123456 (11 characters)"),
        "pay_upi_number" to ("""\d{10}""" to "UPI payment number must be a 10-digit mobile number"))

    fun updateSettings(data: Fields): Map<String, String> {
        tx {
            val before = allSettings()
            val changes = LinkedHashMap<String, JValue>()
            for (k in editableSettings) {
                if (!data.containsKey(k)) continue
                var v = if (data.arg(k).isNull) "" else data.arg(k).py.trim()
                if (v.codePointCount(0, v.length) > 2000) throw AZError.validation("$k is too long", k)
                if (k in setOf("auto_backup_hours", "backup_keep", "session_hours")) {
                    val n = v.toLongOrNull() ?: throw AZError.validation("$k must be a whole number", k)
                    val lim = mapOf("auto_backup_hours" to (0L to 720L), "backup_keep" to (3L to 1000L), "session_hours" to (1L to 72L))[k]!!
                    if (n < lim.first || n > lim.second) throw AZError.validation("$k must be between ${lim.first} and ${lim.second}", k)
                    v = n.toString()
                }
                if (k in setOf("pay_ifsc", "pay_account_name", "business_gstin")) v = v.uppercase()
                if (k == "business_name" && v.isEmpty()) throw AZError.validation("The display name cannot be empty", k)
                choiceSettings[k]?.let { if (v !in it) throw AZError.validation("${V.capitalize(k.replace("_", " "))} must be one of: ${it.joinToString(", ")}", k) }
                if (k in prefixSettings && !Regex("""[A-Za-z0-9][A-Za-z0-9/_\-]{0,11}""").matches(v)) throw AZError.validation("Prefix must be 1-12 letters, digits, / _ or -, e.g. INV/", k)
                formatRules[k]?.let { (p, msg) -> if (v.isNotEmpty() && !Regex(p).matches(v)) throw AZError.validation(msg, k) }
                if (k in setOf("pay_account_number", "pay_upi_number")) v = v.replace(" ", "")
                paymentFormats[k]?.let { (p, msg) -> if (v.isNotEmpty() && !Regex(p).matches(v)) throw AZError.validation(msg, k) }
                if (before[k] != v) {
                    db.run("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", listOf(k, v))
                    changes[k] = J(v)
                }
            }
            if (changes.isNotEmpty()) audit("settings", null, "update", JValue.Obj(changes.keys.associateWith { J(before[it]) }), JValue.Obj(changes))
        }
        return allSettings()
    }

    fun listCurrencies(): List<Row> = db.query("SELECT * FROM currencies ORDER BY CASE code WHEN 'INR' THEN 0 WHEN 'SAR' THEN 1 ELSE 2 END, code")

    fun saveCurrency(data: Fields): Row? {
        val code = V.normCode(data.arg("code"), 3)
        if (code == null || code.length != 3 || !code.all { it.isLetter() }) throw AZError.validation("Currency code must be 3 letters (e.g. SAR)", "code")
        val name = V.s(data, "name", 60, required = true)!!
        val symbol = V.s(data, "symbol", 8) ?: code
        val active = if (V.isOff(data.arg("active"))) 0 else 1
        tx {
            val before = db.one("SELECT * FROM currencies WHERE code = ?", listOf(code))
            if (before != null && code == setting("base_currency") && active == 0) throw AZError.validation("The reporting currency cannot be deactivated")
            db.run("INSERT INTO currencies(code, name, symbol, active) VALUES (?,?,?,?) ON CONFLICT(code) DO UPDATE SET name=excluded.name, symbol=excluded.symbol, active=excluded.active",
                listOf(code, name, symbol, active))
            audit("currency", code, if (before != null) "update" else "create", before?.let { JValue.Obj(it.json) },
                JValue.obj("code" to code, "name" to name, "symbol" to symbol, "active" to active))
        }
        return db.one("SELECT * FROM currencies WHERE code = ?", listOf(code))
    }

    /** (currency, rate as text) for a payload; the reporting currency always has rate "1". */
    internal fun currencyAndRate(data: Fields, curKey: String = "currency", rateKey: String = "exchange_rate"): Pair<String, String> {
        val base = setting("base_currency", "INR") ?: "INR"
        val cur = V.normCode(data.arg(curKey), 3) ?: base
        if (db.one("SELECT * FROM currencies WHERE code = ?", listOf(cur)) == null) throw AZError.validation("Unknown currency $cur - add it in Settings first", curKey)
        if (cur == base) return cur to "1"
        return cur to parseRate(data.arg(rateKey), "Exchange rate to $base")
    }
}
