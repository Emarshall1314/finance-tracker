package ca.emarshall.finance.core.statements

import ca.emarshall.finance.core.reminders.ReminderSchedule
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** A date range some import covers (a statement period, or the span of a CSV/OFX download). */
data class CoveredRange(val start: LocalDate, val end: LocalDate)

enum class StatementStatus { IMPORTED, MISSING }

/** One statement the bank should have issued. */
data class ExpectedStatement(val periodStart: LocalDate, val periodEnd: LocalDate, val status: StatementStatus)

/**
 * Works out which monthly statements are missing for an account.
 *
 * Statements close on the same day each month ([closingDay]; 29-31 means the month's last day, and banks
 * sometimes close a day or two early). A statement period counts as imported when imports cover at least
 * half of its days, which tolerates those small shifts and overlapping downloads. Checking starts at the
 * first imported period (nothing before the user's history is reported) and ends at the latest statement
 * that should already be available ([availableAfterDays] after closing).
 */
object StatementCoverage {

    fun expected(
        covered: List<CoveredRange>,
        closingDay: Int,
        today: LocalDate,
        availableAfterDays: Long = 3,
    ): List<ExpectedStatement> {
        if (covered.isEmpty()) return emptyList()
        val earliest = covered.minOf { it.start }
        val lastAvailable = today.minusDays(availableAfterDays)

        val out = ArrayList<ExpectedStatement>()
        var month = YearMonth.from(earliest).minusMonths(1)
        var prevClose = ReminderSchedule.closingDate(month, closingDay)
        repeat(240) {
            month = month.plusMonths(1)
            val close = ReminderSchedule.closingDate(month, closingDay)
            if (close.isAfter(lastAvailable)) return out
            val start = prevClose.plusDays(1)
            prevClose = close
            if (close.isBefore(earliest)) return@repeat
            val days = ChronoUnit.DAYS.between(start, close) + 1
            val coveredDays = coveredDays(covered, start, close)
            out.add(ExpectedStatement(start, close, if (coveredDays * 2 >= days) StatementStatus.IMPORTED else StatementStatus.MISSING))
        }
        return out
    }

    fun missing(covered: List<CoveredRange>, closingDay: Int, today: LocalDate, availableAfterDays: Long = 3) =
        expected(covered, closingDay, today, availableAfterDays).filter { it.status == StatementStatus.MISSING }

    /**
     * Closing day from the statements already imported: the most common day of month among their period
     * ends, with any day from the 28th on treated as "month end" (31), since month-end statements close on
     * the 28th-31st depending on the month and on weekends.
     */
    fun inferClosingDay(periodEnds: List<LocalDate>): Int? {
        if (periodEnds.isEmpty()) return null
        return periodEnds.map { d -> if (d.dayOfMonth >= 28 || d.dayOfMonth >= d.lengthOfMonth() - 2) 31 else d.dayOfMonth }
            .groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })!!.key
    }

    /** Days in [start, end] covered by the union of [ranges]. */
    private fun coveredDays(ranges: List<CoveredRange>, start: LocalDate, end: LocalDate): Long {
        val clipped = ranges.mapNotNull { r ->
            val s = maxOf(r.start, start)
            val e = minOf(r.end, end)
            if (s.isAfter(e)) null else s to e
        }.sortedBy { it.first }
        var total = 0L
        var curStart: LocalDate? = null
        var curEnd: LocalDate? = null
        for ((s, e) in clipped) {
            if (curEnd == null || s.isAfter(curEnd.plusDays(1))) {
                if (curStart != null) total += ChronoUnit.DAYS.between(curStart, curEnd) + 1
                curStart = s; curEnd = e
            } else if (e.isAfter(curEnd)) {
                curEnd = e
            }
        }
        if (curStart != null) total += ChronoUnit.DAYS.between(curStart, curEnd) + 1
        return total
    }
}
