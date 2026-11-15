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
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Category
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
import kotlinx.coroutines.launch
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
import java.time.YearMonth

@Composable
fun OverviewScreen(nav: NavController, vm: OverviewViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val attention by vm.attention.collectAsStateWithLifecycle()
    var allCategories by remember { mutableStateOf(false) }
    val stale = s.freshness.count { it.stale && it.dataThrough != null }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LargeTitleScreen("Overview", state = listState) {
        item { ca.emarshall.finance.ui.accounts.NewAccountsCard(nav) }
        if (!s.loading && !s.checklist.complete && !s.checklist.dismissed) item { SetupCard(s.checklist, nav, vm::dismissChecklist) }

        if (!s.loading && s.hasTransactions) {
            item { Hero(s, vm::previousMonth, vm::nextMonth) }
            val done = s.completeThrough
            fun monthName(m: YearMonth) = m.month.name.lowercase().replaceFirstChar { it.uppercase() }
            fun around(d: java.time.LocalDate?) = d?.let { " (closes around ${it.format(SHORT_DAY)})" } ?: ""
            if (s.pendingCards.isNotEmpty()) item {
                val cards = s.pendingCards.joinToString(" and ") { (name, close) -> name + around(close) }
                ca.emarshall.finance.ui.design.Notice(
                    "Card spending for ${monthName(s.month)} isn't final yet. This month updates once your " +
                        "$cards statement${if (s.pendingCards.size > 1) "s are" else " is"} imported.",
                )
            }
            val next = s.nextUnlock
            if (done != null && s.month == done && next != null) item {
                ca.emarshall.finance.ui.design.Footnote("${monthName(done.plusMonths(1))} unlocks when your ${next.first} statement arrives${around(next.second)}.")
            }

            if (attention.first.isNotEmpty() || attention.second > 0 || stale > 0 || s.unsortedPlaces > 0) item {
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
                    if (s.unsortedPlaces > 0) {
                        Pill(
                            "${Money.formatWhole(s.unsortedCents)} in Other to sort", Icons.Default.Category, Fin.palette.chart[1],
                        ) { nav.navigate(ca.emarshall.finance.ui.SortOtherRoute) }
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
                    val all = s.categories.sumOf { it.first.cents }.coerceAtLeast(1)
                    val shown = if (allCategories) s.categories else s.categories.take(5)
                    val before = s.month.minusMonths(1).format(SHORT_MONTH)
                    shown.forEachIndexed { i, (c, id) ->
                        val color = Fin.palette.chart[i % Fin.palette.chart.size]
                        Row(
                            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(enabled = id != null) {
                                nav.navigate(ca.emarshall.finance.ui.CategoryRoute(id ?: -1, s.month.toString()))
                            }.padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ca.emarshall.finance.ui.design.IconTile(ca.emarshall.finance.ui.categoryIcon(s.categoryStyle[c.categoryKey]?.first), color, size = 36.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.categoryName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(Money.formatWhole(c.cents), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                }
                                ShareBar(c.cents.toFloat() / max.toFloat(), color)
                                // Share of the month, and how it compares with the month before.
                                val prev = s.previousByCategory[c.categoryKey]
                                val diff = prev?.let { c.cents - it }
                                Row {
                                    Text("${c.cents * 100 / all}% of spending", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Fin.palette.secondaryLabel)
                                    if (s.previousTotals != null) Text(
                                        when {
                                            prev == null -> "new since $before"
                                            diff == 0L -> "same as $before"
                                            else -> "${if (diff!! > 0) "▲" else "▼"} ${Money.formatWhole(Math.abs(diff))} vs $before"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (diff != null && diff < 0) Fin.palette.positive else if (diff != null && diff > 0) Fin.palette.warning else Fin.palette.secondaryLabel,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (s.categories.isNotEmpty()) item {
                Group {
                    GroupRow("Trends", subtitle = "Each category over the last 12 months", icon = Icons.AutoMirrored.Filled.TrendingUp, iconColor = Fin.palette.chart[2],
                        showChevron = true, divider = false, onClick = { nav.navigate(ca.emarshall.finance.ui.TrendsRoute) })
                }
            }
            s.flow?.let { flow ->
                item { SectionHeader("Where it went") }
                item { FlowCard(s, flow, nav) }
            }
            if (s.daily.isNotEmpty()) {
                item { SectionHeader("Day by day") }
                item { CalendarCard(s, nav) }
            }

            if (s.ytd.isNotEmpty()) {
                item { SectionHeader("${s.ytdYear} so far") }
                item { YtdDonutCard(s, nav) }
            }

            if (s.series.any { it.incomeCents != 0L || it.spendingCents != 0L }) {
                item { SectionHeader("Saved each month") }
                item {
                    CashFlowCard(s, nav) { m ->
                        vm.showMonth(m)
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                }
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
            Text(s.month.pretty() + if (s.pendingCards.isNotEmpty()) " · not final" else "", Modifier.weight(1f), color = white, style = MaterialTheme.typography.titleSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            val canNext = s.month < (s.completeThrough ?: YearMonth.now())
            IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month", tint = if (canNext) white else white.copy(alpha = 0.3f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Net saved", color = soft, style = MaterialTheme.typography.bodyMedium)
        Text(Money.formatWhole(ca.emarshall.finance.ui.design.animatedCents(s.totals.netCents)), color = white, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            HeroStat("Income", Money.formatWhole(s.totals.incomeCents))
            HeroStat("Spending", Money.formatWhole(s.totals.spendingCents))
            HeroStat("Saved", s.totals.savingsRatePercent?.let { "$it%" } ?: "–")
        }
        s.previousTotals?.let { prev ->
            val diff = s.totals.spendingCents - prev.spendingCents
            if (diff != 0L) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Spent ${Money.formatWhole(Math.abs(diff))} ${if (diff > 0) "more" else "less"} than in ${s.month.minusMonths(1).month.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        s.setAside?.takeIf { it.totalCents != 0L }?.let { a ->
            Spacer(Modifier.height(6.dp))
            val parts = listOfNotNull(
                a.savingsCents.takeIf { it != 0L }?.let { "${Money.formatWhole(it)} to savings" },
                a.investmentsCents.takeIf { it != 0L }?.let { "${Money.formatWhole(it)} to investments" },
            )
            Text(
                "Put away ${Money.formatWhole(a.totalCents)}" + if (parts.size > 1) " · " + parts.joinToString(" · ") else
                    " " + parts.single().substringAfter(' '),
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
            )
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


/**
 * Month by month: how much was saved (green) or overspent (red), against the dashed average. Tap a month
 * for its income, spending and biggest categories.
 */
@Composable
private fun CashFlowCard(s: OverviewState, nav: NavController, onShowMonth: (YearMonth) -> Unit) {
    val p = Fin.palette
    val months = s.series
    var sel by remember(months) { mutableStateOf<Int?>(null) }
    val avgNet = months.sumOf { it.netCents } / months.size
    fun notFinal(m: YearMonth) = s.pendingCards.isNotEmpty() && m == s.completeThrough
    val picked = sel?.let { months.getOrNull(it) }
    FinCard {
        if (picked == null) {
            val total = months.sumOf { it.netCents }
            Text("Saved over ${months.size} month${if (months.size == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel)
            Text(Money.formatWhole(total), style = MaterialTheme.typography.headlineMedium, color = if (total < 0) p.negative else p.label)
            Text("${Money.formatWhole(avgNet)} a month on average · tap a month for details", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
        } else {
            Text(picked.month.pretty() + if (notFinal(picked.month)) " · not final" else "", style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel)
            Text(
                (if (picked.netCents >= 0) "Saved " else "Overspent ") + Money.formatWhole(Math.abs(picked.netCents)),
                style = MaterialTheme.typography.headlineMedium, color = if (picked.netCents >= 0) p.positive else p.negative,
            )
            val diff = picked.netCents - avgNet
            Text(
                "${Money.formatWhole(Math.abs(diff))} ${if (diff >= 0) "better" else "worse"} than your average month",
                style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
            )
            val away = s.seriesSetAside[picked.month]?.totalCents ?: 0L
            if (away > 0) Text(
                "${Money.formatWhole(away)} ${if (picked.netCents > 0 && away <= picked.netCents) "of it " else ""}was put into savings and investments",
                style = MaterialTheme.typography.bodySmall, color = p.chart[2],
            )
        }
        ca.emarshall.finance.ui.design.BarChart(
            bars = months.map { mt ->
                ca.emarshall.finance.ui.design.BarDatum(
                    if (months.size <= 6) mt.month.format(SHORT_MONTH) else mt.month.format(SHORT_MONTH).take(1),
                    mt.netCents, if (mt.netCents >= 0) p.positive else p.negative, faded = notFinal(mt.month),
                )
            },
            selected = sel, onSelect = { sel = it }, reference = avgNet, height = 170.dp,
            description = "Money saved each month. Tap a month for details.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(p.positive, "Saved")
            LegendDot(p.negative, "Overspent")
            Text("┄ Average", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
        }
        androidx.compose.material3.HorizontalDivider(color = p.separator, thickness = 0.5.dp)
        if (picked == null) {
            val withData = months.filter { !notFinal(it.month) }
            StatRow("Average income", Money.formatWhole(withData.sumOf { it.incomeCents } / withData.size.coerceAtLeast(1)))
            StatRow("Average spending", Money.formatWhole(withData.sumOf { it.spendingCents } / withData.size.coerceAtLeast(1)))
            val n = withData.size.coerceAtLeast(1)
            val toSavings = withData.sumOf { s.seriesSetAside[it.month]?.savingsCents ?: 0L }
            val toInvest = withData.sumOf { s.seriesSetAside[it.month]?.investmentsCents ?: 0L }
            if (toSavings != 0L || toInvest != 0L) StatRow("Average put away", Money.formatWhole((toSavings + toInvest) / n), p.chart[2])
            if (toSavings != 0L && toInvest != 0L) {
                StatRow("   to savings", Money.formatWhole(toSavings / n))
                StatRow("   to investments", Money.formatWhole(toInvest / n))
            }
            if (withData.size >= 2) {
                val best = withData.maxBy { it.netCents }
                val worst = withData.minBy { it.netCents }
                StatRow("Best month", "${best.month.format(SHORT_MONTH)} · ${Money.formatWhole(best.netCents)}", p.positive)
                StatRow("Toughest month", "${worst.month.format(SHORT_MONTH)} · ${Money.formatWhole(worst.netCents)}", if (worst.netCents < 0) p.negative else p.label)
            }
        } else {
            StatRow("Income", Money.formatWhole(picked.incomeCents))
            StatRow("Spending", Money.formatWhole(picked.spendingCents))
            s.seriesSetAside[picked.month]?.let { a ->
                if (a.savingsCents != 0L) StatRow(if (a.savingsCents > 0) "To savings" else "Taken from savings", Money.formatWhole(Math.abs(a.savingsCents)), p.chart[2])
                if (a.investmentsCents != 0L) StatRow(if (a.investmentsCents > 0) "To investments" else "Taken from investments", Money.formatWhole(Math.abs(a.investmentsCents)), p.chart[2])
            }
            StatRow("Kept", picked.savingsRatePercent?.let { "$it% of income" } ?: "–")
            val cats = s.seriesCategories[picked.month].orEmpty()
            if (cats.isNotEmpty()) {
                Text("Biggest spending", style = MaterialTheme.typography.labelMedium, color = p.secondaryLabel, modifier = Modifier.padding(top = 4.dp))
                cats.forEachIndexed { i, (c, id) ->
                    Row(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .clickable(enabled = id != null) { nav.navigate(ca.emarshall.finance.ui.CategoryRoute(id ?: -1, picked.month.toString())) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.chart[i % p.chart.size]))
                        Text(c.categoryName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(Money.formatWhole(c.cents), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = p.tertiaryLabel, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onShowMonth(picked.month) }) { Text("Show this month above") }
                TextButton(onClick = { nav.navigate(TransactionsRoute(month = picked.month.toString())) }) { Text("Transactions") }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Fin.palette.secondaryLabel)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (color == Color.Unspecified) Fin.palette.label else color)
    }
}

/** Total net worth by month; tap or drag for a month's value. */
@Composable
private fun NetWorthTrend(trend: List<Pair<YearMonth, Long>>) {
    if (trend.size < 2) return
    val p = Fin.palette
    var sel by remember(trend) { mutableStateOf<Int?>(null) }
    val change = trend.last().second - trend.first().second
    val point = sel?.let { trend.getOrNull(it) }
    if (point == null) {
        Text(
            "Total ${if (change >= 0) "▲" else "▼"} ${Money.formatWhole(Math.abs(change))} since ${trend.first().first.format(SHORT_MONTH)} · tap the line",
            color = if (change >= 0) p.positive else p.negative, style = MaterialTheme.typography.bodySmall,
        )
    } else {
        val vsStart = point.second - trend.first().second
        Text(
            "${point.first.pretty()}: ${Money.formatWhole(point.second)}" +
                if (sel != 0) " (${if (vsStart >= 0) "+" else "-"}${Money.formatWhole(Math.abs(vsStart))} since ${trend.first().first.format(SHORT_MONTH)})" else "",
            style = MaterialTheme.typography.bodySmall, color = p.label,
        )
    }
    ca.emarshall.finance.ui.design.LineChart(trend.map { it.second }, sel, { sel = it }, p.accent, height = 90.dp)
}

@Composable
private fun NetWorthCard(s: OverviewState) {
    FinCard {
        Text(Money.formatWhole(s.netWorthCents ?: 0), style = MaterialTheme.typography.headlineMedium)
        NetWorthTrend(s.netWorthTrend.filter { it.second != 0L })
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

        // One segment per account with money in it, then what's owed as a red tail. Tap a segment for its account.
        val liquidSegs = (b.bucket(ca.emarshall.finance.core.analytics.LiquidityBucket.CASH) + b.bucket(ca.emarshall.finance.core.analytics.LiquidityBucket.INVESTMENTS))
            .filter { it.position.balanceCents > 0 }
        val debt = -b.debtCents
        val segs = liquidSegs.mapIndexed { i, seg ->
            ca.emarshall.finance.ui.design.ChartSlice(
                "${seg.position.nickname} · ${Money.formatWhole(seg.position.balanceCents)}",
                seg.position.balanceCents, bucketColor.getValue(seg.bucket).copy(alpha = 1f - 0.18f * (i % 3)),
            )
        } + if (debt > 0) listOf(ca.emarshall.finance.ui.design.ChartSlice("Owed on cards and loans · ${Money.formatWhole(debt)}", debt, p.negative.copy(alpha = 0.75f))) else emptyList()
        var sel by remember(segs.size) { mutableStateOf<Int?>(null) }
        ca.emarshall.finance.ui.design.SegmentedBar(segs, sel, { sel = it })
        val total = segs.sumOf { it.value }.coerceAtLeast(1)
        Text(
            sel?.let { i -> segs.getOrNull(i)?.let { "${it.label} · ${it.value * 100 / total}% of the bar" } } ?: "Tap the bar to see each account",
            style = MaterialTheme.typography.bodySmall, color = if (sel != null) p.label else p.tertiaryLabel,
        )

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
        NetWorthTrend(s.netWorthTrend.filter { it.second != 0L })
        val oldest = b.segments.minOfOrNull { it.position.asOf }
        if (oldest != null && oldest.isBefore(java.time.LocalDate.now().minusDays(35))) {
            Text("Some balances are from ${oldest.format(SHORT_DAY)} — import newer statements to update.", style = MaterialTheme.typography.bodySmall, color = p.warning)
        }
    }
}

/**
 * Year-to-date spending by category as a donut: top six categories plus "Everything else". Tap a slice or
 * a row to see its share, monthly average and a way into its transactions.
 */
@Composable
private fun YtdDonutCard(s: OverviewState, nav: NavController) {
    val p = Fin.palette
    val all = s.ytd
    val total = all.sumOf { it.first.cents }.coerceAtLeast(1)
    val top = all.take(6)
    val rest = all.drop(6)
    val slices = top.mapIndexed { i, (c, _) -> ca.emarshall.finance.ui.design.ChartSlice(c.categoryName, c.cents, p.chart[i % p.chart.size]) } +
        if (rest.isNotEmpty()) listOf(ca.emarshall.finance.ui.design.ChartSlice("Everything else", rest.sumOf { it.first.cents }, p.tertiaryLabel)) else emptyList()
    var sel by remember(all) { mutableStateOf<Int?>(null) }
    val monthsSoFar = (s.completeThrough?.monthValue ?: java.time.LocalDate.now().monthValue).coerceAtLeast(1)
    fun pct(cents: Long): String { val t = cents * 1000 / total; return "${t / 10}.${t % 10}%" }
    val linkMonth = (s.completeThrough ?: YearMonth.now()).toString()

    FinCard {
        ca.emarshall.finance.ui.design.DonutChart(slices, sel, { sel = it }) {
            val picked = sel?.let { slices.getOrNull(it) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 48.dp)) {
                if (picked == null) {
                    Text("Spent", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                    Text(Money.formatWhole(all.sumOf { it.first.cents }), style = MaterialTheme.typography.titleLarge)
                    s.completeThrough?.let { Text("to ${it.atEndOfMonth().format(SHORT_DAY)}", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel) }
                } else {
                    Text(picked.label, style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(Money.formatWhole(picked.value), style = MaterialTheme.typography.titleLarge)
                    Text("${pct(picked.value)} · ${Money.formatWhole(picked.value / monthsSoFar)}/mo", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                }
            }
        }
        slices.forEachIndexed { i, sl ->
            val dim = sel != null && sel != i
            Row(
                Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(if (sel == i) p.cardSecondary else Color.Transparent)
                    .clickable { sel = if (sel == i) null else i }.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(sl.color.copy(alpha = if (dim) 0.32f else 1f)))
                Text(sl.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (dim) p.secondaryLabel else p.label)
                Text(pct(sl.value), style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel)
                Text(Money.formatWhole(sl.value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (dim) p.secondaryLabel else p.label)
            }
        }
        val picked = sel
        if (picked != null) {
            androidx.compose.material3.HorizontalDivider(color = p.separator, thickness = 0.5.dp)
            if (picked < top.size) {
                val (c, id) = top[picked]
                Text("About ${Money.formatWhole(c.cents / monthsSoFar)} a month across $monthsSoFar month${if (monthsSoFar == 1) "" else "s"} this year.",
                    style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                if (id != null) TextButton(onClick = { nav.navigate(ca.emarshall.finance.ui.CategoryRoute(id, linkMonth)) }) { Text("Open ${c.categoryName}") }
            } else {
                Text("What's in \"Everything else\"", style = MaterialTheme.typography.labelMedium, color = p.secondaryLabel)
                rest.forEach { (c, id) ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = id != null) { nav.navigate(ca.emarshall.finance.ui.CategoryRoute(id ?: -1, linkMonth)) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.categoryName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(pct(c.cents), style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel, modifier = Modifier.padding(end = 10.dp))
                        Text(Money.formatWhole(c.cents), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** Income on the left pouring into where it went on the right: spending, savings, investments, what's left. */
@Composable
private fun FlowCard(s: OverviewState, flow: ca.emarshall.finance.core.analytics.MoneyFlow, nav: NavController) {
    val p = Fin.palette
    val savings = Color(0xFF26A69A)
    val investments = Color(0xFF5AC8FA)
    var categoryIndex = 0
    fun colorOf(part: ca.emarshall.finance.core.analytics.FlowPart): Color = when (part.kind) {
        ca.emarshall.finance.core.analytics.FlowKind.INCOME -> p.positive
        ca.emarshall.finance.core.analytics.FlowKind.FROM_SAVINGS, ca.emarshall.finance.core.analytics.FlowKind.TO_SAVINGS -> savings
        ca.emarshall.finance.core.analytics.FlowKind.FROM_INVESTMENTS, ca.emarshall.finance.core.analytics.FlowKind.TO_INVESTMENTS -> investments
        ca.emarshall.finance.core.analytics.FlowKind.FROM_BALANCE -> p.negative
        ca.emarshall.finance.core.analytics.FlowKind.CATEGORY -> p.chart[categoryIndex++ % p.chart.size]
        ca.emarshall.finance.core.analytics.FlowKind.OTHER_SPENDING -> p.tertiaryLabel
        ca.emarshall.finance.core.analytics.FlowKind.LEFT_OVER -> p.positive.copy(alpha = 0.6f)
    }
    val sources = flow.sources.map { ca.emarshall.finance.ui.design.FlowNode(it.label, it.cents, colorOf(it)) }
    val targets = flow.targets.map { ca.emarshall.finance.ui.design.FlowNode(it.label, it.cents, colorOf(it)) }
    var sel by remember(flow) { mutableStateOf<Int?>(null) }
    val total = flow.totalCents.coerceAtLeast(1)
    FinCard {
        val picked = sel?.let { flow.targets.getOrNull(it) }
        if (picked == null) {
            Text("${Money.formatWhole(flow.totalCents)} in ${s.month.pretty()}", style = MaterialTheme.typography.titleMedium)
            Text("Followed from where it came from to where it went. Tap a destination.", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
        } else {
            Text("${picked.label} · ${Money.formatWhole(picked.cents)}", style = MaterialTheme.typography.titleMedium)
            Text(
                "${picked.cents * 100 / total}% of the month's money" + when (picked.kind) {
                    ca.emarshall.finance.core.analytics.FlowKind.LEFT_OVER -> ": came in and wasn't spent or moved"
                    ca.emarshall.finance.core.analytics.FlowKind.TO_SAVINGS -> ": moved to your savings account"
                    ca.emarshall.finance.core.analytics.FlowKind.TO_INVESTMENTS -> ": moved to your investment accounts"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
            )
        }
        ca.emarshall.finance.ui.design.FlowChart(sources, targets, sel, { sel = it }, { Money.formatWhole(it) })
        if (flow.sources.any { it.kind == ca.emarshall.finance.core.analytics.FlowKind.FROM_BALANCE }) {
            Text("More went out than came in this month; the difference came out of your chequing balance.", style = MaterialTheme.typography.bodySmall, color = p.warning)
        }
        picked?.categoryKey?.let { key ->
            val id = s.categories.firstOrNull { it.first.categoryKey == key }?.second
            if (id != null) TextButton(onClick = { nav.navigate(ca.emarshall.finance.ui.CategoryRoute(id, s.month.toString())) }) { Text("Open ${picked.label}") }
        }
    }
}

/** The month as a calendar shaded by spending; tap a day for what was bought. */
@Composable
private fun CalendarCard(s: OverviewState, nav: NavController) {
    val p = Fin.palette
    val days = (1..s.month.lengthOfMonth()).map { ca.emarshall.finance.ui.design.HeatDay(it, s.daily[it] ?: 0L) }
    var sel by remember(s.month) { mutableStateOf<Int?>(null) }
    val dayFormat = remember { java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", java.util.Locale.CANADA) }
    FinCard {
        val picked = sel
        if (picked == null) {
            val busiest = s.daily.maxByOrNull { it.value }
            val quiet = days.count { it.cents == 0L }
            Text(
                busiest?.let { "Biggest day: ${s.month.atDay(it.key).format(SHORT_DAY)} · ${Money.formatWhole(it.value)}" } ?: "No spending",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "$quiet day${if (quiet == 1) "" else "s"} with no spending · darker means more · tap a day",
                style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
            )
        } else {
            Text(s.month.atDay(picked).format(dayFormat), style = MaterialTheme.typography.titleMedium)
            Text(
                (s.daily[picked] ?: 0L).let { if (it == 0L) "Nothing spent" else "${Money.format(it)} spent" },
                style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
            )
        }
        ca.emarshall.finance.ui.design.MonthHeatmap(days, s.month.atDay(1).dayOfWeek.value % 7, sel, { sel = it }, p.accent)
        if (picked != null) {
            s.dayItems[picked].orEmpty().forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable { nav.navigate(ca.emarshall.finance.ui.TransactionRoute(item.id)) }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.merchant, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(Money.format(item.cents), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = p.tertiaryLabel, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (s.pendingCards.isNotEmpty()) {
            Text("Card purchases for this month aren't all in yet, so some days will fill in later.", style = MaterialTheme.typography.bodySmall, color = p.tertiaryLabel)
        }
    }
}
