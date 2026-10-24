package com.airzelo.app

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteProgram
import com.airzelo.engine.Row
import com.airzelo.engine.SqlDb
import com.airzelo.engine.SqlDbFactory
import com.airzelo.engine.SqlException
import com.airzelo.engine.SqlValue
import java.io.File

/**
 * [SqlDb] on Android's own SQLite. One connection (WAL journal, synchronous=FULL), values bound with their real
 * types (rawQuery would bind everything as text), and BEGIN / COMMIT / ROLLBACK mapped to Android's transaction API.
 * Nothing here logs SQL or values: payloads can contain passport numbers.
 */
class AndroidSqlDb private constructor(override val path: String, private val db: SQLiteDatabase) : SqlDb {
    companion object {
        val factory = SqlDbFactory { path, readOnly ->
            val params = SQLiteDatabase.OpenParams.Builder()
                .setOpenFlags(if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
            if (!readOnly) params.setJournalMode("WAL").setSynchronousMode("FULL")
            val db = try { SQLiteDatabase.openDatabase(File(path), params.build()) } catch (e: SQLiteException) { throw translate(e, "open") }
            if (!readOnly) db.setForeignKeyConstraintsEnabled(true)
            AndroidSqlDb(path, db)
        }

        private fun translate(e: SQLiteException, sql: String): SqlException {
            // Android's messages end with " (code 1811 SQLITE_CONSTRAINT_TRIGGER[1811])"; the text before it is SQLite's own
            val raw = e.message ?: "SQLite error"
            val code = Regex("""\(code (\d+)""").find(raw)?.groupValues?.get(1)?.toIntOrNull()?.and(0xFF)
                ?: if (e is SQLiteConstraintException) 19 else 1
            return SqlException(code, raw.substringBefore(" (code ") + " in: " + sql.take(80))
        }

        private fun bind(p: SQLiteProgram, args: List<Any?>) {
            args.forEachIndexed { i, a ->
                when (val v = SqlValue.of(a)) {
                    SqlValue.Null -> p.bindNull(i + 1)
                    is SqlValue.I -> p.bindLong(i + 1, v.v)
                    is SqlValue.R -> p.bindDouble(i + 1, v.v)
                    is SqlValue.T -> p.bindString(i + 1, v.v)
                    is SqlValue.B -> p.bindBlob(i + 1, v.v)
                }
            }
        }

        private val JOURNAL = Regex("""^\s*PRAGMA\s+journal_mode\s*=\s*(\w+)\s*;?\s*$""", RegexOption.IGNORE_CASE)
    }

    private fun <T> wrap(sql: String, body: () -> T): T = try { body() } catch (e: SQLiteException) { throw translate(e, sql) }

    override fun exec(sql: String) = wrap(sql) {
        val head = sql.trimStart().uppercase()
        when {
            head.startsWith("BEGIN") -> db.beginTransaction()
            head.startsWith("COMMIT") || head.startsWith("END") -> { db.setTransactionSuccessful(); db.endTransaction() }
            head.startsWith("ROLLBACK") && !head.startsWith("ROLLBACK TO") -> db.endTransaction()
            // some PRAGMAs answer with a row (secure_delete, journal_mode ...), which execSQL refuses
            head.startsWith("PRAGMA") -> db.rawQuery(sql, null).use { it.moveToFirst() }
            else -> db.execSQL(sql)
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Row> = wrap(sql) {
        JOURNAL.matchEntire(sql)?.let { m ->
            // the journal mode is set when the file is opened; DELETE is asked for on snapshot copies before they are archived
            val want = m.groupValues[1].lowercase()
            val now = db.rawQuery("PRAGMA journal_mode", null).use { c -> if (c.moveToFirst()) c.getString(0).lowercase() else "" }
            if (now != want) db.rawQuery(sql, null).use { it.moveToFirst() }
            return@wrap listOf(Row(listOf("journal_mode"), listOf(SqlValue.T(want))))
        }
        val cursor = db.rawQueryWithFactory({ _, driver, editTable, q -> bind(q, args); SQLiteCursor(driver, editTable, q) }, sql, null, "")
        cursor.use { c -> rows(c) }
    }

    private fun rows(c: Cursor): List<Row> {
        val cols = c.columnNames.toList()
        val out = ArrayList<Row>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            out.add(Row(cols, cols.indices.map { i ->
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> SqlValue.Null
                    Cursor.FIELD_TYPE_INTEGER -> SqlValue.I(c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> SqlValue.R(c.getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> SqlValue.B(c.getBlob(i))
                    else -> SqlValue.T(c.getString(i))
                }
            }))
        }
        return out
    }

    override fun run(sql: String, args: List<Any?>): Long = wrap(sql) {
        db.compileStatement(sql).use { st -> bind(st, args); st.execute() }
        db.compileStatement("SELECT last_insert_rowid()").use { it.simpleQueryForLong() }
    }

    override val inTransaction: Boolean get() = db.inTransaction()

    override fun close() { runCatching { db.close() } }

    override fun snapshotTo(dest: File) {
        query("PRAGMA wal_checkpoint(TRUNCATE)")
        db.beginTransaction()  // the only connection: nothing can write while the file is copied
        try {
            File(path).copyTo(dest, overwrite = true)
        } finally {
            db.endTransaction()
        }
    }
}
