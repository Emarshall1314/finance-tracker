package ca.emarshall.finance.data

import android.content.Context
import androidx.room.withTransaction
import ca.emarshall.finance.core.importing.Hashing
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.core.model.TxSource
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.data.db.AccountDao
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.db.BalanceDao
import ca.emarshall.finance.data.db.BalanceSnapshotEntity
import ca.emarshall.finance.data.db.FinanceDatabase
import ca.emarshall.finance.data.db.QtActivityEntity
import ca.emarshall.finance.data.db.QtBalanceEntity
import ca.emarshall.finance.data.db.QtPositionEntity
import ca.emarshall.finance.data.db.QuestradeDao
import ca.emarshall.finance.data.db.TransactionDao
import ca.emarshall.finance.data.db.TransactionEntity
import ca.emarshall.finance.net.questrade.QtActivity
import ca.emarshall.finance.net.questrade.QuestradeAuth
import ca.emarshall.finance.net.questrade.QuestradeClient
import ca.emarshall.finance.security.SecretStore
import ca.emarshall.finance.work.QuestradeSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only Questrade sync: accounts, balances, positions and activities. Never trades. */
@Singleton
class QuestradeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FinanceDatabase,
    private val auth: QuestradeAuth,
    private val client: QuestradeClient,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val accountDao: AccountDao,
    private val qtDao: QuestradeDao,
    private val balanceDao: BalanceDao,
    private val txDao: TransactionDao,
    private val categories: CategoryRepository,
    private val transactions: TransactionRepository,
) {
    val latestBalances = qtDao.observeLatestBalances()
    val latestPositions = qtDao.observeLatestPositions()
    val activities = qtDao.observeActivities()
    private val syncLock = Mutex()

    suspend fun isConnected() = secrets.load() != null && settings.current().questradeStatus == QuestradeStatus.CONNECTED

    /** Redeems the token the user pasted, then runs the first sync (12 months of activity). */
    suspend fun connect(manualToken: String) {
        auth.connect(manualToken)
        settings.setQuestradeStatus(QuestradeStatus.CONNECTED)
        QuestradeSyncWorker.schedule(context)
        sync()
    }

    suspend fun disconnect() {
        secrets.clear()
        settings.setQuestradeStatus(QuestradeStatus.NOT_CONNECTED)
        QuestradeSyncWorker.cancel(context)
    }

    suspend fun sync() = syncLock.withLock {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val today = now.toLocalDate()
        val takenAt = System.currentTimeMillis()
        val snap = categories.snapshot()

        for (qa in client.accounts()) {
            val account = accountDao.byQuestradeNumber(qa.number) ?: run {
                val type = mapType(qa.type)
                val id = accountDao.insert(
                    AccountEntity(
                        institution = Institution.QUESTRADE.name, nickname = "Questrade ${type.displayName}",
                        type = type.name, last4 = qa.number.takeLast(4), questradeNumber = qa.number,
                    ),
                )
                accountDao.get(id)!!
            }

            val balances = client.balances(qa.number)
            val positions = client.positions(qa.number)
            val start = account.questradeSyncedThrough?.minusDays(7)?.atStartOfDay(zone) ?: now.minusMonths(12)
            val acts = client.activities(qa.number, start, now)

            db.withTransaction {
                val rows = balances.perCurrencyBalances.map { b -> qtBalance(account.id, takenAt, b, combined = false) } +
                    balances.combinedBalances.map { b -> qtBalance(account.id, takenAt, b, combined = true) }
                qtDao.insertBalances(rows)
                // Net worth uses Questrade's own combined-in-CAD total equity.
                balances.combinedBalances.firstOrNull { it.currency == "CAD" }?.let { cad ->
                    balanceDao.upsertAll(listOf(BalanceSnapshotEntity(accountId = account.id, date = today, balanceCents = Money.toCents(cad.totalEquity), currency = "CAD", source = TxSource.QUESTRADE.name)))
                }
                qtDao.insertPositions(
                    positions.filter { it.openQuantity.signum() != 0 }.map { p ->
                        QtPositionEntity(
                            accountId = account.id, takenAtMillis = takenAt, symbol = p.symbol, symbolId = p.symbolId,
                            quantity = p.openQuantity.stripTrailingZeros().toPlainString(), currency = currencyOf(p.symbol),
                            marketValueCents = Money.toCents(p.currentMarketValue), currentPrice = p.currentPrice.toPlainString(),
                            averageEntryPrice = p.averageEntryPrice.toPlainString(), openPnlCents = Money.toCents(p.openPnl),
                            totalCostCents = Money.toCents(p.totalCost),
                        )
                    },
                )
                qtDao.prunePositions(account.id, takenAt - 90L * 24 * 3600 * 1000)

                val occurrences = HashMap<String, Int>()
                val entities = acts.map { a ->
                    val base = listOf(a.transactionDate, a.type, a.action, a.symbol, a.netAmount.toPlainString(), a.currency, a.description).joinToString("|")
                    val n = occurrences.merge(base, 1, Int::plus)!!
                    toEntity(account.id, a, Hashing.sha256Hex("$base|$n".toByteArray()))
                }
                val inserted = qtDao.insertActivities(entities)
                // Money moving in or out of the account becomes a transaction: deposits/withdrawals feed
                // transfer matching; dividends and interest are investment income.
                val mirrored = entities.zip(inserted).filter { (_, rowId) -> rowId != -1L }.mapNotNull { (e, _) -> mirror(account.id, e, snap) }
                txDao.insertAll(mirrored)
                accountDao.update(accountDao.get(account.id)!!.copy(questradeSyncedThrough = today))
            }
        }
        settings.setQuestradeStatus(QuestradeStatus.CONNECTED)
        settings.setQuestradeLastSync(System.currentTimeMillis())
        transactions.rematchTransfers()
    }

    private fun qtBalance(accountId: Long, takenAt: Long, b: ca.emarshall.finance.net.questrade.QtBalance, combined: Boolean) =
        QtBalanceEntity(
            accountId = accountId, takenAtMillis = takenAt, currency = b.currency, combined = combined,
            cashCents = Money.toCents(b.cash), marketValueCents = Money.toCents(b.marketValue), totalEquityCents = Money.toCents(b.totalEquity),
        )

    private fun toEntity(accountId: Long, a: QtActivity, key: String) = QtActivityEntity(
        accountId = accountId, activityKey = key, tradeDate = a.tradeDate?.let(::date), transactionDate = date(a.transactionDate) ?: LocalDate.now(),
        settlementDate = a.settlementDate?.let(::date), action = a.action, symbol = a.symbol, description = a.description,
        currency = a.currency, quantity = a.quantity.stripTrailingZeros().toPlainString(),
        netAmountCents = Money.toCents(a.netAmount), grossAmountCents = Money.toCents(a.grossAmount), type = a.type,
    )

    private fun mirror(accountId: Long, e: QtActivityEntity, snap: CategorizerSnapshot): TransactionEntity? {
        val key = when (e.type.lowercase()) {
            "deposits", "withdrawals", "transfers" -> "investments_transfer"
            "dividends", "interest" -> "investment_income"
            "fees and rebates" -> "fees"
            else -> return null // trades, FX conversions and the like stay inside the account
        }
        if (e.netAmountCents == 0L) return null
        val label = when (key) {
            "investments_transfer" -> if (e.netAmountCents > 0) "Questrade deposit" else "Questrade withdrawal"
            "investment_income" -> if (e.symbol.isNotBlank()) "Dividend ${e.symbol}" else e.type
            else -> "Questrade fees"
        }
        return TransactionEntity(
            accountId = accountId, date = e.transactionDate, amountCents = e.netAmountCents, currency = e.currency,
            rawDescription = "${e.type} ${e.description}".trim(), merchant = label, categoryId = snap.idOf(key),
            source = TxSource.QUESTRADE.name, fingerprint = "qt:${e.activityKey}",
        )
    }

    companion object {
        fun mapType(t: String): AccountType = when (t.uppercase()) {
            "TFSA" -> AccountType.TFSA
            "RRSP", "SRRSP", "LIRA", "LRSP", "RRIF" -> AccountType.RRSP
            "FHSA" -> AccountType.FHSA
            "MARGIN" -> AccountType.MARGIN
            "CASH" -> AccountType.CASH
            else -> AccountType.OTHER
        }

        /** Positions don't carry a currency; Canadian listings end in .TO / .V / .CN / .NE. */
        fun currencyOf(symbol: String): String =
            if (Regex("\\.(TO|V|VN|CN|NE)$", RegexOption.IGNORE_CASE).containsMatchIn(symbol)) "CAD" else "USD"

        private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s.take(10)) }.getOrNull()

        /** USD→CAD rate implied by Questrade's own balances (combined CAD vs per-currency), or null. */
        fun impliedUsdRate(perCurrency: List<QtBalanceEntity>, combinedCad: QtBalanceEntity?): BigDecimal? {
            val cad = perCurrency.firstOrNull { it.currency == "CAD" } ?: return null
            val usd = perCurrency.firstOrNull { it.currency == "USD" }?.takeIf { it.totalEquityCents != 0L } ?: return null
            val combined = combinedCad ?: return null
            return BigDecimal.valueOf(combined.totalEquityCents - cad.totalEquityCents)
                .divide(BigDecimal.valueOf(usd.totalEquityCents), 6, java.math.RoundingMode.HALF_UP)
        }
    }
}
