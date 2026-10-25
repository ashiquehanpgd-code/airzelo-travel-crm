package com.airzelo.app

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AssignmentReturn
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airzelo.app.ui.ActionTile
import com.airzelo.app.ui.Avatar
import com.airzelo.app.ui.Az
import com.airzelo.app.ui.AzCard
import com.airzelo.app.ui.BreakdownBars
import com.airzelo.app.ui.DistributionBar
import com.airzelo.app.ui.EmptyState
import com.airzelo.app.ui.FilterRow
import com.airzelo.app.ui.Fmt
import com.airzelo.app.ui.HeroCard
import com.airzelo.app.ui.IconChip
import com.airzelo.app.ui.Labels
import com.airzelo.app.ui.Legend
import com.airzelo.app.ui.ListRow
import com.airzelo.app.ui.Money
import com.airzelo.app.ui.Muted
import com.airzelo.app.ui.Overline
import com.airzelo.app.ui.PayPill
import com.airzelo.app.ui.Pill
import com.airzelo.app.ui.RevenueChart
import com.airzelo.app.ui.RowDivider
import com.airzelo.app.ui.SectionTitle
import com.airzelo.app.ui.StatusPill
import com.airzelo.app.ui.Tone
import com.airzelo.app.ui.fg
import com.airzelo.app.ui.num
import com.airzelo.app.ui.text
import com.airzelo.engine.BOOKING_STATUSES
import com.airzelo.engine.Dashboard
import com.airzelo.engine.Dates
import com.airzelo.engine.JValue
import com.airzelo.engine.Ledger
import com.airzelo.engine.dashboard
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** The desktop's date presets (core.js QT.preset). */
internal fun presetRange(name: String, today: String = Dates.today()): Pair<String, String> {
    val d = LocalDate.parse(today)
    return when (name) {
        "today" -> today to today
        "week" -> d.minusDays((d.dayOfWeek.value - 1).toLong()).toString() to today
        "year" -> today.take(4) + "-01-01" to today
        "fy" -> "${if (d.monthValue >= 4) d.year else d.year - 1}-04-01" to today
        else -> today.take(8) + "01" to today
    }
}

private val PERIODS = listOf("today" to "Today", "week" to "Week", "month" to "Month", "fy" to "FY", "year" to "Year", "custom" to "Custom")

private class DashData(val d: Dashboard, val base: String, val statusCounts: List<Triple<String, Int, Tone>>,
                       val payCounts: List<Triple<String, Int, Tone>>, val periodBookings: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(model: AppModel, nav: NavHostController) {
    val a = model.agency ?: return
    var period by rememberSaveable { mutableStateOf("month") }
    var customFrom by rememberSaveable { mutableStateOf("") }
    var customTo by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val (from, to) = if (period == "custom" && customFrom.isNotEmpty()) customFrom to customTo else presetRange(period)
    val data = remember(model.revision, from, to) {
        runCatching {
            val L = Ledger(a)
            val inPeriod = L.bookingList.filter { it.bookingDate.take(10) in from..to }
            val statuses = BOOKING_STATUSES.map { s -> Triple(Labels.status[s]!!, inPeriod.count { it.status == s }, Labels.statusTone(s)) }
            val pays = listOf("unpaid", "partial", "paid", "advance", "overpaid", "refund_due", "settled").map { s ->
                Triple(Labels.pay[s]!!, inPeriod.count { it.committed && it.calc.paymentStatus == s }, Labels.payTone(s))
            }.filter { it.second > 0 || it.first in listOf("Unpaid", "Part paid", "Paid") }
            DashData(L.dashboard(from, to), L.base, statuses, pays, inPeriod.size)
        }
    }
    val periodLabel = if (period == "custom") "${Fmt.dateShort(from)} – ${Fmt.dateShort(to)}" else
        mapOf("today" to "Today", "week" to "This week", "month" to "This month", "fy" to "This financial year", "year" to "This year")[period]!!
    val name = a.setting("business_name")?.takeIf { it.isNotEmpty() } ?: "AirZelo"
    val hour = LocalTime.now().hour
    val greeting = if (hour < 12) "Good morning" else if (hour < 17) "Good afternoon" else "Good evening"
    val listState = rememberLazyListState()
    val c = Az.colors

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(c.bg).testTag("dashboard"),
        contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting, style = MaterialTheme.typography.headlineSmall, color = c.text)
                    Muted("$name · ${Fmt.longDate(Dates.today())}", maxLines = 1)
                }
                IconButton(onClick = { nav.navigate("customers") }) { Icon(Icons.Rounded.PersonSearch, "Find customer", tint = c.text2) }
                IconButton(onClick = { nav.navigate("bookingForm/0") }, modifier = Modifier.testTag("dash-new-booking")) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.royal), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, "New booking", tint = Color.White)
                    }
                }
            }
        }
        item(key = "periods") {
            FilterRow(PERIODS, period, { p -> if (p == "custom") picking = true else period = p }, tag = "period")
        }
        val dd = data.getOrNull()
        if (dd == null) {
            item(key = "error") { EmptyState(title = "The dashboard could not be calculated", body = AppModel.describe(data.exceptionOrNull()!!)) }
            return@LazyColumn
        }
        val d = dd.d; val s = d.summary; val cash = s.cash; val base = dd.base
        item(key = "hero") { Hero(d, base, periodLabel) }
        item(key = "quick") {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("quick-actions")) {
                item { ActionTile("New booking", Icons.Rounded.ConfirmationNumber, Tone.Blue, "qa-new-booking") { nav.navigate("bookingForm/0") } }
                item { ActionTile("Record payment", Icons.Rounded.ArrowDownward, Tone.Green, "qa-receipt") {
                    nav.navigate("txnForm?kind=customer_receipt&booking=0&customer=0&supplier=0&amount=0") } }
                item { ActionTile("Find customer", Icons.Rounded.PersonSearch, Tone.Cyan, "qa-customers") { nav.navigate("customers") } }
                item { ActionTile("Add expense", Icons.Rounded.Payments, Tone.Red, "qa-expense") {
                    nav.navigate("txnForm?kind=expense&booking=0&customer=0&supplier=0&amount=0") } }
                item { ActionTile("Pay supplier", Icons.Rounded.ArrowUpward, Tone.Warn, "qa-supplier") {
                    nav.navigate("txnForm?kind=supplier_payment&booking=0&customer=0&supplier=0&amount=0") } }
                item { ActionTile("Invoices", Icons.Rounded.ReceiptLong, Tone.Violet, "qa-invoices") { nav.navigate("invoices") } }
                item { ActionTile("Reports", Icons.Rounded.BarChart, Tone.Gray, "qa-reports") { nav.navigate("reports") } }
            }
        }
        item(key = "kpis") {
            val margin = if (s.sales != 0L) String.format(java.util.Locale.ROOT, "%.1f%% margin", 100.0 * s.grossProfit / s.sales) else "No sales yet"
            val kpis = listOf(
                Kpi("Net profit", s.netProfit, Icons.Rounded.Percent, if (s.netProfit < 0) Tone.Red else Tone.Green, "after ${Fmt.money(s.operatingExpenses, base)} expenses", "report/profit", signed = true),
                Kpi("Payments received", cash.customerReceipts, Icons.Rounded.ArrowDownward, Tone.Cyan, "actual cash in", "txns/customer_receipt"),
                Kpi("Customer outstanding", d.customerOutstanding, Icons.Rounded.Groups, if (d.customerOutstanding != 0L) Tone.Warn else Tone.Gray,
                    "${d.pendingCount} bookings pending", "report/customer_outstanding"),
                Kpi("Supplier outstanding", d.supplierOutstanding, Icons.Rounded.Storefront, if (d.supplierOutstanding != 0L) Tone.Red else Tone.Gray,
                    if (d.supplierCreditBalances != 0L) "${Fmt.money(d.supplierCreditBalances, base)} due from suppliers" else "owed to portals", "report/supplier_outstanding"),
                Kpi("Gross profit", s.grossProfit, Icons.Rounded.Savings, Tone.Green, margin, "report/profit", signed = true),
                Kpi("Ticket purchase cost", s.cost, Icons.Rounded.ConfirmationNumber, Tone.Gray, "net of supplier credits", "report/supplier_purchases"),
                Kpi("Ticket markup", s.markup, Icons.Rounded.TrendingUp, Tone.Violet, "selling − purchase", "report/profit"),
                Kpi("Commission earned", s.commission, Icons.Rounded.Percent, Tone.Cyan,
                    if (s.commissionReversed != 0L) "${Fmt.money(s.commissionReversed, base)} reversed" else "net of reversals", "report/commission"),
                Kpi("Operating expenses", s.operatingExpenses, Icons.Rounded.Payments, Tone.Red, "${Fmt.money(s.directExpenses, base)} direct booking costs", "expenses"),
                Kpi("Refunds & cancellations", null, Icons.Rounded.AssignmentReturn, Tone.Warn, "${Fmt.money(cash.customerRefunds, base)} refunded to customers",
                    "report/refunds", count = d.cancellationsCount.toString()),
                Kpi("Net cash flow", cash.netCashFlow, Icons.Rounded.SwapHoriz, if (cash.netCashFlow < 0) Tone.Red else Tone.Green,
                    "${Fmt.money(cash.cashIn, base)} in · ${Fmt.money(cash.cashOut, base)} out", "report/cashbook", signed = true))
            Column {
                SectionTitle("Key figures · $periodLabel", Modifier.padding(horizontal = 20.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("kpi-row")) {
                    items(kpis, key = { it.label }) { k -> KpiCard(k, base) { nav.navigate(k.route) } }
                }
            }
        }
        item(key = "periodcards") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(listOf(Triple("Today", d.today, Tone.Blue), Triple("This month", d.month, Tone.Cyan), Triple("This year", d.year, Tone.Green)), key = { it.first }) { (label, p, tone) ->
                    AzCard(Modifier.width(196.dp)) {
                        Overline(label, tone.fg())
                        Spacer(Modifier.height(6.dp))
                        Money(p.sales, base, MaterialTheme.typography.titleLarge)
                        Muted("${p.bookings} booking${if (p.bookings == 1L) "" else "s"} · gross profit ${Fmt.money(p.grossProfit, base)}", maxLines = 2)
                    }
                }
            }
        }
        item(key = "chart") {
            Card(title = "Revenue & profit", sub = periodLabel) {
                if (d.chart.none { it["sales"].num != 0L || it["gross_profit"].num != 0L }) {
                    EmptyState(Icons.Rounded.ShowChart, "No sales in this period", "Bookings you confirm or ticket will appear here.")
                } else {
                    Legend(listOf("Net sales" to c.royal, "Gross profit" to c.success))
                    Spacer(Modifier.height(10.dp))
                    RevenueChart(d.chart, base, Modifier.testTag("revenue-chart"))
                }
            }
        }
        item(key = "breakdown") {
            Card(title = "Profit breakdown", sub = periodLabel) {
                BreakdownBars(listOf("Markup" to s.markup, "Commission" to s.commission, "Service charges" to s.service,
                    "Other booking income" to s.otherBookingIncome,
                    "Credits & adjustments" to (s.sales - (s.selling + s.service + s.otherBookingIncome) - (s.cost - s.purchase)),
                    "Direct booking costs" to -s.directExpenses, "Other income" to s.otherIncome, "Operating expenses" to -s.operatingExpenses), base)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Net profit", style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
                    Money(s.netProfit, base, MaterialTheme.typography.titleMedium, signed = true, modifier = Modifier.testTag("dash-net-profit"))
                }
                Spacer(Modifier.height(6.dp))
                Row { Muted("Tax collected (liability, not profit)", Modifier.weight(1f)); Money(s.tax, base, MaterialTheme.typography.bodySmall, color = c.muted) }
            }
        }
        item(key = "status") {
            Card(title = "Bookings by status", sub = "${dd.periodBookings} booked · $periodLabel") {
                if (dd.periodBookings == 0) Muted("No bookings were made in this period.") else {
                    DistributionBar(dd.statusCounts.filter { it.second > 0 })
                    Spacer(Modifier.height(16.dp))
                    Overline("Payment status (confirmed & ticketed)")
                    Spacer(Modifier.height(8.dp))
                    DistributionBar(dd.payCounts)
                }
            }
        }
        item(key = "pending") {
            Card(title = "Pending customer payments", sub = "${d.pendingCount} bookings · ${Fmt.money(d.customerOutstanding, base)}", action = "All",
                onAction = { nav.navigate("report/customer_outstanding") }, flush = true) {
                if (d.pending.isEmpty()) EmptyState(Icons.Rounded.CheckCircle, "All customer payments are up to date")
                d.pending.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    ListRow(p["customer_name"].text, listOf(p["ref"].text, p["pnr"].text, p["travel_date"].text.let { if (it.isEmpty()) "" else "travels " + Fmt.dateShort(it) })
                        .filter { it.isNotEmpty() }.joinToString(" · "), onClick = { nav.navigate("booking/${p["id"].num}") }, tag = "pending-${p["ref"].text}",
                        leading = { Avatar(p["customer_name"].text, Tone.Warn) }) { Money(p["customer_outstanding"].num, base, color = c.danger) }
                }
            }
        }
        item(key = "upcoming") {
            Card(title = "Upcoming departures", sub = "next 14 days", flush = true) {
                if (d.upcoming.isEmpty()) EmptyState(Icons.Rounded.Flight, "No departures in the next 14 days")
                d.upcoming.forEachIndexed { i, u ->
                    if (i > 0) RowDivider()
                    val route = if (u["origin"].text.isNotEmpty() && u["destination"].text.isNotEmpty()) "${u["origin"].text} → ${u["destination"].text}" else u["ref"].text
                    ListRow("$route  ${u["flight_no"].text}".trim(), "${u["lead_pax"].text}${if (u["pax_count"].num > 1) " +" + (u["pax_count"].num - 1) else ""} · ${u["pnr"].text.ifEmpty { u["ref"].text }}",
                        onClick = { nav.navigate("booking/${u["booking_id"].num}") }, leading = { IconChip(Icons.Rounded.Flight, Tone.Blue) }) {
                        Text(Fmt.dateShort(u["dep_at"].text), style = MaterialTheme.typography.titleSmall, color = c.text)
                        Muted(Fmt.time(u["dep_at"].text))
                        if (u["outstanding"].num > 0) Pill("Due " + Fmt.money(u["outstanding"].num, base), Tone.Warn)
                    }
                }
            }
        }
        item(key = "follow") {
            Card(title = "Follow-ups", sub = "due & upcoming", flush = true) {
                if (d.followUps.isEmpty()) EmptyState(Icons.Rounded.NotificationsActive, "No follow-ups scheduled", "Add one from a customer profile.")
                d.followUps.forEachIndexed { i, f ->
                    if (i > 0) RowDivider()
                    val overdue = f["overdue"] == JValue.Bool(true)
                    ListRow(f["name"].text, f["follow_up_note"].text.ifEmpty { f["phone"].text }, onClick = { nav.navigate("customer/${f["customer_id"].num}") },
                        leading = { IconChip(Icons.Rounded.NotificationsActive, if (overdue) Tone.Red else Tone.Cyan) }) {
                        Text((if (overdue) "Overdue · " else "") + Fmt.dateShort(f["follow_up_date"].text), style = MaterialTheme.typography.labelLarge,
                            color = if (overdue) c.danger else c.text2)
                    }
                }
            }
        }
        item(key = "recent") {
            Card(title = "Recent bookings", action = "View all", onAction = { nav.navigate("bookings") }, flush = true) {
                if (d.recentBookings.isEmpty()) EmptyState(Icons.Rounded.ConfirmationNumber, "No bookings yet", "Create your first booking to get started.",
                    "New booking") { nav.navigate("bookingForm/0") }
                d.recentBookings.forEachIndexed { i, r ->
                    if (i > 0) RowDivider()
                    ListRow(r["lead_pax"].text.ifEmpty { r["customer_name"].text }, listOf(r["ref"].text, r["pnr"].text, r["route"].text).filter { it.isNotEmpty() }.joinToString(" · "),
                        onClick = { nav.navigate("booking/${r["id"].num}") }, leading = { Avatar(r["customer_name"].text) }) {
                        StatusPill(r["status"].text)
                        if (r["customer_outstanding"].num > 0) Money(r["customer_outstanding"].num, base, MaterialTheme.typography.labelLarge, color = c.danger)
                    }
                }
            }
        }
        item(key = "accounts") {
            Card(title = "Cash & bank", action = "Manage", onAction = { nav.navigate("accounts") }, flush = true) {
                val acc = d.accounts.filter { it.active }
                if (acc.isEmpty()) EmptyState(Icons.Rounded.AccountBalance, "No accounts")
                acc.forEachIndexed { i, ac ->
                    if (i > 0) RowDivider()
                    ListRow(ac.name, "${Labels.title(ac.kind)} · ${ac.currency}", onClick = { nav.navigate("report/cashbook?account=${ac.accountId}") },
                        leading = { IconChip(if (ac.kind == "cash") Icons.Rounded.Wallet else Icons.Rounded.AccountBalance, if (ac.kind == "cash") Tone.Green else Tone.Cyan) }) {
                        Money(ac.balance, ac.currency, color = if (ac.balance < 0) c.danger else c.text)
                    }
                }
            }
        }
        item(key = "txns") {
            Card(title = "Recent transactions", action = "View all", onAction = { nav.navigate("money") }, flush = true) {
                if (d.recentTransactions.isEmpty()) EmptyState(Icons.Rounded.ReceiptLong, "No transactions recorded yet")
                d.recentTransactions.forEachIndexed { i, t ->
                    if (i > 0) RowDivider()
                    TransactionRow(t) { nav.navigate("txn/${t["id"].num}") }
                }
            }
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = (customFrom.ifEmpty { from }).let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
            initialSelectedEndDateMillis = (customTo.ifEmpty { to }).let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() })
        DatePickerDialog(onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    val s0 = state.selectedStartDateMillis; val e0 = state.selectedEndDateMillis ?: s0
                    if (s0 != null && e0 != null) {
                        customFrom = Instant.ofEpochMilli(s0).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        customTo = Instant.ofEpochMilli(e0).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        period = "custom"
                    }
                    picking = false
                }) { Text("Apply") }
            }, dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }) {
            DateRangePicker(state, Modifier.heightIn(max = 520.dp), title = { Text("Dashboard period", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}

@Composable
private fun Hero(d: Dashboard, base: String, periodLabel: String) {
    val s = d.summary
    val margin = if (s.sales != 0L) String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * s.grossProfit / s.sales) else "–"
    HeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("dashboard-hero")) {
        Text("Net sales (ex tax) · $periodLabel", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.72f))
        Spacer(Modifier.height(4.dp))
        Text(Fmt.money(s.sales, base), style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("hero-sales"))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            HeroStat("Bookings", s.bookings.toString(), Modifier.weight(1f))
            HeroStat("Gross profit", Fmt.compact(s.grossProfit, base), Modifier.weight(1f))
            HeroStat("Margin", margin, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

private class Kpi(val label: String, val value: Long?, val icon: ImageVector, val tone: Tone, val sub: String, val route: String,
                  val signed: Boolean = false, val count: String? = null)

@Composable
private fun KpiCard(k: Kpi, base: String, onClick: () -> Unit) {
    AzCard(Modifier.width(184.dp).height(150.dp), onClick = onClick, tag = "kpi-${k.label}") {
        IconChip(k.icon, k.tone, 36.dp)
        Spacer(Modifier.height(10.dp))
        Text(k.label, style = MaterialTheme.typography.labelMedium, color = Az.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (k.count != null) Text(k.count, style = MaterialTheme.typography.titleLarge, color = Az.colors.text)
        else Money(k.value ?: 0, base, MaterialTheme.typography.titleLarge, signed = k.signed)
        Muted(k.sub, maxLines = 2)
    }
}

/** A dashboard card with a title row; [flush] content has no side padding (for list rows). */
@Composable
private fun Card(title: String, sub: String? = null, action: String? = null, onAction: (() -> Unit)? = null, flush: Boolean = false,
                 content: @Composable () -> Unit) {
    AzCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = if (flush) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Az.colors.text)
                if (sub != null) Muted(sub, maxLines = 1)
            }
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
        Column(Modifier.padding(start = if (flush) 0.dp else 16.dp, end = if (flush) 0.dp else 16.dp, bottom = if (flush) 6.dp else 16.dp)) { content() }
    }
}
