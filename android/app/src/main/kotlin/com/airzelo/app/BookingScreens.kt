package com.airzelo.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.airzelo.engine.BOOKING_STATUSES
import com.airzelo.engine.Dates
import com.airzelo.engine.FieldCrypto
import com.airzelo.engine.Fields
import com.airzelo.engine.JValue
import com.airzelo.engine.Ledger
import com.airzelo.engine.SERVICE_TYPES
import com.airzelo.engine.createInvoice
import com.airzelo.engine.bookingEditFields
import com.airzelo.engine.getBooking
import com.airzelo.engine.refundBooking
import com.airzelo.engine.saveBooking
import com.airzelo.engine.searchBookings
import com.airzelo.engine.setBookingStatus
import com.airzelo.engine.ticketPDF
import java.util.UUID

private val PAX_LABEL = mapOf("ADT" to "Adult", "CHD" to "Child", "INF" to "Infant")

// ---- list

@Composable
fun BookingListScreen(model: AppModel, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    val a = model.agency ?: return
    val rows = remember(model.revision, query, status) {
        val r = a.searchBookings(mapOf("q" to query, "status" to status))
        if (query.isEmpty()) r.sortedWith(compareByDescending<Map<String, JValue>> { it["booking_date"].text }.thenByDescending { it["id"].num }) else r
    }
    Screen("Bookings", actions = {
        IconButton(onClick = { nav.navigate("bookingForm/0") }, modifier = Modifier.testTag("new-booking")) { Icon(Icons.Filled.Add, "New booking") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Search PNR, name, ticket no., phone", query, { query = it }, tag = "booking-search")
                Picker("Status", status, listOf("" to "All") + BOOKING_STATUSES.map { it to Fmt.label(it) }, { status = it })
            }
            LazyColumn(Modifier.testTag("booking-list")) {
                items(rows, key = { it["id"].num }) { r -> ListRow({ nav.navigate("booking/${r["id"].num}") }, tag = "booking-${r["ref"].text}") { BookingRow(r) } }
                if (rows.isEmpty()) item { Note(if (query.isEmpty()) "No bookings yet." else "No saved booking matches.") }
            }
        }
    }
}

@Composable
fun BookingRow(r: Map<String, JValue>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(r["customer_name"].text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        StatusBadge(r["status"].text)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Note(listOf(r["ref"].text, r["pnr"].text, r["route"].text).filter { it.isNotEmpty() }.joinToString(" · "))
        Text("", Modifier.weight(1f))
        if (r["customer_outstanding"].num > 0) MoneyText(r["customer_outstanding"].num, colored = true, small = true)
    }
}

// ---- detail

@Composable
fun BookingDetailScreen(model: AppModel, nav: NavHostController, bookingId: Long) {
    val a = model.agency ?: return
    val d = remember(model.revision, bookingId) { runCatching { a.getBooking(bookingId) }.getOrNull() }
    var statusMenu by remember { mutableStateOf(false) }
    Screen(d?.booking?.ref ?: "Booking", onBack = { nav.popBackStack() }, actions = {
        TextButton(onClick = { nav.navigate("bookingForm/$bookingId") }, modifier = Modifier.testTag("edit-booking")) { Text("Edit", color = androidx.compose.ui.graphics.Color.White) }
    }) { pad ->
        if (d == null) return@Screen
        val b = d.booking; val c = b.calc; val base = d.base
        Page(pad, "booking-detail") {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.customerName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("${b.ref}  ·  PNR ${b.pnr ?: "-"}", color = Muted)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusBadge(b.status, tag = "booking-status")
                        StatusBadge(c.paymentStatus, tag = "payment-status")
                    }
                }
                if (d.duplicates.isNotEmpty()) Note("Same PNR in ${d.duplicates.joinToString(", ") { it.str("ref") }}", Warn)
            }
            Section("Customer") {
                FigureRow("Billed", c.customerPayable - c.customerCredits, base)
                FigureRow("Received", c.received - c.refunded, base)
                FigureRow(if (c.customerOutstanding >= 0) "Outstanding" else "Due back to customer", Math.abs(c.customerOutstanding), base, strong = true, tag = "customer-outstanding")
            }
            Section("Supplier" + (if (b.supplierName.isEmpty()) "" else " · " + b.supplierName)) {
                FigureRow("Payable (after commission)", c.supplierPayable - c.supplierCredits, base)
                FigureRow("Paid", c.paidSupplier - c.supplierRefunds, base)
                FigureRow(if (c.supplierOutstanding >= 0) "Outstanding" else "Due from supplier", Math.abs(c.supplierOutstanding), base, strong = true)
            }
            Section("Profit") {
                if (b.currency != base) Note("${b.currency} at ${b.row.str("exchange_rate")} = 1 ${b.currency}")
                FigureRow("Revenue (ex tax)", c.revenue, base)
                FigureRow("Cost", c.cost, base)
                FigureRow("Commission", c.commissionNet, base)
                if (c.directExpenses != 0L) FigureRow("Direct expenses", c.directExpenses, base)
                FigureRow("Gross profit", c.grossProfit, base, strong = true)
                if (c.taxNet != 0L) FigureRow("Tax collected (liability)", c.taxNet, base)
            }
            Section("Passengers") {
                for (p in d.passengers) Column {
                    Text(listOf(p["title"].text, p["name"].text).filter { it.isNotEmpty() }.joinToString(" "), fontWeight = FontWeight.Bold)
                    Note(listOf(PAX_LABEL[p["pax_type"].text] ?: "", if (p["ticket_number"].text.isEmpty()) "No ticket no." else "Ticket " + p["ticket_number"].text,
                        if (p["passport_no"].text.isEmpty()) "" else "Passport " + FieldCrypto.display(p["passport_no"].text)).filter { it.isNotEmpty() }.joinToString(" · "))
                }
            }
            if (d.segments.isNotEmpty()) Section("Flights") {
                for (s in d.segments) Column {
                    Text("${s.str("origin")} -> ${s.str("destination")}  ${s.str("airline")} ${s.str("flight_no")}", fontWeight = FontWeight.Bold)
                    Note(listOf(s.str("dep_at").replace("T", " "), if (s.str("airline_pnr").isEmpty()) "" else "Airline PNR " + s.str("airline_pnr"))
                        .filter { it.isNotEmpty() }.joinToString(" · "))
                }
            }
            Section("Actions") {
                val outstanding = maxOf(c.customerOutstanding, 0)
                ActionButton("Record customer payment", "record-payment") {
                    nav.navigate("txnForm?kind=customer_receipt&booking=$bookingId&customer=${b.customerId}&supplier=0&amount=$outstanding")
                }
                if (b.supplierId != null) ActionButton("Record supplier payment", "record-supplier-payment") {
                    nav.navigate("txnForm?kind=supplier_payment&booking=$bookingId&customer=0&supplier=${b.supplierId}&amount=${maxOf(c.supplierOutstanding, 0)}")
                }
                if (b.committed) ActionButton("Refund / cancel", "refund") { nav.navigate("refund/$bookingId") }
                ActionButton("Ticket PDF", "ticket-pdf") { model.attempt(body = { ag, _ -> ag.ticketPDF(bookingId, false) }) { model.pdf = it; nav.navigate("pdf") } }
                ActionButton("Ticket + payment details PDF", "ticket-payment-pdf") {
                    model.attempt(body = { ag, _ -> ag.ticketPDF(bookingId, true) }) { model.pdf = it; nav.navigate("pdf") }
                }
                if (b.committed && d.invoices.none { it.str("doc_type") == "invoice" && it.str("status") == "issued" }) {
                    ActionButton("Issue invoice", "issue-invoice") { model.attempt(body = { ag, _ -> ag.createInvoice(bookingId) }) { model.changed() } }
                }
                if (!b.committed) {
                    ActionButton("Change status", "change-status") { statusMenu = !statusMenu }
                    if (statusMenu) for (s in listOf("confirmed", "ticketed", "on_hold", "enquiry", "cancelled")) {
                        TextButton(onClick = { statusMenu = false; model.attempt(body = { ag, _ -> ag.setBookingStatus(bookingId, s) }) { model.changed() } },
                            modifier = Modifier.padding(start = 16.dp).testTag("status-$s")) { Text(Fmt.label(s)) }
                    }
                } else if (b.status == "confirmed") {
                    ActionButton("Mark ticketed", "mark-ticketed") { model.attempt(body = { ag, _ -> ag.setBookingStatus(bookingId, "ticketed") }) { model.changed() } }
                }
            }
            if (d.invoices.isNotEmpty()) Section("Invoices & credit notes") {
                for (inv in d.invoices) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { nav.navigate("invoice/${inv.long("id")}") }, modifier = Modifier.weight(1f)) { Text(inv.str("number")) }
                    StatusBadge(inv.str("status"))
                }
            }
            if (d.transactions.isNotEmpty()) Section("Money") {
                for (t in d.transactions) TransactionRow(t) { nav.navigate("txn/${t["id"].num}") }
            }
            if (d.adjustments.isNotEmpty()) Section("Adjustments") {
                for (adj in d.adjustments) Column {
                    Text("${adj.adjNo} · ${Fmt.label(adj.kind)}" + (if (adj.sign < 0) " (reversal)" else ""), fontWeight = FontWeight.Bold)
                    Note("Customer credit ${com.airzelo.engine.fmt(adj["customer_credit"])} · Supplier credit ${com.airzelo.engine.fmt(adj["supplier_credit"])}")
                }
            }
            Section("History") {
                for (h in d.history.take(30)) Column {
                    Text(h.str("detail"), fontSize = 13.sp)
                    Note(h.str("at").replace("T", " "))
                }
            }
        }
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
 * the fields shown here are replaced, so re-saving never loses anything the form does not show.
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

    Screen(if (bookingId == null) "New booking" else "Edit booking", onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-booking")) { Text("Save", color = androidx.compose.ui.graphics.Color.White) }
    }) { pad ->
        Page(pad, "booking-form") {
            Section("Customer") {
                Picker("Customer", customerId, listOf(0L to "New customer") + customers.map { it.long("id") to it.str("name") }, { customerId = it }, tag = "customer-picker")
                if (customerId == 0L) {
                    Field("Name", newName, { newName = it }, tag = "new-customer-name")
                    Field("Phone", newPhone, { newPhone = it }, tag = "new-customer-phone", keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
                }
            }
            Section("Booking") {
                Picker("Status", status, BOOKING_STATUSES.map { it to Fmt.label(it) }, { status = it }, tag = "status-picker")
                Picker("Service", serviceType, SERVICE_TYPES.map { it to Fmt.label(it) }, { serviceType = it })
                Picker("Trip", tripType, listOf("one_way" to "One way", "round_trip" to "Return", "multi_city" to "Multi-city"), { tripType = it })
                Field("PNR", pnr, { pnr = it }, tag = "pnr", caps = true)
                Picker("Supplier", supplierId, listOf(0L to "None") + suppliers.map { it.long("id") to it.str("name") }, { supplierId = it })
                Field("Booking date (YYYY-MM-DD)", bookingDate, { bookingDate = it }, tag = "booking-date")
                Field("Travel date (YYYY-MM-DD)", travelDate, { travelDate = it }, tag = "travel-date")
            }
            Section("Prices") {
                Picker("Currency", currency, currencies.map { it.str("code") to it.str("code") }, { currency = it })
                if (currency != base) AmountField("Rate (1 $currency in $base)", rate, { rate = it }, tag = "rate")
                AmountField("Purchase cost", purchase, { purchase = it }, tag = "purchase-cost")
                AmountField("Selling price", selling, { selling = it }, tag = "selling-price")
                AmountField("Commission", commission, { commission = it }, tag = "commission")
                AmountField("Service charge", service, { service = it }, tag = "service-charge")
                AmountField("Other income", other, { other = it })
                Picker("Tax", taxProfileId, listOf(0L to "No tax") + taxProfiles.map { it.long("id") to it.str("name") }, { taxProfileId = it })
            }
            Section("Passengers") {
                pax.forEachIndexed { i, p ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(p.title, { p.title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.width(90.dp).testTag("pax-title-$i"))
                            OutlinedTextField(p.name, { p.name = it }, label = { Text("Passenger name") }, singleLine = true, modifier = Modifier.weight(1f).testTag("pax-name-$i"))
                        }
                        Picker("Type", p.type, listOf("ADT" to "Adult", "CHD" to "Child", "INF" to "Infant"), { p.type = it })
                        Field("E-ticket number", p.ticket, { p.ticket = it }, tag = "pax-ticket-$i", caps = true)
                        if (FieldCrypto.isSealed(p.passport)) Note("Passport: encrypted on another device (kept as is)")
                        else Field("Passport number (encrypted)", p.passport, { p.passport = it }, tag = "pax-passport-$i", caps = true)
                        if (pax.size > 1) TextButton(onClick = { pax.remove(p) }) { Text("Remove passenger", color = Bad) }
                    }
                }
                TextButton(onClick = { pax.add(PaxDraft()) }, modifier = Modifier.testTag("add-passenger")) { Text("Add passenger") }
            }
            Section("Flights") {
                segs.forEachIndexed { i, s ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Picker("Direction", s.direction, listOf("outbound" to "Onward", "return" to "Return", "other" to "Other"), { s.direction = it })
                        Field("Airline", s.airline, { s.airline = it }, tag = "seg-airline-$i")
                        Field("Flight no.", s.flightNo, { s.flightNo = it }, tag = "seg-flight-$i", caps = true)
                        Field("From (COK)", s.origin, { s.origin = it }, tag = "seg-from-$i", caps = true)
                        Field("To (DXB)", s.destination, { s.destination = it }, tag = "seg-to-$i", caps = true)
                        Field("Departs (YYYY-MM-DDTHH:MM)", s.dep, { s.dep = it }, tag = "seg-dep-$i")
                        Field("Arrives (YYYY-MM-DDTHH:MM)", s.arr, { s.arr = it }, tag = "seg-arr-$i")
                        Field("Baggage", s.baggage, { s.baggage = it })
                        Field("Airline PNR", s.airlinePnr, { s.airlinePnr = it }, caps = true)
                        TextButton(onClick = { segs.remove(s) }) { Text("Remove flight", color = Bad) }
                    }
                }
                TextButton(onClick = { segs.add(SegDraft()) }, modifier = Modifier.testTag("add-flight")) { Text("Add flight") }
            }
            Section("Notes") { Field("Notes", notes, { notes = it }, multiline = true) }
            androidx.compose.material3.Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().testTag("save-booking-bottom")) { Text("Save booking") }
        }
    }
}

// ---- refund / cancel

@Composable
fun RefundScreen(model: AppModel, bookingId: Long, onDone: () -> Unit) {
    val a = model.agency ?: return
    val calc = remember(model.revision) { Ledger(a).bookings[bookingId]?.calc }
    val accounts = remember { a.db.query("SELECT id, name, currency FROM accounts WHERE active = 1 AND currency = ? ORDER BY name", listOf(a.baseCurrency)) }
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

    Screen("Refund / cancel", onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-refund")) { Text("Record", color = androidx.compose.ui.graphics.Color.White) }
    }) { pad ->
        Page(pad, "refund-form") {
            if (calc != null) Section("Now") {
                FigureRow("Billed to customer", calc.customerPayable - calc.customerCredits)
                FigureRow("Received from customer", calc.received - calc.refunded)
                FigureRow("Gross profit", calc.grossProfit)
            }
            Section(footer = "New profit = profit - refund to customer (ex tax) + refund from supplier - commission given back.") {
                AmountField("Refund to customer", customerRefund, { customerRefund = it }, tag = "customer-refund")
                AmountField("of which tax", refundTax, { refundTax = it })
                AmountField("Refund from supplier / airline", supplierRefund, { supplierRefund = it }, tag = "supplier-refund")
                AmountField("Commission given back", commissionBack, { commissionBack = it })
                Field("Date (YYYY-MM-DD)", date, { date = it })
                Picker("Booking status", newStatus, listOf("cancelled" to "Cancelled", "refunded" to "Refunded", "" to "Keep status (partial refund)"), { newStatus = it })
            }
            Section("Money moved now (optional)") {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(payNow, { payNow = it }); Text("Paid the customer now") }
                if (payNow) {
                    Picker("Account", payAccount, accountOptions, { payAccount = it })
                    AmountField("Amount paid", payAmount, { payAmount = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(gotNow, { gotNow = it }); Text("Supplier refund already received") }
                if (gotNow) {
                    Picker("Account", gotAccount, accountOptions, { gotAccount = it })
                    AmountField("Amount received", gotAmount, { gotAmount = it })
                }
            }
        }
    }
}
