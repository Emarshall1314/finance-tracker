package ca.emarshall.finance.data

import ca.emarshall.finance.core.goals.Goal
import ca.emarshall.finance.core.goals.GoalProgress
import ca.emarshall.finance.core.goals.GoalTracker
import ca.emarshall.finance.core.goals.GoalType
import ca.emarshall.finance.core.insights.Insight
import ca.emarshall.finance.core.insights.InsightEngine
import ca.emarshall.finance.core.insights.Severity
import ca.emarshall.finance.core.statements.CoveredRange
import ca.emarshall.finance.core.statements.ExpectedStatement
import ca.emarshall.finance.core.statements.StatementCoverage
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.db.FinanceDatabase
import ca.emarshall.finance.data.db.GoalEntity
import ca.emarshall.finance.data.db.ImportBatchEntity
import ca.emarshall.finance.notify.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Unusual-activity warnings, computed on the phone. */
@Singleton
class InsightsRepository @Inject constructor(
    private val db: FinanceDatabase,
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val statements: StatementsRepository,
) {
    /** All current insights, dismissed ones included (the UI filters). */
    val all: Flow<List<Insight>> = combine(transactions.rows, accounts.accounts, db.balances().observeAll(), statements.latestComplete) { rows, accs, bals, complete ->
        val today = LocalDate.now()
        InsightEngine.analyze(
            facts = rows.map { it.toFact() },
            bills = transactions.recurring(rows, today),
            balances = latestBalances(bals, accs.associateBy { it.id }),
            accountNames = accs.associate { it.id to it.nickname },
            today = today,
            completeThrough = complete,
        )
    }

    val visible: Flow<List<Insight>> = combine(all, settings.settings) { list, s -> list.filter { it.id !in s.dismissedInsights } }

    suspend fun dismiss(id: String) = settings.dismissInsight(id)

    /** After an import or sync: one notification for warnings and alerts not seen before. */
    suspend fun notifyNew() {
        val s = settings.current()
        val fresh = visible.first().filter { it.severity >= Severity.WARNING && it.id !in s.notifiedInsights }
        if (fresh.isEmpty()) return
        notifier.unusualActivity(fresh.size, fresh.first().title)
        settings.markNotified(fresh.map { it.id })
    }
}

data class GoalView(val entity: GoalEntity, val progress: GoalProgress)

@Singleton
class GoalRepository @Inject constructor(
    private val db: FinanceDatabase,
    private val transactions: TransactionRepository,
    statements: StatementsRepository,
) {
    val goals: Flow<List<GoalView>> = combine(db.goals().observeActive(), transactions.rows, statements.latestUnlocked) { goals, rows, latest ->
        val facts = rows.map { it.toFact() }
        val today = LocalDate.now()
        goals.map { g -> GoalView(g, GoalTracker.progress(g.toCore(), facts, today, latest)) }
    }

    suspend fun save(g: GoalEntity): Long = db.goals().upsert(g)
    suspend fun get(id: Long) = db.goals().get(id)
    suspend fun delete(id: Long) = db.goals().delete(id)
}

fun GoalEntity.toCore() = Goal(
    id = id, type = runCatching { GoalType.valueOf(type) }.getOrDefault(GoalType.SAVE_AMOUNT), title = title,
    targetCents = targetCents, targetPercent = targetPercent, categoryKey = categoryKey, start = start, deadline = deadline,
)

data class StatementRow(val batch: ImportBatchEntity, val start: LocalDate?, val end: LocalDate?)

data class AccountStatements(
    val account: AccountEntity,
    val closingDay: Int?,
    val imported: List<StatementRow>,
    val missing: List<ExpectedStatement>,
)

/** The statement library and missing-statement check, per account. */
@Singleton
class StatementsRepository @Inject constructor(private val db: FinanceDatabase, accounts: AccountRepository) {
    /** How far each statement account's imports reach (Questrade syncs live, so it's left out). */
    val coverage: Flow<List<ca.emarshall.finance.core.statements.AccountCoverage>> =
        combine(accounts.accounts, db.imports().observeBatches()) { accs, batches ->
            accs.filter { it.questradeNumber == null }.map { a ->
                ca.emarshall.finance.core.statements.AccountCoverage(
                    a.id, a.nickname, batches.filter { it.accountId == a.id }.mapNotNull { it.periodEnd ?: it.dateTo }.maxOrNull(),
                    // Bank accounts (where income lands) unlock a month; cards only make it final.
                    drivesMonths = !a.typeEnum.isLiability && !a.typeEnum.isInvestment,
                    closingDay = a.closingDay,
                )
            }
        }

    /** Latest month the dashboard shows: the bank statements are in, card statements may still be coming. */
    val latestUnlocked: Flow<java.time.YearMonth?> = coverage.map { ca.emarshall.finance.core.statements.MonthCompleteness.latestUnlocked(it) }

    /** Latest month every statement account fully covers: final numbers (insights, AI summary). */
    val latestComplete: Flow<java.time.YearMonth?> = coverage.map { ca.emarshall.finance.core.statements.MonthCompleteness.latestComplete(it) }

    val byAccount: Flow<List<AccountStatements>> = combine(accounts.accounts, db.imports().observeBatches()) { accs, batches ->
        val today = LocalDate.now()
        accs.filter { it.questradeNumber == null }.map { a ->
            val mine = batches.filter { it.accountId == a.id }
            val rows = mine.map { b -> StatementRow(b, b.periodStart ?: b.dateFrom, b.periodEnd ?: b.dateTo) }
                .sortedByDescending { it.end }
            val ranges = rows.mapNotNull { r -> if (r.start != null && r.end != null) CoveredRange(r.start, r.end) else null }
            val closing = a.closingDay ?: StatementCoverage.inferClosingDay(mine.mapNotNull { it.periodEnd }) ?: if (ranges.isNotEmpty()) 31 else null
            val missing = if (closing != null) StatementCoverage.missing(ranges, closing, today) else emptyList()
            AccountStatements(a, closing, rows, missing.sortedByDescending { it.periodEnd })
        }
    }

    val missingCount: Flow<Int> = byAccount.map { list -> list.sumOf { it.missing.size } }
}
