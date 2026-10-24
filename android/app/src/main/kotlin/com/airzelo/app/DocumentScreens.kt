package com.airzelo.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.airzelo.engine.Dates
import com.airzelo.engine.JValue
import com.airzelo.engine.Ledger
import com.airzelo.engine.Report
import com.airzelo.engine.ReportParams
import com.airzelo.engine.cancelInvoice
import com.airzelo.engine.fmt
import com.airzelo.engine.getInvoice
import com.airzelo.engine.invoicePDF
import com.airzelo.engine.listInvoices
import com.airzelo.engine.report

private val REPORT_TITLES = listOf("profit" to "Profit", "sales" to "Sales", "bookings" to "Bookings",
    "customer_outstanding" to "Customer outstanding", "supplier_outstanding" to "Supplier outstanding", "expenses" to "Expenses",
    "cashbook" to "Cashbook (all accounts)", "refunds" to "Refunds & cancellations", "commission" to "Commission income",
    "monthly" to "Monthly summary", "yearly" to "Yearly summary", "airline" to "Airline-wise sales", "supplier_purchases" to "Supplier-wise purchases")

// ---- reports

@Composable
fun ReportListScreen(nav: NavHostController) {
    Screen("Reports", onBack = { nav.popBackStack() }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            items(REPORT_TITLES) { (name, title) -> ListRow({ nav.navigate("report/$name") }, tag = "report-$name") { Text(title) } }
        }
    }
}

/** One of the desktop's 13 reports, from the same ledger as the dashboard; the CSV is the desktop's export format. */
@Composable
fun ReportScreen(model: AppModel, name: String, accountId: Long?, onBack: () -> Unit) {
    val a = model.agency ?: return
    val ctx = LocalContext.current
    val usesRange = name !in listOf("customer_outstanding", "supplier_outstanding", "monthly", "yearly")
    var from by rememberSaveable { mutableStateOf(Dates.today().take(5) + "01-01") }
    var to by rememberSaveable { mutableStateOf(Dates.today()) }
    var allTime by rememberSaveable { mutableStateOf(accountId != null || name == "refunds") }
    val result = remember(model.revision, from, to, allTime) {
        val p = HashMap<String, String>()
        accountId?.let { p["account_id"] = it.toString() }
        if (usesRange && !allTime) { p["from"] = from; p["to"] = to }
        if (name == "monthly") p["year"] = to.take(4)
        runCatching { Ledger(a).report(name, ReportParams(p)) }
    }
    val r: Report? = result.getOrNull()
    Screen(r?.title ?: "Report", onBack, actions = {
        if (r != null) TextButton(onClick = {
            runCatching { Share.send(ctx, Share.file(ctx, "${r.name}-${Dates.today()}.csv", r.csv.toByteArray()), "text/csv") }.onFailure { model.fail(it) }
        }, modifier = Modifier.testTag("share-csv")) { Text("CSV", color = Color.White) }
    }) { pad ->
        Page(pad, "report-$name") {
            if (usesRange) Section {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allTime, { allTime = it }); Text("All dates") }
                if (!allTime) {
                    Field("From (YYYY-MM-DD)", from, { from = it })
                    Field("To (YYYY-MM-DD)", to, { to = it })
                }
            }
            result.exceptionOrNull()?.let { Text(AppModel.describe(it), color = Bad) }
            if (r == null) return@Page
            r.note?.let { Note(it) }
            r.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
                Section("Summary") {
                    for (s in summary) {
                        if (s["raw"] == JValue.Bool(true)) LabeledValue(s["label"].text, fmt(s["value"].num))
                        else FigureRow(s["label"].text, s["value"].num, r.base, strong = s["strong"] == JValue.Bool(true))
                    }
                }
            }
            val totals = r.columns.filter { r.totals.containsKey(it.key) }
            if (totals.isNotEmpty()) Section("Totals") {
                for (c in totals) {
                    if (c.type == "money") FigureRow(c.label, r.totals[c.key].num, r.base, tag = "total-${c.key}")
                    else LabeledValue(c.label, if (c.isMoney) fmt(r.totals[c.key].num) else r.totals[c.key].text)
                }
            }
            Section("${r.rows.size} rows") {
                for (row in r.rows.take(300)) ReportRow(row, r)
                if (r.rows.size > 300) Note("Showing 300 of ${r.rows.size}. Share the CSV for all rows.")
            }
        }
    }
}

@Composable
private fun ReportRow(row: Map<String, JValue>, r: Report) {
    val text = r.columns.filter { !it.isMoney }
    val money = r.columns.filter { it.isMoney }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text.take(3).map { row[it.key].text }.filter { it.isNotEmpty() }.joinToString(" · "), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (text.size > 3) Note(text.drop(3).map { row[it.key].text }.filter { it.isNotEmpty() }.joinToString(" · "))
        for (c in money) {
            val v = row[c.key]?.long ?: continue
            if (v == 0L) continue
            Row { Note(c.label); Text("", Modifier.weight(1f)); Text(if (c.type == "money_raw") fmt(v) else Fmt.money(v, r.base), fontSize = 12.sp) }
        }
    }
}

// ---- invoices

@Composable
fun InvoiceListScreen(model: AppModel, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    val a = model.agency ?: return
    val rows = remember(model.revision, query) { a.listInvoices(mapOf("q" to query)) }
    Screen("Invoices", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Field("Search number, booking, PNR, customer", query, { query = it }) }
            LazyColumn {
                items(rows, key = { it.long("id") }) { inv ->
                    ListRow({ nav.navigate("invoice/${inv.long("id")}") }, tag = "invoice-${inv.str("number")}") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(inv.str("number"), fontWeight = FontWeight.Bold)
                                Note("${inv.str("customer_name")} · ${inv.str("booking_ref")} · ${inv.str("issue_date")}")
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                MoneyText(inv.long("total"), inv.str("currency"), small = true)
                                if (inv.str("status") == "cancelled") StatusBadge("cancelled")
                            }
                        }
                    }
                }
                if (rows.isEmpty()) item { Note("  No invoices yet. Issue one from a confirmed booking.") }
            }
        }
    }
}

@Composable
fun InvoiceDetailScreen(model: AppModel, invoiceId: Long, onBack: () -> Unit) {
    val a = model.agency ?: return
    val doc = remember(model.revision, invoiceId) { runCatching { a.invoicePDF(invoiceId) }.onFailure { model.fail(it) }.getOrNull() }
    val status = remember(model.revision, invoiceId) { runCatching { a.getInvoice(invoiceId).row.str("status") }.getOrDefault("") }
    var cancelling by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    if (doc == null) { Screen("Invoice", onBack) {}; return }
    PdfView(model, doc, onBack, extraAction = {
        if (status == "issued") TextButton(onClick = { cancelling = true }, modifier = Modifier.testTag("cancel-invoice")) { Text("Cancel", color = Color.White) }
    })
    if (cancelling) ConfirmDialog("Cancel this document?", "Issued documents are never deleted; it will be marked cancelled with your reason.",
        "Cancel document", onDismiss = { cancelling = false }, onConfirm = {
            cancelling = false
            model.attempt(mapOf("reason" to JValue.Str(reason)), { ag, f -> ag.cancelInvoice(invoiceId, f) }) { model.changed() }
        }) { Field("Reason", reason, { reason = it }) }
}

// ---- PDF preview and sharing

@Composable
fun PdfScreen(model: AppModel, onBack: () -> Unit) {
    val doc = model.pdf
    if (doc == null) { LaunchedEffect(Unit) { onBack() }; return }
    PdfView(model, doc, onBack)
}

@Composable
private fun PdfView(model: AppModel, doc: com.airzelo.engine.PdfFile, onBack: () -> Unit, extraAction: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val pages = remember(doc) { runCatching { render(ctx, doc) }.getOrDefault(emptyList()) }
    Screen(doc.fileName.removeSuffix(".pdf").replace("_", " "), onBack, actions = {
        extraAction()
        TextButton(onClick = {
            runCatching { Share.send(ctx, Share.file(ctx, doc.fileName, doc.data), "application/pdf") }.onFailure { model.fail(it) }
        }, modifier = Modifier.testTag("share-pdf")) { Text("Share", color = Color.White) }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().background(Color(0xFFE9EDF3)).testTag("pdf-view"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Note("  ${doc.fileName} · ${doc.data.size / 1024 + 1} KB · made on this phone") }
            items(pages) { bmp -> Image(bmp.asImageBitmap(), contentDescription = "PDF page", modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) }
        }
    }
}

/** Renders each page with Android's PdfRenderer from a private cache file (deleted straight after). */
private fun render(ctx: android.content.Context, doc: com.airzelo.engine.PdfFile): List<Bitmap> {
    val f = java.io.File(ctx.cacheDir, "preview.pdf")
    f.writeBytes(doc.data)
    try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                return (0 until r.pageCount).map { i ->
                    r.openPage(i).use { p ->
                        val scale = 2
                        val bmp = Bitmap.createBitmap(p.width * scale, p.height * scale, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }
    } finally {
        f.delete()
    }
}
