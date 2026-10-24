package com.airzelo.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airzelo.engine.BackupCrypto
import com.airzelo.engine.Ledger
import com.airzelo.engine.Zip
import com.airzelo.engine.bookingEditFields
import com.airzelo.engine.createCustomer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * The app's main workflow, driven through the real MainActivity and its Compose screens on Android's own SQLite
 * (Robolectric): create a booking with a passenger and a flight, record the payment and see Unpaid change to Paid,
 * edit without losing any field, then back up, change the books and restore - from a device backup and from a
 * passphrase export.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppWorkflowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var root: File
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() {
        root = Files.createTempDirectory("az-app-").toFile()
        AppConfig.overrides = AppConfig.Overrides(root, ByteArray(32).also { SecureRandom().nextBytes(it) })
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() {
        scenario.close()
        AppConfig.overrides = null
        root.deleteRecursively()
    }

    private fun <T> onMain(body: (AppModel) -> T): T {
        var out: Result<T>? = null
        scenario.onActivity { out = runCatching { body(ViewModelProvider(it)[AppModel::class.java]) } }
        return out!!.getOrThrow()
    }

    private fun noProblem(step: String) {
        compose.waitForIdle()
        assertNull("$step: ${onMain { it.problem?.message }}", onMain { it.problem?.message })
    }

    private fun type(tag: String, text: String) = compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test fun bookingPaymentEditBackupRestore() {
        compose.waitForIdle()
        println(compose.onRoot().printToString(maxDepth = 6))
        assertNull("startup: " + onMain { it.startupProblem }, onMain { it.startupProblem })
        assertFalse("unlocked in tests", onMain { it.locked })
        assertTrue("books open", onMain { it.agency != null })

        // 1. a booking with a new customer, a passenger (passport encrypted) and a flight
        compose.onNodeWithTag("tab-bookings").performClick()
        compose.onNodeWithTag("new-booking").performClick()
        type("new-customer-name", "Workflow Customer")
        type("new-customer-phone", "+91 90000 11111")
        type("pnr", "WF1234")
        type("purchase-cost", "40000")
        type("selling-price", "43000")
        type("pax-title-0", "MRS")
        type("pax-name-0", "Asha Menon")
        type("pax-ticket-0", "0651234567890")
        type("pax-passport-0", "k1234567")
        click("add-flight")
        type("seg-airline-0", "IndiGo")
        type("seg-flight-0", "6E 1407")
        type("seg-from-0", "COK")
        type("seg-to-0", "DXB")
        type("seg-dep-0", "2026-11-02T04:10")
        compose.onNodeWithTag("save-booking").performClick()
        noProblem("save booking")

        val bid = onMain { it.agency!!.db.scalarLong("SELECT id FROM bookings WHERE pnr = 'WF1234'") }
        assertTrue("booking saved", bid > 0)
        onMain { m ->
            val stored = m.agency!!.db.one("SELECT passport_no FROM booking_passengers WHERE booking_id = ?", listOf(bid))!!.str("passport_no")
            assertTrue("passport stored encrypted", stored.startsWith("azenc1:"))
        }

        // 2. the booking is Unpaid; recording the payment (amount due is preset) makes it Paid
        compose.onNodeWithText("Workflow Customer").performClick()
        compose.onNodeWithTag("payment-status").assertTextEquals("Unpaid")
        click("record-payment")
        compose.onNodeWithTag("txn-amount").assertIsDisplayed()
        compose.onNodeWithTag("save-transaction").performClick()
        noProblem("record payment")
        compose.onNodeWithTag("payment-status").assertTextEquals("Paid")
        assertEquals(0L, onMain { Ledger(it.agency!!).bookings[bid]!!.calc.customerOutstanding })

        // 3. editing and saving again keeps every stored field (passengers, passport, flight, prices) and every figure
        val before = onMain { it.agency!!.bookingEditFields(bid) }
        val calcBefore = onMain { Ledger(it.agency!!).bookings[bid]!!.calc.json }
        compose.onNodeWithTag("edit-booking").performClick()
        compose.onNodeWithTag("save-booking").performClick()
        noProblem("re-save booking")
        assertEquals(before, onMain { it.agency!!.bookingEditFields(bid) })
        assertEquals(calcBefore, onMain { Ledger(it.agency!!).bookings[bid]!!.calc.json })
        assertEquals("K1234567", before["passengers"]!!.array!![0]["passport_no"].string)
        compose.onNodeWithTag("payment-status").assertTextEquals("Paid")

        // 4. device backup from the Backup screen, a change, then restore from the list
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("tab-more").performClick()
        compose.onNodeWithTag("more-backup").performClick()
        click("backup-now")
        noProblem("back up now")
        compose.onNodeWithTag("backup-message").assertIsDisplayed()
        val backupName = onMain { it.store!!.listBackups().first().name }
        onMain { m -> m.agency!!.createCustomer(mapOf("name" to com.airzelo.engine.J("Added After Backup"))); m.changed() }
        assertEquals(1L, onMain { it.agency!!.db.scalarLong("SELECT COUNT(*) FROM customers WHERE name = 'Added After Backup'") })
        click("backup-row-$backupName")
        compose.onNodeWithTag("confirm").performClick()
        noProblem("restore device backup")
        assertEquals(0L, onMain { it.agency!!.db.scalarLong("SELECT COUNT(*) FROM customers WHERE name = 'Added After Backup'") })
        assertEquals("paid", onMain { Ledger(it.agency!!).bookings[bid]!!.calc.paymentStatus })
        assertEquals(before, onMain { it.agency!!.bookingEditFields(bid) })
        assertTrue("a safety backup was made before restoring", onMain { m -> m.store!!.listBackups().any { it.kind == "pre-restore" } })

        // 5. passphrase export (what the export button hands to the file picker) and import from it
        val export = onMain { it.exportBytes("workflow-pass-2026") }!!
        val zip = Zip.Reader(BackupCrypto.open(export, null, "workflow-pass-2026"))
        assertTrue("export is a desktop backup inside", "agency.db" in zip.names && "manifest.json" in zip.names)
        onMain { m -> m.agency!!.createCustomer(mapOf("name" to com.airzelo.engine.J("Added After Export"))); m.changed() }
        assertFalse("wrong passphrase refused", onMain { it.restoreBytes(export, "not-the-passphrase") })
        assertTrue(onMain { it.problem != null })
        onMain { it.problem = null }
        assertEquals("books untouched after a refused import", 1L,
            onMain { it.agency!!.db.scalarLong("SELECT COUNT(*) FROM customers WHERE name = 'Added After Export'") })
        assertTrue(onMain { it.restoreBytes(export, "workflow-pass-2026") })
        assertEquals(0L, onMain { it.agency!!.db.scalarLong("SELECT COUNT(*) FROM customers WHERE name = 'Added After Export'") })
        assertEquals(before, onMain { it.agency!!.bookingEditFields(bid) })
        assertEquals("ok", onMain { it.agency!!.integrityCheck() })
    }
}
