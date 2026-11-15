package ca.emarshall.finance.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Home
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.TransactionRepository
import ca.emarshall.finance.data.db.CategoryEntity
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.MenuRow
import ca.emarshall.finance.ui.LabeledRow
import ca.emarshall.finance.ui.MoneyText
import ca.emarshall.finance.ui.Picker
import ca.emarshall.finance.ui.SectionCard
import ca.emarshall.finance.ui.TransactionRoute
import ca.emarshall.finance.ui.pretty
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
) : ViewModel() {
    val id = savedState.toRoute<TransactionRoute>().id
    val row = transactions.row(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val allCategories = categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every payment to the same place, for "how much do I spend here". */
    val history = kotlinx.coroutines.flow.combine(transactions.row(id), transactions.rows) { t, all ->
        if (t == null || t.transferLinkId != null || t.amountCents >= 0) return@combine null
        val same = all.filter { it.merchant.equals(t.merchant, ignoreCase = true) && it.amountCents < 0 && it.transferLinkId == null && it.currency == t.currency }
        if (same.size < 2) return@combine null
        val end = java.time.YearMonth.from(same.maxOf { it.date })
        val months = (11 downTo 0).map { end.minusMonths(it.toLong()) }
        MerchantHistory(
            count = same.size,
            totalCents = -same.sumOf { it.amountCents },
            since = same.minOf { it.date },
            monthly = months.map { m -> m to -same.filter { java.time.YearMonth.from(it.date) == m }.sumOf { it.amountCents } },
            recent = same.filter { it.id != t.id }.sortedByDescending { it.date }.take(4),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** After a manual recategorisation: offer "Always do this for <merchant>". */
    val offerLearn = MutableStateFlow<CategoryEntity?>(null)
    val message = MutableStateFlow<String?>(null)

    fun setCategory(c: CategoryEntity) = viewModelScope.launch {
        transactions.setCategory(id, c.id)
        offerLearn.value = c
    }

    fun learn() = viewModelScope.launch {
        val c = offerLearn.value ?: return@launch
        val merchant = row.value?.merchant ?: return@launch
        val n = categories.learn(merchant, c.id)
        offerLearn.value = null
        message.value = "Rule saved. $n transaction(s) from $merchant are now ${c.name}."
    }

    fun saveNotes(notes: String) = viewModelScope.launch { transactions.setNotes(id, notes) }
    fun confirmTransfer() = viewModelScope.launch { transactions.confirmTransfer(id); message.value = "Transfer confirmed." }
    fun unlinkTransfer() = viewModelScope.launch { transactions.unlinkTransfer(id); message.value = "Unlinked. These now count as income/spending." }
}

data class MerchantHistory(
    val count: Int,
    val totalCents: Long,
    val since: java.time.LocalDate,
    val monthly: List<Pair<java.time.YearMonth, Long>>,
    val recent: List<ca.emarshall.finance.data.db.TxRow>,
)

@Composable
fun TransactionDetailScreen(nav: NavController, vm: TransactionDetailViewModel = hiltViewModel()) {
    val t by vm.row.collectAsStateWithLifecycle()
    val cats by vm.allCategories.collectAsStateWithLifecycle()
    val learn by vm.offerLearn.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var notes by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(ca.emarshall.finance.ui.theme.Fin.palette.background)) {
        BackTopBar("", onBack = { nav.popBackStack() })
        val tx = t ?: return@Column
        LaunchedEffect(tx.id) { if (notes == null) notes = tx.notes.orEmpty() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val p = ca.emarshall.finance.ui.theme.Fin.palette
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ca.emarshall.finance.ui.design.IconTile(
                    if (tx.transferLinkId != null) androidx.compose.material.icons.Icons.Default.SwapHoriz else ca.emarshall.finance.ui.categoryIcon(tx.categoryIcon),
                    androidx.compose.ui.graphics.Color((tx.categoryColor ?: 0xFF8E8E93).toInt()), size = 60.dp,
                )
                Text(tx.merchant, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(
                    (if (tx.amountCents > 0) "+" else "−") + ca.emarshall.finance.core.money.Money.format(Math.abs(tx.amountCents), tx.currency, showCurrency = true),
                    style = MaterialTheme.typography.headlineLarge, color = if (tx.amountCents > 0) p.positive else p.label,
                )
                Text("${tx.date.pretty()} · ${tx.accountNickname}", style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel)
            }
            ca.emarshall.finance.ui.design.Group {
                ca.emarshall.finance.ui.design.GroupRow("Bank description", subtitle = tx.rawDescription)
                ca.emarshall.finance.ui.design.GroupRow("Imported from", value = tx.source.lowercase().replaceFirstChar { it.uppercase() }, divider = false)
            }
            Group {
                MenuRow(
                    "Category", cats.firstOrNull { it.id == tx.categoryId }, cats,
                    { it?.let { c -> c.name + if (c.kind == "TRANSFER") " (transfer)" else "" } ?: "Uncategorised" },
                    { c -> c?.let(vm::setCategory) }, divider = false,
                )
            }
            if (tx.categoryLocked) ca.emarshall.finance.ui.design.Footnote("You set this category, so rules won't change it.")
            val investment = runCatching { ca.emarshall.finance.core.model.AccountType.valueOf(tx.accountType) }.getOrNull()?.isInvestment == true
            if (tx.amountCents < 0 && tx.transferLinkId == null && !investment) {
                Group {
                    ca.emarshall.finance.ui.design.GroupRow(
                        "I pay this amount regularly",
                        subtitle = "Every ${ca.emarshall.finance.core.money.Money.format(-tx.amountCents)} payment gets the same category, e.g. rent",
                        icon = androidx.compose.material.icons.Icons.Filled.Home, iconColor = p.chart[0], showChevron = true, divider = false,
                        onClick = { nav.navigate(ca.emarshall.finance.ui.KnownPaymentEditRoute(amountCents = -tx.amountCents, day = tx.date.dayOfMonth)) },
                    )
                }
            }

            if (tx.transferLinkId != null) {
                ca.emarshall.finance.ui.design.SectionHeader("Transfer")
                ca.emarshall.finance.ui.design.FinCard {
                    Text(
                        "Paired with a matching amount in another of your accounts. Transfers don't count as income or spending." +
                            if (tx.transferLocked) " You confirmed it." else "",
                        style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!tx.transferLocked) ca.emarshall.finance.ui.design.PrimaryButton("Confirm", vm::confirmTransfer, Modifier.weight(1f))
                        ca.emarshall.finance.ui.design.SecondaryButton("Not a transfer", vm::unlinkTransfer, Modifier.weight(1f))
                    }
                }
            }

            history?.let { h ->
                ca.emarshall.finance.ui.design.SectionHeader("At ${tx.merchant}", "See all") { nav.navigate(ca.emarshall.finance.ui.TransactionsRoute(query = tx.merchant)) }
                ca.emarshall.finance.ui.design.FinCard {
                    Text(
                        "${ca.emarshall.finance.core.money.Money.formatWhole(h.totalCents)} over ${h.count} payments",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Since ${h.since.pretty()} · about ${ca.emarshall.finance.core.money.Money.format(h.totalCents / h.count)} each time",
                        style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
                    )
                    ca.emarshall.finance.ui.design.BarChart(
                        bars = h.monthly.map { (m, cents) -> ca.emarshall.finance.ui.design.BarDatum(m.format(ca.emarshall.finance.ui.SHORT_MONTH).take(1), cents, p.accent) },
                        selected = h.monthly.indexOfFirst { it.first == java.time.YearMonth.from(tx.date) }.takeIf { it >= 0 },
                        onSelect = { }, height = 110.dp,
                        description = "Spending at ${tx.merchant} by month.",
                    )
                }
                if (h.recent.isNotEmpty()) {
                    Group {
                        h.recent.forEachIndexed { i, r ->
                            ca.emarshall.finance.ui.design.GroupRow(
                                r.date.pretty(), subtitle = r.accountNickname, value = ca.emarshall.finance.core.money.Money.format(-r.amountCents),
                                showChevron = true, divider = i < h.recent.size - 1, onClick = { nav.navigate(TransactionRoute(r.id)) },
                            )
                        }
                    }
                }
            }

            ca.emarshall.finance.ui.design.SectionHeader("Notes")
            ca.emarshall.finance.ui.design.FinCard {
                ca.emarshall.finance.ui.design.FinTextField(notes.orEmpty(), { notes = it }, "Add a note", singleLine = false)
                if (notes != tx.notes.orEmpty()) ca.emarshall.finance.ui.design.PrimaryButton("Save note", { vm.saveNotes(notes.orEmpty()) })
            }
            message?.let { ca.emarshall.finance.ui.design.Notice(it, ca.emarshall.finance.ui.design.NoticeTone.SUCCESS) }
        }

        learn?.let { c ->
            AlertDialog(
                onDismissRequest = { vm.offerLearn.value = null },
                title = { Text("Always do this for ${tx.merchant}?") },
                text = { Text("Future and past transactions from ${tx.merchant} will be categorised as ${c.name}.") },
                confirmButton = { TextButton(onClick = vm::learn) { Text("Always do this") } },
                dismissButton = { TextButton(onClick = { vm.offerLearn.value = null }) { Text("Just this one") } },
            )
        }
    }
}
