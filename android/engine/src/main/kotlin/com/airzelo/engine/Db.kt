package com.airzelo.engine

/** One SQLite value, typed as SQLite stores it. */
sealed class SqlValue {
    object Null : SqlValue()
    data class I(val v: Long) : SqlValue()
    data class R(val v: Double) : SqlValue()
    data class T(val v: String) : SqlValue()
    class B(val v: ByteArray) : SqlValue()

    val isNull get() = this === Null

    companion object {
        fun of(x: Any?): SqlValue = when (x) {
            null -> Null
            is SqlValue -> x
            is Long -> I(x)
            is Int -> I(x.toLong())
            is Boolean -> I(if (x) 1 else 0)
            is Double -> R(x)
            is String -> T(x)
            is ByteArray -> B(x)
            is JValue -> when (x) {
                JValue.Null -> Null
                is JValue.Int -> I(x.v)
                is JValue.Dbl -> R(x.v)
                is JValue.Bool -> I(if (x.v) 1 else 0)
                is JValue.Str -> T(x.v)
                else -> T(JValue.serialize(x))
            }
            else -> T(x.toString())
        }
    }
}

/** A result row; column order is kept. */
class Row(val columns: List<String>, val values: List<SqlValue>) {
    private val index: Map<String, Int> = columns.withIndex().reversed().associate { it.value to it.index }

    operator fun get(key: String): SqlValue = index[key]?.let { values[it] } ?: SqlValue.Null
    fun has(key: String) = index.containsKey(key)

    fun long(key: String): Long = when (val v = this[key]) {
        is SqlValue.I -> v.v
        is SqlValue.R -> v.v.toLong()
        is SqlValue.T -> v.v.toLongOrNull() ?: 0
        else -> 0
    }
    fun int(key: String): Int = long(key).toInt()
    fun optLong(key: String): Long? = if (this[key].isNull) null else long(key)
    fun optInt(key: String): Int? = optLong(key)?.toInt()
    fun optStr(key: String): String? = when (val v = this[key]) {
        is SqlValue.T -> v.v
        is SqlValue.I -> v.v.toString()
        is SqlValue.R -> v.v.toString()
        is SqlValue.B -> String(v.v)
        SqlValue.Null -> null
    }
    fun str(key: String): String = optStr(key) ?: ""
    fun bool(key: String) = long(key) != 0L

    /** The row as a JSON object (integers stay integers, text stays text). */
    val json: LinkedHashMap<String, JValue>
        get() = LinkedHashMap<String, JValue>().also { m -> columns.forEachIndexed { i, c -> if (!m.containsKey(c)) m[c] = JValue.fromSql(values[i]) } }
}

/** [message] is SQLite's own error text (for a trigger, its RAISE message), optionally followed by " in: " and the SQL. */
class SqlException(val code: Int, message: String) : RuntimeException(message) {
    /** SQLITE_CONSTRAINT: a trigger refusal or a constraint (RAISE(ABORT, ...)). */
    val isConstraint get() = code == 19
}

/**
 * Synchronous SQLite access. Implemented with Android's SQLiteDatabase in the app and with plain SQLite
 * (xerial sqlite-jdbc) in the JVM tests, so all business logic is tested off-device against the same SQL.
 */
interface SqlDb {
    val path: String
    fun exec(sql: String)
    fun query(sql: String, args: List<Any?> = emptyList()): List<Row>
    /** Executes a write and returns the last inserted row id. */
    fun run(sql: String, args: List<Any?> = emptyList()): Long
    val inTransaction: Boolean
    fun close()

    fun one(sql: String, args: List<Any?> = emptyList()): Row? = query(sql, args).firstOrNull()
    fun scalarLong(sql: String, args: List<Any?> = emptyList()): Long {
        val r = one(sql, args) ?: return 0
        return when (val v = r.values.firstOrNull()) {
            is SqlValue.I -> v.v
            is SqlValue.R -> v.v.toLong()
            is SqlValue.T -> v.v.toLongOrNull() ?: 0
            else -> 0
        }
    }

    /**
     * Consistent copy of the database to [dest]: the WAL is checkpointed and the file copied while a read
     * transaction holds the snapshot (works on every Android version; no VACUUM INTO needed).
     */
    fun snapshotTo(dest: java.io.File)
}

/** Opens databases (Android or JDBC). */
fun interface SqlDbFactory {
    fun open(path: String, readOnly: Boolean): SqlDb
}
