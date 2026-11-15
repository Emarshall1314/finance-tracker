package ca.emarshall.finance.core.statements

import java.time.LocalDate
import java.time.YearMonth

/**
 * How far one account's imported statements reach. [drivesMonths] marks the accounts that unlock a new month
 * on the dashboard (bank accounts, where income lands); the others (cards) only make it final.
 */
data class AccountCoverage(
    val accountId: Long,
    val name: String,
    val coveredThrough: LocalDate?,
    val drivesMonths: Boolean = true,
    val closingDay: Int? = null,
)

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

    /**
     * The last month the dashboard shows: the bank accounts' statements cover it, even if card statements
     * for it haven't arrived yet (see [waitingOn]). Falls back to [latestComplete] with no bank statements.
     */
    fun latestUnlocked(accounts: List<AccountCoverage>, toleranceDays: Long = 3): YearMonth? {
        val drivers = accounts.filter { it.drivesMonths && it.coveredThrough != null }
        if (drivers.isEmpty()) return latestComplete(accounts, toleranceDays)
        return drivers.minOf { lastFullMonth(it.coveredThrough!!, toleranceDays) }
    }

    /** When this account's next statement should close: the day after its coverage ends, a cycle later. */
    fun nextClose(a: AccountCoverage): LocalDate? {
        val through = a.coveredThrough ?: return null
        val next = YearMonth.from(through).plusMonths(1)
        val day = a.closingDay ?: through.dayOfMonth
        // 28 and later is treated as "month end" throughout the app (see StatementCoverage.inferClosingDay).
        return if (day >= 28) next.atEndOfMonth() else next.atDay(day)
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
