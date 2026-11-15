package ca.emarshall.finance.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.core.insights.CategoryTrend
import ca.emarshall.finance.core.insights.Trends
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.StatementsRepository
import ca.emarshall.finance.data.TransactionRepository
import ca.emarshall.finance.data.ruleKey
import ca.emarshall.finance.data.toFacts
import ca.emarshall.finance.ui.CategoryRoute
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.EmptyMessage
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.MiniBars
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.theme.Fin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class TrendsState(val month: YearMonth, val trends: List<CategoryTrend>, val ids: Map<String, Long>)

@HiltViewModel
class TrendsViewModel @Inject constructor(transactions: TransactionRepository, statements: StatementsRepository, categories: CategoryRepository) : ViewModel() {
    val state = combine(transactions.rows, statements.latestUnlocked, categories.categories) { rows, latest, cats ->
        val month = latest ?: YearMonth.now()
        TrendsState(month, Trends.byCategory(rows.toFacts(), month), cats.associate { it.ruleKey to it.id })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Every spending category over the last twelve months, the ones that changed most first. */
@Composable
fun TrendsScreen(nav: NavController, vm: TrendsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = Fin.palette
    DetailScreen("Trends", onBack = { nav.popBackStack() }) {
        val s = state ?: return@DetailScreen
        if (s.trends.isEmpty()) {
            item { EmptyMessage("No spending to chart yet.", Icons.AutoMirrored.Filled.TrendingUp) }
            return@DetailScreen
        }
        item {
            Footnote("Each category over the last 12 months. The solid bars are the latest three months; the note compares them with the months before.")
        }
        val (moving, steady) = s.trends.partition { t -> t.changeCents?.let { Math.abs(it) >= 25_00 } == true }
        @Composable
        fun rows(list: List<CategoryTrend>) = Group {
            list.forEachIndexed { i, t ->
                val change = t.changeCents
                val color = when {
                    change == null || Math.abs(change) < 25_00 -> p.accent
                    change > 0 -> p.warning
                    else -> p.positive
                }
                val id = s.ids[t.categoryKey]
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = id != null) { nav.navigate(CategoryRoute(id ?: -1, s.month.toString())) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t.categoryName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Money.formatWhole(t.recentCents)} a month lately", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                        Text(
                            when {
                                change == null -> "Not enough history to compare yet"
                                Math.abs(change) < 25_00 -> "About the same as before"
                                else -> "${if (change > 0) "▲" else "▼"} ${Money.formatWhole(Math.abs(change))} a month ${if (change > 0) "more" else "less"} than before"
                            },
                            style = MaterialTheme.typography.bodySmall, color = if (color == p.accent) p.tertiaryLabel else color,
                            fontWeight = if (color == p.accent) FontWeight.Normal else FontWeight.SemiBold,
                        )
                    }
                    MiniBars(t.monthly, color, Modifier.width(120.dp))
                }
                if (i < list.size - 1) HorizontalDivider(Modifier.padding(start = 16.dp), color = p.separator, thickness = 0.5.dp)
            }
        }
        if (moving.isNotEmpty()) {
            item { SectionHeader("Changing") }
            item { rows(moving) }
        }
        if (steady.isNotEmpty()) {
            item { SectionHeader(if (moving.isEmpty()) "All categories" else "Steady") }
            item { rows(steady) }
        }
    }
}
