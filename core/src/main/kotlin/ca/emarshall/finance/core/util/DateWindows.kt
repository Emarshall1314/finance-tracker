package ca.emarshall.finance.core.util

import java.time.ZonedDateTime

data class Window(val start: ZonedDateTime, val end: ZonedDateTime)

object DateWindows {
    /**
     * Splits [start, end) into consecutive windows of at most [days] days. Questrade's activities endpoint
     * accepts at most 31 days per request, so callers use 30.
     */
    fun chunk(start: ZonedDateTime, end: ZonedDateTime, days: Long = 30): List<Window> {
        require(days > 0)
        val out = ArrayList<Window>()
        var s = start
        while (s.isBefore(end)) {
            val e = s.plusDays(days).let { if (it.isAfter(end)) end else it }
            out.add(Window(s, e))
            s = e
        }
        return out
    }
}
