package com.airzelo.app

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.airzelo.engine.Dates
import com.airzelo.engine.JValue
import com.airzelo.engine.KIND_LABELS
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

private val INFLOWS = setOf("customer_receipt", "supplier_receipt", "other_income", "loan_received")

// ---- transactions

@Composable
fun TransactionListScreen(model: AppModel, nav: NavHostController, kindFilter: String, onBack: (() -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(kindFilter) }
    val a = model.agency ?: return
    val rows = remember(model.revision, query, kind) { a.listTransactions(mapOf("q" to query, "kind" to kind)) }
    Screen(if (kindFilter == "expense") "Expenses" else "Money", onBack, actions = {
        IconButton(onClick = { nav.navigate("txnForm?kind=${kindFilter.ifEmpty { "expense" }}&booking=0&customer=0&supplier=0&amount=0") },
            modifier = Modifier.testTag("new-transaction")) { Icon(Icons.Filled.Add, "New entry") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Field("Search number, reference, party, notes", query, { query = it }, tag = "txn-search")
                if (kindFilter.isEmpty()) Picker("Type", kind, listOf("" to "All") + KIND_ORDER.filter { !it.startsWith("loan") }.map { it to KIND_LABELS[it]!! }, { kind = it })
            }
            LazyColumn {
                items(rows.take(500), key = { it["id"].num }) { r -> Column(Modifier.padding(horizontal = 16.dp)) { TransactionRow(r) { nav.navigate("txn/${r["id"].num}") } } }
                if (rows.isEmpty()) item { Note("  No entries yet.") }
            }
        }
    }
}

@Composable
fun TransactionRow(r: Map<String, JValue>, onClick: () -> Unit) {
    val inflow = r["kind"].text in INFLOWS
    val reversed = !(r["reversed_by"]?.isNull ?: true)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp).testTag("txn-${r["txn_no"].text}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r["kind_label"].text + (if (r["sign"].num < 0) " (reversal)" else ""), fontWeight = FontWeight.Bold)
            Note(listOf(r["txn_no"].text, r["txn_date"].text, listOf(r["customer_name"].text, r["supplier_name"].text, r["payee"].text, r["category"].text)
                .firstOrNull { it.isNotEmpty() } ?: "").filter { it.isNotEmpty() }.joinToString(" · "))
        }
        Text((if (inflow == (r["sign"].num > 0)) "+" else "-") + Fmt.money(r["amount"].num, r["currency"].text),
            color = if (reversed) Muted else if (inflow) Good else Color.Unspecified,
            textDecoration = if (reversed) TextDecoration.LineThrough else null)
    }
}

@Composable
fun TransactionDetailScreen(model: AppModel, nav: NavHostController, txnId: Long) {
    val a = model.agency ?: return
    val r = remember(model.revision, txnId) { Ledger(a).let { L -> L.txnById[txnId]?.let { L.txnRow(it) } } }
    var reversing by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    Screen(r?.get("txn_no").text.ifEmpty { "Entry" }, onBack = { nav.popBackStack() }) { pad ->
        if (r == null) return@Screen
        Page(pad, "txn-detail") {
            Section {
                LabeledValue("Type", r["kind_label"].text)
                LabeledValue("Number", r["txn_no"].text)
                LabeledValue("Date", r["txn_date"].text)
                LabeledValue("Amount", Fmt.money(r["amount"].num, r["currency"].text))
                if (r["currency"].text != a.baseCurrency) LabeledValue("In ${a.baseCurrency}", Fmt.money(r["base_amount"].num, a.baseCurrency))
                LabeledValue("Account", r["account_name"].text + (if (r["to_account_name"].text.isEmpty()) "" else " -> " + r["to_account_name"].text))
                LabeledValue("Method", Fmt.label(r["method"].text))
                for (k in listOf("customer_name", "supplier_name", "booking_ref", "payee", "category", "reference", "notes")) {
                    LabeledValue(Fmt.label(k.replace("_name", "")), r[k].text)
                }
            }
            val allocs = r["allocations"]?.array.orEmpty()
            if (allocs.isNotEmpty()) Section("Allocated to") {
                for (al in allocs) FigureRow(al["ref"].text, al["amount"].num)
                if (r["unallocated"].num != 0L) FigureRow("Unallocated", r["unallocated"].num)
            }
            if (r["kind"].text in listOf("customer_receipt", "customer_refund")) {
                ActionButton(if (r["kind"].text == "customer_receipt") "Payment receipt PDF" else "Refund voucher PDF", "receipt-pdf") {
                    model.attempt(body = { ag, _ -> ag.receiptPDF(txnId) }) { model.pdf = it; nav.navigate("pdf") }
                }
            }
            Section(footer = "Posted entries are never edited or deleted. Reversing posts an opposite entry, so the audit trail stays complete.") {
                if (!(r["reversed_by"]?.isNull ?: true)) Text("Reversed", color = Muted)
                else if (r["sign"].num > 0) TextButton(onClick = { reversing = true }, modifier = Modifier.testTag("reverse")) { Text("Reverse this entry", color = Bad) }
            }
        }
    }
    if (reversing) ConfirmDialog("Reverse entry", "An opposite entry will be posted today.", "Reverse", onDismiss = { reversing = false }, onConfirm = {
        reversing = false
        model.attempt(mapOf("reason" to JValue.Str(reason)), { ag, f -> ag.reverseTransaction(txnId, f) }) { model.changed() }
    }) { Field("Reason", reason, { reason = it }, tag = "reverse-reason") }
}

/** Records money in or out. From a booking, the type, party and amount due come preset and the entry is allocated to that booking. */
@Composable
fun TransactionFormScreen(model: AppModel, presetKind: String, bookingId: Long?, customerId0: Long?, supplierId0: Long?, amount0: Long, onDone: () -> Unit) {
    val a = model.agency ?: return
    val base = a.baseCurrency
    val accounts = remember { a.db.query("SELECT id, name, currency FROM accounts WHERE active = 1 ORDER BY kind, name") }
    val customers = remember { a.db.query("SELECT id, name FROM customers WHERE active = 1 ORDER BY name COLLATE NOCASE") }
    val suppliers = remember { a.db.query("SELECT id, name FROM suppliers WHERE active = 1 ORDER BY name COLLATE NOCASE") }
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

    Screen(KIND_LABELS[kind] ?: "Entry", onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-transaction")) { Text("Save", color = Color.White) }
    }) { pad ->
        Page(pad, "txn-form") {
            Section {
                if (bookingId == null) {
                    Picker("Type", kind, listOf("customer_receipt", "supplier_payment", "customer_refund", "supplier_receipt", "expense", "other_income", "transfer")
                        .map { it to KIND_LABELS[it]!! }, { k -> kind = k
                            if (k == "other_income") category = "Other income" else if (k == "expense" && category == "Other income") category = categories.first() },
                        tag = "txn-kind")
                } else LabeledValue("Type", KIND_LABELS[kind] ?: kind)
                Field("Date (YYYY-MM-DD)", date, { date = it }, tag = "txn-date")
                Picker(if (kind == "transfer") "From" else "Account", accountId, accountOptions, { accountId = it }, tag = "txn-account")
                AmountField("Amount" + (account?.let { " (${it.str("currency")})" } ?: ""), amount, { amount = it }, tag = "txn-amount")
                if (account != null && account.str("currency") != base) AmountField("Rate (1 ${account.str("currency")} in $base)", rate, { rate = it }, tag = "txn-rate")
                if (kind == "transfer") {
                    Picker("To", toAccountId, accountOptions.filter { it.first != accountId }, { toAccountId = it })
                    if (account != null && toAccount != null && account.str("currency") != toAccount.str("currency")) {
                        AmountField("Received (${toAccount.str("currency")})", toAmount, { toAmount = it })
                    }
                } else Picker("Method", method, METHODS.map { it to Fmt.label(it) }, { method = it }, tag = "txn-method")
            }
            if (bookingId == null) {
                if (kind in listOf("customer_receipt", "customer_refund")) Section {
                    Picker("Customer", customerId, listOf(0L to "Select") + customers.map { it.long("id") to it.str("name") }, { customerId = it }, tag = "txn-customer")
                }
                if (kind in listOf("supplier_payment", "supplier_receipt", "expense")) Section {
                    Picker(if (kind == "expense") "Supplier (optional)" else "Supplier", supplierId,
                        listOf(0L to (if (kind == "expense") "None" else "Select")) + suppliers.map { it.long("id") to it.str("name") }, { supplierId = it })
                }
            }
            if (kind in listOf("expense", "other_income")) Section {
                Picker("Category", category, categories.map { it to it }, { category = it }, tag = "txn-category")
                Field("Payee / from", payee, { payee = it })
            }
            Section {
                Field("Reference (UTR, cheque no.)", reference, { reference = it }, tag = "txn-reference")
                Field("Notes", notes, { notes = it }, multiline = true)
            }
            if (kind in listOf("customer_refund", "supplier_receipt") && bookingId == null) {
                Note("To refund a booking, open it and use Refund / cancel: that records the credit and adjusts the profit first.")
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().testTag("save-transaction-bottom")) { Text("Save entry") }
        }
    }
}

// ---- accounts

@Composable
fun AccountListScreen(model: AppModel, nav: NavHostController) {
    val a = model.agency ?: return
    val rows = remember(model.revision) { a.listAccounts() }
    Screen("Accounts", onBack = { nav.popBackStack() }, actions = {
        IconButton(onClick = { nav.navigate("accountForm") }, modifier = Modifier.testTag("new-account")) { Icon(Icons.Filled.Add, "New account") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            items(rows, key = { it["id"].num }) { acc ->
                ListRow({ nav.navigate("report/cashbook?account=${acc["id"].num}") }, tag = "account-${acc["name"].text}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(acc["name"].text, fontWeight = FontWeight.Bold)
                            Note("${Fmt.label(acc["account_type"].text)} · ${acc["txn_count"].num} entries" + (if (acc["active"].num == 0L) " · inactive" else ""))
                        }
                        Text(Fmt.money(acc["balance"].num, acc["currency"].text), fontSize = 14.sp)
                    }
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
    Screen("New account", onBack = onDone, actions = {
        TextButton(onClick = { save() }, modifier = Modifier.testTag("save-account")) { Text("Save", color = Color.White) }
    }) { pad ->
        Page(pad, "account-form") {
            Section {
                Field("Name (e.g. HDFC Current)", name, { name = it }, tag = "account-name")
                Picker("Type", type, listOf("cash", "bank", "current", "savings", "upi", "credit_card", "wallet", "gateway").map { it to Fmt.label(it) }, { type = it })
                Field("Currency", currency, { currency = it }, caps = true)
                AmountField("Opening balance", opening, { opening = it })
                Field("Bank name", bank, { bank = it })
                Field("Last 4 digits only", last4, { last4 = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            }
            Note("Only the last 4 digits of an account or card number are kept.")
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save account") }
        }
    }
}
