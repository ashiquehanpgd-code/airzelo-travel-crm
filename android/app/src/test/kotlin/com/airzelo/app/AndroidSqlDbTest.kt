package com.airzelo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airzelo.engine.AZError
import com.airzelo.engine.Agency
import com.airzelo.engine.AgencyStore
import com.airzelo.engine.FieldCrypto
import com.airzelo.engine.Ledger
import com.airzelo.engine.createCustomer
import com.airzelo.engine.createTransaction
import com.airzelo.engine.fieldsOf
import com.airzelo.engine.saveBooking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/** The engine on Android's own SQLite (AndroidSqlDb): schema, typed values, transactions, trigger refusals, backup and restore. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidSqlDbTest {
    private fun <T> withDir(body: (File) -> T): T {
        val dir = Files.createTempDirectory("az-sql-").toFile()
        try { return body(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun engineRunsOnAndroidSqlite() = withDir { dir ->
        val a = Agency.open(AndroidSqlDb.factory, File(dir, "agency.db"), FieldCrypto.ephemeral())
        try {
            assertEquals("ok", a.integrityCheck())
            val cash = a.db.scalarLong("SELECT id FROM accounts WHERE name = 'Cash in hand'")
            val cid = a.createCustomer(fieldsOf("name" to "Android Customer", "passport_no" to "p7654321"))
            val bid = a.saveBooking(fieldsOf("customer_id" to cid, "status" to "ticketed", "purchase_cost" to "40000", "selling_price" to "43000",
                "commission" to "500", "service_charge" to "1000", "tax_profile_id" to 2, "passengers" to listOf(mapOf("name" to "Pax One"))))
            val c = Ledger(a).bookings[bid]!!.calc
            assertEquals(18_000L, c.base.tax_amount)
            assertEquals(4_418_000L, c.customerPayable)
            val (tid, _) = a.createTransaction(fieldsOf("kind" to "customer_receipt", "account_id" to cash, "amount" to "44180", "booking_id" to bid))
            assertEquals("paid", Ledger(a).bookings[bid]!!.calc.paymentStatus)
            // a database trigger refusal reaches the user as a validation message, and the transaction is rolled back
            try {
                a.tx { a.db.run("DELETE FROM transactions WHERE id = ?", listOf(tid)) }
                fail("posted money was deleted")
            } catch (e: AZError) {
                assertEquals("validation", e.code)
                assertTrue(e.message, !e.message.contains("code ") && e.message.isNotBlank())
            }
            assertEquals(1L, a.db.scalarLong("SELECT COUNT(*) FROM transactions WHERE id = ?", listOf(tid)))
            assertTrue(!a.db.inTransaction)
            // typed binding: integers stay integers (rawQuery would bind text)
            assertEquals(1, a.db.query("SELECT typeof(?) AS t", listOf(5L)).size)
            assertEquals("integer", a.db.one("SELECT typeof(?) AS t", listOf(5L))!!.str("t"))
            assertEquals("real", a.db.one("SELECT typeof(?) AS t", listOf(2.5))!!.str("t"))
            assertEquals("null", a.db.one("SELECT typeof(?) AS t", listOf(null))!!.str("t"))
        } finally { a.close() }
    }

    @Test fun backupAndRestoreOnAndroidSqlite() = withDir { dir ->
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val store = AgencyStore(dir, key, AndroidSqlDb.factory)
        try {
            val a = store.open()
            a.createCustomer(fieldsOf("name" to "Kept", "passport_no" to "k1111111"))
            val b = store.createBackup()
            store.open().createCustomer(fieldsOf("name" to "Dropped"))
            store.restore(store.backupData(b))
            val r = store.open()
            assertEquals(1L, r.db.scalarLong("SELECT COUNT(*) FROM customers WHERE name = 'Kept'"))
            assertEquals(0L, r.db.scalarLong("SELECT COUNT(*) FROM customers WHERE name = 'Dropped'"))
            assertEquals("ok", r.integrityCheck())
            val export = store.exportBackup("android-sqlite-pass")
            val other = AgencyStore(File(dir, "other"), ByteArray(32).also { SecureRandom().nextBytes(it) }, AndroidSqlDb.factory)
            other.restore(export, "android-sqlite-pass")
            val o = other.open()
            assertEquals("K1111111", o.crypto.open(o.db.one("SELECT passport_no FROM customers WHERE name = 'Kept'")!!.str("passport_no"), "passport_no"))
            other.close()
        } finally { store.close() }
    }
}
