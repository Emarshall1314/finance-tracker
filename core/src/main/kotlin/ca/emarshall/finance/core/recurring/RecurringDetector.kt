package ca.emarshall.finance.core.recurring

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class RecurringInput(
    val id: Long,
    val date: LocalDate,
    val amountCents: Long,
    val merchant: String,
    val currency: String = "CAD",
    /**
     * How far this row's account has data (its latest statement). A bill isn't "cancelled" just because
     * the statement that would show its next charge hasn't been imported yet.
     */
    val dataThrough: LocalDate? = null,
)

data class RecurringSeries(
    val merchant: String,
    /** Typical charge, negative (money out). */
    val typicalAmountCents: Long,
    val monthlyCostCents: Long,
    val occurrences: Int,
    val lastDate: LocalDate,
    val nextExpected: LocalDate,
    val transactionIds: List<Long>,
    val currency: String,
)

/**
 * Finds bills and subscriptions: the same merchant, a similar amount (within [tolerance] of the median),
 * roughly monthly (gaps of 25-35 days on average, none under 20 or over 40), at least [minCount] times.
 * Only money out is considered; transfers should be filtered out by the caller.
 */
object RecurringDetector {

    fun detect(
        txs: List<RecurringInput>,
        today: LocalDate,
        minCount: Int = 3,
        tolerance: Double = 0.10,
        staleAfterDays: Long = 45,
    ): List<RecurringSeries> {
        val result = ArrayList<RecurringSeries>()
        val byMerchant = txs.filter { it.amountCents < 0 && it.merchant.isNotBlank() }
            .groupBy { it.merchant.trim().lowercase() to it.currency }
        for ((_, list) in byMerchant) {
            if (list.size < minCount) continue
            for (cluster in clusterByAmount(list.sortedBy { it.date }, tolerance)) {
                // One charge per month: when two land in the same ~3 weeks, keep the first.
                val deduped = ArrayList<RecurringInput>()
                for (t in cluster.sortedBy { it.date }) {
                    if (deduped.isEmpty() || ChronoUnit.DAYS.between(deduped.last().date, t.date) >= 20) deduped.add(t)
                }
                if (deduped.size < minCount) continue
                val gaps = deduped.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
                val avg = gaps.average()
                if (avg < 25 || avg > 35 || gaps.any { it > 40 }) continue
                val last = deduped.last().date
                // Judged against the data we have, not the calendar: statements arrive up to a month late.
                val horizon = deduped.last().dataThrough?.let { minOf(it, today) } ?: today
                if (ChronoUnit.DAYS.between(last, horizon) > staleAfterDays) continue // cancelled
                // The next charge still to come (charges since the last statement are assumed to have happened).
                var months = 1L
                while (last.plusMonths(months).isBefore(today)) months++
                val next = last.plusMonths(months)
                val typical = median(deduped.map { it.amountCents })
                result.add(
                    RecurringSeries(
                        merchant = deduped.last().merchant,
                        typicalAmountCents = typical,
                        monthlyCostCents = -typical,
                        occurrences = deduped.size,
                        lastDate = last,
                        nextExpected = next,
                        transactionIds = deduped.map { it.id },
                        currency = deduped.last().currency,
                    ),
                )
            }
        }
        return result.sortedBy { it.nextExpected }
    }

    /** Greedy clustering on amount: each cluster stays within [tolerance] of its median. */
    private fun clusterByAmount(list: List<RecurringInput>, tolerance: Double): List<List<RecurringInput>> {
        val clusters = ArrayList<MutableList<RecurringInput>>()
        for (t in list.sortedBy { it.amountCents }) {
            val c = clusters.lastOrNull()
            if (c != null && withinTolerance(median(c.map { it.amountCents }), t.amountCents, tolerance)) c.add(t)
            else clusters.add(mutableListOf(t))
        }
        return clusters
    }

    private fun withinTolerance(ref: Long, v: Long, tolerance: Double): Boolean {
        val r = Math.abs(ref)
        // Integer arithmetic on cents; the tolerance is a ratio, not money.
        return Math.abs(Math.abs(v) - r) * 1000 <= r * Math.round(tolerance * 1000)
    }

    private fun median(v: List<Long>): Long {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
