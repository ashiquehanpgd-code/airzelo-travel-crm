package com.airzelo.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AssignmentReturn
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airzelo.app.ui.Az
import com.airzelo.app.ui.AzCard
import com.airzelo.app.ui.AzField
import com.airzelo.app.ui.AzScreen
import com.airzelo.app.ui.Banner
import com.airzelo.app.ui.ConfirmDialog
import com.airzelo.app.ui.DateField
import com.airzelo.app.ui.EmptyState
import com.airzelo.app.ui.FigureRow
import com.airzelo.app.ui.FilterRow
import com.airzelo.app.ui.Fmt
import com.airzelo.app.ui.IconChip
import com.airzelo.app.ui.Labels
import com.airzelo.app.ui.ListRow
import com.airzelo.app.ui.LoadingState
import com.airzelo.app.ui.Money
import com.airzelo.app.ui.Muted
import com.airzelo.app.ui.Overline
import com.airzelo.app.ui.Pill
import com.airzelo.app.ui.SearchField
import com.airzelo.app.ui.Share
import com.airzelo.app.ui.Tone
import com.airzelo.app.ui.num
import com.airzelo.app.ui.text
import com.airzelo.engine.Dates
import com.airzelo.engine.JValue
import com.airzelo.engine.Ledger
import com.airzelo.engine.PdfFile
import com.airzelo.engine.Report
import com.airzelo.engine.ReportParams
import com.airzelo.engine.cancelInvoice
import com.airzelo.engine.fmt
import com.airzelo.engine.getInvoice
import com.airzelo.engine.invoicePDF
import com.airzelo.engine.listInvoices
import com.airzelo.engine.report

private class ReportInfo(val name: String, val title: String, val sub: String, val icon: ImageVector, val tone: Tone)
private val REPORTS = listOf(
    ReportInfo("profit", "Profit", "Gross & net profit by booking", Icons.Rounded.TrendingUp, Tone.Green),
    ReportInfo("sales", "Sales", "Gross bookings billed", Icons.Rounded.ShoppingCart, Tone.Blue),
    ReportInfo("bookings", "Bookings", "Status, payment, outstanding", Icons.Rounded.ConfirmationNumber, Tone.Cyan),
    ReportInfo("customer_outstanding", "Customer outstanding", "Who owes you", Icons.Rounded.Groups, Tone.Warn),
    ReportInfo("supplier_outstanding", "Supplier outstanding", "What you owe", Icons.Rounded.Storefront, Tone.Red),
    ReportInfo("expenses", "Expenses", "By category and account", Icons.Rounded.Payments, Tone.Red),
    ReportInfo("cashbook", "Cashbook", "Every account's money in and out", Icons.Rounded.MenuBook, Tone.Gray),
    ReportInfo("refunds", "Refunds & cancellations", "Credits, charges, cash", Icons.Rounded.AssignmentReturn, Tone.Violet),
    ReportInfo("commission", "Commission income", "Earned and reversed", Icons.Rounded.Percent, Tone.Cyan),
    ReportInfo("monthly", "Monthly summary", "Twelve months side by side", Icons.Rounded.CalendarMonth, Tone.Blue),
    ReportInfo("yearly", "Yearly summary", "Calendar or financial years", Icons.Rounded.CalendarToday, Tone.Blue),
    ReportInfo("airline", "Airline-wise sales", "By first-segment airline", Icons.Rounded.BarChart, Tone.Green),
    ReportInfo("supplier_purchases", "Supplier-wise purchases", "By portal or supplier", Icons.Rounded.Inventory2, Tone.Warn))

// ---- reports

@Composable
fun ReportListScreen(nav: NavHostController) {
    AzScreen("Reports", "The desktop's 13 reports, from the same books", onBack = { nav.popBackStack() }) {
        LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize().testTag("report-list"), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(REPORTS, key = { it.name }) { r ->
                AzCard(Modifier.fillMaxWidth().height(138.dp), onClick = { nav.navigate("report/${r.name}") }, tag = "report-${r.name}") {
                    IconChip(r.icon, r.tone)
                    Spacer(Modifier.height(10.dp))
                    Text(r.title, style = MaterialTheme.typography.titleSmall, color = Az.colors.text, maxLines = 2)
                    Muted(r.sub, maxLines = 2)
                }
            }
        }
    }
}

/** One of the desktop's 13 reports, from the same ledger as the dashboard; the CSV is the desktop's export format. */
@Composable
fun ReportScreen(model: AppModel, name: String, accountId: Long?, onBack: () -> Unit) {
    val a = model.agency ?: return
    val ctx = LocalContext.current
    val usesRange = name !in listOf("customer_outstanding", "supplier_outstanding", "monthly", "yearly")
    var preset by rememberSaveable { mutableStateOf(if (accountId != null || name == "refunds" || name == "cashbook") "all" else "year") }
    var from by rememberSaveable { mutableStateOf(Dates.today().take(5) + "01-01") }
    var to by rememberSaveable { mutableStateOf(Dates.today()) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var basisFy by rememberSaveable { mutableStateOf(false) }
    val range: Pair<String, String>? = when (preset) { "all" -> null; "custom" -> from to to; else -> presetRange(preset) }
    val result = remember(model.revision, range, showAll, basisFy, to) {
        val p = HashMap<String, String>()
        accountId?.let { p["account_id"] = it.toString() }
        if (usesRange && range != null) { p["from"] = range.first; p["to"] = range.second }
        if (name == "monthly") p["year"] = (range?.second ?: Dates.today()).take(4)
        if (showAll) p["show"] = "all"
        if (basisFy) p["basis"] = "fy"
        runCatching { Ledger(a).report(name, ReportParams(p)) }
    }
    val r: Report? = result.getOrNull()
    val c = Az.colors
    AzScreen(r?.title ?: "Report", if (range != null && usesRange) "${Fmt.date(range.first)} – ${Fmt.date(range.second)}" else if (usesRange) "All dates" else null, onBack, actions = {
        if (r != null) IconButton(onClick = {
            runCatching { Share.send(ctx, Share.file(ctx, "${r.name}-${Dates.today()}.csv", r.csv.toByteArray()), "text/csv") }.onFailure { model.fail(it) }
        }, modifier = Modifier.testTag("share-csv")) { Icon(Icons.Rounded.Share, "Share CSV", tint = c.text) }
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("report-$name"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (usesRange) FilterRow(listOf("all" to "All dates", "month" to "This month", "fy" to "This FY", "year" to "This year", "custom" to "Custom"),
                        preset, { preset = it }, tag = "report-range")
                    if (name == "monthly") FilterRow(listOf("year" to "This year", "custom" to "Pick year"), if (preset == "custom") "custom" else "year", { preset = it })
                    if (name in listOf("customer_outstanding", "supplier_outstanding")) FilterRow(listOf(false to "With a balance", true to "Everyone"), showAll, { showAll = it })
                    if (name == "yearly") FilterRow(listOf(false to "Calendar years", true to "Financial years"), basisFy, { basisFy = it })
                    if (preset == "custom" && (usesRange || name == "monthly")) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (usesRange) DateField("From", from, { from = it }, modifier = Modifier.weight(1f))
                        DateField(if (name == "monthly") "Any date in the year" else "To", to, { to = it }, modifier = Modifier.weight(1f))
                    }
                }
            }
            result.exceptionOrNull()?.let { e -> item(key = "error") { Box(Modifier.padding(horizontal = 16.dp)) { Banner(AppModel.describe(e), Tone.Red) } } }
            if (r == null) return@LazyColumn
            r.note?.let { n -> item(key = "note") { Box(Modifier.padding(horizontal = 16.dp)) { Banner(n, Tone.Gray) } } }
            r.summary?.takeIf { it.isNotEmpty() }?.let { summary ->
                item(key = "summary") {
                    AzCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Overline("Summary")
                        for (s in summary) {
                            if (s["raw"] == JValue.Bool(true)) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(s["label"].text, color = c.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(fmt(s["value"].num), color = c.text, style = MaterialTheme.typography.bodyMedium) }
                            else FigureRow(s["label"].text, s["value"].num, r.base, strong = s["strong"] == JValue.Bool(true))
                        }
                    }
                }
            }
            val totals = r.columns.filter { r.totals.containsKey(it.key) }
            if (totals.isNotEmpty()) item(key = "totals") {
                AzCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Overline("Totals")
                    for (col in totals) {
                        if (col.type == "money") FigureRow(col.label, r.totals[col.key].num, r.base, tag = "total-${col.key}")
                        else Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(col.label, color = c.text2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(if (col.isMoney) fmt(r.totals[col.key].num) else r.totals[col.key].text, color = c.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item(key = "rows-title") { Text("${r.rows.size} rows", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.padding(horizontal = 20.dp)) }
            if (r.rows.isEmpty()) item(key = "rows-empty") { EmptyState(Icons.Rounded.Search, "Nothing in this report for the chosen dates") }
            itemsIndexed(r.rows, key = { i, _ -> "r$i" }) { _, row -> ReportRowCard(row, r) }
        }
    }
}

@Composable
private fun ReportRowCard(row: Map<String, JValue>, r: Report) {
    val text = r.columns.filter { !it.isMoney }
    val money = r.columns.filter { it.isMoney }
    val c = Az.colors
    // dates and statuses read as on the desktop: "13 May 2026", "Ticketed", "Part paid"
    fun show(col: com.airzelo.engine.ReportColumn): String {
        val v = row[col.key].text
        return when (col.type) {
            "date" -> if (v.isEmpty()) "" else Fmt.date(v)
            "status" -> Labels.status[v] ?: Labels.pay[v] ?: v
            "number" -> if (v.isEmpty()) "" else "$v ${col.label.lowercase()}"
            else -> v
        }
    }
    AzCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), padding = 14.dp) {
        Text(text.take(3).map { show(it) }.filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.titleSmall, color = c.text,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (text.size > 3) Muted(text.drop(3).map { show(it) }.filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 2)
        val shown = money.mapNotNull { col -> row[col.key]?.long?.takeIf { it != 0L }?.let { col to it } }
        if (shown.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = c.border)
            for ((col, v) in shown) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(col.label, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
                Text(if (col.type == "money_raw") fmt(v) else Fmt.money(v, r.base), style = MaterialTheme.typography.labelLarge,
                    color = if (v < 0) c.danger else c.text)
            }
        }
    }
}

// ---- invoices

@Composable
fun InvoiceListScreen(model: AppModel, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    val a = model.agency ?: return
    val rows = remember(model.revision, query, type) { a.listInvoices(mapOf("q" to query, "doc_type" to type)) }
    AzScreen("Invoices", "${rows.size} documents", onBack = { nav.popBackStack() }) {
        LazyColumn(Modifier.fillMaxSize().testTag("invoice-list"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.padding(horizontal = 16.dp)) { SearchField(query, { query = it }, "Search number, booking, PNR, customer") }
                    FilterRow(listOf("" to "All", "invoice" to "Invoices", "credit_note" to "Credit notes"), type, { type = it })
                }
            }
            if (rows.isEmpty()) item(key = "empty") { EmptyState(Icons.Rounded.ReceiptLong, "No invoices yet", "Issue one from a confirmed or ticketed booking.") }
            items(rows, key = { it.long("id") }) { inv ->
                AzCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), padding = 0.dp) {
                    ListRow(inv.str("number"), "${inv.str("customer_name")} · ${inv.str("booking_ref")}", meta = Fmt.date(inv.str("issue_date")),
                        onClick = { nav.navigate("invoice/${inv.long("id")}") }, tag = "invoice-${inv.str("number")}",
                        leading = { IconChip(Icons.Rounded.ReceiptLong, if (inv.str("doc_type") == "credit_note") Tone.Violet else Tone.Blue) }) {
                        Money(inv.long("total"), inv.str("currency"), MaterialTheme.typography.labelLarge)
                        if (inv.str("status") == "cancelled") Pill("Cancelled", Tone.Red)
                        else if (inv.str("doc_type") == "credit_note") Pill("Credit note", Tone.Violet)
                    }
                }
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
    if (doc == null) { AzScreen("Invoice", onBack = onBack) { LoadingState() }; return }
    PdfView(model, doc, onBack, extraAction = {
        if (status == "issued") TextButton(onClick = { cancelling = true }, modifier = Modifier.testTag("cancel-invoice")) { Text("Cancel", color = Az.colors.danger) }
    })
    if (cancelling) ConfirmDialog("Cancel this document?", "Issued documents are never deleted; it will be marked cancelled with your reason.",
        "Cancel document", onDismiss = { cancelling = false }, onConfirm = {
            cancelling = false
            model.attempt(mapOf("reason" to JValue.Str(reason)), { ag, f -> ag.cancelInvoice(invoiceId, f) }) { model.changed() }
        }) { AzField("Reason", reason, { reason = it }) }
}

// ---- PDF preview and sharing

@Composable
fun PdfScreen(model: AppModel, onBack: () -> Unit) {
    val doc = model.pdf
    if (doc == null) { LaunchedEffect(Unit) { onBack() }; return }
    PdfView(model, doc, onBack)
}

@Composable
private fun PdfView(model: AppModel, doc: PdfFile, onBack: () -> Unit, extraAction: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val pages = remember(doc) { runCatching { render(ctx, doc) }.getOrDefault(emptyList()) }
    AzScreen(doc.fileName.removeSuffix(".pdf").replace("_", " "), "${doc.data.size / 1024 + 1} KB · made on this phone", onBack, actions = {
        extraAction()
        IconButton(onClick = { runCatching { Share.send(ctx, Share.file(ctx, doc.fileName, doc.data), "application/pdf") }.onFailure { model.fail(it) } },
            modifier = Modifier.testTag("share-pdf")) { Icon(Icons.Rounded.Share, "Share PDF", tint = Az.colors.text) }
    }) {
        LazyColumn(Modifier.fillMaxSize().background(Az.colors.surface3).testTag("pdf-view"), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pages.isEmpty()) item { EmptyState(Icons.Rounded.ReceiptLong, "Preview not available", "Use Share to open or send the PDF.") }
            items(pages) { bmp -> Image(bmp.asImageBitmap(), contentDescription = "PDF page", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))) }
        }
    }
}

/** Renders each page with Android's PdfRenderer from a private cache file (deleted straight after). */
private fun render(ctx: android.content.Context, doc: PdfFile): List<Bitmap> {
    val f = java.io.File(ctx.cacheDir, "preview.pdf")
    f.writeBytes(doc.data)
    try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                return (0 until r.pageCount).map { i ->
                    r.openPage(i).use { p ->
                        val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
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
