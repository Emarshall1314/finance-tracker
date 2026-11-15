package ca.emarshall.finance.ui.bills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.core.recurring.RecurringSeries
import ca.emarshall.finance.data.TransactionRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.ui.graphics.Color
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.EmptyMessage
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.GroupRow
import ca.emarshall.finance.ui.design.HeroCard
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.theme.Fin
import ca.emarshall.finance.ui.pretty
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class BillsViewModel @Inject constructor(tx: TransactionRepository) : ViewModel() {
    val bills = tx.rows.map { tx.recurring(it, LocalDate.now()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun BillsScreen(nav: NavController, vm: BillsViewModel = hiltViewModel()) {
    val bills by vm.bills.collectAsStateWithLifecycle()
    val list: List<RecurringSeries>? = bills
    DetailScreen("Bills & subscriptions", onBack = { nav.popBackStack() }) {
        if (list == null) return@DetailScreen
        if (list.isEmpty()) {
            item {
                EmptyMessage(
                    "Nothing recurring yet. A charge shows up here once the same merchant has charged a similar amount about monthly three times.",
                    Icons.Outlined.Autorenew,
                )
            }
            return@DetailScreen
        }
        val total = list.filter { it.currency == "CAD" }.sumOf { it.monthlyCostCents }
        item {
            HeroCard(Fin.palette.heroGradient) {
                val white = Color.White
                Text("Every month", color = white.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                Text(Money.formatWhole(total), color = white, style = MaterialTheme.typography.headlineLarge)
                Text("${Money.formatWhole(total * 12)} a year across ${list.size} bills and subscriptions", color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { SectionHeader("Coming up") }
        item {
            Group {
                list.sortedBy { it.nextExpected }.forEachIndexed { i, b ->
                    GroupRow(
                        b.merchant,
                        subtitle = "Next ${b.nextExpected.pretty()} · seen ${b.occurrences}×",
                        icon = Icons.Outlined.Autorenew, iconColor = Fin.palette.chart[i % Fin.palette.chart.size],
                        value = Money.format(b.monthlyCostCents, b.currency, true),
                        divider = i < list.size - 1,
                    )
                }
            }
        }
        item { Footnote("Found from charges that repeat about every month at a similar amount.") }
    }
}
