package com.airzelo.app

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AssignmentReturn
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airzelo.app.ui.ActionTile
import com.airzelo.app.ui.AmountField
import com.airzelo.app.ui.Avatar
import com.airzelo.app.ui.Az
import com.airzelo.app.ui.AzCard
import com.airzelo.app.ui.AzField
import com.airzelo.app.ui.AzScreen
import com.airzelo.app.ui.Banner
import com.airzelo.app.ui.DateField
import com.airzelo.app.ui.DateTimeField
import com.airzelo.app.ui.Dropdown
import com.airzelo.app.ui.EmptyState
import com.airzelo.app.ui.FigureRow
import com.airzelo.app.ui.FilterRow
import com.airzelo.app.ui.Fmt
import com.airzelo.app.ui.FormSection
import com.airzelo.app.ui.HeroCard
import com.airzelo.app.ui.IconChip
import com.airzelo.app.ui.Labels
import com.airzelo.app.ui.ListRow
import com.airzelo.app.ui.LoadingState
import com.airzelo.app.ui.Money
import com.airzelo.app.ui.Muted
import com.airzelo.app.ui.Overline
import com.airzelo.app.ui.PayPill
import com.airzelo.app.ui.Pill
import com.airzelo.app.ui.PrimaryButton
import com.airzelo.app.ui.RowDivider
import com.airzelo.app.ui.SearchField
import com.airzelo.app.ui.SecondaryButton
import com.airzelo.app.ui.SectionTitle
import com.airzelo.app.ui.StatusPill
import com.airzelo.app.ui.Tone
import com.airzelo.app.ui.num
import com.airzelo.app.ui.text
import com.airzelo.engine.BOOKING_STATUSES
import com.airzelo.engine.Dates
import com.airzelo.engine.FieldCrypto
import com.airzelo.engine.Fields
import com.airzelo.engine.JValue
import com.airzelo.engine.Ledger
import com.airzelo.engine.SERVICE_TYPES
import com.airzelo.engine.bookingEditFields
import com.airzelo.engine.createInvoice
import com.airzelo.engine.getBooking
import com.airzelo.engine.refundBooking
import com.airzelo.engine.saveBooking
import com.airzelo.engine.searchBookings
import com.airzelo.engine.setBookingStatus
import com.airzelo.engine.ticketPDF
import java.util.UUID

// ---- list

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookingListScreen(model: AppModel, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    val a = model.agency ?: return
    val rows = remember(model.revision, query, status) {
        val r = a.searchBookings(mapOf("q" to query, "status" to status))
        if (query.isEmpty()) r.sortedWith(compareByDescending<Map<String, JValue>> { it["booking_date"].text }.thenByDescending { it["id"].num }) else r
    }
    // sticky month headers when browsing; one flat list when searching (best match first)
    val groups = remember(rows, query) { if (query.isEmpty()) rows.groupBy { Fmt.month(it["booking_date"].text).ifEmpty { "No date" } }.toList() else listOf("" to rows) }
    val listState = rememberLazyListState()
    AzScreen("Bookings", "${rows.size} ${if (rows.size == 1) "booking" else "bookings"}", actions = {
        IconButton(onClick = { nav.navigate("bookingForm/0") }, modifier = Modifier.testTag("new-booking")) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Az.colors.royal), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, "New booking", tint = Color.White)
            }
        }
    }) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("booking-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "search") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SearchField(query, { query = it }, "Search PNR, name, ticket no., phone", tag = "booking-search")
                }
                FilterRow(listOf("" to "All") + BOOKING_STATUSES.map { it to Labels.status[it]!! }, status, { status = it }, tag = "status-filter")
                Spacer(Modifier.height(6.dp))
            }
            if (rows.isEmpty()) item(key = "empty") {
                EmptyState(if (query.isEmpty()) Icons.Rounded.ConfirmationNumber else Icons.Rounded.Search,
                    if (query.isEmpty() && status.isEmpty()) "No bookings yet" else "No saved booking matches",
                    if (query.isEmpty() && status.isEmpty()) "Record a flight, visa, hotel or package booking." else "Try a PNR, a passenger name or a ticket number.",
                    if (query.isEmpty() && status.isEmpty()) "New booking" else null) { nav.navigate("bookingForm/0") }
            }
            for ((month, list) in groups) {
                if (month.isNotEmpty()) stickyHeader(key = "h-$month") { MonthHeader(month, list.size) }
                items(list, key = { it["id"].num }) { r ->
                    BookingCardRow(r) { nav.navigate("booking/${r["id"].num}") }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: String, count: Int) {
    Row(Modifier.fillMaxWidth().background(Az.colors.bg.copy(alpha = 0.96f)).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(month, style = MaterialTheme.typography.titleSmall, color = Az.colors.text, modifier = Modifier.weight(1f))
        Muted("$count")
    }
}

@Composable
fun BookingCardRow(r: Map<String, JValue>, onClick: () -> Unit) {
    val c = Az.colors
    AzCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), onClick = onClick, tag = "booking-${r["ref"].text}", padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(r["customer_name"].text, Tone.Blue, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r["customer_name"].text, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(listOf(r["ref"].text, r["pnr"].text).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1)
            }
            StatusPill(r["status"].text)
        }
        val route = r["route"].text
        if (route.isNotEmpty() || r["travel_date"].text.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FlightTakeoff, null, tint = c.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(listOf(route.replace("-", " → "), r["travel_date"].text.let { if (it.isEmpty()) "" else Fmt.dateShort(it) }).filter { it.isNotEmpty() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r["committed"].num == 1L) PayPill(r["payment_status"].text)
            }
        }
        if (r["customer_outstanding"].num > 0) {
            Spacer(Modifier.height(8.dp))
            Row { Muted("Due", Modifier.weight(1f)); Money(r["customer_outstanding"].num, style = MaterialTheme.typography.labelLarge, color = c.danger) }
        }
    }
}

// ---- detail

@Composable
fun BookingDetailScreen(model: AppModel, nav: NavHostController, bookingId: Long) {
    val a = model.agency ?: return
    val d = remember(model.revision, bookingId) { runCatching { a.getBooking(bookingId) }.getOrNull() }
    var statusMenu by remember { mutableStateOf(false) }
    val c = Az.colors
    AzScreen(d?.booking?.ref ?: "Booking", d?.booking?.let { Labels.service[it.serviceType] ?: "" }, onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = { nav.navigate("bookingForm/$bookingId") }, modifier = Modifier.testTag("edit-booking")) { Icon(Icons.Rounded.Edit, "Edit booking", tint = c.text) }
    }) {
        if (d == null) { LoadingState(); return@AzScreen }
        val b = d.booking; val cl = b.calc; val base = d.base
        LazyColumn(Modifier.fillMaxSize().testTag("booking-detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "hero") {
                HeroCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.customerName, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("PNR ${b.pnr?.ifEmpty { null } ?: "-"}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f))
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusPill(b.status, tag = "booking-status")
                            PayPill(cl.paymentStatus, tag = "payment-status")
                        }
                    }
                    if (b.route.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(b.route.replace("-", "  →  "), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Text(listOf(b.airlines.joinToString(", "), b.travelDate?.let { Fmt.date(it) } ?: "").filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        HeroFigure("Billed", Fmt.money(cl.customerPayable - cl.customerCredits, base), Modifier.weight(1f))
                        HeroFigure(if (cl.customerOutstanding >= 0) "Due" else "Due back", Fmt.money(Math.abs(cl.customerOutstanding), base), Modifier.weight(1f))
                        HeroFigure("Profit", Fmt.money(cl.grossProfit, base), Modifier.weight(1f))
                    }
                }
            }
            if (d.duplicates.isNotEmpty()) item(key = "dup") { Banner("Same PNR in ${d.duplicates.joinToString(", ") { it.str("ref") }}", Tone.Warn, Icons.Rounded.WarningAmber) }
            item(key = "actions") {
                val outstanding = maxOf(cl.customerOutstanding, 0)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("booking-actions")) {
                    item { ActionTile("Record payment", Icons.Rounded.ArrowDownward, Tone.Green, "record-payment") {
                        nav.navigate("txnForm?kind=customer_receipt&booking=$bookingId&customer=${b.customerId}&supplier=0&amount=$outstanding") } }
                    if (b.supplierId != null) item { ActionTile("Pay supplier", Icons.Rounded.ArrowUpward, Tone.Warn, "record-supplier-payment") {
                        nav.navigate("txnForm?kind=supplier_payment&booking=$bookingId&customer=0&supplier=${b.supplierId}&amount=${maxOf(cl.supplierOutstanding, 0)}") } }
                    item { ActionTile("Ticket PDF", Icons.Rounded.PictureAsPdf, Tone.Blue, "ticket-pdf") {
                        model.attempt(body = { ag, _ -> ag.ticketPDF(bookingId, false) }) { model.pdf = it; nav.navigate("pdf") } } }
                    item { ActionTile("Ticket + payment", Icons.Rounded.PictureAsPdf, Tone.Cyan, "ticket-payment-pdf") {
                        model.attempt(body = { ag, _ -> ag.ticketPDF(bookingId, true) }) { model.pdf = it; nav.navigate("pdf") } } }
                    if (b.committed && d.invoices.none { it.str("doc_type") == "invoice" && it.str("status") == "issued" }) item {
                        ActionTile("Issue invoice", Icons.Rounded.ReceiptLong, Tone.Violet, "issue-invoice") {
                            model.attempt(body = { ag, _ -> ag.createInvoice(bookingId) }) { model.changed() } } }
                    if (b.committed) item { ActionTile("Refund / cancel", Icons.Rounded.AssignmentReturn, Tone.Red, "refund") { nav.navigate("refund/$bookingId") } }
                    if (!b.committed) item { Box {
                        ActionTile("Change status", Icons.Rounded.Flag, Tone.Gray, "change-status") { statusMenu = true }
                        DropdownMenu(statusMenu, { statusMenu = false }) {
                            for (s in listOf("confirmed", "ticketed", "on_hold", "enquiry", "cancelled")) DropdownMenuItem(text = { Text(Labels.status[s]!!) },
                                modifier = Modifier.testTag("status-$s"),
                                onClick = { statusMenu = false; model.attempt(body = { ag, _ -> ag.setBookingStatus(bookingId, s) }) { model.changed() } })
                        }
                    } } else if (b.status == "confirmed") item {
                        ActionTile("Mark ticketed", Icons.Rounded.CheckCircle, Tone.Green, "mark-ticketed") {
                            model.attempt(body = { ag, _ -> ag.setBookingStatus(bookingId, "ticketed") }) { model.changed() } } }
                }
            }
            item(key = "customer") {
                AzCard(Modifier.fillMaxWidth()) {
                    Overline("Customer")
                    FigureRow("Billed", cl.customerPayable - cl.customerCredits, base)
                    FigureRow("Received", cl.received - cl.refunded, base)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.border)
                    FigureRow(if (cl.customerOutstanding >= 0) "Outstanding" else "Due back to customer", Math.abs(cl.customerOutstanding), base, strong = true,
                        tone = if (cl.customerOutstanding > 0) c.danger else c.success, tag = "customer-outstanding")
                }
            }
            item(key = "supplier") {
                AzCard(Modifier.fillMaxWidth()) {
                    Overline("Supplier" + (if (b.supplierName.isEmpty()) "" else " · " + b.supplierName))
                    FigureRow("Payable (after commission)", cl.supplierPayable - cl.supplierCredits, base)
                    FigureRow("Paid", cl.paidSupplier - cl.supplierRefunds, base)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.border)
                    FigureRow(if (cl.supplierOutstanding >= 0) "Outstanding" else "Due from supplier", Math.abs(cl.supplierOutstanding), base, strong = true)
                }
            }
            item(key = "profit") {
                AzCard(Modifier.fillMaxWidth()) {
                    Overline("Profit")
                    if (b.currency != base) Muted("${b.currency} at ${b.row.str("exchange_rate")} = 1 ${b.currency}")
                    FigureRow("Revenue (ex tax)", cl.revenue, base)
                    FigureRow("Cost", cl.cost, base)
                    FigureRow("Commission", cl.commissionNet, base)
                    if (cl.directExpenses != 0L) FigureRow("Direct expenses", cl.directExpenses, base)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.border)
                    FigureRow("Gross profit", cl.grossProfit, base, strong = true, tone = if (cl.grossProfit < 0) c.danger else c.success)
                    if (cl.taxNet != 0L) FigureRow("Tax collected (liability)", cl.taxNet, base)
                }
            }
            item(key = "pax") {
                AzCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    SectionTitle("Passengers (${d.passengers.size})", Modifier.padding(horizontal = 16.dp))
                    d.passengers.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        ListRow(listOf(p["title"].text, p["name"].text).filter { it.isNotEmpty() }.joinToString(" "),
                            listOf(Labels.pax[p["pax_type"].text] ?: "", if (p["ticket_number"].text.isEmpty()) "No ticket no." else "Ticket " + p["ticket_number"].text)
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            meta = if (p["passport_no"].text.isEmpty()) "" else "Passport " + FieldCrypto.display(p["passport_no"].text),
                            leading = { Avatar(p["name"].text, Tone.Cyan) })
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (d.segments.isNotEmpty()) item(key = "flights") {
                AzCard(Modifier.fillMaxWidth()) {
                    Overline("Flights")
                    Spacer(Modifier.height(6.dp))
                    d.segments.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = c.border)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconChip(Icons.Rounded.Flight, Tone.Blue, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${s.str("origin")} → ${s.str("destination")}", style = MaterialTheme.typography.titleSmall, color = c.text)
                                Muted(listOf("${s.str("airline")} ${s.str("flight_no")}".trim(), if (s.str("airline_pnr").isEmpty()) "" else "Airline PNR " + s.str("airline_pnr"))
                                    .filter { it.isNotEmpty() }.joinToString(" · "))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Fmt.dateShort(s.str("dep_at")), style = MaterialTheme.typography.labelLarge, color = c.text)
                                Muted(listOf(Fmt.time(s.str("dep_at")), Fmt.time(s.str("arr_at"))).filter { it.isNotEmpty() }.joinToString(" – "))
                            }
                        }
                    }
                }
            }
            if (d.invoices.isNotEmpty()) item(key = "invoices") {
                AzCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    SectionTitle("Invoices & credit notes", Modifier.padding(horizontal = 16.dp))
                    d.invoices.forEachIndexed { i, inv ->
                        if (i > 0) RowDivider()
                        ListRow(inv.str("number"), "${if (inv.str("doc_type") == "credit_note") "Credit note" else "Invoice"} · ${Fmt.date(inv.str("issue_date"))}",
                            onClick = { nav.navigate("invoice/${inv.long("id")}") }, leading = { IconChip(Icons.Rounded.ReceiptLong, Tone.Violet) }) {
                            Money(inv.long("total"), inv.str("currency"), MaterialTheme.typography.labelLarge)
                            if (inv.str("status") == "cancelled") Pill("Cancelled", Tone.Red)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (d.transactions.isNotEmpty()) item(key = "money") {
                AzCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    SectionTitle("Money", Modifier.padding(horizontal = 16.dp))
                    d.transactions.forEachIndexed { i, t -> if (i > 0) RowDivider(); TransactionRow(t) { nav.navigate("txn/${t["id"].num}") } }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (d.adjustments.isNotEmpty()) item(key = "adj") {
                AzCard(Modifier.fillMaxWidth()) {
                    Overline("Adjustments")
                    for (adj in d.adjustments) Column(Modifier.padding(vertical = 6.dp)) {
                        Text("${adj.adjNo} · ${Labels.adj[adj.kind] ?: Labels.title(adj.kind)}" + (if (adj.sign < 0) " (reversal)" else ""),
                            style = MaterialTheme.typography.titleSmall, color = c.text)
                        Muted("${Fmt.date(adj.adjDate)} · customer credit ${Fmt.money(adj["customer_credit"], base)} · supplier credit ${Fmt.money(adj["supplier_credit"], base)}")
                    }
                }
            }
            item(key = "history") {
                var expanded by rememberSaveable { mutableStateOf(false) }
                AzCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, tint = c.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text("History (${d.history.size})", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
                    }
                    if (expanded) for (h in d.history.take(50)) Column(Modifier.padding(vertical = 5.dp)) {
                        Text(h.str("detail"), style = MaterialTheme.typography.bodySmall, color = c.text2)
                        Muted(h.str("at").replace("T", " "))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroFigure(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- form

private class PaxDraft(title: String = "", name: String = "", type: String = "ADT", ticket: String = "", passport: String = "", val notes: String = "") {
    val key = UUID.randomUUID().toString()
    var title by mutableStateOf(title); var name by mutableStateOf(name); var type by mutableStateOf(type)
    var ticket by mutableStateOf(ticket); var passport by mutableStateOf(passport)
}

private class SegDraft(direction: String = "outbound", airline: String = "", flightNo: String = "", origin: String = "", destination: String = "",
                       dep: String = "", arr: String = "", baggage: String = "", airlinePnr: String = "", val cabin: String = "") {
    val key = UUID.randomUUID().toString()
    var direction by mutableStateOf(direction); var airline by mutableStateOf(airline); var flightNo by mutableStateOf(flightNo)
    var origin by mutableStateOf(origin); var destination by mutableStateOf(destination); var dep by mutableStateOf(dep); var arr by mutableStateOf(arr)
    var baggage by mutableStateOf(baggage); var airlinePnr by mutableStateOf(airlinePnr)
}

/**
 * New or edited booking. An edit starts from bookingEditFields (every stored field, manual tax included) and only
 * the fields shown here are replaced, so re-saving never loses anything the form does not show. The form is one
 * (non-lazy) scrolling column: no field is ever disposed while typing or scrolling.
 */
@Composable
fun BookingFormScreen(model: AppModel, bookingId: Long?, onDone: () -> Unit) {
    val a = model.agency ?: return
    val base = a.baseCurrency
    val customers = remember { a.db.query("SELECT id, name FROM customers WHERE active = 1 ORDER BY name COLLATE NOCASE") }
    val suppliers = remember { a.db.query("SELECT id, name FROM suppliers WHERE active = 1 ORDER BY name COLLATE NOCASE") }
    val currencies = remember { a.db.query("SELECT code FROM currencies WHERE active = 1 ORDER BY CASE code WHEN 'INR' THEN 0 WHEN 'SAR' THEN 1 ELSE 2 END, code") }
    val taxProfiles = remember { a.db.query("SELECT id, name FROM tax_profiles WHERE active = 1 AND basis <> 'none' ORDER BY id") }
    val original = remember { bookingId?.let { a.bookingEditFields(it) } ?: LinkedHashMap() }
    val row = remember { bookingId?.let { a.getBooking(it) } }
    val r = row?.booking?.row
    fun money(k: String) = r?.long(k)?.let { Fmt.amountText(it) } ?: ""

    var customerId by rememberSaveable { mutableStateOf(r?.long("customer_id") ?: 0L) }
    var newName by rememberSaveable { mutableStateOf("") }
    var newPhone by rememberSaveable { mutableStateOf("") }
    var supplierId by rememberSaveable { mutableStateOf(r?.optLong("supplier_id") ?: 0L) }
    var status by rememberSaveable { mutableStateOf(r?.str("status") ?: "confirmed") }
    var serviceType by rememberSaveable { mutableStateOf(r?.str("service_type") ?: "flight") }
    var tripType by rememberSaveable { mutableStateOf(r?.str("trip_type")?.ifEmpty { null } ?: "one_way") }
    var bookingDate by rememberSaveable { mutableStateOf(r?.str("booking_date") ?: Dates.today()) }
    var travelDate by rememberSaveable { mutableStateOf(r?.str("travel_date") ?: "") }
    var pnr by rememberSaveable { mutableStateOf(r?.str("pnr") ?: "") }
    var currency by rememberSaveable { mutableStateOf(r?.str("currency") ?: base) }
    var rate by rememberSaveable { mutableStateOf(if (r == null || r.str("currency") == base) "" else r.str("exchange_rate")) }
    var purchase by rememberSaveable { mutableStateOf(money("purchase_cost")) }
    var selling by rememberSaveable { mutableStateOf(money("selling_price")) }
    var commission by rememberSaveable { mutableStateOf(money("commission")) }
    var service by rememberSaveable { mutableStateOf(money("service_charge")) }
    var other by rememberSaveable { mutableStateOf(money("other_income")) }
    var taxProfileId by rememberSaveable { mutableStateOf(r?.optLong("tax_profile_id") ?: 0L) }
    var notes by rememberSaveable { mutableStateOf(r?.str("notes") ?: "") }
    val pax = remember {
        mutableStateListOf<PaxDraft>().apply {
            row?.passengers?.forEach { p -> add(PaxDraft(p["title"].text, p["name"].text, p["pax_type"].text.ifEmpty { "ADT" }, p["ticket_number"].text, p["passport_no"].text, p["notes"].text)) }
            if (isEmpty()) add(PaxDraft())
        }
    }
    val segs = remember {
        mutableStateListOf<SegDraft>().apply {
            row?.segments?.forEach { s -> add(SegDraft(s.str("direction"), s.str("airline"), s.str("flight_no"), s.str("origin"), s.str("destination"),
                s.str("dep_at"), s.str("arr_at"), s.str("baggage"), s.str("airline_pnr"), s.str("cabin"))) }
        }
    }

    fun fields(): Fields {
        val f = LinkedHashMap(original)
        for ((k, v) in listOf("status" to status, "service_type" to serviceType, "trip_type" to tripType, "booking_date" to bookingDate,
            "travel_date" to travelDate, "pnr" to pnr, "currency" to currency, "exchange_rate" to rate, "purchase_cost" to purchase,
            "selling_price" to selling, "commission" to commission, "service_charge" to service, "other_income" to other, "notes" to notes)) f[k] = JValue.Str(v)
        f["supplier_id"] = if (supplierId == 0L) JValue.Null else JValue.Int(supplierId)
        f["tax_profile_id"] = if (taxProfileId == 0L) JValue.Null else JValue.Int(taxProfileId)
        if (customerId == 0L) { f.remove("customer_id"); f["new_customer"] = JValue.obj("name" to newName, "phone" to newPhone) }
        else { f.remove("new_customer"); f["customer_id"] = JValue.Int(customerId) }
        f["passengers"] = JValue.arr(pax.map { p -> mapOf("title" to p.title, "name" to p.name, "pax_type" to p.type, "ticket_number" to p.ticket,
            "passport_no" to p.passport, "notes" to p.notes) })
        f["segments"] = JValue.arr(segs.map { s -> mapOf("direction" to s.direction, "airline" to s.airline, "flight_no" to s.flightNo, "origin" to s.origin,
            "destination" to s.destination, "airline_pnr" to s.airlinePnr, "baggage" to s.baggage, "cabin" to s.cabin,
            "dep_at" to s.dep.ifEmpty { null }, "arr_at" to s.arr.ifEmpty { null }) })
        return f
    }

    fun save() = model.attempt(fields(), { ag, f -> ag.saveBooking(f, bookingId) }) { model.changed(); onDone() }

    AzScreen(if (bookingId == null) "New booking" else "Edit booking", if (bookingId == null) null else r?.str("ref"), onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-booking")) { Text("Save") }
    }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("booking-form"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FormSection("Customer", Icons.Rounded.Person) {
                Dropdown("Customer", customerId, listOf(0L to "+ New customer") + customers.map { it.long("id") to it.str("name") }, { customerId = it }, tag = "customer-picker")
                if (customerId == 0L) {
                    AzField("Name", newName, { newName = it }, tag = "new-customer-name")
                    AzField("Phone", newPhone, { newPhone = it }, tag = "new-customer-phone", keyboard = KeyboardType.Phone)
                }
            }
            FormSection("Booking", Icons.Rounded.ConfirmationNumber) {
                Dropdown("Status", status, BOOKING_STATUSES.map { it to Labels.status[it]!! }, { status = it }, tag = "status-picker")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dropdown("Service", serviceType, SERVICE_TYPES.map { it to (Labels.service[it] ?: it) }, { serviceType = it }, modifier = Modifier.weight(1f))
                    Dropdown("Trip", tripType, listOf("one_way" to "One way", "round_trip" to "Return", "multi_city" to "Multi-city"), { tripType = it }, modifier = Modifier.weight(1f))
                }
                AzField("PNR", pnr, { pnr = it }, tag = "pnr", caps = true)
                Dropdown("Supplier", supplierId, listOf(0L to "None") + suppliers.map { it.long("id") to it.str("name") }, { supplierId = it })
                DateField("Booking date", bookingDate, { bookingDate = it }, tag = "booking-date")
                DateField("Travel date", travelDate, { travelDate = it }, tag = "travel-date", clearable = true)
            }
            FormSection("Prices", Icons.Rounded.Payments, footer = "Amounts in ${if (currency == base) base else currency}. Tax is calculated from the tax profile unless set on the desktop.") {
                Dropdown("Currency", currency, currencies.map { it.str("code") to it.str("code") }, { currency = it })
                if (currency != base) AmountField("Rate (1 $currency in $base)", rate, { rate = it }, tag = "rate")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmountField("Purchase cost", purchase, { purchase = it }, tag = "purchase-cost", modifier = Modifier.weight(1f))
                    AmountField("Selling price", selling, { selling = it }, tag = "selling-price", modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmountField("Commission", commission, { commission = it }, tag = "commission", modifier = Modifier.weight(1f))
                    AmountField("Service charge", service, { service = it }, tag = "service-charge", modifier = Modifier.weight(1f))
                }
                AmountField("Other income", other, { other = it })
                Dropdown("Tax", taxProfileId, listOf(0L to "No tax") + taxProfiles.map { it.long("id") to it.str("name") }, { taxProfileId = it })
            }
            FormSection("Passengers (${pax.size})", Icons.Rounded.Badge, footer = "Passport numbers are encrypted on this phone.") {
                pax.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Az.colors.border)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AzField("Title", p.title, { p.title = it }, tag = "pax-title-$i", caps = true, modifier = Modifier.width(96.dp))
                        AzField("Passenger name", p.name, { p.name = it }, tag = "pax-name-$i", modifier = Modifier.weight(1f))
                    }
                    Dropdown("Type", p.type, listOf("ADT" to "Adult", "CHD" to "Child", "INF" to "Infant"), { p.type = it })
                    AzField("E-ticket number", p.ticket, { p.ticket = it }, tag = "pax-ticket-$i", caps = true)
                    if (FieldCrypto.isSealed(p.passport)) Banner("Passport: encrypted on another device (kept as is)", Tone.Gray)
                    else AzField("Passport number", p.passport, { p.passport = it }, tag = "pax-passport-$i", caps = true)
                    if (pax.size > 1) TextButton(onClick = { pax.remove(p) }) {
                        Icon(Icons.Rounded.Delete, null, tint = Az.colors.danger); Spacer(Modifier.width(6.dp)); Text("Remove passenger", color = Az.colors.danger) }
                }
                SecondaryButton("Add passenger", { pax.add(PaxDraft()) }, Modifier.fillMaxWidth(), Icons.Rounded.Add, "add-passenger")
            }
            FormSection("Flights (${segs.size})", Icons.Rounded.Flight) {
                if (segs.isEmpty()) Muted("No flight segments yet. Add the onward and return flights for the ticket PDF.")
                segs.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Az.colors.border)
                    Dropdown("Direction", s.direction, listOf("outbound" to "Onward", "return" to "Return", "other" to "Other"), { s.direction = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AzField("Airline", s.airline, { s.airline = it }, tag = "seg-airline-$i", modifier = Modifier.weight(1f))
                        AzField("Flight no.", s.flightNo, { s.flightNo = it }, tag = "seg-flight-$i", caps = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AzField("From", s.origin, { s.origin = it }, tag = "seg-from-$i", caps = true, modifier = Modifier.weight(1f), hint = "e.g. COK")
                        AzField("To", s.destination, { s.destination = it }, tag = "seg-to-$i", caps = true, modifier = Modifier.weight(1f), hint = "e.g. DXB")
                    }
                    DateTimeField("Departs", s.dep, { s.dep = it }, tag = "seg-dep-$i")
                    DateTimeField("Arrives", s.arr, { s.arr = it }, tag = "seg-arr-$i")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AzField("Baggage", s.baggage, { s.baggage = it }, modifier = Modifier.weight(1f))
                        AzField("Airline PNR", s.airlinePnr, { s.airlinePnr = it }, caps = true, modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = { segs.remove(s) }) { Icon(Icons.Rounded.Delete, null, tint = Az.colors.danger); Spacer(Modifier.width(6.dp)); Text("Remove flight", color = Az.colors.danger) }
                }
                SecondaryButton("Add flight", { segs.add(SegDraft()) }, Modifier.fillMaxWidth(), Icons.Rounded.Add, "add-flight")
            }
            FormSection("Notes", Icons.Rounded.Notes) { AzField("Notes", notes, { notes = it }, multiline = true) }
            PrimaryButton(if (bookingId == null) "Save booking" else "Save changes", { save() }, Modifier.fillMaxWidth(), tag = "save-booking-bottom")
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- refund / cancel

@Composable
fun RefundScreen(model: AppModel, bookingId: Long, onDone: () -> Unit) {
    val a = model.agency ?: return
    val calc = remember(model.revision) { Ledger(a).bookings[bookingId]?.calc }
    val base = a.baseCurrency
    val accounts = remember { a.db.query("SELECT id, name, currency FROM accounts WHERE active = 1 AND currency = ? ORDER BY name", listOf(base)) }
    var customerRefund by rememberSaveable { mutableStateOf("") }
    var refundTax by rememberSaveable { mutableStateOf("") }
    var supplierRefund by rememberSaveable { mutableStateOf("") }
    var commissionBack by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(Dates.today()) }
    var newStatus by rememberSaveable { mutableStateOf("cancelled") }
    var payNow by rememberSaveable { mutableStateOf(false) }
    var payAccount by rememberSaveable { mutableStateOf(accounts.firstOrNull()?.long("id") ?: 0L) }
    var payAmount by rememberSaveable { mutableStateOf("") }
    var gotNow by rememberSaveable { mutableStateOf(false) }
    var gotAccount by rememberSaveable { mutableStateOf(accounts.firstOrNull()?.long("id") ?: 0L) }
    var gotAmount by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val idem = rememberSaveable { UUID.randomUUID().toString() }
    val accountOptions = accounts.map { it.long("id") to "${it.str("name")} (${it.str("currency")})" }

    fun save() {
        val f = linkedMapOf<String, JValue>("customer_refund" to JValue.Str(customerRefund), "customer_refund_tax" to JValue.Str(refundTax),
            "supplier_refund" to JValue.Str(supplierRefund), "commission_reversal" to JValue.Str(commissionBack), "date" to JValue.Str(date),
            "new_status" to JValue.Str(newStatus), "idem_key" to JValue.Str(idem))
        if (payNow) f["pay_customer"] = JValue.obj("account_id" to payAccount, "amount" to payAmount)
        if (gotNow) f["supplier_received"] = JValue.obj("account_id" to gotAccount, "amount" to gotAmount)
        model.attempt(f, { ag, x -> ag.refundBooking(bookingId, x) }) { model.changed(); onDone() }
    }

    AzScreen("Refund / cancel", onBack = onDone, actions = { TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("save-refund")) { Text("Record") } }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("refund-form"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (calc != null) AzCard(Modifier.fillMaxWidth()) {
                Overline("Now")
                FigureRow("Billed to customer", calc.customerPayable - calc.customerCredits, base)
                FigureRow("Received from customer", calc.received - calc.refunded, base)
                FigureRow("Gross profit", calc.grossProfit, base, strong = true)
            }
            FormSection("Refund", Icons.Rounded.AssignmentReturn, footer = "New profit = profit − refund to customer (ex tax) + refund from supplier − commission given back.") {
                AmountField("Refund to customer", customerRefund, { customerRefund = it }, tag = "customer-refund", currency = base)
                AmountField("of which tax", refundTax, { refundTax = it }, currency = base)
                AmountField("Refund from supplier / airline", supplierRefund, { supplierRefund = it }, tag = "supplier-refund", currency = base)
                AmountField("Commission given back", commissionBack, { commissionBack = it }, currency = base)
                DateField("Date", date, { date = it })
                Dropdown("Booking status", newStatus, listOf("cancelled" to "Cancelled", "refunded" to "Refunded", "" to "Keep status (partial refund)"), { newStatus = it })
            }
            FormSection("Money moved now (optional)", Icons.Rounded.Savings) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(payNow, { payNow = it }); Text("Paid the customer now", color = Az.colors.text) }
                if (payNow) { Dropdown("Account", payAccount, accountOptions, { payAccount = it }); AmountField("Amount paid", payAmount, { payAmount = it }, currency = base) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(gotNow, { gotNow = it }); Text("Supplier refund already received", color = Az.colors.text) }
                if (gotNow) { Dropdown("Account", gotAccount, accountOptions, { gotAccount = it }); AmountField("Amount received", gotAmount, { gotAmount = it }, currency = base) }
            }
            PrimaryButton("Record refund", { confirm = true }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirm) com.airzelo.app.ui.ConfirmDialog("Record this refund?", "Posted entries are never edited later: a mistake is corrected with a reversal.",
        "Record", onConfirm = { confirm = false; save() }, onDismiss = { confirm = false }, danger = false)
}
