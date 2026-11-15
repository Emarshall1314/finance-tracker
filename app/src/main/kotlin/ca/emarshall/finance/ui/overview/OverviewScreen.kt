package ca.emarshall.finance.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.ui.AccountsRoute
import ca.emarshall.finance.ui.BillsRoute
import ca.emarshall.finance.ui.ColorDot
import ca.emarshall.finance.ui.ImportRoute
import ca.emarshall.finance.ui.InsightsRoute
import ca.emarshall.finance.ui.MoneyText
import ca.emarshall.finance.ui.QuestradeRoute
import ca.emarshall.finance.ui.RemindersRoute
import ca.emarshall.finance.ui.SHORT_DAY
import ca.emarshall.finance.ui.SHORT_MONTH
import ca.emarshall.finance.ui.ShareBar
import ca.emarshall.finance.ui.StatementsRoute
import ca.emarshall.finance.ui.TransactionsRoute
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.GroupRow
import ca.emarshall.finance.ui.design.HeroCard
import ca.emarshall.finance.ui.design.LargeTitleScreen
import ca.emarshall.finance.ui.design.LegendDot
import ca.emarshall.finance.ui.design.Pill
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.pretty
import ca.emarshall.finance.ui.theme.Fin
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
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

@Composable
fun OverviewScreen(nav: NavController, vm: OverviewViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val attention by vm.attention.collectAsStateWithLifecycle()
    var allCategories by remember { mutableStateOf(false) }
    val stale = s.freshness.count { it.stale && it.dataThrough != null }

    LargeTitleScreen("Overview") {
        item { ca.emarshall.finance.ui.accounts.NewAccountsCard(nav) }
        if (!s.loading && !s.checklist.complete && !s.checklist.dismissed) item { SetupCard(s.checklist, nav, vm::dismissChecklist) }

        if (!s.loading && s.hasTransactions) {
            item { Hero(s, vm::previousMonth, vm::nextMonth) }
            val done = s.completeThrough
            if (done != null && s.month == done && s.waitingOn.isNotEmpty()) item {
                Text(
                    "${done.plusMonths(1).month.name.lowercase().replaceFirstChar { it.uppercase() }} appears once all statements are in. " +
                        "Waiting on: ${s.waitingOn.joinToString(", ")}.",
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = Fin.palette.secondaryLabel,
                )
            }

            if (attention.first.isNotEmpty() || attention.second > 0 || stale > 0) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (attention.first.isNotEmpty()) {
                        Pill(
                            if (attention.first.size == 1) "1 thing needs attention" else "${attention.first.size} things need attention",
                            Icons.Default.Warning, Fin.palette.warning,
                        ) { nav.navigate(InsightsRoute) }
                    }
                    if (attention.second > 0) {
                        Pill(if (attention.second == 1) "1 statement missing" else "${attention.second} statements missing", Icons.Default.Description, Fin.palette.accent) {
                            nav.navigate(StatementsRoute)
                        }
                    }
                    if (stale > 0) Pill("$stale account${if (stale > 1) "s" else ""} out of date", Icons.Default.Schedule, Fin.palette.secondaryLabel) {
                        nav.navigate(StatementsRoute)
                    }
                }
            }

            item { SectionHeader("Spending", if (s.categories.size > 5) (if (allCategories) "Less" else "See all") else null) { allCategories = !allCategories } }
            item {
                FinCard {
                    if (s.categories.isEmpty()) Text("No spending this month.", color = Fin.palette.secondaryLabel)
                    val max = s.categories.maxOfOrNull { it.first.cents }?.coerceAtLeast(1) ?: 1L
                    val shown = if (allCategories) s.categories else s.categories.take(5)
                    shown.forEachIndexed { i, (c, id) ->
                        val color = Fin.palette.chart[i % Fin.palette.chart.size]
                        Column(
                            Modifier.fillMaxWidth().clickable(enabled = id != null) {
                                nav.navigate(ca.emarshall.finance.ui.CategoryRoute(id ?: -1, s.month.toString()))
                            }.padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.categoryName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(Money.formatWhole(c.cents), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            }
                            ShareBar(c.cents.toFloat() / max.toFloat(), color)
                        }
                    }
                }
            }

            if (s.ytd.isNotEmpty()) {
                item { SectionHeader("${s.ytdYear} so far") }
                item { YtdDonutCard(s) }
            }

            if (s.series.any { it.incomeCents != 0L || it.spendingCents != 0L }) {
                item { SectionHeader("Cash flow") }
                item { CashFlowCard(s) }
            }
        }

        if (!s.loading && s.netWorthCents != null) {
            item { SectionHeader("Liquid net worth", "Accounts") { nav.navigate(AccountsRoute) } }
            item { s.breakdown?.let { LiquidCard(it, s) } ?: NetWorthCard(s) }
        }

        if (!s.loading && s.upcomingBills.isNotEmpty()) {
            item { SectionHeader("Coming up", "All bills") { nav.navigate(BillsRoute) } }
            item {
                Group {
                    s.upcomingBills.take(3).forEachIndexed { i, b ->
                        GroupRow(
                            b.merchant, subtitle = b.nextExpected.format(SHORT_DAY),
                            value = Money.format(b.monthlyCostCents), divider = i < minOf(3, s.upcomingBills.size) - 1,
                        )
                    }
                }
            }
        }

        if (!s.loading && !s.hasTransactions && s.checklist.dismissed) {
            item {
                FinCard {
                    Text("Import a statement to see your dashboard.", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { nav.navigate(ImportRoute()) }) { Text("Import statements") }
                }
            }
        }
    }
}

@Composable
private fun Hero(s: OverviewState, onPrev: () -> Unit, onNext: () -> Unit) {
    val white = Color.White
    val soft = Color.White.copy(alpha = 0.78f)
    HeroCard(Fin.palette.heroGradient) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month", tint = white)
            }
            Text(s.month.pretty(), Modifier.weight(1f), color = white, style = MaterialTheme.typography.titleSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            val canNext = s.month < (s.completeThrough ?: YearMonth.now())
            IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month", tint = if (canNext) white else white.copy(alpha = 0.3f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Net saved", color = soft, style = MaterialTheme.typography.bodyMedium)
        Text(Money.formatWhole(s.totals.netCents), color = white, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            HeroStat("Income", Money.formatWhole(s.totals.incomeCents))
            HeroStat("Spending", Money.formatWhole(s.totals.spendingCents))
            HeroStat("Saved", s.totals.savingsRatePercent?.let { "$it%" } ?: "–")
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SetupCard(c: Checklist, nav: NavController, onDismiss: () -> Unit) {
    FinCard {
        Text("Welcome", style = MaterialTheme.typography.headlineSmall)
        Text("A few steps to get everything in one place.", color = Fin.palette.secondaryLabel)
        Step("Import your statements (accounts are set up from them)", c.hasImports) { nav.navigate(ImportRoute()) }
        Step("Connect Questrade", c.questradeConnected) { nav.navigate(QuestradeRoute) }
        Step("Turn on statement reminders", c.hasReminders) { nav.navigate(RemindersRoute) }
        TextButton(onClick = onDismiss) { Text("Hide") }
    }
}

@Composable
private fun Step(label: String, done: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (done) "Done" else "Not done",
            tint = if (done) Fin.palette.positive else Fin.palette.tertiaryLabel,
        )
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (done) Fin.palette.secondaryLabel else Fin.palette.label)
    }
}

@Composable
private fun CashFlowCard(s: OverviewState) {
    FinCard {
        val producer = remember { CartesianChartModelProducer() }
        val labels = s.series.map { it.month.format(SHORT_MONTH).take(1) }
        LaunchedEffect(s.series) {
            // Whole dollars for plotting only; numbers shown as text stay exact cents.
            producer.runTransaction {
                columnSeries {
                    series(s.series.map { it.incomeCents / 100 })
                    series(s.series.map { it.spendingCents / 100 })
                }
            }
        }
        ProvideVicoTheme(rememberM3VicoTheme(columnCartesianLayerColors = listOf(Fin.palette.positive, Fin.palette.accent))) {
            CartesianChartHost(
                rememberCartesianChart(
                    rememberColumnCartesianLayer(),
                    bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = CartesianValueFormatter { _, x, _ -> labels.getOrElse(x.toInt()) { "" } }),
                ),
                producer,
                Modifier.fillMaxWidth().height(180.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(Fin.palette.positive, "Income")
            LegendDot(Fin.palette.accent, "Spending")
            val avg = s.series.filter { it.incomeCents != 0L || it.spendingCents != 0L }
            if (avg.isNotEmpty()) Text("Avg saved ${Money.formatWhole(avg.sumOf { it.netCents } / avg.size)}/mo", style = MaterialTheme.typography.bodySmall, color = Fin.palette.secondaryLabel)
        }
    }
}

@Composable
private fun NetWorthCard(s: OverviewState) {
    FinCard {
        Text(Money.formatWhole(s.netWorthCents ?: 0), style = MaterialTheme.typography.headlineMedium)
        val trend = s.netWorthTrend.filter { it.second != 0L }
        if (trend.size >= 2) {
            val change = trend.last().second - trend.first().second
            Text(
                "${if (change >= 0) "▲" else "▼"} ${Money.formatWhole(Math.abs(change))} since ${trend.first().first.format(SHORT_MONTH)}",
                color = if (change >= 0) Fin.palette.positive else Fin.palette.negative, style = MaterialTheme.typography.bodyMedium,
            )
            val producer = remember { CartesianChartModelProducer() }
            LaunchedEffect(trend) { producer.runTransaction { lineSeries { series(trend.map { it.second / 100 }) } } }
            ProvideVicoTheme(rememberM3VicoTheme(lineCartesianLayerColors = listOf(Fin.palette.accent))) {
                CartesianChartHost(rememberCartesianChart(rememberLineCartesianLayer()), producer, Modifier.fillMaxWidth().height(90.dp))
            }
        }
        s.balances.forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorDot(if (b.isLiability) 0xFFFF9F0A else 0xFF5E5CE6)
                Text(b.nickname, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                MoneyText(b.balanceCents, b.currency, colored = false, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Money you could use soon, and where it sits. RRSP/FHSA are shown but not counted as liquid. */
@Composable
private fun LiquidCard(b: ca.emarshall.finance.core.analytics.NetWorthBreakdown, s: OverviewState) {
    val p = Fin.palette
    val bucketColor = mapOf(
        ca.emarshall.finance.core.analytics.LiquidityBucket.CASH to p.chart[0],
        ca.emarshall.finance.core.analytics.LiquidityBucket.INVESTMENTS to p.chart[2],
        ca.emarshall.finance.core.analytics.LiquidityBucket.DEBT to p.negative,
        ca.emarshall.finance.core.analytics.LiquidityBucket.LONG_TERM to p.tertiaryLabel,
    )
    FinCard {
        Text(Money.formatWhole(b.liquidCents), style = MaterialTheme.typography.headlineMedium)
        Text("Cash and investments you can reach, minus what you owe", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)

        // Stacked bar of liquid assets, one segment per account; debt as a red tail proportional to its size.
        val liquidSegs = (b.bucket(ca.emarshall.finance.core.analytics.LiquidityBucket.CASH) + b.bucket(ca.emarshall.finance.core.analytics.LiquidityBucket.INVESTMENTS))
            .filter { it.position.balanceCents > 0 }
        val assets = liquidSegs.sumOf { it.position.balanceCents }.coerceAtLeast(1)
        val debt = -b.debtCents
        Row(
            Modifier.fillMaxWidth().height(14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            liquidSegs.forEachIndexed { i, seg ->
                val base = bucketColor.getValue(seg.bucket)
                androidx.compose.foundation.layout.Box(
                    Modifier.weight(seg.position.balanceCents.toFloat() / (assets + debt.coerceAtLeast(0)).toFloat()).height(14.dp)
                        .background(base.copy(alpha = 1f - 0.18f * (i % 3))),
                )
            }
            if (debt > 0) androidx.compose.foundation.layout.Box(
                Modifier.weight(debt.toFloat() / (assets + debt).toFloat()).height(14.dp).background(p.negative.copy(alpha = 0.75f)),
            )
        }

        listOf(
            ca.emarshall.finance.core.analytics.LiquidityBucket.CASH to b.cashCents,
            ca.emarshall.finance.core.analytics.LiquidityBucket.INVESTMENTS to b.investmentsCents,
            ca.emarshall.finance.core.analytics.LiquidityBucket.DEBT to b.debtCents,
        ).filter { (bucket, _) -> b.bucket(bucket).isNotEmpty() }.forEach { (bucket, total) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(bucketColor.getValue(bucket), bucket.label)
                    Spacer(Modifier.weight(1f))
                    Text(Money.formatWhole(total), style = MaterialTheme.typography.titleSmall)
                }
                b.bucket(bucket).forEach { seg ->
                    Row(Modifier.padding(start = 14.dp)) {
                        Text("${seg.position.nickname} · ${seg.position.institution.displayName}", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel, maxLines = 1)
                        Text(Money.formatWhole(seg.position.balanceCents), style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel)
                    }
                }
            }
        }
        if (b.bucket(ca.emarshall.finance.core.analytics.LiquidityBucket.LONG_TERM).isNotEmpty()) {
            androidx.compose.material3.HorizontalDivider(color = p.separator, thickness = 0.5.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(p.tertiaryLabel, "Long-term (RRSP, FHSA) · not liquid")
                Spacer(Modifier.weight(1f))
                Text(Money.formatWhole(b.longTermCents), style = MaterialTheme.typography.titleSmall, color = p.secondaryLabel)
            }
        }
        Row {
            Text("Total net worth", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel)
            Text(Money.formatWhole(b.totalCents), style = MaterialTheme.typography.bodyMedium)
        }
        val trend = s.netWorthTrend.filter { it.second != 0L }
        if (trend.size >= 2) {
            val change = trend.last().second - trend.first().second
            Text(
                "Total ${if (change >= 0) "▲" else "▼"} ${Money.formatWhole(Math.abs(change))} since ${trend.first().first.format(SHORT_MONTH)}",
                color = if (change >= 0) p.positive else p.negative, style = MaterialTheme.typography.bodySmall,
            )
        }
        val oldest = b.segments.minOfOrNull { it.position.asOf }
        if (oldest != null && oldest.isBefore(java.time.LocalDate.now().minusDays(35))) {
            Text("Some balances are from ${oldest.format(SHORT_DAY)} — import newer statements to update.", style = MaterialTheme.typography.bodySmall, color = p.warning)
        }
    }
}

/** Year-to-date spending by category as a donut: top six categories plus "Everything else". */
@Composable
private fun YtdDonutCard(s: OverviewState) {
    val p = Fin.palette
    val total = s.ytd.sumOf { it.cents }.coerceAtLeast(1)
    val top = s.ytd.take(6)
    val rest = s.ytd.drop(6).sumOf { it.cents }
    val slices = top.map { it.categoryName to it.cents } + if (rest > 0) listOf("Everything else" to rest) else emptyList()
    val colors = slices.indices.map { i -> if (i < 6) p.chart[i % p.chart.size] else p.tertiaryLabel }
    FinCard {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
            val track = p.cardSecondary
            androidx.compose.foundation.Canvas(Modifier.size(200.dp)) {
                val stroke = 30.dp.toPx()
                val inset = stroke / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                val gap = if (slices.size > 1) 1.6f else 0f
                var start = -90f
                slices.forEachIndexed { i, (_, cents) ->
                    val sweep = 360f * cents / total
                    if (sweep > gap) drawArc(colors[i], start + gap / 2, sweep - gap, false, topLeft, arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Spent", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                Text(Money.formatWhole(s.ytd.sumOf { it.cents }), style = MaterialTheme.typography.titleLarge)
                s.completeThrough?.let { Text("to ${it.atEndOfMonth().format(SHORT_DAY)}", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel) }
            }
        }
        slices.forEachIndexed { i, (name, cents) ->
            val pct = cents * 1000 / total
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors[i]))
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${pct / 10}.${pct % 10}%", style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel)
                Text(Money.formatWhole(cents), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
