package com.airzelo.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airzelo.app.ui.Fmt
import com.airzelo.engine.Ledger
import com.airzelo.engine.dashboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * The redesigned screens with synthetic demo books: real figures on the dashboard, navigation, scrolling, forms that
 * keep their values, and PNG screenshots of every key screen (build/screenshots, uploaded by CI for design review).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
open class DesignTest {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var root: File
    protected lateinit var scenario: ActivityScenario<MainActivity>
    open val bookings = 36
    open val shotPrefix = "phone"

    @Before fun launch() {
        root = Files.createTempDirectory("az-design-").toFile()
        AppConfig.overrides = AppConfig.Overrides(root, ByteArray(32).also { SecureRandom().nextBytes(it) })
        scenario = ActivityScenario.launch(MainActivity::class.java)
        onMain { m -> DemoData.seed(m.agency!!, bookings); m.changed() }
        compose.waitForIdle()
    }

    @After fun close() { scenario.close(); AppConfig.overrides = null; root.deleteRecursively() }

    protected fun <T> onMain(body: (AppModel) -> T): T {
        var out: Result<T>? = null
        scenario.onActivity { out = runCatching { body(ViewModelProvider(it)[AppModel::class.java]) } }
        return out!!.getOrThrow()
    }

    protected fun shot(name: String) {
        compose.waitForIdle()
        val dir = File("build/screenshots").also { it.mkdirs() }
        val bmp = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }.getOrElse {
            // fallback: draw the window in software
            var b: Bitmap? = null
            scenario.onActivity { act ->
                val v = act.window.decorView
                b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { bm -> v.draw(android.graphics.Canvas(bm)) }
            }
            b!!
        }
        File(dir, "$shotPrefix-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    protected fun open(tag: String) { compose.onNodeWithTag(tag).performClick(); compose.waitForIdle() }
    protected fun back() { compose.onNodeWithTag("back").performClick(); compose.waitForIdle() }
    protected fun title() = compose.onNodeWithTag("screen-title")

    @Test fun dashboardShowsTheLedgersRealFigures() {
        val (from, to) = presetRange("month")
        val s = onMain { Ledger(it.agency!!).dashboard(from, to).summary }
        compose.onNodeWithTag("hero-sales").assertTextEquals(Fmt.money(s.sales, "INR"))
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("dash-net-profit"))
        compose.onNodeWithTag("dash-net-profit").assertTextEquals(Fmt.money(s.netProfit, "INR"))
        // the whole dashboard scrolls to its last section
        compose.onNodeWithTag("dashboard").performScrollToNode(hasText("Recent transactions"))
        compose.onNodeWithText("Recent transactions").assertIsDisplayed()
        // another period recomputes from the same ledger
        compose.onNodeWithTag("dashboard").performScrollToIndex(0)
        compose.onNodeWithTag("period-Year").performClick()
        val y = onMain { Ledger(it.agency!!).dashboard(presetRange("year").first, presetRange("year").second).summary }
        compose.onNodeWithTag("hero-sales").assertTextEquals(Fmt.money(y.sales, "INR"))
        assertTrue("the demo books have sales this year", y.sales > 0)
    }

    @Test fun kpiCardsSwipeAndOpenTheirReports() {
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("kpi-row"))
        compose.onNodeWithTag("kpi-row").performScrollToNode(hasTestTag("kpi-Net cash flow"))
        open("kpi-Net cash flow")
        title().assertTextContains("Cashbook", substring = true)
        back()
        compose.onNodeWithTag("dashboard").assertIsDisplayed()
    }

    @Test fun everyTabAndEveryMoreEntryOpens() {
        for ((tab, expected) in listOf("tab-bookings" to "Bookings", "tab-customers" to "Customers", "tab-money" to "Money", "tab-more" to "More")) {
            open(tab); title().assertTextEquals(expected)
        }
        for (route in listOf("reports", "invoices", "suppliers", "accounts", "expenses", "report/refunds", "report/cashbook", "backup", "settings", "online", "about")) {
            compose.onNodeWithTag("more-list").performScrollToNode(hasTestTag("more-$route"))
            open("more-$route")
            title().assertIsDisplayed()
            back()
        }
    }

    @Test fun tabsKeepTheirStateAndLongListsScroll() {
        open("tab-bookings")
        compose.onNodeWithTag("booking-search").performTextInput("QT")
        open("tab-customers")
        open("tab-bookings")
        compose.onNodeWithTag("booking-search").assertTextContains("QT")
        // every booking can be reached by scrolling (lazy list, no cap)
        val last = onMain { it.agency!!.db.one("SELECT ref FROM bookings ORDER BY booking_date, id LIMIT 1")!!.str("ref") }
        compose.onNodeWithTag("booking-list").performScrollToNode(hasTestTag("booking-$last"))
        compose.onNodeWithTag("booking-$last").assertIsDisplayed()
    }

    @Test fun bookingFormKeepsValuesWhileScrolling() {
        open("tab-bookings"); open("new-booking")
        compose.onNodeWithTag("pnr").performScrollTo().performTextInput("KEEP42")
        compose.onNodeWithTag("pax-name-0").performScrollTo().performTextInput("Scroll Tester")
        compose.onNodeWithTag("save-booking-bottom").performScrollTo()
        compose.onNodeWithTag("new-customer-name").performScrollTo()
        compose.onNodeWithTag("pnr").performScrollTo().assertTextContains("KEEP42")
        compose.onNodeWithTag("pax-name-0").performScrollTo().assertTextContains("Scroll Tester")
    }

    @Test fun screenshots() {
        shot("01-dashboard")
        compose.onNodeWithTag("dashboard").performScrollToNode(hasTestTag("revenue-chart")); shot("02-dashboard-chart")
        compose.onNodeWithTag("dashboard").performScrollToNode(hasText("Pending customer payments")); shot("03-dashboard-lists")
        open("tab-bookings"); shot("04-bookings")
        val ref = onMain { it.agency!!.db.one("SELECT ref FROM bookings WHERE status = 'ticketed' ORDER BY id DESC LIMIT 1")!!.str("ref") }
        compose.onNodeWithTag("booking-list").performScrollToNode(hasTestTag("booking-$ref"))
        open("booking-$ref"); shot("05-booking-detail")
        compose.onNodeWithTag("booking-detail").performScrollToNode(hasText("Passengers", substring = true)); shot("06-booking-detail-lower")
        open("edit-booking"); shot("07-booking-form")
        compose.onNodeWithTag("add-flight").performScrollTo(); shot("08-booking-form-flights")
        back(); back()
        open("tab-customers"); shot("09-customers")
        val cust = onMain { it.agency!!.db.one("SELECT name FROM customers ORDER BY name LIMIT 1")!!.str("name") }
        open("customer-$cust"); shot("10-customer")
        back()
        open("tab-money"); shot("11-money")
        open("new-transaction"); shot("12-new-entry")
        back()
        open("tab-more"); shot("13-more")
        open("more-reports"); shot("14-reports")
        open("report-profit"); shot("15-report-profit")
        back(); back()
        compose.onNodeWithTag("more-list").performScrollToNode(hasTestTag("more-backup"))
        open("more-backup"); shot("16-backup")
        back()
        compose.onNodeWithTag("more-list").performScrollToNode(hasTestTag("more-settings"))
        open("more-settings"); shot("17-settings")
        assertTrue(File("build/screenshots/$shotPrefix-17-settings.png").length() > 10_000)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithText(text: String) =
        onNode(hasText(text), useUnmergedTree = false)
}

/** A small phone (360 x 640 dp): the same flows must fit and scroll. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class SmallPhoneDesignTest : DesignTest() { override val shotPrefix = "small" }

/** Dark theme (follows the phone setting). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class DarkDesignTest : DesignTest() { override val shotPrefix = "dark" }

/** Realistic volume: 400 bookings with payments; lists, the dashboard and reports stay usable. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VolumeDesignTest : DesignTest() {
    override val bookings = 400
    override val shotPrefix = "volume"
    @Test fun reportsShowEveryRow() {
        open("tab-more"); open("more-reports"); open("report-bookings")
        compose.onNodeWithTag("report-range-All dates").performClick()
        val n = onMain { it.agency!!.db.scalarLong("SELECT COUNT(*) FROM bookings") }
        assertEquals(400L, n)
        compose.onNodeWithTag("report-bookings").performScrollToNode(hasText("400 rows"))
        assertEquals(1, compose.onAllNodesWithTag("report-bookings").fetchSemanticsNodes().size)
    }
}
