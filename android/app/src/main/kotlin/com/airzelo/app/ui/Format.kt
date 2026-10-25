package com.airzelo.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.airzelo.app.MainActivity
import com.airzelo.engine.Dates
import com.airzelo.engine.JValue
import com.airzelo.engine.KIND_LABELS
import com.airzelo.engine.fmt
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text of a JSON value for screens (null shows as empty). */
val JValue?.text: String get() = if (this == null || isNull) "" else py
val JValue?.num: Long get() = this?.long ?: 0

object Fmt {
    fun title(key: String): String = key.replace("_", " ").split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    fun amountText(minor: Long): String = if (minor == 0L) "" else fmt(minor)
    fun money(minor: Long, cur: String = "INR"): String = com.airzelo.engine.money(minor, cur)

    /** Short money for charts and tight tiles: 1.2Cr, 45.3L, 12.5K (Indian units for INR). */
    fun compact(minor: Long, cur: String = "INR"): String {
        val v = minor / 100.0
        val a = Math.abs(v)
        val sign = if (v < 0) "-" else ""
        fun f(x: Double) = if (x >= 100) String.format(Locale.ROOT, "%.0f", x) else String.format(Locale.ROOT, "%.1f", x).removeSuffix(".0")
        return sign + when {
            cur == "INR" && a >= 1e7 -> f(a / 1e7) + "Cr"
            cur == "INR" && a >= 1e5 -> f(a / 1e5) + "L"
            cur != "INR" && a >= 1e6 -> f(a / 1e6) + "M"
            a >= 1e3 -> f(a / 1e3) + "K"
            else -> String.format(Locale.ROOT, "%.0f", a)
        }
    }

    private val dayFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
    private val shortFmt = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)
    private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val longFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

    private fun parse(iso: String?): LocalDate? = runCatching { LocalDate.parse((iso ?: "").take(10)) }.getOrNull()
    /** "25 Oct 2026" (the text as typed when it is not a date). */
    fun date(iso: String?): String = parse(iso)?.format(dayFmt) ?: (iso ?: "")
    fun dateShort(iso: String?): String = parse(iso)?.format(shortFmt) ?: (iso ?: "")
    fun month(iso: String?): String = parse(iso)?.format(monthFmt) ?: ""
    fun longDate(iso: String?): String = parse(iso)?.format(longFmt) ?: ""
    /** "04:10" from "2026-11-02T04:10". */
    fun time(dt: String?): String = if (dt != null && dt.length >= 16) dt.substring(11, 16) else ""
    fun relativeDay(iso: String?): String {
        val d = parse(iso) ?: return ""
        val today = LocalDate.parse(Dates.today())
        return when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; today.plusDays(1) -> "Tomorrow"; else -> d.format(dayFmt) }
    }
}

/** The desktop's labels and badge colours (core/static/js/core.js QT.labels). */
object Labels {
    val status = mapOf("enquiry" to "Enquiry", "on_hold" to "On hold", "confirmed" to "Confirmed", "ticketed" to "Ticketed",
        "cancelled" to "Cancelled", "refunded" to "Refunded")
    val pay = mapOf("paid" to "Paid", "partial" to "Part paid", "unpaid" to "Unpaid", "overpaid" to "Overpaid / credit", "settled" to "Settled",
        "refund_due" to "Refund due", "advance" to "Advance held", "not_billed" to "Not billed")
    val service = mapOf("flight" to "Flight", "visa" to "Visa", "hotel" to "Hotel", "package" to "Tour package", "umrah" to "Umrah", "other" to "Other")
    val method = mapOf("cash" to "Cash", "bank_transfer" to "Bank transfer", "upi" to "UPI", "card" to "Card", "cheque" to "Cheque",
        "payment_gateway" to "Payment gateway", "portal_wallet" to "Portal wallet", "adjustment" to "Adjustment", "other" to "Other")
    val supplierKind = mapOf("b2b_portal" to "B2B portal", "airline" to "Airline", "consolidator" to "Consolidator", "hotel" to "Hotel",
        "visa" to "Visa agency", "other" to "Other")
    val adj = mapOf("cancellation" to "Cancellation", "refund" to "Refund", "reissue" to "Reissue", "date_change" to "Date change", "other" to "Other")
    val accType = mapOf("cash" to "Cash in hand", "bank" to "Bank account", "current" to "Current account", "savings" to "Savings account",
        "upi" to "UPI / payment account", "credit_card" to "Credit card", "wallet" to "Wallet / portal balance", "gateway" to "Payment gateway")
    val pax = mapOf("ADT" to "Adult", "CHD" to "Child", "INF" to "Infant")
    fun kind(k: String) = KIND_LABELS[k] ?: title(k)
    fun title(k: String) = Fmt.title(k)

    fun statusTone(s: String) = when (s) { "on_hold" -> Tone.Warn; "confirmed" -> Tone.Blue; "ticketed" -> Tone.Green; "cancelled" -> Tone.Red
        "refunded" -> Tone.Violet; else -> Tone.Gray }
    fun payTone(s: String) = when (s) { "paid" -> Tone.Green; "partial" -> Tone.Warn; "unpaid" -> Tone.Red; "overpaid", "refund_due" -> Tone.Violet
        "advance" -> Tone.Cyan; else -> Tone.Gray }
    val inflow = setOf("customer_receipt", "supplier_receipt", "other_income", "loan_received")
}

/** Files shared from the app's cache through its FileProvider (old shared files are removed first: they can hold customer data). */
object Share {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "shared").also { it.mkdirs() }

    fun file(ctx: Context, name: String, data: ByteArray): File {
        val d = dir(ctx)
        d.listFiles()?.forEach { it.delete() }
        return File(d, name.replace(Regex("[^A-Za-z0-9._+-]"), "_")).also { it.writeBytes(data) }
    }

    fun send(ctx: Context, file: File, mime: String) {
        MainActivity.expectingExternal = true
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
