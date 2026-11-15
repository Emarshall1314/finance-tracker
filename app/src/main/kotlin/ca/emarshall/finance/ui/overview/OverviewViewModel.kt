package ca.emarshall.finance.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.emarshall.finance.core.analytics.AccountBalance
import ca.emarshall.finance.core.analytics.Analytics
import ca.emarshall.finance.core.analytics.BalancePoint
import ca.emarshall.finance.core.analytics.CategoryTotal
import ca.emarshall.finance.core.analytics.DataFreshness
import ca.emarshall.finance.core.analytics.MerchantTotal
import ca.emarshall.finance.core.analytics.MonthTotals
import ca.emarshall.finance.core.analytics.NetWorth
import ca.emarshall.finance.core.recurring.RecurringSeries
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.QuestradeStatus
import ca.emarshall.finance.data.ReminderRepository
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.data.TransactionRepository
import ca.emarshall.finance.data.db.FinanceDatabase
import ca.emarshall.finance.data.latestBalances
import ca.emarshall.finance.data.ruleKey
import ca.emarshall.finance.data.toFacts
import ca.emarshall.finance.data.typeEnum
import ca.emarshall.finance.data.institutionEnum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class Checklist(
    val hasAccounts: Boolean,
    val hasImports: Boolean,
    val questradeConnected: Boolean,
    val hasReminders: Boolean,
    val dismissed: Boolean,
) {
    val complete get() = hasImports && questradeConnected && hasReminders
}

data class FreshnessRow(val accountId: Long, val nickname: String, val dataThrough: LocalDate?, val stale: Boolean)

data class OverviewState(
    val loading: Boolean = true,
    val checklist: Checklist = Checklist(false, false, false, false, false),
    val month: YearMonth = YearMonth.now(),
    val totals: MonthTotals = MonthTotals(YearMonth.now(), 0, 0),
    val categories: List<Pair<CategoryTotal, Long?>> = emptyList(),
    /** Up to 12 months ending at the latest unlocked month, starting at the first month with any data. */
    val series: List<MonthTotals> = emptyList(),
    /** Biggest spending categories for each month in [series], for the chart's tap details. */
    val seriesCategories: Map<YearMonth, List<Pair<CategoryTotal, Long?>>> = emptyMap(),
    /** Net moved into investment accounts (Questrade) in each month of [series]; part of what was saved. */
    val seriesInvested: Map<YearMonth, Long> = emptyMap(),
    val topMerchants: List<MerchantTotal> = emptyList(),
    val feesCents: Long = 0,
    val upcomingBills: List<RecurringSeries> = emptyList(),
    val netWorthCents: Long? = null,
    val netWorthTrend: List<Pair<YearMonth, Long>> = emptyList(),
    val balances: List<AccountBalance> = emptyList(),
    /** Liquid net worth, segmented by where the money sits. */
    val breakdown: ca.emarshall.finance.core.analytics.NetWorthBreakdown? = null,
    val freshness: List<FreshnessRow> = emptyList(),
    val hasTransactions: Boolean = false,
    /** Latest month with every account's statements in; the dashboard never shows a partial month. */
    val completeThrough: YearMonth? = null,
    /** Card statements the shown month still needs before its numbers are final, with when each should close. */
    val pendingCards: List<Pair<String, LocalDate?>> = emptyList(),
    /** The account whose statement unlocks the next month (chequing), and when it should close. */
    val nextUnlock: Pair<String, LocalDate?>? = null,
    /** Year to date (Jan 1 to the end of [completeThrough]): spending by category, for the donut. */
    val ytd: List<Pair<CategoryTotal, Long?>> = emptyList(),
    val ytdYear: Int = LocalDate.now().year,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    db: FinanceDatabase,
    accounts: AccountRepository,
    private val transactions: TransactionRepository,
    categories: CategoryRepository,
    reminders: ReminderRepository,
    private val settings: SettingsRepository,
    insights: ca.emarshall.finance.data.InsightsRepository,
    statements: ca.emarshall.finance.data.StatementsRepository,
) : ViewModel() {
    /** Top unusual-activity items and missing statements, for the "Needs attention" card. */
    val attention = kotlinx.coroutines.flow.combine(insights.visible, statements.missingCount) { ins, missing ->
        ins.filter { it.severity >= ca.emarshall.finance.core.insights.Severity.WARNING }.take(3) to missing
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<ca.emarshall.finance.core.insights.Insight>() to 0)

    /** Month the user picked; null means "latest complete month". */
    private val month = MutableStateFlow<YearMonth?>(null)
    private val complete = statements.latestUnlocked.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val coverage = statements.coverage

    private val base = combine(transactions.rows, accounts.accounts, categories.categories, db.balances().observeAll(), db.imports().observeCount()) {
            rows, accs, cats, bals, imports ->
        Base(rows, accs, cats, bals, imports)
    }

    private data class Base(
        val rows: List<ca.emarshall.finance.data.db.TxRow>,
        val accounts: List<ca.emarshall.finance.data.db.AccountEntity>,
        val categories: List<ca.emarshall.finance.data.db.CategoryEntity>,
        val balances: List<ca.emarshall.finance.data.db.BalanceSnapshotEntity>,
        val importCount: Int,
    )

    private val monthInfo = combine(month, statements.latestUnlocked, coverage) { picked, done, cov -> Triple(picked, done, cov) }

    val state: StateFlow<OverviewState> = combine(base, reminders.reminders, settings.settings, monthInfo) { b, rems, s, mi ->
        val today = LocalDate.now()
        val (picked, done, cov) = mi
        // Only complete months: before any statements, fall back to the current month.
        val last = done ?: YearMonth.from(today)
        val m = picked?.coerceAtMost(last) ?: last
        val facts = b.rows.toFacts()
        val a = Analytics(facts)
        val catIdByKey = b.categories.associate { it.ruleKey to it.id }
        val byId = b.accounts.associateBy { it.id }
        val latestTx = b.rows.groupBy { it.accountId }.mapValues { (_, l) -> l.maxOf { it.date } }
        val latestBalance = b.balances.groupBy { it.accountId }.mapValues { (_, l) -> l.maxOf { it.date } }
        val bills = transactions.recurring(b.rows, today)
        OverviewState(
            loading = false,
            checklist = Checklist(
                hasAccounts = b.accounts.isNotEmpty(),
                hasImports = b.importCount > 0,
                questradeConnected = s.questradeStatus == QuestradeStatus.CONNECTED,
                hasReminders = rems.any { it.enabled } || s.generalReminderEnabled,
                dismissed = s.checklistDismissed,
            ),
            month = m,
            totals = a.monthTotals(m),
            categories = a.spendingByCategory(m).map { it to catIdByKey[it.categoryKey] },
            series = a.monthSeries(last, 12).dropWhile { it.incomeCents == 0L && it.spendingCents == 0L },
            seriesInvested = a.monthSeries(last, 12).associate { mt -> mt.month to a.investedIn(mt.month) },
            seriesCategories = a.monthSeries(last, 12).associate { mt ->
                mt.month to a.spendingByCategory(mt.month).take(3).map { it to catIdByKey[it.categoryKey] }
            },
            topMerchants = a.topMerchants(m.atDay(1), m.atEndOfMonth(), 5),
            feesCents = a.feesAndInterest(m.atDay(1), m.atEndOfMonth()),
            upcomingBills = bills.filter { !it.nextExpected.isAfter(today.plusDays(31)) },
            netWorthCents = if (b.balances.isEmpty()) null else NetWorth.total(latestBalances(b.balances, byId)),
            netWorthTrend = NetWorth.trend(b.balances.map { BalancePoint(it.accountId, it.date, it.balanceCents, it.currency) }, YearMonth.from(today), 12),
            balances = latestBalances(b.balances, byId),
            breakdown = if (b.balances.isEmpty()) null else ca.emarshall.finance.core.analytics.LiquidNetWorth.breakdown(
                latestBalances(b.balances, byId).filter { it.currency == "CAD" }.mapNotNull { bal ->
                    val a = byId[bal.accountId] ?: return@mapNotNull null
                    ca.emarshall.finance.core.analytics.AccountPosition(a.id, a.nickname, a.institutionEnum, a.typeEnum, bal.balanceCents, bal.asOf)
                },
            ),
            freshness = b.accounts.filter { !it.typeEnum.isInvestment || it.questradeNumber == null }.map { acc ->
                val through = listOfNotNull(latestTx[acc.id], latestBalance[acc.id]).maxOrNull()
                val f = DataFreshness.of(acc.id, through, today)
                FreshnessRow(acc.id, acc.nickname, through, f.isStale)
            },
            hasTransactions = b.rows.isNotEmpty(),
            completeThrough = done,
            pendingCards = if (done == null) emptyList() else
                ca.emarshall.finance.core.statements.MonthCompleteness.waitingOn(cov, m).map { it.name to ca.emarshall.finance.core.statements.MonthCompleteness.nextClose(it) },
            nextUnlock = cov.filter { it.drivesMonths && it.coveredThrough != null }.minByOrNull { it.coveredThrough!! }
                ?.let { it.name to ca.emarshall.finance.core.statements.MonthCompleteness.nextClose(it) },
            ytd = a.spendingByCategory(LocalDate.of(last.year, 1, 1), last.atEndOfMonth()).map { it to catIdByKey[it.categoryKey] },
            ytdYear = last.year,
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewState())

    fun previousMonth() { month.value = state.value.month.minusMonths(1) }
    fun nextMonth() {
        val limit = complete.value ?: YearMonth.now()
        if (state.value.month < limit) month.value = state.value.month.plusMonths(1)
    }
    /** Show [m] in the hero (from tapping a month in the chart). */
    fun showMonth(m: YearMonth) { month.value = m }
    fun dismissChecklist() = viewModelScope.launch { settings.setChecklistDismissed(true) }
}
