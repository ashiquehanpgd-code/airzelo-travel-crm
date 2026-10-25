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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airzelo.app.ui.Avatar
import com.airzelo.app.ui.Az
import com.airzelo.app.ui.AzCard
import com.airzelo.app.ui.AzField
import com.airzelo.app.ui.AzScreen
import com.airzelo.app.ui.Banner
import com.airzelo.app.ui.ConfirmDialog
import com.airzelo.app.ui.DateField
import com.airzelo.app.ui.Dropdown
import com.airzelo.app.ui.EmptyState
import com.airzelo.app.ui.FigureRow
import com.airzelo.app.ui.Fmt
import com.airzelo.app.ui.FormSection
import com.airzelo.app.ui.HeroCard
import com.airzelo.app.ui.Labels
import com.airzelo.app.ui.LabeledValue
import com.airzelo.app.ui.ListRow
import com.airzelo.app.ui.LoadingState
import com.airzelo.app.ui.Money
import com.airzelo.app.ui.Muted
import com.airzelo.app.ui.Overline
import com.airzelo.app.ui.PrimaryButton
import com.airzelo.app.ui.RowDivider
import com.airzelo.app.ui.SearchField
import com.airzelo.app.ui.SecondaryButton
import com.airzelo.app.ui.SectionTitle
import com.airzelo.app.ui.Share
import com.airzelo.app.ui.StatusPill
import com.airzelo.app.ui.Tone
import com.airzelo.app.ui.num
import com.airzelo.app.ui.text
import com.airzelo.engine.FieldCrypto
import com.airzelo.engine.JValue
import com.airzelo.engine.Ledger
import com.airzelo.engine.SUPPLIER_KINDS
import com.airzelo.engine.createCustomer
import com.airzelo.engine.createSupplier
import com.airzelo.engine.customerStatement
import com.airzelo.engine.deleteCustomer
import com.airzelo.engine.fmt
import com.airzelo.engine.getCustomer
import com.airzelo.engine.listCustomers
import com.airzelo.engine.listSuppliers
import com.airzelo.engine.updateCustomer
import com.airzelo.engine.updateSupplier

// ---- customers

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomerListScreen(model: AppModel, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    val a = model.agency ?: return
    val rows = remember(model.revision, query) { a.listCustomers(query) }
    val groups = remember(rows) { rows.groupBy { it["name"].text.take(1).uppercase().ifEmpty { "#" } }.toList() }
    val owed = remember(rows) { rows.sumOf { maxOf(it["outstanding"].num, 0) } }
    AzScreen("Customers", "${rows.size} customers · ${Fmt.money(owed, a.baseCurrency)} to collect", actions = {
        IconButton(onClick = { nav.navigate("customerForm/0") }, modifier = Modifier.testTag("new-customer")) { Icon(Icons.Rounded.PersonAdd, "New customer", tint = Az.colors.royal) }
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("customer-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "search") { Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { SearchField(query, { query = it }, "Search name, phone or email", "customer-search") } }
            if (rows.isEmpty()) item(key = "empty") {
                EmptyState(if (query.isEmpty()) Icons.Rounded.Groups else Icons.Rounded.Search, if (query.isEmpty()) "No customers yet" else "No customer matches",
                    if (query.isEmpty()) "Customers are added with their first booking, or here." else "", if (query.isEmpty()) "Add customer" else null) { nav.navigate("customerForm/0") }
            }
            for ((letter, list) in groups) {
                stickyHeader(key = "h-$letter") {
                    Text(letter, style = MaterialTheme.typography.labelLarge, color = Az.colors.royal,
                        modifier = Modifier.fillMaxWidth().background(Az.colors.bg.copy(alpha = 0.96f)).padding(horizontal = 20.dp, vertical = 6.dp))
                }
                items(list, key = { it["id"].num }) { cu ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(Az.colors.surface)) {
                        ListRow(cu["name"].text, listOf(cu["phone"].text, cu["city"].text).filter { it.isNotEmpty() }.joinToString(" · "),
                            onClick = { nav.navigate("customer/${cu["id"].num}") }, tag = "customer-${cu["name"].text}",
                            meta = "${cu["bookings"].num} booking${if (cu["bookings"].num == 1L) "" else "s"}",
                            leading = { Avatar(cu["name"].text, if (cu["outstanding"].num > 0) Tone.Warn else Tone.Blue) }) {
                            val o = cu["outstanding"].num
                            if (o != 0L) { Money(o, a.baseCurrency, MaterialTheme.typography.labelLarge, color = if (o > 0) Az.colors.danger else Az.colors.violet)
                                Muted(if (o > 0) "to collect" else "credit") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerDetailScreen(model: AppModel, nav: NavHostController, customerId: Long) {
    val a = model.agency ?: return
    val ctx = LocalContext.current
    val d = remember(model.revision, customerId) { runCatching { a.getCustomer(customerId) }.getOrNull() }
    val st = remember(model.revision, customerId) { runCatching { Ledger(a).customerStatement(customerId) }.getOrNull() }
    var confirmRemove by remember { mutableStateOf(false) }
    val base = a.baseCurrency
    val c = Az.colors
    AzScreen(d?.customer?.get("name").text.ifEmpty { "Customer" }, onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = { nav.navigate("customerForm/$customerId") }, modifier = Modifier.testTag("edit-customer")) { Icon(Icons.Rounded.Edit, "Edit customer", tint = c.text) }
    }) {
        if (d == null) { LoadingState(); return@AzScreen }
        val cu = d.customer; val b = d.balance
        LazyColumn(Modifier.fillMaxSize().testTag("customer-detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "hero") {
                HeroCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            Text(cu["name"].text.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                                color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cu["name"].text, style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Text(listOf(cu["phone"].text, cu["city"].text).filter { it.isNotEmpty() }.joinToString(" · "), color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(if (b.outstanding >= 0) "Outstanding" else "Due back to customer", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
                    Text(Fmt.money(Math.abs(b.outstanding), base), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
            }
            item(key = "balance") {
                AzCard(Modifier.fillMaxWidth()) {
                    Overline("Balance")
                    FigureRow("Billed", b.billed - b.credits, base)
                    FigureRow("Received", b.received - b.refunded, base)
                    if (b.unallocated != 0L) FigureRow("Unallocated advance", b.unallocated, base)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = c.border)
                    FigureRow(if (b.outstanding >= 0) "Outstanding" else "Due back to customer", Math.abs(b.outstanding), base, strong = true,
                        tone = if (b.outstanding > 0) c.danger else c.success)
                }
            }
            item(key = "contact") {
                AzCard(Modifier.fillMaxWidth()) {
                    Overline("Details")
                    LabeledValue("Phone", cu["phone"].text, Icons.Rounded.Phone)
                    LabeledValue("Alt. phone", cu["alt_phone"].text, Icons.Rounded.Phone)
                    LabeledValue("Email", cu["email"].text, Icons.Rounded.Email)
                    LabeledValue("City", listOf(cu["city"].text, cu["state"].text).filter { it.isNotEmpty() }.joinToString(", "), Icons.Rounded.LocationCity)
                    LabeledValue("GSTIN", cu["gstin"].text, Icons.Rounded.Badge)
                    LabeledValue("Passport", FieldCrypto.display(cu["passport_no"].text.ifEmpty { null }), Icons.Rounded.Badge)
                    if (cu["follow_up_date"].text.isNotEmpty()) LabeledValue("Follow up", "${Fmt.date(cu["follow_up_date"].text)} ${cu["follow_up_note"].text}", Icons.Rounded.NotificationsActive)
                    if (cu["notes"].text.isNotEmpty()) LabeledValue("Notes", cu["notes"].text, Icons.Rounded.Notes)
                }
            }
            item(key = "bookings") {
                AzCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    SectionTitle("Bookings (${d.bookings.size})", Modifier.padding(horizontal = 16.dp))
                    if (d.bookings.isEmpty()) Muted("No bookings yet.", Modifier.padding(16.dp))
                    d.bookings.forEachIndexed { i, r ->
                        if (i > 0) RowDivider()
                        ListRow(listOf(r["ref"].text, r["pnr"].text).filter { it.isNotEmpty() }.joinToString(" · "), listOf(r["route"].text, Fmt.date(r["booking_date"].text))
                            .filter { it.isNotEmpty() }.joinToString(" · "), onClick = { nav.navigate("booking/${r["id"].num}") },
                            leading = { Avatar(r["lead_pax"].text.ifEmpty { cu["name"].text }, Tone.Cyan) }) {
                            StatusPill(r["status"].text)
                            if (r["customer_outstanding"].num > 0) Money(r["customer_outstanding"].num, base, MaterialTheme.typography.labelLarge, color = c.danger)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (st != null) item(key = "statement") {
                AzCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Statement", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f).padding(top = 8.dp))
                        TextButton(onClick = {
                            val out = StringBuilder("Date,Type,Ref,Description,Debit,Credit,Balance\r\n")
                            fun esc(s: String) = if (s.contains(",") || s.contains("\"")) "\"" + s.replace("\"", "\"\"") + "\"" else s
                            for (l in st.lines) out.append(listOf(l["date"].text, l["type"].text, l["ref"].text, esc(l["description"].text), fmt(l["debit"].num),
                                fmt(l["credit"].num), fmt(l["balance"].num)).joinToString(",")).append("\r\n")
                            runCatching { Share.send(ctx, Share.file(ctx, "Statement-$customerId.csv", out.toString().toByteArray()), "text/csv") }.onFailure { model.fail(it) }
                        }) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("CSV") }
                    }
                    if (st.lines.isEmpty()) Muted("No entries yet.", Modifier.padding(16.dp))
                    st.lines.forEachIndexed { i, l ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = c.border)
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(l["description"].text, style = MaterialTheme.typography.bodyMedium, color = c.text)
                                Muted("${Fmt.date(l["date"].text)} · ${l["ref"].text}")
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val debit = l["debit"].num != 0L
                                Text(if (debit) "+" + fmt(l["debit"].num) else "−" + fmt(l["credit"].num), style = MaterialTheme.typography.labelLarge,
                                    color = if (debit) c.text else c.success)
                                Muted(fmt(l["balance"].num))
                            }
                        }
                    }
                    HorizontalDivider(color = c.border)
                    Box(Modifier.padding(16.dp)) { FigureRow("Closing balance", st.closing, base, strong = true) }
                }
            }
            item(key = "remove") {
                SecondaryButton(if (d.bookings.isEmpty() && d.transactions.isEmpty()) "Delete customer" else "Deactivate customer", { confirmRemove = true },
                    Modifier.fillMaxWidth(), danger = true)
            }
        }
    }
    if (confirmRemove) ConfirmDialog("Remove this customer?", "A customer with bookings or payments is deactivated (kept for the books); one with none is deleted.",
        "Remove", onConfirm = { confirmRemove = false; model.attempt(body = { ag, _ -> ag.deleteCustomer(customerId) }) { model.changed(); nav.popBackStack() } },
        onDismiss = { confirmRemove = false })
}

private val CUSTOMER_KEYS = listOf("name", "phone", "alt_phone", "email", "address", "city", "state", "gstin", "passport_no", "notes", "follow_up_date", "follow_up_note")

@Composable
fun CustomerFormScreen(model: AppModel, customerId: Long?, onDone: () -> Unit) {
    val a = model.agency ?: return
    val f = remember {
        mutableStateMapOf<String, String>().apply {
            val c = customerId?.let { runCatching { a.getCustomer(it).customer }.getOrNull() }
            for (k in CUSTOMER_KEYS) put(k, c?.get(k).text)
        }
    }
    fun v(k: String) = f[k] ?: ""
    fun save() {
        val data = f.mapValues { JValue.Str(it.value) as JValue }
        model.attempt(data, { ag, d -> if (customerId == null) ag.createCustomer(d) else ag.updateCustomer(customerId, d) }) { model.changed(); onDone() }
    }
    AzScreen(if (customerId == null) "New customer" else "Edit customer", onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-customer")) { Text("Save") }
    }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("customer-form"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FormSection("Customer", Icons.Rounded.Person) {
                AzField("Name", v("name"), { f["name"] = it }, tag = "customer-name")
                AzField("Phone", v("phone"), { f["phone"] = it }, tag = "customer-phone", keyboard = KeyboardType.Phone, leading = Icons.Rounded.Phone)
                AzField("Alternate phone", v("alt_phone"), { f["alt_phone"] = it }, keyboard = KeyboardType.Phone, leading = Icons.Rounded.Phone)
                AzField("Email", v("email"), { f["email"] = it }, keyboard = KeyboardType.Email, leading = Icons.Rounded.Email)
            }
            FormSection("Address", Icons.Rounded.Home) {
                AzField("Address", v("address"), { f["address"] = it }, multiline = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AzField("City", v("city"), { f["city"] = it }, tag = "customer-city", modifier = Modifier.weight(1f))
                    AzField("State", v("state"), { f["state"] = it }, modifier = Modifier.weight(1f))
                }
            }
            FormSection("Documents", Icons.Rounded.Badge, footer = "Passport numbers are encrypted on this phone.") {
                AzField("GSTIN", v("gstin"), { f["gstin"] = it }, caps = true)
                if (FieldCrypto.isSealed(f["passport_no"])) Banner("Passport: encrypted on another device (kept as is)", Tone.Gray)
                else AzField("Passport number", v("passport_no"), { f["passport_no"] = it }, tag = "customer-passport", caps = true)
            }
            FormSection("Follow-up", Icons.Rounded.NotificationsActive) {
                DateField("Follow-up date", v("follow_up_date"), { f["follow_up_date"] = it }, clearable = true)
                AzField("Note", v("follow_up_note"), { f["follow_up_note"] = it })
            }
            FormSection("Notes", Icons.Rounded.Notes) { AzField("Notes", v("notes"), { f["notes"] = it }, multiline = true) }
            PrimaryButton("Save customer", { save() }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- suppliers

@Composable
fun SupplierListScreen(model: AppModel, nav: NavHostController) {
    val a = model.agency ?: return
    val rows = remember(model.revision) { a.listSuppliers() }
    AzScreen("Suppliers", "${rows.size} suppliers", onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = { nav.navigate("supplierForm/0") }, modifier = Modifier.testTag("new-supplier")) { Icon(Icons.Rounded.PersonAdd, "New supplier", tint = Az.colors.royal) }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) item { EmptyState(Icons.Rounded.Storefront, "No suppliers yet", "B2B portals, airlines and agents you buy from.", "Add supplier") { nav.navigate("supplierForm/0") } }
            items(rows, key = { it["id"].num }) { s ->
                AzCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    ListRow(s["name"].text, "${Labels.supplierKind[s["kind"].text] ?: Labels.title(s["kind"].text)} · ${s["bookings"].num} bookings",
                        onClick = { nav.navigate("supplierForm/${s["id"].num}") }, tag = "supplier-${s["name"].text}", leading = { Avatar(s["name"].text, Tone.Cyan) }) {
                        val o = s["outstanding"].num
                        if (o != 0L) { Money(o, a.baseCurrency, MaterialTheme.typography.labelLarge, color = if (o > 0) Az.colors.danger else Az.colors.success)
                            Muted(if (o > 0) "you owe" else "owes you") }
                    }
                }
            }
        }
    }
}

@Composable
fun SupplierFormScreen(model: AppModel, supplierId: Long?, onDone: () -> Unit) {
    val a = model.agency ?: return
    val keys = listOf("name", "kind", "contact_person", "phone", "email", "gstin", "notes")
    val f = remember {
        mutableStateMapOf<String, String>().apply {
            val s = supplierId?.let { a.db.one("SELECT * FROM suppliers WHERE id = ?", listOf(it)) }
            for (k in keys) put(k, s?.str(k) ?: "")
            if (get("kind").isNullOrEmpty()) put("kind", "b2b_portal")
        }
    }
    fun save() {
        val data = f.mapValues { JValue.Str(it.value) as JValue }
        model.attempt(data, { ag, d -> if (supplierId == null) ag.createSupplier(d) else ag.updateSupplier(supplierId, d) }) { model.changed(); onDone() }
    }
    AzScreen(if (supplierId == null) "New supplier" else "Edit supplier", onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-supplier")) { Text("Save") }
    }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("supplier-form"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FormSection("Supplier", Icons.Rounded.Storefront) {
                AzField("Name", f["name"] ?: "", { f["name"] = it }, tag = "supplier-name")
                Dropdown("Type", f["kind"] ?: "b2b_portal", SUPPLIER_KINDS.map { it to (Labels.supplierKind[it] ?: it) }, { f["kind"] = it })
                AzField("Contact person", f["contact_person"] ?: "", { f["contact_person"] = it }, leading = Icons.Rounded.Person)
                AzField("Phone", f["phone"] ?: "", { f["phone"] = it }, keyboard = KeyboardType.Phone, leading = Icons.Rounded.Phone)
                AzField("Email", f["email"] ?: "", { f["email"] = it }, keyboard = KeyboardType.Email, leading = Icons.Rounded.Email)
                AzField("GSTIN", f["gstin"] ?: "", { f["gstin"] = it }, caps = true)
                AzField("Notes", f["notes"] ?: "", { f["notes"] = it }, multiline = true)
            }
            if (supplierId != null) Muted("The supplier's statement and balances are in Reports > Supplier outstanding.")
            PrimaryButton("Save supplier", { save() }, Modifier.fillMaxWidth())
        }
    }
}
