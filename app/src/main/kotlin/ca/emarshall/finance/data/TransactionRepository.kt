package ca.emarshall.finance.data

import androidx.room.withTransaction
import ca.emarshall.finance.core.analytics.TxFact
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.core.recurring.RecurringDetector
import ca.emarshall.finance.core.recurring.RecurringInput
import ca.emarshall.finance.core.recurring.RecurringSeries
import ca.emarshall.finance.core.transfers.TransferCandidate
import ca.emarshall.finance.core.transfers.TransferMatcher
import ca.emarshall.finance.data.db.AccountDao
import ca.emarshall.finance.data.db.FinanceDatabase
import ca.emarshall.finance.data.db.TransactionDao
import ca.emarshall.finance.data.db.TxRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

fun TxRow.toFact() = TxFact(
    id = id, accountId = accountId, date = date, amountCents = amountCents, currency = currency,
    categoryKey = categoryKey ?: categoryId?.let { "id:$it" } ?: if (amountCents >= 0) "other_income" else "other",
    categoryName = categoryName ?: if (amountCents >= 0) "Other Income" else "Other",
    kind = categoryKind?.let { runCatching { CategoryKind.valueOf(it) }.getOrNull() }
        ?: if (amountCents >= 0) CategoryKind.INCOME else CategoryKind.EXPENSE,
    merchant = merchant,
    isTransfer = transferLinkId != null,
    accountKind = when (runCatching { ca.emarshall.finance.core.model.AccountType.valueOf(accountType) }.getOrNull()) {
        ca.emarshall.finance.core.model.AccountType.CREDIT_CARD, ca.emarshall.finance.core.model.AccountType.LINE_OF_CREDIT ->
            ca.emarshall.finance.core.analytics.AccountKind.CARD
        ca.emarshall.finance.core.model.AccountType.TFSA, ca.emarshall.finance.core.model.AccountType.RRSP,
        ca.emarshall.finance.core.model.AccountType.FHSA, ca.emarshall.finance.core.model.AccountType.MARGIN,
        ca.emarshall.finance.core.model.AccountType.CASH -> ca.emarshall.finance.core.analytics.AccountKind.INVESTMENT
        else -> ca.emarshall.finance.core.analytics.AccountKind.BANK
    },
)

/**
 * Facts for analytics, with moves between a bank or card account and an investment account marked
 * (categorized To/From Investments, or linked to a row in an investment account).
 */
fun List<TxRow>.toFacts(): List<TxFact> {
    val byId = associateBy { it.id }
    return map { r ->
        val linkedToInvestment = r.transferLinkId?.let { byId[it] }?.let { other ->
            runCatching { ca.emarshall.finance.core.model.AccountType.valueOf(other.accountType) }.getOrNull()?.isInvestment == true
        } == true
        r.toFact().copy(toInvestments = r.categoryKey == "investments_transfer" || linkedToInvestment)
    }
}

val TxRow.isTransfer: Boolean get() = transferLinkId != null || categoryKind == CategoryKind.TRANSFER.name

@Singleton
class TransactionRepository @Inject constructor(
    private val db: FinanceDatabase,
    private val dao: TransactionDao,
    private val accountDao: AccountDao,
    private val categories: CategoryRepository,
) {
    val rows: Flow<List<TxRow>> = dao.observeAllRows()
    val facts: Flow<List<TxFact>> = rows.map { list -> list.map { it.toFact() } }
    val latestDates = dao.observeLatestDates()
    val count = dao.observeCount()

    fun search(accountId: Long?, categoryId: Long?, from: LocalDate, to: LocalDate, q: String) =
        dao.search(accountId, categoryId, from, to, q.trim())

    fun row(id: Long) = dao.observeRow(id)

    suspend fun setCategory(id: Long, categoryId: Long) = dao.setCategory(id, categoryId, locked = true)
    suspend fun setNotes(id: Long, notes: String?) = dao.setNotes(id, notes?.trim()?.ifEmpty { null })

    /** Bills & subscriptions: recurring money out, excluding transfers. */
    fun recurring(rows: List<TxRow>, today: LocalDate): List<RecurringSeries> =
        RecurringDetector.detect(rows.filter { !it.isTransfer }.map { RecurringInput(it.id, it.date, it.amountCents, it.merchant, it.currency) }, today)

    /**
     * Pairs transfers between my accounts (card payments, moves to Questrade) and gives both sides a
     * transfer category unless the user picked one. Pairs the user unlinked or confirmed are left alone.
     */
    suspend fun rematchTransfers(): Int = db.withTransaction {
        val all = dao.all()
        val accounts = accountDao.all().associateBy { it.id }
        val candidates = all.filter { it.transferLinkId == null && !it.transferLocked }
            .map { TransferCandidate(it.id, it.accountId, it.date, it.amountCents, it.currency, it.rawDescription) }
        val pairs = TransferMatcher.match(candidates)
        if (pairs.isEmpty()) return@withTransaction 0
        val snap = categories.snapshot()
        val byId = all.associateBy { it.id }
        for (p in pairs) {
            val out = byId.getValue(p.outId)
            val inn = byId.getValue(p.inId)
            val types = listOfNotNull(accounts[out.accountId]?.typeEnum, accounts[inn.accountId]?.typeEnum)
            val key = when {
                types.any { it == AccountType.CREDIT_CARD || it == AccountType.LINE_OF_CREDIT } -> "card_payment"
                types.any { it.isInvestment } -> "investments_transfer"
                else -> "between_accounts"
            }
            val catId = snap.idOf(key)
            dao.update(out.copy(transferLinkId = inn.id, categoryId = if (out.categoryLocked) out.categoryId else catId ?: out.categoryId))
            dao.update(inn.copy(transferLinkId = out.id, categoryId = if (inn.categoryLocked) inn.categoryId else catId ?: inn.categoryId))
        }
        pairs.size
    }

    /** Keep this pair: automatic matching won't touch it again. */
    suspend fun confirmTransfer(id: Long) = db.withTransaction {
        val t = dao.get(id) ?: return@withTransaction
        dao.setTransferLink(t.id, t.transferLinkId, locked = true)
        t.transferLinkId?.let { other -> dao.setTransferLink(other, t.id, locked = true) }
    }

    /** Not a transfer: unlink both sides and stop them being matched again. */
    suspend fun unlinkTransfer(id: Long) = db.withTransaction {
        val t = dao.get(id) ?: return@withTransaction
        dao.setTransferLink(t.id, null, locked = true)
        t.transferLinkId?.let { other -> dao.setTransferLink(other, null, locked = true) }
        categories.recategorizeAll()
    }
}
