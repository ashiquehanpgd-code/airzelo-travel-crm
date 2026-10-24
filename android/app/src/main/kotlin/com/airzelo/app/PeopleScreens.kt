package com.airzelo.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
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

@Composable
fun CustomerListScreen(model: AppModel, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    val a = model.agency ?: return
    val rows = remember(model.revision, query) { a.listCustomers(query) }
    Screen("Customers", actions = {
        IconButton(onClick = { nav.navigate("customerForm/0") }, modifier = Modifier.testTag("new-customer")) { Icon(Icons.Filled.Add, "New customer") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Field("Search name, phone or email", query, { query = it }, tag = "customer-search") }
            LazyColumn {
                items(rows, key = { it["id"].num }) { c ->
                    ListRow({ nav.navigate("customer/${c["id"].num}") }, tag = "customer-${c["name"].text}") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c["name"].text, fontWeight = FontWeight.Bold)
                                Note(c["phone"].text)
                            }
                            if (c["outstanding"].num != 0L) MoneyText(c["outstanding"].num, colored = true, small = true)
                        }
                    }
                }
                if (rows.isEmpty()) item { Note(if (query.isEmpty()) "  No customers yet." else "  No customer matches.") }
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
    Screen(d?.customer?.get("name").text.ifEmpty { "Customer" }, onBack = { nav.popBackStack() }, actions = {
        TextButton(onClick = { nav.navigate("customerForm/$customerId") }, modifier = Modifier.testTag("edit-customer")) { Text("Edit", color = Color.White) }
    }) { pad ->
        if (d == null) return@Screen
        val c = d.customer
        Page(pad, "customer-detail") {
            Section {
                Text(c["name"].text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                for (k in listOf("phone", "alt_phone", "email", "city", "gstin", "passport_no")) {
                    LabeledValue(Fmt.label(if (k == "passport_no") "passport" else k), FieldCrypto.display(c[k].text.ifEmpty { null }))
                }
                if (c["follow_up_date"].text.isNotEmpty()) LabeledValue("Follow up", "${c["follow_up_date"].text} ${c["follow_up_note"].text}")
            }
            val b = d.balance
            Section("Balance") {
                FigureRow("Billed", b.billed - b.credits)
                FigureRow("Received", b.received - b.refunded)
                if (b.unallocated != 0L) FigureRow("Unallocated advance", b.unallocated)
                FigureRow(if (b.outstanding >= 0) "Outstanding" else "Due back to customer", Math.abs(b.outstanding), strong = true)
            }
            Section("Bookings (${d.bookings.size})") {
                for (r in d.bookings) Column(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { nav.navigate("booking/${r["id"].num}") }) { Text("${r["ref"].text}  ${r["pnr"].text}  ${Fmt.label(r["status"].text)}") }
                }
            }
            if (st != null) Section("Statement") {
                for (l in st.lines) Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(l["description"].text, fontSize = 13.sp)
                        Note("${l["date"].text} · ${l["ref"].text}")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (l["debit"].num != 0L) "+" + fmt(l["debit"].num) else "-" + fmt(l["credit"].num), fontSize = 13.sp)
                        Note(fmt(l["balance"].num))
                    }
                }
                FigureRow("Closing balance", st.closing, strong = true)
                TextButton(onClick = {
                    val out = StringBuilder("Date,Type,Ref,Description,Debit,Credit,Balance\r\n")
                    fun esc(s: String) = if (s.contains(",") || s.contains("\"")) "\"" + s.replace("\"", "\"\"") + "\"" else s
                    for (l in st.lines) out.append(listOf(l["date"].text, l["type"].text, l["ref"].text, esc(l["description"].text), fmt(l["debit"].num),
                        fmt(l["credit"].num), fmt(l["balance"].num)).joinToString(",")).append("\r\n")
                    runCatching { Share.send(ctx, Share.file(ctx, "Statement-$customerId.csv", out.toString().toByteArray()), "text/csv") }.onFailure { model.fail(it) }
                }) { Text("Share statement (CSV)") }
            }
            Section {
                TextButton(onClick = { model.attempt(body = { ag, _ -> ag.deleteCustomer(customerId) }) { model.changed(); nav.popBackStack() } }) {
                    Text(if (d.bookings.isEmpty() && d.transactions.isEmpty()) "Delete customer" else "Deactivate customer", color = Bad)
                }
            }
        }
    }
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
    fun bind(k: String): Pair<String, (String) -> Unit> = (f[k] ?: "") to { v: String -> f[k] = v }
    fun save() {
        val data = f.mapValues { JValue.Str(it.value) as JValue }
        model.attempt(data, { ag, d -> if (customerId == null) ag.createCustomer(d) else ag.updateCustomer(customerId, d) }) { model.changed(); onDone() }
    }
    Screen(if (customerId == null) "New customer" else "Edit customer", onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-customer")) { Text("Save", color = Color.White) }
    }) { pad ->
        Page(pad, "customer-form") {
            Section("Customer") {
                bind("name").let { (v, s) -> Field("Name", v, s, tag = "customer-name") }
                bind("phone").let { (v, s) -> Field("Phone", v, s, tag = "customer-phone", keyboard = KeyboardType.Phone) }
                bind("alt_phone").let { (v, s) -> Field("Alternate phone", v, s, keyboard = KeyboardType.Phone) }
                bind("email").let { (v, s) -> Field("Email", v, s, keyboard = KeyboardType.Email) }
            }
            Section("Address") {
                bind("address").let { (v, s) -> Field("Address", v, s, multiline = true) }
                bind("city").let { (v, s) -> Field("City", v, s, tag = "customer-city") }
                bind("state").let { (v, s) -> Field("State", v, s) }
            }
            Section(footer = "Passport numbers are encrypted on this phone.") {
                bind("gstin").let { (v, s) -> Field("GSTIN", v, s, caps = true) }
                if (FieldCrypto.isSealed(f["passport_no"])) Note("Passport: encrypted on another device (kept as is)")
                else bind("passport_no").let { (v, s) -> Field("Passport number", v, s, tag = "customer-passport", caps = true) }
            }
            Section("Follow-up") {
                bind("follow_up_date").let { (v, s) -> Field("Follow-up date (YYYY-MM-DD)", v, s) }
                bind("follow_up_note").let { (v, s) -> Field("Note", v, s) }
            }
            Section("Notes") { bind("notes").let { (v, s) -> Field("Notes", v, s, multiline = true) } }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save customer") }
        }
    }
}

// ---- suppliers

@Composable
fun SupplierListScreen(model: AppModel, nav: NavHostController) {
    val a = model.agency ?: return
    val rows = remember(model.revision) { a.listSuppliers() }
    Screen("Suppliers", onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = { nav.navigate("supplierForm/0") }, modifier = Modifier.testTag("new-supplier")) { Icon(Icons.Filled.Add, "New supplier") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            items(rows, key = { it["id"].num }) { s ->
                ListRow({ nav.navigate("supplierForm/${s["id"].num}") }, tag = "supplier-${s["name"].text}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s["name"].text, fontWeight = FontWeight.Bold)
                            Note("${Fmt.label(s["kind"].text)} · ${s["bookings"].num} bookings")
                        }
                        if (s["outstanding"].num != 0L) MoneyText(s["outstanding"].num, colored = true, small = true)
                    }
                }
            }
            if (rows.isEmpty()) item { Note("  No suppliers yet.") }
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
    Screen(if (supplierId == null) "New supplier" else "Edit supplier", onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-supplier")) { Text("Save", color = Color.White) }
    }) { pad ->
        Page(pad, "supplier-form") {
            Section {
                Field("Name", f["name"] ?: "", { f["name"] = it }, tag = "supplier-name")
                Picker("Type", f["kind"] ?: "b2b_portal", SUPPLIER_KINDS.map { it to Fmt.label(it) }, { f["kind"] = it })
                Field("Contact person", f["contact_person"] ?: "", { f["contact_person"] = it })
                Field("Phone", f["phone"] ?: "", { f["phone"] = it }, keyboard = KeyboardType.Phone)
                Field("Email", f["email"] ?: "", { f["email"] = it }, keyboard = KeyboardType.Email)
                Field("GSTIN", f["gstin"] ?: "", { f["gstin"] = it }, caps = true)
                Field("Notes", f["notes"] ?: "", { f["notes"] = it }, multiline = true)
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save supplier") }
        }
    }
}
