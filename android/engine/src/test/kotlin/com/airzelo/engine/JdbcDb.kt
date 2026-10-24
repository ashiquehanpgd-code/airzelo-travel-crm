package com.airzelo.engine

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * [SqlDb] on plain SQLite through xerial sqlite-jdbc, so the engine runs (and is tested) off-device against
 * the same SQL as the app. The connection stays in auto-commit mode; Agency.tx runs BEGIN / COMMIT / ROLLBACK
 * itself, and this class follows them to answer [inTransaction].
 */
class JdbcDb(override val path: String, readOnly: Boolean = false) : SqlDb {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path",
        org.sqlite.SQLiteConfig().apply { setReadOnly(readOnly) }.toProperties())
    private var txOpen = false

    companion object {
        val factory = SqlDbFactory { path, readOnly -> JdbcDb(path, readOnly) }
    }

    private fun <T> wrap(sql: String, body: () -> T): T = try { body() } catch (e: SQLException) {
        // xerial: errorCode is the primary result code (19 = SQLITE_CONSTRAINT); the message is
        // "[SQLITE_CONSTRAINT_TRIGGER] <generic text> (<SQLite's own message>)"
        val raw = e.message ?: "SQLite error"
        val own = Regex("""^\[\w+] .*? \((.*)\)$""", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1) ?: raw
        throw SqlException(e.errorCode and 0xFF, own + " in: " + sql.take(80))
    }

    private fun bind(st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (val v = SqlValue.of(a)) {
                SqlValue.Null -> st.setObject(i + 1, null)
                is SqlValue.I -> st.setLong(i + 1, v.v)
                is SqlValue.R -> st.setDouble(i + 1, v.v)
                is SqlValue.T -> st.setString(i + 1, v.v)
                is SqlValue.B -> st.setBytes(i + 1, v.v)
            }
        }
    }

    override fun exec(sql: String) = wrap(sql) {
        conn.createStatement().use { it.execute(sql) }
        val head = sql.trimStart().uppercase()
        if (head.startsWith("BEGIN")) txOpen = true
        else if (head.startsWith("COMMIT") || head.startsWith("END") || head.startsWith("ROLLBACK") && !head.startsWith("ROLLBACK TO")) txOpen = false
    }

    override fun query(sql: String, args: List<Any?>): List<Row> = wrap(sql) {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            if (!st.execute()) return@use emptyList<Row>()
            st.resultSet.use { rs ->
                val md = rs.metaData
                val cols = (1..md.columnCount).map { md.getColumnLabel(it) }
                val out = ArrayList<Row>()
                while (rs.next()) {
                    out.add(Row(cols, cols.indices.map { i ->
                        when (val o = rs.getObject(i + 1)) {
                            null -> SqlValue.Null
                            is Int -> SqlValue.I(o.toLong())
                            is Long -> SqlValue.I(o)
                            is Double -> SqlValue.R(o)
                            is Float -> SqlValue.R(o.toDouble())
                            is String -> SqlValue.T(o)
                            is ByteArray -> SqlValue.B(o)
                            else -> SqlValue.T(o.toString())
                        }
                    }))
                }
                out
            }
        }
    }

    override fun run(sql: String, args: List<Any?>): Long = wrap(sql) {
        conn.prepareStatement(sql).use { st -> bind(st, args); st.execute() }
        conn.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    override val inTransaction: Boolean get() = txOpen

    override fun close() { runCatching { conn.close() } }

    override fun snapshotTo(dest: File) {
        query("PRAGMA wal_checkpoint(TRUNCATE)")
        exec("BEGIN")
        try {
            query("SELECT COUNT(*) FROM sqlite_master")  // holds a read snapshot while the file is copied
            File(path).copyTo(dest, overwrite = true)
        } finally {
            exec("COMMIT")
        }
    }
}
