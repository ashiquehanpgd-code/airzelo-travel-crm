package com.airzelo.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airzelo.app.ui.AmountField
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
import com.airzelo.app.ui.FilterRow
import com.airzelo.app.ui.Fmt
import com.airzelo.app.ui.FormSection
import com.airzelo.app.ui.HeroCard
import com.airzelo.app.ui.IconChip
import com.airzelo.app.ui.Labels
import com.airzelo.app.ui.LabeledValue
import com.airzelo.app.ui.ListRow
import com.airzelo.app.ui.LoadingState
import com.airzelo.app.ui.Money
import com.airzelo.app.ui.Muted
import com.airzelo.app.ui.Overline
import com.airzelo.app.ui.Pill
import com.airzelo.app.ui.PrimaryButton
import com.airzelo.app.ui.SearchField
import com.airzelo.app.ui.SecondaryButton
import com.airzelo.app.ui.Tone
import com.airzelo.app.ui.num
import com.airzelo.app.ui.text
import com.airzelo.engine.Dates
import com.airzelo.engine.JValue
import com.airzelo.engine.KIND_ORDER
import com.airzelo.engine.Ledger
import com.airzelo.engine.METHODS
import com.airzelo.engine.createTransaction
import com.airzelo.engine.fmt
import com.airzelo.engine.listAccounts
import com.airzelo.engine.listExpenseCategories
import com.airzelo.engine.listTransactions
import com.airzelo.engine.receiptPDF
import com.airzelo.engine.reverseTransaction
import com.airzelo.engine.saveAccount
import java.util.UUID

private fun kindIcon(kind: String) = when (kind) {
    "customer_receipt", "supplier_receipt", "other_income", "loan_received" -> Icons.Rounded.ArrowDownward
    "transfer" -> Icons.Rounded.SwapHoriz
    "customer_refund" -> Icons.Rounded.Undo
    "expense" -> Icons.Rounded.Payments
    else -> Icons.Rounded.ArrowUpward
}
private fun kindTone(kind: String) = when (kind) {
    "customer_receipt", "supplier_receipt", "other_income", "loan_received" -> Tone.Green
    "transfer" -> Tone.Cyan; "expense" -> Tone.Red; "customer_refund" -> Tone.Violet; else -> Tone.Warn
}

// ---- transactions

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionListScreen(model: AppModel, nav: NavHostController, kindFilter: String, onBack: (() -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(kindFilter) }
    val a = model.agency ?: return
    val rows = remember(model.revision, query, kind) { a.listTransactions(mapOf("q" to query, "kind" to kind)) }
    val groups = remember(rows) { rows.groupBy { it["txn_date"].text.take(10) }.toList() }
    val base = a.baseCurrency
    val totals = remember(rows) {
        var inn = 0L; var out = 0L
        for (r in rows) { val v = r["signed_base"].num; if (r["kind"].text in Labels.inflow) inn += v else if (r["kind"].text != "transfer") out += v }
        inn to out
    }
    val title = when (kindFilter) { "" -> "Money"; "expense" -> "Expenses"; else -> Labels.kind(kindFilter) + "s" }
    AzScreen(title, "${rows.size} entries", onBack = onBack, actions = {
        IconButton(onClick = { nav.navigate("txnForm?kind=${kindFilter.ifEmpty { "customer_receipt" }}&booking=0&customer=0&supplier=0&amount=0") },
            modifier = Modifier.testTag("new-transaction")) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Az.colors.royal), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, "New entry", tint = Color.White)
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("txn-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SearchField(query, { query = it }, "Search number, reference, party, notes", "txn-search")
                    if (rows.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AzCard(Modifier.weight(1f), padding = 12.dp) { Overline("Money in", Az.colors.success); Money(totals.first, base, MaterialTheme.typography.titleMedium) }
                        AzCard(Modifier.weight(1f), padding = 12.dp) { Overline("Money out", Az.colors.danger); Money(totals.second, base, MaterialTheme.typography.titleMedium) }
                    }
                }
                if (kindFilter.isEmpty()) FilterRow(listOf("" to "All") + KIND_ORDER.filter { !it.startsWith("loan") }.map { it to Labels.kind(it) }, kind, { kind = it }, tag = "kind-filter")
                Spacer(Modifier.height(4.dp))
            }
            if (rows.isEmpty()) item(key = "empty") {
                EmptyState(if (query.isEmpty()) Icons.Rounded.ReceiptLong else Icons.Rounded.Search, if (query.isEmpty()) "No entries yet" else "Nothing matches",
                    if (query.isEmpty()) "Receipts, supplier payments, refunds and expenses appear here." else "")
            }
            for ((day, list) in groups) {
                stickyHeader(key = "d-$day") {
                    Row(Modifier.fillMaxWidth().background(Az.colors.bg.copy(alpha = 0.96f)).padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(Fmt.relativeDay(day), style = MaterialTheme.typography.titleSmall, color = Az.colors.text, modifier = Modifier.weight(1f))
                        Muted("${list.size}")
                    }
                }
                items(list, key = { it["id"].num }) { r ->
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(Az.colors.surface)) {
                        TransactionRow(r) { nav.navigate("txn/${r["id"].num}") }
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionRow(r: Map<String, JValue>, onClick: () -> Unit) {
    val c = Az.colors
    val kind = r["kind"].text
    val inflow = kind in Labels.inflow
    val reversed = !(r["reversed_by"]?.isNull ?: true)
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
        .testTag("txn-${r["txn_no"].text}"), verticalAlignment = Alignment.CenterVertically) {
        IconChip(kindIcon(kind), if (reversed) Tone.Gray else kindTone(kind))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r["kind_label"].text + (if (r["sign"].num < 0) " (reversal)" else ""), style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1)
            Muted(listOf(listOf(r["customer_name"].text, r["supplier_name"].text, r["payee"].text, r["category"].text).firstOrNull { it.isNotEmpty() } ?: "",
                r["txn_no"].text, r["booking_ref"].text).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text((if (inflow == (r["sign"].num > 0)) "+" else "−") + Fmt.money(r["amount"].num, r["currency"].text), style = MaterialTheme.typography.titleSmall,
            color = if (reversed) c.muted else if (inflow) c.success else c.text, textDecoration = if (reversed) TextDecoration.LineThrough else null, maxLines = 1)
    }
}

@Composable
fun TransactionDetailScreen(model: AppModel, nav: NavHostController, txnId: Long) {
    val a = model.agency ?: return
    val r = remember(model.revision, txnId) { Ledger(a).let { L -> L.txnById[txnId]?.let { L.txnRow(it) } } }
    var reversing by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    AzScreen(r?.get("txn_no").text.ifEmpty { "Entry" }, r?.get("kind_label").text, onBack = { nav.popBackStack() }) {
        if (r == null) { LoadingState(); return@AzScreen }
        val reversed = !(r["reversed_by"]?.isNull ?: true)
        val kind = r["kind"].text
        LazyColumn(Modifier.fillMaxSize().testTag("txn-detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "hero") {
                HeroCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r["kind_label"].text, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
                        if (reversed) Pill("Reversed", Tone.Gray) else if (r["sign"].num < 0) Pill("Reversal", Tone.Warn)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(Fmt.money(r["amount"].num, r["currency"].text), style = MaterialTheme.typography.displaySmall, color = Color.White)
                    Text(Fmt.date(r["txn_date"].text) + (if (r["currency"].text != a.baseCurrency) " · ${Fmt.money(r["base_amount"].num, a.baseCurrency)}" else ""),
                        style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
            item(key = "facts") {
                AzCard(Modifier.fillMaxWidth()) {
                    LabeledValue("Number", r["txn_no"].text)
                    LabeledValue("Account", r["account_name"].text + (if (r["to_account_name"].text.isEmpty()) "" else " → " + r["to_account_name"].text))
                    LabeledValue("Method", Labels.method[r["method"].text] ?: Labels.title(r["method"].text))
                    for ((k, l) in listOf("customer_name" to "Customer", "supplier_name" to "Supplier", "booking_ref" to "Booking", "payee" to "Payee",
                        "category" to "Category", "reference" to "Reference", "notes" to "Notes")) LabeledValue(l, r[k].text)
                }
            }
            val allocs = r["allocations"]?.array.orEmpty()
            if (allocs.isNotEmpty()) item(key = "alloc") {
                AzCard(Modifier.fillMaxWidth()) {
                    Overline("Allocated to")
                    for (al in allocs) FigureRow(al["ref"].text, al["amount"].num, a.baseCurrency)
                    if (r["unallocated"].num != 0L) FigureRow("Unallocated", r["unallocated"].num, a.baseCurrency)
                }
            }
            if (kind == "customer_receipt" || kind == "customer_refund") item(key = "pdf") {
                SecondaryButton(if (kind == "customer_receipt") "Payment receipt PDF" else "Refund voucher PDF", {
                    model.attempt(body = { ag, _ -> ag.receiptPDF(txnId) }) { model.pdf = it; nav.navigate("pdf") }
                }, Modifier.fillMaxWidth(), Icons.Rounded.PictureAsPdf, "receipt-pdf")
            }
            item(key = "reverse") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!reversed && r["sign"].num > 0) SecondaryButton("Reverse this entry", { reversing = true }, Modifier.fillMaxWidth(), Icons.Rounded.Undo, "reverse", danger = true)
                    Muted("Posted entries are never edited or deleted. Reversing posts an opposite entry, so the audit trail stays complete.")
                }
            }
        }
    }
    if (reversing) ConfirmDialog("Reverse entry", "An opposite entry will be posted today.", "Reverse", onDismiss = { reversing = false }, onConfirm = {
        reversing = false
        model.attempt(mapOf("reason" to JValue.Str(reason)), { ag, f -> ag.reverseTransaction(txnId, f) }) { model.changed() }
    }) { AzField("Reason", reason, { reason = it }, tag = "reverse-reason") }
}

private val ENTRY_KINDS = listOf("customer_receipt", "supplier_payment", "customer_refund", "supplier_receipt", "expense", "other_income", "transfer")

/** Records money in or out. From a booking, the type, party and amount due come preset and the entry is allocated to that booking. */
@Composable
fun TransactionFormScreen(model: AppModel, presetKind: String, bookingId: Long?, customerId0: Long?, supplierId0: Long?, amount0: Long, onDone: () -> Unit) {
    val a = model.agency ?: return
    val base = a.baseCurrency
    val accounts = remember { a.db.query("SELECT id, name, currency FROM accounts WHERE active = 1 ORDER BY kind, name") }
    val customers = remember { a.db.query("SELECT id, name FROM customers WHERE active = 1 ORDER BY name COLLATE NOCASE") }
    val suppliers = remember { a.db.query("SELECT id, name FROM suppliers WHERE active = 1 ORDER BY name COLLATE NOCASE") }
    val bookingRef = remember { bookingId?.let { a.db.one("SELECT ref, pnr FROM bookings WHERE id = ?", listOf(it)) } }
    val categories = remember {
        val c = a.listExpenseCategories().filter { it.bool("active") }.map { it.str("name") }.toMutableList()
        if ("Office expenses" !in c) c.add(0, "Office expenses")
        if ("Other income" !in c) c.add("Other income")
        c
    }
    var kind by rememberSaveable { mutableStateOf(presetKind) }
    var date by rememberSaveable { mutableStateOf(Dates.today()) }
    var accountId by rememberSaveable { mutableStateOf(accounts.firstOrNull { it.str("currency") == base }?.long("id") ?: accounts.firstOrNull()?.long("id") ?: 0L) }
    var toAccountId by rememberSaveable { mutableStateOf(accounts.firstOrNull { it.long("id") != accountId }?.long("id") ?: 0L) }
    var amount by rememberSaveable { mutableStateOf(if (amount0 > 0) fmt(amount0) else "") }
    var toAmount by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("cash") }
    var reference by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(if (presetKind == "other_income") "Other income" else "Office expenses") }
    var payee by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var customerId by rememberSaveable { mutableStateOf(customerId0 ?: 0L) }
    var supplierId by rememberSaveable { mutableStateOf(supplierId0 ?: 0L) }
    val idem = rememberSaveable { UUID.randomUUID().toString() }
    val account = accounts.firstOrNull { it.long("id") == accountId }
    val toAccount = accounts.firstOrNull { it.long("id") == toAccountId }
    val accountOptions = accounts.map { it.long("id") to "${it.str("name")} (${it.str("currency")})" }

    fun save() {
        val f = linkedMapOf<String, JValue>("kind" to JValue.Str(kind), "txn_date" to JValue.Str(date), "account_id" to JValue.Int(accountId),
            "amount" to JValue.Str(amount), "exchange_rate" to JValue.Str(rate), "method" to JValue.Str(method), "reference" to JValue.Str(reference),
            "notes" to JValue.Str(notes), "idem_key" to JValue.Str(idem))
        if (bookingId != null) f["booking_id"] = JValue.Int(bookingId)
        if (customerId != 0L) f["customer_id"] = JValue.Int(customerId)
        if (supplierId != 0L) f["supplier_id"] = JValue.Int(supplierId)
        if (kind in listOf("expense", "other_income")) { f["category"] = JValue.Str(category); f["payee"] = JValue.Str(payee) }
        if (kind == "transfer") { f["to_account_id"] = JValue.Int(toAccountId); f["to_amount"] = JValue.Str(toAmount) }
        model.attempt(f, { ag, x -> ag.createTransaction(x) }) { model.changed(); onDone() }
    }

    AzScreen(Labels.kind(kind), bookingRef?.let { "For booking ${it.str("ref")}" + (if (it.str("pnr").isEmpty()) "" else " · PNR ${it.str("pnr")}") }, onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-transaction")) { Text("Save") }
    }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(vertical = 16.dp).testTag("txn-form"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (bookingId == null) {
                FilterRow(ENTRY_KINDS.map { it to Labels.kind(it) }, kind, { k -> kind = k
                    if (k == "other_income") category = "Other income" else if (k == "expense" && category == "Other income") category = categories.first() }, tag = "txn-kind")
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FormSection("Amount", kindIcon(kind)) {
                    AmountField("Amount" + (account?.let { " (${it.str("currency")})" } ?: ""), amount, { amount = it }, tag = "txn-amount")
                    if (account != null && account.str("currency") != base) AmountField("Rate (1 ${account.str("currency")} in $base)", rate, { rate = it }, tag = "txn-rate")
                    DateField("Date", date, { date = it }, tag = "txn-date")
                    Dropdown(if (kind == "transfer") "From account" else "Account", accountId, accountOptions, { accountId = it }, tag = "txn-account")
                    if (kind == "transfer") {
                        Dropdown("To account", toAccountId, accountOptions.filter { it.first != accountId }, { toAccountId = it })
                        if (account != null && toAccount != null && account.str("currency") != toAccount.str("currency"))
                            AmountField("Received (${toAccount.str("currency")})", toAmount, { toAmount = it })
                    } else Dropdown("Method", method, METHODS.map { it to (Labels.method[it] ?: it) }, { method = it }, tag = "txn-method")
                }
                if (bookingId == null && kind in listOf("customer_receipt", "customer_refund", "supplier_payment", "supplier_receipt", "expense")) {
                    FormSection(if (kind.startsWith("customer")) "Customer" else "Supplier", Icons.Rounded.AccountBalance) {
                        if (kind in listOf("customer_receipt", "customer_refund"))
                            Dropdown("Customer", customerId, listOf(0L to "Select") + customers.map { it.long("id") to it.str("name") }, { customerId = it }, tag = "txn-customer")
                        else Dropdown(if (kind == "expense") "Supplier (optional)" else "Supplier", supplierId,
                            listOf(0L to (if (kind == "expense") "None" else "Select")) + suppliers.map { it.long("id") to it.str("name") }, { supplierId = it })
                        if (kind in listOf("customer_refund", "supplier_receipt"))
                            Banner("To refund a booking, open it and use Refund / cancel: that records the credit and adjusts the profit first.", Tone.Blue)
                    }
                }
                if (kind in listOf("expense", "other_income")) FormSection("Category", Icons.Rounded.Category) {
                    Dropdown("Category", category, categories.map { it to it }, { category = it }, tag = "txn-category")
                    AzField("Payee / from", payee, { payee = it })
                }
                FormSection("Reference & notes", Icons.Rounded.Notes) {
                    AzField("Reference (UTR, cheque no.)", reference, { reference = it }, tag = "txn-reference", caps = true)
                    AzField("Notes", notes, { notes = it }, multiline = true)
                }
                PrimaryButton("Save entry", { save() }, Modifier.fillMaxWidth(), tag = "save-transaction-bottom")
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---- accounts

@Composable
fun AccountListScreen(model: AppModel, nav: NavHostController) {
    val a = model.agency ?: return
    val rows = remember(model.revision) { a.listAccounts() }
    AzScreen("Cash & bank", "${rows.count { it["active"].num == 1L }} active accounts", onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = { nav.navigate("accountForm") }, modifier = Modifier.testTag("new-account")) { Icon(Icons.Rounded.Add, "New account", tint = Az.colors.royal) }
    }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it["id"].num }) { acc ->
                AzCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("report/cashbook?account=${acc["id"].num}") }, tag = "account-${acc["name"].text}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip(if (acc["kind"].text == "cash") Icons.Rounded.Wallet else Icons.Rounded.AccountBalance, if (acc["active"].num == 0L) Tone.Gray
                            else if (acc["kind"].text == "cash") Tone.Green else Tone.Cyan)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(acc["name"].text, style = MaterialTheme.typography.titleSmall, color = Az.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted("${Labels.accType[acc["account_type"].text] ?: Labels.title(acc["account_type"].text)} · ${acc["txn_count"].num} entries" +
                                (if (acc["active"].num == 0L) " · inactive" else ""))
                        }
                        Money(acc["balance"].num, acc["currency"].text, MaterialTheme.typography.titleMedium, color = if (acc["balance"].num < 0) Az.colors.danger else null)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row { Muted("In ${Fmt.money(acc["inflow"].num, acc["currency"].text)}", Modifier.weight(1f)); Muted("Out ${Fmt.money(acc["outflow"].num, acc["currency"].text)}") }
                }
            }
        }
    }
}

@Composable
fun AccountFormScreen(model: AppModel, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("bank") }
    var currency by rememberSaveable { mutableStateOf("INR") }
    var opening by rememberSaveable { mutableStateOf("") }
    var bank by rememberSaveable { mutableStateOf("") }
    var last4 by rememberSaveable { mutableStateOf("") }
    fun save() {
        val f = mapOf("name" to name, "account_type" to type, "currency" to currency, "opening_balance" to opening, "bank_name" to bank, "account_last4" to last4)
            .mapValues { JValue.Str(it.value) as JValue }
        model.attempt(f, { ag, x -> ag.saveAccount(x) }) { model.changed(); onDone() }
    }
    AzScreen("New account", onBack = onDone, actions = { TextButton(onClick = { save() }, modifier = Modifier.testTag("save-account")) { Text("Save") } }) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("account-form"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FormSection("Account", Icons.Rounded.AccountBalance, footer = "Only the last 4 digits of an account or card number are kept.") {
                AzField("Name (e.g. HDFC Current)", name, { name = it }, tag = "account-name")
                Dropdown("Type", type, Labels.accType.toList(), { type = it })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AzField("Currency", currency, { currency = it.uppercase().take(3) }, caps = true, modifier = Modifier.weight(1f))
                    AmountField("Opening balance", opening, { opening = it }, modifier = Modifier.weight(1.4f))
                }
                AzField("Bank name", bank, { bank = it })
                AzField("Last 4 digits", last4, { last4 = it.filter(Char::isDigit).take(4) }, keyboard = KeyboardType.Number)
            }
            PrimaryButton("Save account", { save() }, Modifier.fillMaxWidth())
        }
    }
}
