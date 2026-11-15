package ca.emarshall.finance.core.analytics

import ca.emarshall.finance.core.model.CategoryKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** A transaction as analytics sees it. */
data class TxFact(
    val id: Long,
    val accountId: Long,
    val date: LocalDate,
    val amountCents: Long,
    val currency: String,
    val categoryKey: String,
    val categoryName: String,
    val kind: CategoryKind,
    val merchant: String,
    /** Linked to another of my accounts. Transfers never count as income or spending. */
    val isTransfer: Boolean,
    /** What kind of account it's in; decides whether money in counts as income. */
    val accountKind: AccountKind = AccountKind.BANK,
    /** Money moved between this (bank or card) account and an investment account, e.g. a Questrade deposit. */
    val toInvestments: Boolean = false,
) {
    val countsAsTransfer get() = isTransfer || kind == CategoryKind.TRANSFER
}

/**
 * Income only ever comes from bank accounts. On a card, money in that isn't a payment is a return or
 * refund: it reduces spending. Money moving inside an investment account (dividends, interest, fees) stays
 * there and is neither income nor spending.
 */
enum class AccountKind { BANK, CARD, INVESTMENT }

/** Applies the account rules above, so every total treats card refunds and investment activity the same way. */
fun TxFact.forCashFlow(): TxFact? = when {
    accountKind == AccountKind.INVESTMENT -> null
    accountKind == AccountKind.CARD && kind == CategoryKind.INCOME ->
        copy(kind = CategoryKind.EXPENSE, categoryKey = "refunds", categoryName = "Returns & refunds")
    else -> this
}

data class MonthTotals(val month: YearMonth, val incomeCents: Long, val spendingCents: Long) {
    val netCents get() = incomeCents - spendingCents

    /** Savings rate in whole percent, or null with no income. */
    val savingsRatePercent: Int?
        get() = if (incomeCents <= 0) null else Math.round(netCents * 100.0 / incomeCents).toInt()
}

data class CategoryTotal(val categoryKey: String, val categoryName: String, val cents: Long)
data class MerchantTotal(val merchant: String, val cents: Long, val count: Int)

/**
 * Totals are in the home currency only (CAD). Foreign-currency rows are reported separately by
 * [foreignCurrencyTotals] rather than converted at a guessed rate.
 */
class Analytics(rawFacts: List<TxFact>, private val homeCurrency: String = "CAD") {

    private val facts = rawFacts.mapNotNull { it.forCashFlow() }
    private val home = facts.filter { it.currency == homeCurrency }
    private val all = facts

    private fun inMonth(m: YearMonth) = home.filter { YearMonth.from(it.date) == m }
    private fun inRange(from: LocalDate, to: LocalDate) = home.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    fun monthTotals(m: YearMonth): MonthTotals = totals(m, inMonth(m))

    fun monthSeries(endMonth: YearMonth, months: Int): List<MonthTotals> =
        (months - 1 downTo 0).map { endMonth.minusMonths(it.toLong()) }.map { monthTotals(it) }

    fun spendingByCategory(from: LocalDate, to: LocalDate): List<CategoryTotal> =
        inRange(from, to).filter { !it.countsAsTransfer && it.kind == CategoryKind.EXPENSE }
            .groupBy { it.categoryKey }
            .map { (k, l) -> CategoryTotal(k, l.first().categoryName, -l.sumOf { it.amountCents }) }
            .filter { it.cents > 0L }
            .sortedByDescending { it.cents }

    fun spendingByCategory(m: YearMonth) = spendingByCategory(m.atDay(1), m.atEndOfMonth())

    fun topMerchants(from: LocalDate, to: LocalDate, limit: Int = 10): List<MerchantTotal> =
        inRange(from, to).filter { !it.countsAsTransfer && it.kind == CategoryKind.EXPENSE }
            .groupBy { it.merchant }
            .map { (k, l) -> MerchantTotal(k, -l.sumOf { it.amountCents }, l.size) }
            .filter { it.cents > 0 }
            .sortedByDescending { it.cents }
            .take(limit)

    fun feesAndInterest(from: LocalDate, to: LocalDate): Long =
        -inRange(from, to).filter { it.categoryKey == "fees" }.sumOf { it.amountCents }

    fun feeTransactions(from: LocalDate, to: LocalDate): List<TxFact> =
        inRange(from, to).filter { it.categoryKey == "fees" }.sortedBy { it.date }

    fun foreignCurrencyTotals(from: LocalDate, to: LocalDate): Map<String, MonthTotalsLite> =
        all.filter { it.currency != homeCurrency && !it.date.isBefore(from) && !it.date.isAfter(to) && !it.countsAsTransfer }
            .groupBy { it.currency }
            .mapValues { (_, l) ->
                MonthTotalsLite(
                    incomeCents = l.filter { it.kind == CategoryKind.INCOME }.sumOf { it.amountCents },
                    spendingCents = -l.filter { it.kind == CategoryKind.EXPENSE }.sumOf { it.amountCents },
                )
            }

    /**
     * Net money moved from bank accounts into investment accounts in [m]: deposits minus withdrawals, seen
     * from the bank side (so it works from statements alone, without a Questrade sync).
     */
    fun investedIn(m: YearMonth): Long = -inMonth(m).filter { it.toInvestments }.sumOf { it.amountCents }

    /** Income and spending over every fact given, with the same rules as the monthly totals. */
    fun overall(): MonthTotalsLite {
        val counted = home.filterNot { it.countsAsTransfer }
        return MonthTotalsLite(
            incomeCents = counted.filter { it.kind == CategoryKind.INCOME }.sumOf { it.amountCents },
            spendingCents = -counted.filter { it.kind == CategoryKind.EXPENSE }.sumOf { it.amountCents },
        )
    }

    private fun totals(m: YearMonth, rows: List<TxFact>): MonthTotals {
        val counted = rows.filterNot { it.countsAsTransfer }
        return MonthTotals(
            month = m,
            incomeCents = counted.filter { it.kind == CategoryKind.INCOME }.sumOf { it.amountCents },
            spendingCents = -counted.filter { it.kind == CategoryKind.EXPENSE }.sumOf { it.amountCents },
        )
    }
}

data class MonthTotalsLite(val incomeCents: Long, val spendingCents: Long)

/** Latest known balance for an account. Positive = asset, negative = owed. */
data class AccountBalance(
    val accountId: Long,
    val nickname: String,
    val isLiability: Boolean,
    val balanceCents: Long,
    val asOf: LocalDate,
    val currency: String = "CAD",
)

data class BalancePoint(val accountId: Long, val date: LocalDate, val balanceCents: Long, val currency: String = "CAD")

object NetWorth {
    /** Bank balances + investment equity - card balances. Card balances are already negative when owed. */
    fun total(balances: List<AccountBalance>, homeCurrency: String = "CAD"): Long =
        balances.filter { it.currency == homeCurrency }.sumOf { it.balanceCents }

    /** Net worth at each month end: each account's latest balance on or before that day. */
    fun trend(points: List<BalancePoint>, endMonth: YearMonth, months: Int, homeCurrency: String = "CAD"): List<Pair<YearMonth, Long>> {
        val byAccount = points.filter { it.currency == homeCurrency }.groupBy { it.accountId }
            .mapValues { (_, l) -> l.sortedBy { it.date } }
        return (months - 1 downTo 0).map { endMonth.minusMonths(it.toLong()) }.map { m ->
            val end = m.atEndOfMonth()
            m to byAccount.values.sumOf { l -> l.lastOrNull { !it.date.isAfter(end) }?.balanceCents ?: 0L }
        }
    }
}

data class Freshness(val accountId: Long, val dataThrough: LocalDate?, val isStale: Boolean)

object DataFreshness {
    const val STALE_AFTER_DAYS = 35L

    fun of(accountId: Long, dataThrough: LocalDate?, today: LocalDate): Freshness =
        Freshness(accountId, dataThrough, dataThrough == null || ChronoUnit.DAYS.between(dataThrough, today) > STALE_AFTER_DAYS)
}
