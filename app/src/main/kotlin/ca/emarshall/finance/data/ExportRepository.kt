package ca.emarshall.finance.data

import android.content.Context
import android.net.Uri
import ca.emarshall.finance.core.analytics.AccountBalance
import ca.emarshall.finance.core.analytics.BalancePoint
import ca.emarshall.finance.core.analytics.NetWorth
import ca.emarshall.finance.core.summary.SummaryAccount
import ca.emarshall.finance.core.summary.SummaryExport
import ca.emarshall.finance.core.summary.SummaryInput
import ca.emarshall.finance.core.summary.TransactionCsvExport
import ca.emarshall.finance.data.db.BalanceSnapshotEntity
import ca.emarshall.finance.data.db.FinanceDatabase
import ca.emarshall.finance.security.SecretStore
import ca.emarshall.finance.work.QuestradeSyncWorker
import ca.emarshall.finance.work.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Latest balance per account (positive = asset, negative = owed). */
fun latestBalances(snapshots: List<BalanceSnapshotEntity>, accounts: Map<Long, ca.emarshall.finance.data.db.AccountEntity>): List<AccountBalance> =
    snapshots.groupBy { it.accountId }.mapNotNull { (id, l) ->
        val last = l.maxByOrNull { it.date } ?: return@mapNotNull null
        val a = accounts[id] ?: return@mapNotNull null
        AccountBalance(id, a.nickname, a.typeEnum.isLiability, last.balanceCents, last.date, last.currency)
    }.sortedBy { it.nickname }

@Singleton
class ExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FinanceDatabase,
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
    private val questrade: QuestradeRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val categories: CategoryRepository,
    private val reminders: ReminderScheduler,
) {
    /** The Markdown report for pasting into Claude. Nicknames only; numbers masked. */
    suspend fun summaryMarkdown(months: Int): String = withContext(Dispatchers.Default) {
        val today = LocalDate.now()
        val from = YearMonth.from(today).minusMonths((months - 1).toLong()).atDay(1)
        val accs = accounts.all()
        val byId = accs.associateBy { it.id }
        val rows = transactions.rows.first()
        val snapshots = db.balances().all()
        val inv = InvestmentsCalculator.build(
            accs, questrade.latestBalances.first(), questrade.latestPositions.first(), questrade.activities.first(), today,
            settings.current().questradeLastSyncMillis,
        )
        SummaryExport.markdown(
            SummaryInput(
                from = from,
                to = today,
                accounts = accs.map { SummaryAccount(it.nickname, it.typeEnum.displayName, it.institutionEnum.displayName) },
                facts = rows.filter { !it.date.isBefore(from) && !it.date.isAfter(today) }.map { it.toFact() },
                bills = transactions.recurring(rows, today),
                balances = latestBalances(snapshots, byId),
                netWorthTrend = NetWorth.trend(snapshots.map { BalancePoint(it.accountId, it.date, it.balanceCents, it.currency) }, YearMonth.from(today), months),
                investments = inv?.toSummary(),
            ),
        )
    }

    /** All transactions as CSV: account nicknames, no account numbers. */
    suspend fun transactionsCsv(): String = withContext(Dispatchers.Default) {
        TransactionCsvExport.csv(
            transactions.rows.first().map {
                TransactionCsvExport.Row(it.date, it.accountNickname, it.merchant, it.rawDescription, it.categoryName ?: "", it.amountCents, it.currency, it.notes)
            },
        )
    }

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw java.io.IOException("Couldn't write to that file")
    }

    /** Everything in the database as JSON, for the user's own backup. Contains no secrets or tokens. */
    suspend fun exportAllJson(): String = withContext(Dispatchers.IO) {
        fun arr(items: List<JsonObject>) = JsonArray(items)
        val json = buildJsonObject {
            put("app", "finance-tracker")
            put("format", 1)
            put("exportedAt", java.time.Instant.now().toString())
            put("accounts", arr(db.accounts().all().map { a ->
                buildJsonObject {
                    put("id", a.id); put("institution", a.institution); put("nickname", a.nickname); put("type", a.type)
                    put("currency", a.currency); put("last4", a.last4); put("closingDay", a.closingDay); put("questradeNumber", a.questradeNumber)
                }
            }))
            put("categories", arr(db.categories().all().map { c ->
                buildJsonObject { put("id", c.id); put("key", c.key); put("name", c.name); put("kind", c.kind); put("builtIn", c.builtIn) }
            }))
            put("rules", arr(db.categories().rules().map { r ->
                buildJsonObject {
                    put("pattern", r.pattern); put("matchType", r.matchType); put("categoryId", r.categoryId)
                    put("priority", r.priority); put("learned", r.learned); put("amountSign", r.amountSign)
                }
            }))
            put("transactions", arr(db.transactions().all().map { t ->
                buildJsonObject {
                    put("id", t.id); put("accountId", t.accountId); put("date", t.date.toString()); put("amountCents", t.amountCents)
                    put("currency", t.currency); put("rawDescription", t.rawDescription); put("merchant", t.merchant)
                    put("categoryId", t.categoryId); put("categoryLocked", t.categoryLocked); put("transferLinkId", t.transferLinkId)
                    put("transferLocked", t.transferLocked); put("source", t.source); put("importBatchId", t.importBatchId)
                    put("fingerprint", t.fingerprint); put("fitId", t.fitId); put("notes", t.notes)
                }
            }))
            put("balances", arr(db.balances().all().map { b ->
                buildJsonObject {
                    put("accountId", b.accountId); put("date", b.date.toString()); put("balanceCents", b.balanceCents)
                    put("currency", b.currency); put("source", b.source)
                }
            }))
            put("reminders", arr(db.reminders().all().map { r ->
                buildJsonObject {
                    put("accountId", r.accountId); put("closingDay", r.closingDay); put("offsetDays", r.offsetDays)
                    put("timeOfDayMinutes", r.timeOfDayMinutes); put("enabled", r.enabled)
                }
            }))
            put("note", JsonPrimitive("Questrade tokens are deliberately not included."))
        }
        json.toString()
    }

    /** Deletes every transaction, account, setting and token, then restores the default categories. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        reminders.cancelAll()
        QuestradeSyncWorker.cancel(context)
        db.clearAllTables()
        secrets.clear()
        settings.clearAll()
        categories.ensureSeeded()
    }
}
