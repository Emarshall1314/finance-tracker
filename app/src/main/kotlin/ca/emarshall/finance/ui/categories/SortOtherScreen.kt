package ca.emarshall.finance.ui.categories

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.core.categorize.DefaultCategories
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.TransactionRepository
import ca.emarshall.finance.data.db.CategoryEntity
import ca.emarshall.finance.data.db.TxRow
import ca.emarshall.finance.data.kindEnum
import ca.emarshall.finance.ui.SHORT_DAY
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.EmptyMessage
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.MenuRow
import ca.emarshall.finance.ui.design.Notice
import ca.emarshall.finance.ui.design.NoticeTone
import ca.emarshall.finance.ui.design.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** A merchant whose payments the rules couldn't place. */
data class UnsortedMerchant(val merchant: String, val cents: Long, val count: Int, val last: LocalDate)

/** Spending the rules left in "Other": money out of a bank or card account that isn't a transfer. */
fun List<TxRow>.unsortedMerchants(): List<UnsortedMerchant> = filter { t ->
    val investment = runCatching { AccountType.valueOf(t.accountType) }.getOrNull()?.isInvestment == true
    !investment && t.amountCents < 0 && t.transferLinkId == null && !t.categoryLocked &&
        (t.categoryId == null || t.categoryKey == DefaultCategories.FALLBACK_EXPENSE)
}.groupBy { it.merchant }
    .map { (m, l) -> UnsortedMerchant(m, -l.sumOf { it.amountCents }, l.size, l.maxOf { it.date }) }
    .sortedByDescending { it.cents }

@HiltViewModel
class SortOtherViewModel @Inject constructor(private val categories: CategoryRepository, transactions: TransactionRepository) : ViewModel() {
    val merchants = transactions.rows.map { rows -> rows.unsortedMerchants() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val spendingCategories = categories.categories.map { c ->
        c.filter { it.kindEnum == CategoryKind.EXPENSE && it.key != DefaultCategories.FALLBACK_EXPENSE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)

    fun assign(m: UnsortedMerchant, c: CategoryEntity) = viewModelScope.launch {
        val n = categories.learn(m.merchant, c.id)
        message.value = "${m.merchant} is now ${c.name} ($n payment${if (n == 1) "" else "s"}). New ones will be too."
    }
}

/** Everything left in "Other", biggest first: pick a category once and the app remembers it for that merchant. */
@Composable
fun SortOtherScreen(nav: NavController, vm: SortOtherViewModel = hiltViewModel()) {
    val merchants by vm.merchants.collectAsStateWithLifecycle()
    val cats by vm.spendingCategories.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    DetailScreen("Sort out Other", onBack = { nav.popBackStack() }) {
        val list = merchants ?: return@DetailScreen
        message?.let { m -> item { Notice(m, NoticeTone.SUCCESS) } }
        if (list.isEmpty()) {
            item { EmptyMessage("Nothing left in Other. Every payment has a category.", Icons.Default.CheckCircle) }
            return@DetailScreen
        }
        item {
            Footnote(
                "These are the places the app couldn't recognise, biggest first. Pick a category once: it applies to every " +
                    "payment to that place, past and future.",
            )
        }
        item { SectionHeader("${list.size} place${if (list.size == 1) "" else "s"} · ${Money.formatWhole(list.sumOf { it.cents })}") }
        // In groups of eight so a long list still scrolls smoothly.
        list.chunked(8).forEach { chunk ->
            item(key = chunk.first().merchant) {
                Group {
                    chunk.forEachIndexed { i, m ->
                        MenuRow<CategoryEntity?>(
                            m.merchant, null, cats, { it?.name ?: "Choose" }, { c -> c?.let { vm.assign(m, it) } },
                            subtitle = "${Money.format(m.cents)} · ${m.count} payment${if (m.count == 1) "" else "s"} · last ${m.last.format(SHORT_DAY)}",
                            divider = i < chunk.size - 1,
                        )
                    }
                }
            }
        }
    }
}
