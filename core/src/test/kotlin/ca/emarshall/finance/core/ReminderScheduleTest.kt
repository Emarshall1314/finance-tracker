package ca.emarshall.finance.core

import ca.emarshall.finance.core.reminders.ReminderRule
import ca.emarshall.finance.core.reminders.ReminderSchedule
import ca.emarshall.finance.core.util.DateWindows
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderScheduleTest {
    private val van = ZoneId.of("America/Vancouver")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, van)

    @Test fun thirtyFirstInShortMonths() {
        assertEquals(LocalDate.of(2026, 4, 30), ReminderSchedule.closingDate(YearMonth.of(2026, 4), 31))
        assertEquals(LocalDate.of(2026, 2, 28), ReminderSchedule.closingDate(YearMonth.of(2026, 2), 31))
        assertEquals(LocalDate.of(2026, 2, 28), ReminderSchedule.closingDate(YearMonth.of(2026, 2), 29))
        assertEquals(LocalDate.of(2026, 3, 31), ReminderSchedule.closingDate(YearMonth.of(2026, 3), 31))
        assertEquals(LocalDate.of(2026, 3, 31), ReminderSchedule.next(ReminderRule(31, 2), at(2026, 4, 1), van).closingDate)
        val next = ReminderSchedule.next(ReminderRule(31, 2), at(2026, 4, 3), van)
        assertEquals(LocalDate.of(2026, 4, 30), next.closingDate)
        assertEquals(at(2026, 5, 2, 9), next.fireAt)
    }

    @Test fun leapYearFeb29In2028() {
        assertEquals(LocalDate.of(2028, 2, 29), ReminderSchedule.closingDate(YearMonth.of(2028, 2), 29))
        assertEquals(LocalDate.of(2028, 2, 29), ReminderSchedule.closingDate(YearMonth.of(2028, 2), 31))
        val next = ReminderSchedule.next(ReminderRule(29, 2), at(2028, 2, 15), van)
        assertEquals(LocalDate.of(2028, 2, 29), next.closingDate)
        assertEquals(at(2028, 3, 2, 9), next.fireAt) // offset crosses into March
        // 2027 is not a leap year.
        assertEquals(LocalDate.of(2027, 2, 28), ReminderSchedule.next(ReminderRule(29, 0), at(2027, 2, 1), van).closingDate)
    }

    @Test fun offsetsCrossIntoTheNextMonthAndYear() {
        val next = ReminderSchedule.next(ReminderRule(28, 5), at(2026, 12, 29), van)
        assertEquals(LocalDate.of(2026, 12, 28), next.closingDate)
        assertEquals(at(2027, 1, 2, 9), next.fireAt)
    }

    @Test fun largeOffsetStillFindsLastMonthsStatement() {
        // Closed Mar 25, remind 10 days later (Apr 4). On Apr 1 that's still upcoming.
        val next = ReminderSchedule.next(ReminderRule(25, 10), at(2026, 4, 1), van)
        assertEquals(LocalDate.of(2026, 3, 25), next.closingDate)
    }

    @Test fun dstSpringForwardKeepsLocalTime() {
        // DST starts 2026-03-08 at 02:00 in Vancouver. A 9:00 reminder stays at 9:00 local (UTC-7 now).
        val next = ReminderSchedule.next(ReminderRule(6, 2), at(2026, 3, 1), van)
        assertEquals(LocalDateTime.of(2026, 3, 8, 9, 0), next.fireAt.toLocalDateTime())
        assertEquals(-7 * 3600, next.fireAt.offset.totalSeconds)
    }

    @Test fun dstGapTimeMovesForward() {
        val rule = ReminderRule(6, 2, LocalTime.of(2, 30)) // 02:30 on Mar 8 doesn't exist
        val next = ReminderSchedule.next(rule, at(2026, 3, 1), van)
        assertEquals(LocalDateTime.of(2026, 3, 8, 3, 30), next.fireAt.toLocalDateTime())
    }

    @Test fun dstFallBackUsesEarlierOffset() {
        // DST ends 2026-11-01 at 02:00; 01:30 happens twice.
        val rule = ReminderRule(30, 2, LocalTime.of(1, 30))
        val next = ReminderSchedule.next(rule, at(2026, 10, 20), van)
        assertEquals(LocalDateTime.of(2026, 11, 1, 1, 30), next.fireAt.toLocalDateTime())
        assertEquals(-7 * 3600, next.fireAt.offset.totalSeconds)
    }

    @Test fun firesStrictlyAfterNow() {
        val rule = ReminderRule(15, 2)
        val exactly = at(2026, 6, 17, 9)
        assertEquals(LocalDate.of(2026, 6, 15), ReminderSchedule.next(rule, exactly.minusMinutes(1), van).closingDate)
        assertEquals(LocalDate.of(2026, 7, 15), ReminderSchedule.next(rule, exactly, van).closingDate)
    }

    @Test fun skipsStatementsAlreadyImported() {
        val rule = ReminderRule(15, 2)
        val next = ReminderSchedule.next(rule, at(2026, 6, 16), van, importedThrough = LocalDate.of(2026, 6, 15))
        assertEquals(LocalDate.of(2026, 7, 15), next.closingDate)
    }

    @Test fun monthlyGeneralReminder() {
        assertEquals(at(2026, 7, 3, 9), ReminderSchedule.nextMonthly(3, LocalTime.of(9, 0), at(2026, 6, 3, 10), van))
    }

    @Test fun questradeWindowsAreAtMost30Days() {
        val start = at(2025, 11, 14, 0)
        val end = at(2026, 11, 14, 0)
        val windows = DateWindows.chunk(start, end, 30)
        assertEquals(start, windows.first().start)
        assertEquals(end, windows.last().end)
        windows.zipWithNext { a, b -> assertEquals(a.end, b.start) }
        windows.forEach { assert(Duration.between(it.start, it.end).toDays() <= 30) }
        assertEquals(13, windows.size)
    }
}
