package com.airzelo.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airzelo.engine.Dates
import com.airzelo.engine.Ledger
import com.airzelo.engine.OnlineFeature
import com.airzelo.engine.dashboard

@Composable
fun AppRoot(model: AppModel, onUnlock: () -> Unit) {
    val problem = model.startupProblem
    when {
        problem != null -> StartupProblem(model, problem)
        model.lock.state == AppLock.State.NO_SCREEN_LOCK -> NoScreenLockScreen(onUnlock)
        !model.lock.unlocked -> LockScreen(model, onUnlock)
        model.agency != null -> MainNav(model)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
    ProblemDialog(model)
}

@Composable
fun LockScreen(model: AppModel, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp).testTag("lock-screen"), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("AirZelo", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text("Your agency's books are stored only on this phone. Unlock with your fingerprint, face or screen lock.",
            color = Muted, textAlign = TextAlign.Center)
        model.lock.message?.let { Text(it, color = Bad, textAlign = TextAlign.Center, modifier = Modifier.testTag("lock-message")) }
        Button(onClick = onUnlock, enabled = model.lock.state != AppLock.State.AUTHENTICATING,
            modifier = Modifier.fillMaxWidth().testTag("unlock")) { Text("Unlock") }
    }
}

/** No PIN, pattern or password on the phone: the books stay closed, and this explains how to set one. */
@Composable
fun NoScreenLockScreen(onCheckAgain: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().padding(28.dp).testTag("no-screen-lock"), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Text("Set up a screen lock", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text("AirZelo keeps your agency's books on this phone and opens them only after the phone confirms it is you. " +
            "This phone has no screen lock, so anyone holding it could open them.")
        Text("1. Open Settings > Security (or Security & privacy) > Screen lock.\n" +
            "2. Choose PIN, Pattern or Password and set it.\n" +
            "3. Optional: add your fingerprint or face in the same place.\n" +
            "4. Come back here and tap Check again.")
        Note("AirZelo never sees your PIN, pattern, password, fingerprint or face: Android checks them and only tells the app " +
            "whether it is you.")
        Button(onClick = {
            MainActivity.expectingExternal = true
            val intent = android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            runCatching { ctx.startActivity(intent) }.onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }, modifier = Modifier.fillMaxWidth().testTag("open-security-settings")) { Text("Open screen lock settings") }
        androidx.compose.material3.OutlinedButton(onClick = onCheckAgain, modifier = Modifier.fillMaxWidth().testTag("check-again")) { Text("Check again") }
    }
}

@Composable
fun StartupProblem(model: AppModel, problem: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your books could not be opened", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(problem, color = Muted, textAlign = TextAlign.Center)
        if (model.corrupted) {
            Button(onClick = { model.recover() }) { Text("Restore the latest backup") }
            Note("A copy of the damaged files is kept aside.")
        }
    }
}

private val TABS = listOf(Triple("home", "Home", Icons.Filled.Home), Triple("bookings", "Bookings", Icons.Filled.List),
    Triple("customers", "Customers", Icons.Filled.AccountCircle), Triple("money", "Money", Icons.Filled.ShoppingCart), Triple("more", "More", Icons.Filled.Menu))

@Composable
fun MainNav(model: AppModel) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    Scaffold(bottomBar = {
        if (route in TABS.map { it.first }) NavigationBar {
            for ((r, label, icon) in TABS) NavigationBarItem(selected = route == r, label = { Text(label) }, icon = { Icon(icon, label) },
                modifier = Modifier.testTag("tab-$r"),
                onClick = { nav.navigate(r) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } })
        }
    }) { outer ->
        Box(Modifier.padding(outer)) { Routes(model, nav) }
    }
}

@Composable
private fun Routes(model: AppModel, nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    val idArg = listOf(navArgument("id") { type = NavType.LongType })
    NavHost(nav, startDestination = "home") {
        composable("home") { DashboardScreen(model, nav) }
        composable("bookings") { BookingListScreen(model, nav) }
        composable("customers") { CustomerListScreen(model, nav) }
        composable("money") { TransactionListScreen(model, nav, kindFilter = "") }
        composable("more") { MoreScreen(nav) }
        composable("booking/{id}", idArg) { BookingDetailScreen(model, nav, it.arguments!!.getLong("id")) }
        composable("bookingForm/{id}", idArg) { BookingFormScreen(model, it.arguments!!.getLong("id").takeIf { id -> id > 0 }, back) }
        composable("refund/{id}", idArg) { RefundScreen(model, it.arguments!!.getLong("id"), back) }
        composable("txnForm?kind={kind}&booking={booking}&customer={customer}&supplier={supplier}&amount={amount}",
            listOf("kind", "booking", "customer", "supplier", "amount").map { n ->
                if (n == "kind") navArgument(n) { type = NavType.StringType; defaultValue = "expense" }
                else navArgument(n) { type = NavType.LongType; defaultValue = 0L }
            }) {
            val a = it.arguments!!
            TransactionFormScreen(model, a.getString("kind") ?: "expense", a.getLong("booking").takeIf { v -> v > 0 },
                a.getLong("customer").takeIf { v -> v > 0 }, a.getLong("supplier").takeIf { v -> v > 0 }, a.getLong("amount"), back)
        }
        composable("txn/{id}", idArg) { TransactionDetailScreen(model, nav, it.arguments!!.getLong("id")) }
        composable("customer/{id}", idArg) { CustomerDetailScreen(model, nav, it.arguments!!.getLong("id")) }
        composable("customerForm/{id}", idArg) { CustomerFormScreen(model, it.arguments!!.getLong("id").takeIf { id -> id > 0 }, back) }
        composable("suppliers") { SupplierListScreen(model, nav) }
        composable("supplierForm/{id}", idArg) { SupplierFormScreen(model, it.arguments!!.getLong("id").takeIf { id -> id > 0 }, back) }
        composable("accounts") { AccountListScreen(model, nav) }
        composable("accountForm") { AccountFormScreen(model, back) }
        composable("expenses") { TransactionListScreen(model, nav, kindFilter = "expense", onBack = back) }
        composable("reports") { ReportListScreen(nav) }
        composable("report/{name}?account={account}", listOf(navArgument("name") { type = NavType.StringType },
            navArgument("account") { type = NavType.LongType; defaultValue = 0L })) {
            ReportScreen(model, it.arguments!!.getString("name")!!, it.arguments!!.getLong("account").takeIf { v -> v > 0 }, back)
        }
        composable("invoices") { InvoiceListScreen(model, nav) }
        composable("invoice/{id}", idArg) { InvoiceDetailScreen(model, it.arguments!!.getLong("id"), back) }
        composable("pdf") { PdfScreen(model, back) }
        composable("backup") { BackupScreen(model, back) }
        composable("settings") { SettingsScreen(model, back) }
        composable("online") { OnlineFeaturesScreen(back) }
    }
}

@Composable
fun MoreScreen(nav: NavHostController) {
    Screen("More") { pad ->
        LazyColumn(Modifier.padding(pad).testTag("more-list")) {
            val items = listOf("reports" to "Reports", "invoices" to "Invoices & credit notes", "suppliers" to "Suppliers",
                "accounts" to "Cash & bank accounts (cashbook)", "expenses" to "Expenses", "report/refunds" to "Refunds & cancellations",
                "backup" to "Backup & restore", "settings" to "Company & documents", "online" to "Online features")
            items(items) { (r, label) -> ListRow({ nav.navigate(r) }, tag = "more-$r") { Text(label) } }
        }
    }
}

@Composable
fun OnlineFeaturesScreen(onBack: () -> Unit) {
    Screen("Online features", onBack) { pad ->
        Page(pad) {
            Note("AirZelo works fully offline. These features need a live airline, portal or payment-gateway connection, which this " +
                "version does not have, so they are switched off - nothing is simulated.")
            for (f in OnlineFeature.values()) Section(f.title) {
                Text("Unavailable", color = Warn, fontWeight = FontWeight.Bold)
                Text(f.offlineAlternative, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(model: AppModel, nav: NavHostController) {
    var period by rememberSaveable { mutableStateOf("month") }
    val today = Dates.today()
    val range = when (period) {
        "today" -> today to today
        "year" -> today.take(4) + "-01-01" to today
        "fy" -> { val y = today.take(4).toInt(); val m = today.substring(5, 7).toInt(); "${if (m >= 4) y else y - 1}-04-01" to today }
        else -> today.take(8) + "01" to today
    }
    val a = model.agency ?: return
    val dash = remember(model.revision, period) { runCatching { Ledger(a).dashboard(range.first, range.second) }.getOrNull() }
    val base = a.baseCurrency
    Screen(a.setting("business_name")?.takeIf { it.isNotEmpty() } ?: "AirZelo") { pad ->
        Page(pad, "dashboard") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val ps = listOf("today" to "Today", "month" to "Month", "fy" to "FY", "year" to "Year")
                ps.forEachIndexed { i, (k, l) ->
                    SegmentedButton(selected = period == k, onClick = { period = k }, shape = SegmentedButtonDefaults.itemShape(i, ps.size)) { Text(l) }
                }
            }
            if (dash == null) return@Page
            val s = dash.summary
            Section("Profit") {
                LabeledValue("Bookings", s.bookings.toString())
                FigureRow("Net sales (ex tax)", s.sales, base)
                FigureRow("Gross profit", s.grossProfit, base, strong = true)
                FigureRow("Operating expenses", s.operatingExpenses, base)
                FigureRow("Net profit", s.netProfit, base, strong = true)
                FigureRow("Tax collected (liability)", s.tax, base)
            }
            Section("Cash") {
                FigureRow("Money in", s.cash.cashIn, base)
                FigureRow("Money out", s.cash.cashOut, base)
                FigureRow("Net cash flow", s.cash.netCashFlow, base, strong = true)
            }
            Section("Who owes whom") {
                FigureRow("Customers owe you", dash.customerOutstanding, base, strong = true, tag = "dash-customer-outstanding")
                FigureRow("You owe suppliers", dash.supplierOutstanding, base, strong = true)
                if (dash.customerCreditBalances > 0) FigureRow("Customer advances / refunds due", dash.customerCreditBalances, base)
                if (dash.supplierCreditBalances > 0) FigureRow("Supplier credits due to you", dash.supplierCreditBalances, base)
            }
            Section("Accounts") {
                for (acc in dash.accounts.filter { it.active }) FigureRow(acc.name, acc.balance, acc.currency)
            }
            if (dash.pending.isNotEmpty()) Section("Payment pending (${dash.pendingCount})") {
                for (r in dash.pending) Row(Modifier.fillMaxWidth().clickable { nav.navigate("booking/${r["id"].num}") }.padding(vertical = 4.dp)
                    .testTag("pending-${r["ref"].text}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(r["customer_name"].text, fontWeight = FontWeight.Bold)
                        Note("${r["ref"].text}  ${r["pnr"].text}")
                    }
                    MoneyText(r["customer_outstanding"].num, base, colored = true)
                }
            }
            if (dash.upcoming.isNotEmpty()) Section("Departures in the next 14 days") {
                for (u in dash.upcoming) Column {
                    Text("${u["origin"].text} -> ${u["destination"].text}  ${u["flight_no"].text}", fontWeight = FontWeight.Bold)
                    Note("${u["dep_at"].text.replace("T", " ")}  ·  ${u["lead_pax"].text}")
                }
            }
            if (dash.followUps.isNotEmpty()) Section("Follow-ups") {
                for (fu in dash.followUps) Column {
                    Text(fu["name"].text, fontWeight = FontWeight.Bold)
                    Note("${fu["follow_up_date"].text} · ${fu["follow_up_note"].text}", if (fu["overdue"].text == "True") Bad else Muted)
                }
            }
        }
    }
}
