package ca.emarshall.finance.core.insights

import ca.emarshall.finance.core.analytics.Analytics
import ca.emarshall.finance.core.analytics.TxFact
import java.time.YearMonth

/** How one category has been moving: the last [recentMonths] months against the months before them. */
data class CategoryTrend(
    val categoryKey: String,
    val categoryName: String,
    /** Spending per month, oldest first, ending at the latest month. */
    val monthly: List<Long>,
    /** Average of the latest months. */
    val recentCents: Long,
    /** Average of the earlier months that have any data at all, or null when there aren't enough to compare. */
    val earlierCents: Long?,
) {
    /** Recent minus earlier, per month. Positive = spending more lately. */
    val changeCents: Long? get() = earlierCents?.let { recentCents - it }
}

object Trends {
    /**
     * Each spending category over the [months] months ending at [end]. The comparison only uses months in
     * which anything at all was recorded, so the months before the first statement don't drag averages down.
     */
    fun byCategory(facts: List<TxFact>, end: YearMonth, months: Int = 12, recentMonths: Int = 3): List<CategoryTrend> {
        val a = Analytics(facts)
        val span = (months - 1 downTo 0).map { end.minusMonths(it.toLong()) }
        val perMonth = span.map { m -> a.spendingByCategory(m).associateBy { it.categoryKey } }
        val active = span.indices.filter { i -> a.monthTotals(span[i]).let { it.incomeCents != 0L || it.spendingCents != 0L } }
        val recent = active.takeLast(recentMonths)
        val earlier = active.dropLast(recentMonths)
        val names = perMonth.flatMap { it.values }.associate { it.categoryKey to it.categoryName }
        return names.map { (key, name) ->
            val series = perMonth.map { it[key]?.cents ?: 0L }
            CategoryTrend(
                key, name, series,
                recentCents = if (recent.isEmpty()) 0 else recent.sumOf { series[it] } / recent.size,
                earlierCents = if (earlier.size < 2) null else earlier.sumOf { series[it] } / earlier.size,
            )
        }.sortedWith(compareByDescending<CategoryTrend> { Math.abs(it.changeCents ?: 0) }.thenByDescending { it.recentCents })
    }
}
