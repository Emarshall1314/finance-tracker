package ca.emarshall.finance.ui.categories

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import ca.emarshall.finance.core.analytics.Analytics
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.TransactionRepository
import ca.emarshall.finance.data.db.TxRow
import ca.emarshall.finance.data.toFact
import ca.emarshall.finance.ui.CategoryRoute
import ca.emarshall.finance.ui.SHORT_MONTH
import ca.emarshall.finance.ui.TransactionRoute
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.GroupRow
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.pretty
import ca.emarshall.finance.ui.theme.Fin
import ca.emarshall.finance.ui.transactions.TxListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class CategoryDetail(
    val name: String,
    val month: YearMonth,
    val monthCents: Long,
    val averageCents: Long,
    val series: List<Pair<YearMonth, Long>>,
    val merchants: List<Pair<String, Long>>,
    val rows: List<TxRow>,
)

@HiltViewModel
class CategoryDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    transactions: TransactionRepository,
    categories: CategoryRepository,
) : ViewModel() {
    private val route = savedState.toRoute<CategoryRoute>()
    /** The 12-month window ends at the month the screen was opened for; tapping a bar picks a month inside it. */
    private val endMonth = runCatching { YearMonth.parse(route.month) }.getOrDefault(YearMonth.now())
    private val shown = kotlinx.coroutines.flow.MutableStateFlow(endMonth)

    fun show(m: YearMonth) { shown.value = m }

    val detail = combine(transactions.rows, categories.categories, shown) { rows, cats, month ->
        val cat = cats.firstOrNull { it.id == route.categoryId } ?: return@combine null
        val mine = rows.filter { it.categoryId == cat.id && it.transferLinkId == null }
        val a = Analytics(mine.map { it.toFact() })
        val months = (11 downTo 0).map { endMonth.minusMonths(it.toLong()) }
        val series = months.map { m -> m to (a.spendingByCategory(m).firstOrNull()?.cents ?: 0L) }
        val active = series.filter { it.first != month && it.second != 0L }
        val inMonth = mine.filter { YearMonth.from(it.date) == month }
        CategoryDetail(
            name = cat.name,
            month = month,
            monthCents = series.firstOrNull { it.first == month }?.second ?: 0L,
            averageCents = if (active.isEmpty()) 0 else active.sumOf { it.second } / active.size,
            series = series,
            merchants = inMonth.filter { it.amountCents < 0 }.groupBy { it.merchant }
                .map { (m, l) -> m to -l.sumOf { it.amountCents } }.sortedByDescending { it.second }.take(5),
            rows = inMonth.sortedByDescending { it.date },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun CategoryDetailScreen(nav: NavController, vm: CategoryDetailViewModel = hiltViewModel()) {
    val d by vm.detail.collectAsStateWithLifecycle()
    val p = Fin.palette
    DetailScreen(d?.name ?: "", onBack = { nav.popBackStack() }) {
        val c = d ?: return@DetailScreen
        item {
            FinCard {
                Text(c.month.pretty(), style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel)
                Text(Money.formatWhole(c.monthCents), style = MaterialTheme.typography.headlineLarge)
                if (c.averageCents > 0) {
                    val diff = c.monthCents - c.averageCents
                    Text(
                        "${if (diff > 0) "▲" else "▼"} ${Money.formatWhole(Math.abs(diff))} vs your ${Money.formatWhole(c.averageCents)} monthly average",
                        color = if (diff > 0) p.negative else p.positive, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ca.emarshall.finance.ui.design.BarChart(
                    bars = c.series.map { (m, cents) -> ca.emarshall.finance.ui.design.BarDatum(m.format(SHORT_MONTH).take(1), cents, p.accent) },
                    selected = c.series.indexOfFirst { it.first == c.month }.takeIf { it >= 0 },
                    // Tapping the shown month again keeps it; there is always one month on screen.
                    onSelect = { i -> i?.let { c.series.getOrNull(it)?.first }?.let(vm::show) },
                    reference = c.averageCents.takeIf { it > 0 }, height = 160.dp,
                    description = "Spending in ${c.name} for the last 12 months. Tap a month to see it.",
                )
                Text("Tap a month to see it · dashed line is your average", style = MaterialTheme.typography.bodySmall, color = p.tertiaryLabel)
            }
        }
        if (c.merchants.isNotEmpty()) {
            item { SectionHeader("Where it went") }
            item {
                Group {
                    c.merchants.forEachIndexed { i, (m, cents) -> GroupRow(m, value = Money.format(cents), divider = i < c.merchants.size - 1) }
                }
            }
        }
        item { SectionHeader("Transactions") }
        item {
            if (c.rows.isEmpty()) {
                FinCard { Text("Nothing in ${c.month.pretty()}.", color = p.secondaryLabel) }
            } else {
                Group { c.rows.forEachIndexed { i, t -> TxListItem(t, last = i == c.rows.size - 1) { nav.navigate(TransactionRoute(t.id)) } } }
            }
        }
    }
}
