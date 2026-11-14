package ca.emarshall.finance.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.TransactionRepository
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.db.CategoryEntity
import ca.emarshall.finance.data.db.TxRow
import ca.emarshall.finance.ui.ColorDot
import ca.emarshall.finance.ui.EmptyState
import ca.emarshall.finance.ui.MoneyText
import ca.emarshall.finance.ui.Picker
import ca.emarshall.finance.ui.SHORT_DAY
import ca.emarshall.finance.ui.TransactionRoute
import ca.emarshall.finance.ui.TransactionsRoute
import ca.emarshall.finance.ui.pretty
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.FlowPreview
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class Period(val label: String) { THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_3("3 months"), THIS_YEAR("This year"), ALL("All"), CUSTOM_MONTH("") }

data class TxFilter(
    val query: String = "",
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val period: Period = Period.LAST_3,
    val month: YearMonth? = null,
) {
    fun range(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> = when (period) {
        Period.THIS_MONTH -> today.withDayOfMonth(1) to today
        Period.LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
        Period.LAST_3 -> YearMonth.from(today).minusMonths(2).atDay(1) to today
        Period.THIS_YEAR -> today.withDayOfYear(1) to today
        Period.ALL -> LocalDate.of(1970, 1, 1) to LocalDate.of(2999, 12, 31)
        Period.CUSTOM_MONTH -> (month ?: YearMonth.from(today)).let { it.atDay(1) to it.atEndOfMonth() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
) : ViewModel() {
    private val route = savedState.toRoute<TransactionsRoute>()
    val filter = MutableStateFlow(
        TxFilter(
            accountId = route.accountId.takeIf { it > 0 },
            categoryId = route.categoryId.takeIf { it > 0 },
            period = if (route.month.isNotEmpty()) Period.CUSTOM_MONTH else Period.LAST_3,
            month = route.month.takeIf { it.isNotEmpty() }?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
        ),
    )
    val accounts = accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rows = filter.debounce(150).flatMapLatest { f ->
        val (from, to) = f.range()
        transactions.search(f.accountId, f.categoryId, from, to, f.query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val totals = combine(rows, filter) { r, _ ->
        r.orEmpty().filter { it.currency == "CAD" }.let { l -> l.filter { it.amountCents > 0 }.sumOf { it.amountCents } to l.filter { it.amountCents < 0 }.sumOf { it.amountCents } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L to 0L)

    fun update(f: (TxFilter) -> TxFilter) { filter.value = f(filter.value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(nav: NavController, route: TransactionsRoute, vm: TransactionsViewModel = hiltViewModel()) {
    val f by vm.filter.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Transactions") })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = f.query, onValueChange = { q -> vm.update { it.copy(query = q) } },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("Search merchant, description or notes") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(
                    "Account", accounts.firstOrNull { it.id == f.accountId }, listOf<AccountEntity?>(null) + accounts,
                    { it?.nickname ?: "All accounts" }, { a -> vm.update { it.copy(accountId = a?.id) } }, Modifier.weight(1f), "All accounts",
                )
                Picker(
                    "Category", categories.firstOrNull { it.id == f.categoryId }, listOf<CategoryEntity?>(null) + categories,
                    { it?.name ?: "All categories" }, { c -> vm.update { it.copy(categoryId = c?.id) } }, Modifier.weight(1f), "All categories",
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (f.month != null) {
                    FilterChip(f.period == Period.CUSTOM_MONTH, { vm.update { it.copy(period = Period.CUSTOM_MONTH) } }, label = { Text(f.month!!.pretty()) })
                }
                Period.entries.filter { it != Period.CUSTOM_MONTH }.forEach { p ->
                    FilterChip(f.period == p, { vm.update { it.copy(period = p) } }, label = { Text(p.label) })
                }
            }
            Row {
                Text("In ", style = MaterialTheme.typography.bodySmall); MoneyText(totals.first, style = MaterialTheme.typography.bodySmall)
                Text("   Out ", style = MaterialTheme.typography.bodySmall); MoneyText(totals.second, style = MaterialTheme.typography.bodySmall)
            }
        }
        val list = rows
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState("No transactions match.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { t ->
                    TxListItem(t) { nav.navigate(TransactionRoute(t.id)) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun TxListItem(t: TxRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (t.transferLinkId != null) Icon(Icons.Default.SwapHoriz, contentDescription = "Transfer")
        else ColorDot(t.categoryColor ?: 0xFF9E9E9E)
        Column(Modifier.weight(1f)) {
            Text(t.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${t.date.format(SHORT_DAY)} · ${t.categoryName ?: "Uncategorized"} · ${t.accountNickname}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        MoneyText(t.amountCents, currency = t.currency)
    }
}
