package ca.emarshall.finance.core.statements

import java.time.LocalDate
import java.time.YearMonth

/** How far one account's imported statements reach. */
data class AccountCoverage(val accountId: Long, val name: String, val coveredThrough: LocalDate?)

/**
 * Statements arrive at different times (a card closes on the 5th, another on the 15th, chequing at month
 * end), so a month's numbers are only final once every account's statements cover the whole month. The
 * dashboard waits for that instead of showing half a month.
 */
object MonthCompleteness {

    /** The last month every account with statements fully covers, or null if none yet. */
    fun latestComplete(accounts: List<AccountCoverage>, toleranceDays: Long = 3): YearMonth? {
        val withData = accounts.mapNotNull { it.coveredThrough }
        if (withData.isEmpty()) return null
        return withData.minOf { lastFullMonth(it, toleranceDays) }
    }

    /** Month fully covered by statements reaching [through]: month-end statements may close up to [toleranceDays] early. */
    fun lastFullMonth(through: LocalDate, toleranceDays: Long = 3): YearMonth {
        val m = YearMonth.from(through)
        return if (!through.plusDays(toleranceDays).isBefore(m.atEndOfMonth())) m else m.minusMonths(1)
    }

    /** Accounts whose statements don't yet cover [month]. */
    fun waitingOn(accounts: List<AccountCoverage>, month: YearMonth, toleranceDays: Long = 3): List<AccountCoverage> =
        accounts.filter { a -> a.coveredThrough != null && lastFullMonth(a.coveredThrough, toleranceDays) < month }
}
