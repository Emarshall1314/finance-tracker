package ca.emarshall.finance.ui.categories

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import ca.emarshall.finance.core.categorize.AmountRule
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.TransactionRepository
import ca.emarshall.finance.data.db.AmountRuleEntity
import ca.emarshall.finance.data.db.CategoryEntity
import ca.emarshall.finance.data.db.TxRow
import ca.emarshall.finance.data.kindEnum
import ca.emarshall.finance.ui.KnownPaymentEditRoute
import ca.emarshall.finance.ui.SHORT_DAY
import ca.emarshall.finance.ui.TransactionRoute
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.EmptyMessage
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.FinTextField
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.GroupRow
import ca.emarshall.finance.ui.design.MenuRow
import ca.emarshall.finance.ui.design.Notice
import ca.emarshall.finance.ui.design.NoticeTone
import ca.emarshall.finance.ui.design.PrimaryButton
import ca.emarshall.finance.ui.design.SecondaryButton
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.theme.Fin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "1st", "2nd", "23rd". */
fun ordinal(day: Int): String = day.toString() + when {
    day % 100 in 11..13 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}

private fun AmountRuleEntity.toCore() = AmountRule(id, name, amountCents, "id:$categoryId", toleranceCents, dayOfMonth)

/**
 * Payments this rule would claim, roughly as the categorizer sees them: money out of a bank or card account,
 * not a transfer, not a category the user picked by hand.
 */
private fun List<TxRow>.matching(rule: AmountRule): List<TxRow> = filter { t ->
    val investment = runCatching { AccountType.valueOf(t.accountType) }.getOrNull()?.isInvestment == true
    !investment && t.transferLinkId == null && t.categoryKind != CategoryKind.TRANSFER.name && !t.categoryLocked &&
        rule.matches(t.amountCents, t.date)
}.sortedByDescending { it.date }

data class KnownPaymentRow(val rule: AmountRuleEntity, val categoryName: String, val matches: Int, val last: java.time.LocalDate?)

@HiltViewModel
class KnownPaymentsViewModel @Inject constructor(categories: CategoryRepository, transactions: TransactionRepository) : ViewModel() {
    val rows = combine(categories.amountRules, categories.categories, transactions.rows) { rules, cats, txs ->
        val names = cats.associate { it.id to it.name }
        rules.map { r ->
            val m = txs.matching(r.toCore())
            KnownPaymentRow(r, names[r.categoryId] ?: "?", m.size, m.firstOrNull()?.date)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

fun describe(r: AmountRuleEntity): String = buildString {
    append(Money.format(r.amountCents))
    if (r.toleranceCents > 0) append(" (± ${Money.formatWhole(r.toleranceCents)})")
    append(r.dayOfMonth?.let { " · around the ${ordinal(it)}" } ?: " · any day")
}

@Composable
fun KnownPaymentsScreen(nav: NavController, vm: KnownPaymentsViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val p = Fin.palette
    DetailScreen("Known payments", onBack = { nav.popBackStack() }) {
        item {
            Footnote(
                "Tell the app about payments you make regularly, like rent. Any payment of that amount gets the category you choose, " +
                    "even when the bank's description doesn't say what it was (an e-Transfer to your landlord, for example).",
            )
        }
        val list = rows ?: return@DetailScreen
        if (list.isEmpty()) {
            item { EmptyMessage("No known payments yet.", Icons.Default.Home, action = "Add one", onAction = { nav.navigate(KnownPaymentEditRoute()) }) }
        } else {
            item {
                Group {
                    list.forEachIndexed { i, r ->
                        GroupRow(
                            r.rule.name,
                            subtitle = "${describe(r.rule)} → ${r.categoryName}\n" +
                                if (r.matches == 0) "No payments matched yet" else
                                    "${r.matches} payment${if (r.matches == 1) "" else "s"}, last on ${r.last?.format(SHORT_DAY)}",
                            icon = Icons.Default.Home, iconColor = p.chart[i % p.chart.size], showChevron = true,
                            divider = i < list.size - 1, onClick = { nav.navigate(KnownPaymentEditRoute(id = r.rule.id)) },
                        )
                    }
                }
            }
            item { PrimaryButton("Add a payment", { nav.navigate(KnownPaymentEditRoute()) }) }
        }
        item {
            Footnote("Payments the app already knows are transfers (card payments, Questrade deposits) are never changed, and neither is a category you picked by hand.")
        }
    }
}

@HiltViewModel
class KnownPaymentEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val categories: CategoryRepository,
    transactions: TransactionRepository,
) : ViewModel() {
    val route = savedState.toRoute<KnownPaymentEditRoute>()
    val spendingCategories = categories.categories.map { c -> c.filter { it.kindEnum == CategoryKind.EXPENSE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val existing = MutableStateFlow<AmountRuleEntity?>(null)
    val loaded = MutableStateFlow(route.id <= 0)

    /** The rule as typed so far, for the live list of past payments it would match. */
    val draft = MutableStateFlow<AmountRule?>(null)
    val preview = combine(draft, transactions.rows) { d, txs -> d?.let { txs.matching(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val result = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    init {
        if (route.id > 0) viewModelScope.launch { existing.value = categories.amountRule(route.id); loaded.value = true }
    }

    fun save(name: String, amountCents: Long, toleranceCents: Long, day: Int?, category: CategoryEntity?) = viewModelScope.launch {
        busy.value = true
        val rule = AmountRuleEntity(
            id = existing.value?.id ?: 0, name = name.trim(), amountCents = amountCents, toleranceCents = toleranceCents,
            categoryId = category?.id ?: 0, dayOfMonth = day,
        )
        val n = categories.saveAmountRule(rule, newCategoryName = if (category == null) name else null)
        busy.value = false
        result.value = if (n == 0) "Saved. No past payments match yet; new statements will be checked."
        else "Saved. $n payment${if (n == 1) " is" else "s are"} now ${name.trim()}."
    }

    fun delete() = viewModelScope.launch {
        existing.value?.let { categories.deleteAmountRule(it.id) }
        result.value = "deleted"
    }
}

private val TOLERANCES = listOf(0L, 500L, 2_500L, 5_000L, 10_000L)
private val DAYS: List<Int?> = listOf<Int?>(null) + (1..31).toList()

@Composable
fun KnownPaymentEditScreen(nav: NavController, vm: KnownPaymentEditViewModel = hiltViewModel()) {
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val existing by vm.existing.collectAsStateWithLifecycle()
    val cats by vm.spendingCategories.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val p = Fin.palette

    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf(if (vm.route.amountCents > 0) Money.fromCents(vm.route.amountCents).toPlainString() else "") }
    var tolerance by remember { mutableStateOf(0L) }
    var day by remember { mutableStateOf(vm.route.day.takeIf { it in 1..31 }) }
    /** null = make a new category named after the payment. */
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var categoryTouched by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(existing) {
        existing?.let { e ->
            name = e.name; amount = Money.fromCents(e.amountCents).toPlainString(); tolerance = e.toleranceCents
            day = e.dayOfMonth; categoryId = e.categoryId; categoryTouched = true
        }
    }
    // Until the user picks one, use the category with the same name as the payment if there is one.
    val sameName = cats.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    val chosen = if (categoryTouched) cats.firstOrNull { it.id == categoryId } else sameName
    val cents = runCatching { Money.parse(amount) }.getOrNull()?.let { Math.abs(it) }?.takeIf { it > 0 }
    LaunchedEffect(cents, tolerance, day) {
        vm.draft.value = cents?.let { AmountRule(name = "", amountCents = it, categoryKey = "", toleranceCents = tolerance, dayOfMonth = day) }
    }
    LaunchedEffect(result) { if (result == "deleted") nav.popBackStack() }

    DetailScreen(if (existing != null) existing!!.name else "New known payment", onBack = { nav.popBackStack() }) {
        if (!loaded) return@DetailScreen
        item {
            FinCard {
                FinTextField(name, { name = it }, "What is it?", placeholder = "Rent")
                FinTextField(
                    amount, { amount = it }, "Amount each time", placeholder = "1800.00",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
        item {
            Group {
                MenuRow(
                    "Amount can vary by", tolerance, TOLERANCES, { if (it == 0L) "Exact amount" else "Up to ${Money.formatWhole(it)}" }, { tolerance = it },
                    icon = Icons.Default.Tune, iconColor = p.chart[2],
                )
                MenuRow(
                    "Usually paid", day, DAYS, { d -> d?.let { "Around the ${ordinal(it)}" } ?: "Any day" }, { day = it },
                    icon = Icons.Default.CalendarMonth, iconColor = p.chart[3],
                )
                MenuRow(
                    "Category", chosen, listOf<CategoryEntity?>(null) + cats,
                    { c -> c?.name ?: "New category: ${name.trim().ifEmpty { "…" }}" }, { c -> categoryTouched = true; categoryId = c?.id },
                    icon = Icons.Default.Category, iconColor = p.chart[1], divider = false,
                )
            }
        }
        item { Footnote("\"Around\" means within ${AmountRule.DEFAULT_DAY_WINDOW} days either side, so rent paid a day or two early still counts.") }

        val matches = preview
        if (cents != null && matches != null) {
            item { SectionHeader(if (matches.isEmpty()) "No past payments match" else "Matches ${matches.size} past payment${if (matches.size == 1) "" else "s"}") }
            if (matches.isNotEmpty()) item {
                Group {
                    val shown = matches.take(6)
                    shown.forEachIndexed { i, t ->
                        GroupRow(
                            t.merchant, subtitle = "${t.date.format(SHORT_DAY)} ${t.date.year} · ${t.accountNickname} · now ${t.categoryName ?: "Other"}",
                            value = Money.format(-t.amountCents), divider = i < shown.size - 1,
                            onClick = { nav.navigate(TransactionRoute(t.id)) },
                        )
                    }
                }
            }
            if (matches.size > 6) item { Footnote("And ${matches.size - 6} more.") }
        }

        item {
            val ok = name.isNotBlank() && cents != null
            PrimaryButton(
                "Save", { if (cents != null) vm.save(name, cents, tolerance, day, chosen) },
                enabled = ok && !busy, loading = busy,
            )
        }
        result?.takeIf { it != "deleted" }?.let { r -> item { Notice(r, NoticeTone.SUCCESS, action = "Done", onAction = { nav.popBackStack() }) } }
        if (existing != null) item { SecondaryButton("Delete", { confirmDelete = true }, destructive = true) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing?.name}?") },
            text = { Text("Its payments go back to the category the bank description suggests. The category itself is kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
