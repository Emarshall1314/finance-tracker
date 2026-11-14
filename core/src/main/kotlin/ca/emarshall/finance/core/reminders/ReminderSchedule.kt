package ca.emarshall.finance.core.reminders

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/** "Remind me [offsetDays] after my statement closes on day [closingDay], at [time]." */
data class ReminderRule(
    val closingDay: Int,
    val offsetDays: Int = 2,
    val time: LocalTime = LocalTime.of(9, 0),
) {
    init {
        require(closingDay in 1..31) { "closing day must be 1-31" }
        require(offsetDays in 0..60) { "offset must be 0-60 days" }
    }
}

data class ScheduledReminder(
    /** The statement closing date this reminder is about. */
    val closingDate: LocalDate,
    val fireAt: ZonedDateTime,
)

object ReminderSchedule {

    /** Closing date in a month: day 29-31 becomes the month's last day in shorter months. */
    fun closingDate(month: YearMonth, closingDay: Int): LocalDate = month.atDay(minOf(closingDay, month.lengthOfMonth()))

    /**
     * Fire time for one statement: closing date + offset, at the local time of day. ZonedDateTime.of
     * moves a time inside a DST gap forward and picks the earlier offset in an overlap.
     */
    fun fireTime(rule: ReminderRule, month: YearMonth, zone: ZoneId): ScheduledReminder {
        val closing = closingDate(month, rule.closingDay)
        return ScheduledReminder(closing, ZonedDateTime.of(closing.plusDays(rule.offsetDays.toLong()), rule.time, zone))
    }

    /**
     * Next reminder strictly after [now]. Statements already covered by an import (closing date on or before
     * [importedThrough]) are skipped.
     */
    fun next(rule: ReminderRule, now: ZonedDateTime, zone: ZoneId, importedThrough: LocalDate? = null): ScheduledReminder {
        // Start a few months back: a large offset can push last month's reminder past today.
        var month = YearMonth.from(now.withZoneSameInstant(zone)).minusMonths(3)
        repeat(30) {
            val r = fireTime(rule, month, zone)
            val covered = importedThrough != null && !r.closingDate.isAfter(importedThrough)
            if (r.fireAt.isAfter(now) && !covered) return r
            month = month.plusMonths(1)
        }
        error("no reminder found within 30 months")
    }

    /** A general monthly "import your statements" reminder on [dayOfMonth] (29-31 clamp like closing days). */
    fun nextMonthly(dayOfMonth: Int, time: LocalTime, now: ZonedDateTime, zone: ZoneId): ZonedDateTime =
        next(ReminderRule(dayOfMonth, 0, time), now, zone).fireAt
}
