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
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.EmptyState
import ca.emarshall.finance.ui.LabeledRow
import ca.emarshall.finance.ui.MoneyText
import ca.emarshall.finance.ui.SectionCard
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
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Bills & subscriptions", onBack = { nav.popBackStack() })
        val list: List<RecurringSeries> = bills ?: return@Column
        if (list.isEmpty()) {
            EmptyState("Nothing recurring yet. A charge shows up here after the same merchant charges a similar amount about monthly, 3 times.")
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val total = list.filter { it.currency == "CAD" }.sumOf { it.monthlyCostCents }
            ca.emarshall.finance.ui.design.HeroCard(ca.emarshall.finance.ui.theme.Fin.palette.heroGradient) {
                val white = androidx.compose.ui.graphics.Color.White
                Text("Every month", color = white.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                Text(Money.formatWhole(total), color = white, style = MaterialTheme.typography.headlineLarge)
                Text("${Money.formatWhole(total * 12)} a year across ${list.size} bills and subscriptions", color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            }
            ca.emarshall.finance.ui.design.Group {
                list.forEachIndexed { i, b ->
                    ca.emarshall.finance.ui.design.GroupRow(
                        b.merchant,
                        subtitle = "Next ${b.nextExpected.pretty()} · seen ${b.occurrences}×",
                        value = Money.format(b.monthlyCostCents, b.currency, true),
                        divider = i < list.size - 1,
                    )
                }
            }
        }
    }
}
