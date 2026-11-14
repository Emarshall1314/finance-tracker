package ca.emarshall.finance.ui.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.ui.AccountsRoute
import ca.emarshall.finance.ui.BillsRoute
import ca.emarshall.finance.ui.ImportRoute
import ca.emarshall.finance.ui.LabeledRow
import ca.emarshall.finance.ui.MoneyText
import ca.emarshall.finance.ui.QuestradeRoute
import ca.emarshall.finance.ui.RemindersRoute
import ca.emarshall.finance.ui.SHORT_DAY
import ca.emarshall.finance.ui.SHORT_MONTH
import ca.emarshall.finance.ui.SectionCard
import ca.emarshall.finance.ui.ShareBar
import ca.emarshall.finance.ui.StatTile
import ca.emarshall.finance.ui.TransactionsRoute
import ca.emarshall.finance.ui.pretty
import ca.emarshall.finance.ui.theme.incomeColor
import ca.emarshall.finance.ui.theme.spendColor
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(nav: NavController, vm: OverviewViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Overview") })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!s.loading && !s.checklist.complete && !s.checklist.dismissed) {
                SetupChecklist(s.checklist, nav, onDismiss = vm::dismissChecklist)
            }
            if (!s.loading && s.hasTransactions) {
                MonthPicker(s.month, vm::previousMonth, vm::nextMonth)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                    val tile = Modifier.weight(1f).widthIn(min = 140.dp)
                    StatTile("Income", Money.formatWhole(s.totals.incomeCents), tile, incomeColor())
                    StatTile("Spending", Money.formatWhole(s.totals.spendingCents), tile, spendColor())
                    StatTile("Net saved", Money.formatWhole(s.totals.netCents), tile, if (s.totals.netCents >= 0) incomeColor() else spendColor())
                    StatTile("Savings rate", s.totals.savingsRatePercent?.let { "$it%" } ?: "–", tile)
                }
                SpendingByCategory(s, nav)
                IncomeVsSpendingChart(s)
                if (s.topMerchants.isNotEmpty()) {
                    SectionCard("Top merchants — ${s.month.format(SHORT_MONTH)}") {
                        s.topMerchants.forEach { m -> LabeledRow("${m.merchant} (${m.count})") { MoneyText(-m.cents, colored = false) } }
                    }
                }
                SectionCard("Fees and interest — ${s.month.format(SHORT_MONTH)}") {
                    if (s.feesCents == 0L) Text("None this month.") else MoneyText(-s.feesCents, bold = true)
                }
                SectionCard("Upcoming bills") {
                    if (s.upcomingBills.isEmpty()) Text("No recurring bills detected yet.")
                    s.upcomingBills.take(6).forEach { b ->
                        LabeledRow("${b.merchant} · ${b.nextExpected.format(SHORT_DAY)}") { MoneyText(b.typicalAmountCents, b.currency, colored = false) }
                    }
                    TextButton(onClick = { nav.navigate(BillsRoute) }) { Text("All bills & subscriptions") }
                }
            }
            if (!s.loading && (s.netWorthCents != null)) NetWorthCard(s)
            if (s.freshness.isNotEmpty()) FreshnessCard(s, nav)
            if (!s.loading && !s.hasTransactions && s.checklist.dismissed) {
                Text("Import a statement to see your dashboard.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SetupChecklist(c: Checklist, nav: NavController, onDismiss: () -> Unit) {
    SectionCard("Get set up") {
        ChecklistItem("Add your accounts", c.hasAccounts) { nav.navigate(AccountsRoute) }
        ChecklistItem("Import your first statements", c.hasImports) { nav.navigate(ImportRoute()) }
        ChecklistItem("Connect Questrade (read-only)", c.questradeConnected) { nav.navigate(QuestradeRoute) }
        ChecklistItem("Set statement reminders", c.hasReminders) { nav.navigate(RemindersRoute) }
        TextButton(onClick = onDismiss) { Text("Hide checklist") }
    }
}

@Composable
private fun ChecklistItem(label: String, done: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "Not done",
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MonthPicker(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
        Text(month.pretty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onNext, enabled = month < YearMonth.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}

@Composable
private fun SpendingByCategory(s: OverviewState, nav: NavController) {
    SectionCard("Spending by category") {
        if (s.categories.isEmpty()) Text("No spending this month.")
        val max = s.categories.maxOfOrNull { it.first.cents } ?: 1L
        s.categories.forEach { (c, id) ->
            Column(
                Modifier.fillMaxWidth().clickable(enabled = id != null) {
                    nav.navigate(TransactionsRoute(categoryId = id ?: -1, month = s.month.toString()))
                }.padding(vertical = 4.dp),
            ) {
                LabeledRow(c.categoryName) { MoneyText(-c.cents, colored = false) }
                ShareBar((c.cents.toFloat() / max.toFloat()), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun IncomeVsSpendingChart(s: OverviewState) {
    if (s.series.isEmpty()) return
    SectionCard("Income vs spending, 12 months") {
        val producer = remember { CartesianChartModelProducer() }
        val labels = s.series.map { it.month.format(SHORT_MONTH) }
        LaunchedEffect(s.series) {
            // Whole dollars for plotting only; the numbers shown elsewhere stay exact cents.
            producer.runTransaction {
                columnSeries {
                    series(s.series.map { it.incomeCents / 100 })
                    series(s.series.map { it.spendingCents / 100 })
                }
                lineSeries { series(s.series.map { it.netCents / 100 }) }
            }
        }
        ProvideVicoTheme(rememberM3VicoTheme()) {
            CartesianChartHost(
                rememberCartesianChart(
                    rememberColumnCartesianLayer(),
                    rememberLineCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        valueFormatter = CartesianValueFormatter { _, x, _ -> labels.getOrElse(x.toInt()) { "" } },
                    ),
                ),
                producer,
                Modifier.fillMaxWidth().height(220.dp),
            )
        }
        Text("Bars: income and spending. Line: net saved.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NetWorthCard(s: OverviewState) {
    SectionCard("Net worth") {
        MoneyText(s.netWorthCents ?: 0, style = MaterialTheme.typography.headlineSmall, colored = false, bold = true)
        Text("Latest bank balances + Questrade equity − card balances", style = MaterialTheme.typography.bodySmall)
        s.balances.forEach { b -> LabeledRow("${b.nickname} · ${b.asOf.format(SHORT_DAY)}") { MoneyText(b.balanceCents, b.currency) } }
        val trend = s.netWorthTrend.filter { it.second != 0L }
        if (trend.size >= 2) {
            val producer = remember { CartesianChartModelProducer() }
            LaunchedEffect(trend) { producer.runTransaction { lineSeries { series(trend.map { it.second / 100 }) } } }
            ProvideVicoTheme(rememberM3VicoTheme()) {
                CartesianChartHost(
                    rememberCartesianChart(
                        rememberLineCartesianLayer(),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = CartesianValueFormatter { _, x, _ -> trend.getOrNull(x.toInt())?.first?.format(SHORT_MONTH) ?: "" },
                        ),
                    ),
                    producer,
                    Modifier.fillMaxWidth().height(180.dp),
                )
            }
        }
    }
}

@Composable
private fun FreshnessCard(s: OverviewState, nav: NavController) {
    SectionCard("Data freshness") {
        s.freshness.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clickable { nav.navigate(ImportRoute(accountId = f.accountId)) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (f.stale) Icon(Icons.Default.Warning, contentDescription = "Out of date", tint = Color(0xFFE6A100))
                Text(f.nickname, Modifier.weight(1f))
                Text(f.dataThrough?.let { "data through ${it.format(SHORT_DAY)}" } ?: "no data yet", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (s.freshness.any { it.stale }) Text("Accounts with a warning haven't been imported in over 35 days.", style = MaterialTheme.typography.bodySmall)
    }
}
