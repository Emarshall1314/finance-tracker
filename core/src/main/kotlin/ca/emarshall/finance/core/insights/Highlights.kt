package ca.emarshall.finance.core.insights

import ca.emarshall.finance.core.analytics.Analytics
import ca.emarshall.finance.core.analytics.TxFact
import ca.emarshall.finance.core.analytics.forCashFlow
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.core.recurring.RecurringSeries
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

enum class HighlightKind { SAVED, OVERSPENT, CATEGORY_UP, CATEGORY_DOWN, BIGGEST_PURCHASE, MOST_VISITED, BILLS, PUT_AWAY, QUIET_DAYS, FEES }

/** One fact about a month worth a glance: a big [value] and a short [caption] that explains it. */
data class Highlight(
    val kind: HighlightKind,
    val value: String,
    val caption: String,
    val transactionId: Long? = null,
)

/**
 * A month in a handful of numbers, worked out on the phone from the same figures as the dashboard: how much
 * was kept, what changed most, the biggest purchase, the most visited place, regular bills, money put away,
 * quiet days and fees. Only the ones that apply are returned.
 */
object Highlights {

    fun forMonth(facts: List<TxFact>, month: YearMonth, bills: List<RecurringSeries>): List<Highlight> {
        val a = Analytics(facts)
        val out = ArrayList<Highlight>()
        val t = a.monthTotals(month)
        if (t.incomeCents == 0L && t.spendingCents == 0L) return out
        fun w(c: Long) = Money.formatWhole(c)

        // Kept, against the usual month.
        val earlier = (1L..6L).map { a.monthTotals(month.minusMonths(it)) }.filter { it.incomeCents != 0L || it.spendingCents != 0L }
        val usual = if (earlier.size >= 2) earlier.sumOf { it.netCents } / earlier.size else null
        val versus = usual?.let { u ->
            val d = t.netCents - u
            if (d == 0L) "" else ", ${w(Math.abs(d))} ${if (d > 0) "more" else "less"} than your usual month"
        }.orEmpty()
        if (t.netCents >= 0 && t.incomeCents > 0) {
            out += Highlight(HighlightKind.SAVED, "${t.savingsRatePercent}%", "of your income kept: ${w(t.netCents)}$versus")
        } else if (t.netCents < 0) {
            out += Highlight(HighlightKind.OVERSPENT, w(-t.netCents), "more went out than came in")
        }

        // The category that moved most against its recent average.
        val before = (1L..3L).map { month.minusMonths(it) }.filter { m -> earlier.any { it.month == m } }
        if (before.size >= 2) {
            val now = a.spendingByCategory(month).associateBy { it.categoryKey }
            val past = before.flatMap { a.spendingByCategory(it) }.groupBy { it.categoryKey }
            val mover = (now.keys + past.keys).mapNotNull { key ->
                val current = now[key]?.cents ?: 0L
                val average = (past[key]?.sumOf { it.cents } ?: 0L) / before.size
                val diff = current - average
                val name = now[key]?.categoryName ?: past[key]?.first()?.categoryName ?: return@mapNotNull null
                if (Math.abs(diff) < 50_00 || Math.abs(diff) * 4 < average) null else Triple(name, current, diff)
            }.maxByOrNull { Math.abs(it.third) }
            mover?.let { (name, current, diff) ->
                out += Highlight(
                    if (diff > 0) HighlightKind.CATEGORY_UP else HighlightKind.CATEGORY_DOWN,
                    "${if (diff > 0) "▲" else "▼"} ${w(Math.abs(diff))}",
                    "$name: ${w(current)}, usually about ${w(current - diff)}",
                )
            }
        }

        val spent = facts.mapNotNull { it.forCashFlow() }.filter {
            it.currency == "CAD" && YearMonth.from(it.date) == month && !it.countsAsTransfer && it.kind == CategoryKind.EXPENSE && it.amountCents < 0
        }
        spent.minByOrNull { it.amountCents }?.let { big ->
            val day = "${big.date.month.getDisplayName(TextStyle.SHORT, Locale.CANADA)} ${big.date.dayOfMonth}"
            out += Highlight(HighlightKind.BIGGEST_PURCHASE, w(-big.amountCents), "Biggest purchase: ${big.merchant} on $day", big.id)
        }
        spent.groupBy { it.merchant }.filter { it.value.size >= 4 }.maxByOrNull { it.value.size }?.let { (merchant, visits) ->
            out += Highlight(HighlightKind.MOST_VISITED, "${visits.size}×", "$merchant, your most visited place (${w(-visits.sumOf { it.amountCents })})")
        }

        val aside = a.setAsideIn(month)
        if (aside.totalCents > 0) {
            val parts = listOfNotNull(
                aside.savingsCents.takeIf { it > 0 }?.let { "${w(it)} to savings" },
                aside.investmentsCents.takeIf { it > 0 }?.let { "${w(it)} to investments" },
            )
            out += Highlight(HighlightKind.PUT_AWAY, w(aside.totalCents), "put away: " + parts.joinToString(", "))
        }
        if (bills.isNotEmpty()) {
            out += Highlight(HighlightKind.BILLS, w(bills.sumOf { it.monthlyCostCents }), "a month in ${bills.size} regular bill${if (bills.size == 1) "" else "s"} and subscriptions")
        }
        val quiet = month.lengthOfMonth() - a.dailySpending(month).size
        if (quiet >= 3) out += Highlight(HighlightKind.QUIET_DAYS, quiet.toString(), "days with no spending at all")
        val fees = a.feesAndInterest(month.atDay(1), month.atEndOfMonth())
        if (fees > 0) out += Highlight(HighlightKind.FEES, w(fees), "in bank fees and interest")
        return out
    }
}
