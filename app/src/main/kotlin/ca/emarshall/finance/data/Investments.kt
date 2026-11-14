package ca.emarshall.finance.data

import ca.emarshall.finance.core.summary.HoldingSummary
import ca.emarshall.finance.core.summary.InvestmentAccountSummary
import ca.emarshall.finance.core.summary.InvestmentSummary
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.db.QtActivityEntity
import ca.emarshall.finance.data.db.QtBalanceEntity
import ca.emarshall.finance.data.db.QtPositionEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class Holding(
    val symbol: String,
    val quantity: BigDecimal,
    val currency: String,
    val marketValueCents: Long,
    /** In CAD, using the USD rate implied by Questrade's own balances when the position is in USD. */
    val marketValueCadCents: Long,
    val openPnlCents: Long,
)

data class InvestmentAccountView(val account: AccountEntity, val equityCadCents: Long, val cashCadCents: Long, val depositsThisYearCents: Long)

data class InvestmentsView(
    val totalEquityCadCents: Long,
    val accounts: List<InvestmentAccountView>,
    val holdings: List<Holding>,
    val recent: List<QtActivityEntity>,
    val dividends12mCents: Long,
    val dividendsYtdCents: Long,
    val contributionsByType: Map<String, Long>,
    val lastSyncMillis: Long,
) {
    val holdingsTotalCadCents get() = holdings.sumOf { it.marketValueCadCents }

    fun toSummary() = InvestmentSummary(
        totalEquityCents = totalEquityCadCents,
        accounts = accounts.map { InvestmentAccountSummary(it.account.nickname, it.account.typeEnum.displayName, it.equityCadCents, it.cashCadCents) },
        holdings = holdings.map {
            HoldingSummary(it.symbol + if (it.currency != "CAD") " (${it.currency})" else "", it.quantity.toPlainString(), it.marketValueCadCents, it.openPnlCents)
        },
        dividendsLast12MonthsCents = dividends12mCents,
        dividendsThisYearCents = dividendsYtdCents,
        contributionsThisYearByType = contributionsByType,
    )
}

object InvestmentsCalculator {
    fun build(
        accounts: List<AccountEntity>,
        balances: List<QtBalanceEntity>,
        positions: List<QtPositionEntity>,
        activities: List<QtActivityEntity>,
        today: LocalDate,
        lastSyncMillis: Long,
    ): InvestmentsView? {
        val qtAccounts = accounts.filter { it.questradeNumber != null }
        if (qtAccounts.isEmpty()) return null
        val byAccount = balances.groupBy { it.accountId }
        val yearStart = today.withDayOfYear(1)
        val views = qtAccounts.map { a ->
            val b = byAccount[a.id].orEmpty()
            val combinedCad = b.firstOrNull { it.combined && it.currency == "CAD" }
            InvestmentAccountView(
                account = a,
                equityCadCents = combinedCad?.totalEquityCents ?: 0,
                cashCadCents = combinedCad?.cashCents ?: 0,
                depositsThisYearCents = activities.filter { it.accountId == a.id && it.type.equals("Deposits", true) && !it.transactionDate.isBefore(yearStart) && it.netAmountCents > 0 }
                    .sumOf { it.netAmountCents },
            )
        }
        val rates = qtAccounts.associate { a ->
            val b = byAccount[a.id].orEmpty()
            a.id to QuestradeRepository.impliedUsdRate(b.filter { !it.combined }, b.firstOrNull { it.combined && it.currency == "CAD" })
        }
        val holdings = positions.groupBy { it.symbol }.map { (symbol, ps) ->
            val currency = ps.first().currency
            Holding(
                symbol = symbol,
                quantity = ps.fold(BigDecimal.ZERO) { acc, p -> acc + BigDecimal(p.quantity) },
                currency = currency,
                marketValueCents = ps.sumOf { it.marketValueCents },
                marketValueCadCents = ps.sumOf { p ->
                    if (p.currency == "CAD") p.marketValueCents
                    else rates[p.accountId]?.let { r -> BigDecimal.valueOf(p.marketValueCents).multiply(r).setScale(0, RoundingMode.HALF_UP).toLong() } ?: p.marketValueCents
                },
                openPnlCents = ps.sumOf { it.openPnlCents ?: 0 },
            )
        }.sortedByDescending { it.marketValueCadCents }

        val dividends = activities.filter { it.type.equals("Dividends", true) && it.currency == "CAD" }
        return InvestmentsView(
            totalEquityCadCents = views.sumOf { it.equityCadCents },
            accounts = views,
            holdings = holdings,
            recent = activities.sortedByDescending { it.transactionDate }.take(30),
            dividends12mCents = dividends.filter { it.transactionDate.isAfter(today.minusMonths(12)) }.sumOf { it.netAmountCents },
            dividendsYtdCents = dividends.filter { !it.transactionDate.isBefore(yearStart) }.sumOf { it.netAmountCents },
            contributionsByType = views.groupBy { it.account.typeEnum.displayName }.mapValues { (_, v) -> v.sumOf { it.depositsThisYearCents } }
                .filterValues { it != 0L },
            lastSyncMillis = lastSyncMillis,
        )
    }
}
