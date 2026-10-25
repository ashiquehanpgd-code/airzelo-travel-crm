package com.airzelo.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AssignmentReturn
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airzelo.app.ui.Az
import com.airzelo.app.ui.AzCard
import com.airzelo.app.ui.AzScreen
import com.airzelo.app.ui.Banner
import com.airzelo.app.ui.IconChip
import com.airzelo.app.ui.Muted
import com.airzelo.app.ui.Overline
import com.airzelo.app.ui.Pill
import com.airzelo.app.ui.Tone
import com.airzelo.engine.OnlineFeature

@Composable
fun AppRoot(model: AppModel, onUnlock: () -> Unit) {
    val problem = model.startupProblem
    val unlocked = model.lock.unlocked
    // the screens are built only after the first unlock, and then kept while the app re-locks, so unlocking returns to
    // the same screen with any half-filled form intact; while locked they are not drawn and not readable by accessibility
    var opened by rememberSaveable { mutableStateOf(false) }
    if (unlocked) opened = true
    Box(Modifier.fillMaxSize().background(Az.colors.bg)) {
        if (problem == null && opened && model.agency != null) {
            Box(if (unlocked) Modifier.fillMaxSize() else Modifier.fillMaxSize().alpha(0f).clearAndSetSemantics {}) { MainNav(model) }
        }
        when {
            problem != null -> StartupProblem(model, problem)
            model.lock.state == AppLock.State.NO_SCREEN_LOCK -> Cover { NoScreenLockScreen(onUnlock) }
            !unlocked -> Cover { LockScreen(model, onUnlock) }
            model.agency == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
    ProblemDialog(model)
}

/** An opaque layer that takes every touch, so nothing underneath can be reached while the app is locked. */
@Composable
private fun Cover(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Az.colors.hero).pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }) { content() }
}

@Composable
private fun BrandMark() {
    Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.FlightTakeoff, null, tint = Color.White, modifier = Modifier.size(40.dp))
    }
}

@Composable
fun LockScreen(model: AppModel, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp).testTag("lock-screen"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Spacer(Modifier.height(60.dp))
        BrandMark()
        Text("AirZelo", style = MaterialTheme.typography.displaySmall, color = Color.White)
        Text("Your agency's books are stored only on this phone. Unlock with your fingerprint, face or screen lock.",
            style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        model.lock.message?.let {
            Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .background(Color(0x33D33447)).padding(12.dp).testTag("lock-message"))
        }
        Button(onClick = onUnlock, enabled = model.lock.state != AppLock.State.AUTHENTICATING, shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Az.colors.navy),
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("unlock")) {
            Icon(Icons.Rounded.Fingerprint, null); Spacer(Modifier.width(10.dp)); Text("Unlock", fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WifiOff, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text("Works offline · nothing leaves this phone", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

/** No PIN, pattern or password on the phone: the books stay closed, and this explains how to set one. */
@Composable
fun NoScreenLockScreen(onCheckAgain: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("no-screen-lock"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Text("Set up a screen lock", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text("This phone has no screen lock. AirZelo opens your agency's books only after the phone confirms it is you.",
            style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
        Button(onClick = {
            MainActivity.expectingExternal = true
            val intent = android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            runCatching { ctx.startActivity(intent) }.onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Az.colors.navy),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("open-security-settings")) { Text("Open screen lock settings", fontWeight = FontWeight.SemiBold) }
        OutlinedButton(onClick = onCheckAgain, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("check-again")) { Text("Check again") }
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, step) in listOf("Open Settings > Security (or Security & privacy) > Screen lock.", "Choose PIN, Pattern or Password and set it.",
                "Optional: add your fingerprint or face in the same place.", "Come back here and tap Check again.").withIndex()) {
                Text("${i + 1}.  $step", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("AirZelo never sees your PIN, pattern, password, fingerprint or face: Android checks them and only tells the app whether it is you.",
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
fun StartupProblem(model: AppModel, problem: String) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        IconChip(Icons.Rounded.WarningAmber, Tone.Warn, 64.dp)
        Text("Your books could not be opened", style = MaterialTheme.typography.titleLarge, color = Az.colors.text)
        Text(problem, color = Az.colors.muted, textAlign = TextAlign.Center)
        if (model.corrupted) {
            Button(onClick = { model.recover() }, shape = RoundedCornerShape(14.dp)) { Text("Restore the latest backup") }
            Muted("A copy of the damaged files is kept aside.")
        }
    }
}

@Composable
fun ProblemDialog(model: AppModel) {
    val p = model.problem ?: return
    AlertDialog(onDismissRequest = { model.problem = null }, title = { Text(p.title) }, shape = RoundedCornerShape(24.dp), containerColor = Az.colors.surface,
        text = { Text(p.message, color = Az.colors.text2, modifier = Modifier.testTag("problem-message")) },
        confirmButton = {
            if (p.retry != null) TextButton(onClick = { model.problem = null; p.retry.invoke() }, modifier = Modifier.testTag("save-anyway")) { Text("Save anyway") }
            else TextButton(onClick = { model.problem = null }, modifier = Modifier.testTag("problem-ok")) { Text("OK") }
        },
        dismissButton = { if (p.retry != null) TextButton(onClick = { model.problem = null }) { Text("Cancel") } })
}

// ---- navigation

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val TABS = listOf(Tab("home", "Home", Icons.Rounded.Dashboard), Tab("bookings", "Bookings", Icons.Rounded.FlightTakeoff),
    Tab("customers", "Customers", Icons.Rounded.Groups), Tab("money", "Money", Icons.Rounded.AccountBalanceWallet), Tab("more", "More", Icons.Rounded.GridView))

@Composable
fun MainNav(model: AppModel) {
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route
    val onTab = route in TABS.map { it.route }
    val c = Az.colors
    Scaffold(containerColor = c.bg, contentWindowInsets = WindowInsets(0), bottomBar = {
        if (onTab) NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
            for (t in TABS) NavigationBarItem(selected = route == t.route, label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                icon = { Icon(t.icon, t.label) }, modifier = Modifier.testTag("tab-${t.route}"),
                colors = NavigationBarItemDefaults.colors(selectedIconColor = c.royal, selectedTextColor = c.royal, indicatorColor = c.royal50,
                    unselectedIconColor = c.muted, unselectedTextColor = c.muted),
                onClick = { nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } })
        }
    }) { outer ->
        Box(Modifier.padding(outer).let { if (onTab) it else it.navigationBarsPadding() }) { Routes(model, nav) }
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
        composable("txns/{kind}", listOf(navArgument("kind") { type = NavType.StringType })) {
            TransactionListScreen(model, nav, kindFilter = it.arguments!!.getString("kind") ?: "", onBack = back)
        }
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
        composable("about") { AboutScreen(model, back) }
    }
}

// ---- More

private data class MoreItem(val route: String, val label: String, val sub: String, val icon: ImageVector, val tone: Tone)
private val MORE = listOf(
    "Books" to listOf(MoreItem("reports", "Reports", "13 reports, CSV export", Icons.Rounded.BarChart, Tone.Blue),
        MoreItem("invoices", "Invoices", "Invoices & credit notes", Icons.Rounded.ReceiptLong, Tone.Violet),
        MoreItem("suppliers", "Suppliers", "Portals, airlines, agents", Icons.Rounded.Storefront, Tone.Cyan),
        MoreItem("accounts", "Cash & bank", "Balances and cashbook", Icons.Rounded.AccountBalance, Tone.Green),
        MoreItem("expenses", "Expenses", "Office and booking costs", Icons.Rounded.Payments, Tone.Red),
        MoreItem("report/refunds", "Refunds", "Refunds & cancellations", Icons.Rounded.AssignmentReturn, Tone.Warn),
        MoreItem("report/cashbook", "Cashbook", "All accounts", Icons.Rounded.MenuBook, Tone.Gray)),
    "Safety" to listOf(MoreItem("backup", "Backup & restore", "Encrypted backups, import", Icons.Rounded.SettingsBackupRestore, Tone.Green)),
    "Settings" to listOf(MoreItem("settings", "Company & documents", "Business details, numbering", Icons.Rounded.Tune, Tone.Blue),
        MoreItem("online", "Online features", "What needs a connection", Icons.Rounded.WifiOff, Tone.Gray),
        MoreItem("about", "About & security", "Version, privacy, data", Icons.Rounded.Info, Tone.Violet)),
)

@Composable
fun MoreScreen(nav: NavHostController) {
    AzScreen("More") {
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize().testTag("more-list"), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((group, items) in MORE) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-$group") { Overline(group, Az.colors.muted) }
                items(items, key = { it.route }) { m ->
                    AzCard(Modifier.fillMaxWidth(), onClick = { nav.navigate(m.route) }, tag = "more-${m.route}") {
                        IconChip(m.icon, m.tone)
                        Spacer(Modifier.height(12.dp))
                        Text(m.label, style = MaterialTheme.typography.titleSmall, color = Az.colors.text)
                        Muted(m.sub, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineFeaturesScreen(onBack: () -> Unit) {
    AzScreen("Online features", "Not available in this version", onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Banner("AirZelo works fully offline. These features need a live airline, portal or payment-gateway connection, which this " +
                "version does not have, so they are switched off - nothing is simulated.", Tone.Blue, Icons.Rounded.WifiOff) }
            for (f in OnlineFeature.values()) item(key = f.id) {
                AzCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.title, style = MaterialTheme.typography.titleSmall, color = Az.colors.text, modifier = Modifier.weight(1f))
                        Pill("Unavailable", Tone.Warn)
                    }
                    Spacer(Modifier.height(6.dp))
                    Muted(f.offlineAlternative)
                }
            }
        }
    }
}

@Composable
fun AboutScreen(model: AppModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    AzScreen("About & security", onBack = onBack) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AzCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip(Icons.Rounded.FlightTakeoff, Tone.Blue, 48.dp); Spacer(Modifier.width(12.dp))
                        Column { Text("AirZelo for Android", style = MaterialTheme.typography.titleMedium, color = Az.colors.text); Muted("Version $version") }
                    }
                }
            }
            val facts = listOf(
                Icons.Rounded.WifiOff to "Offline: the app has no internet permission. Nothing is synced or sent anywhere.",
                Icons.Rounded.Lock to "Opens only after your fingerprint, face or screen lock; locks again after 30 seconds away. Screenshots and the recent-apps preview are blocked.",
                Icons.Rounded.Fingerprint to "Passport numbers are encrypted with a key kept in the Android Keystore.",
                Icons.Rounded.SettingsBackupRestore to "Backups are encrypted. Exports use a passphrase you choose and open in the desktop app and on other phones.",
                Icons.Rounded.Business to "Same accounting rules as the AirZelo desktop app: amounts, tax, profit and balances agree to the paisa.")
            for ((i, f) in facts.withIndex()) item(key = "f$i") {
                AzCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) { IconChip(f.first, Tone.Green, 36.dp); Spacer(Modifier.width(12.dp))
                        Text(f.second, style = MaterialTheme.typography.bodyMedium, color = Az.colors.text2) }
                }
            }
            item { Muted("Books are stored in this app's private storage on the phone.") }
        }
    }
}
