package ca.emarshall.finance.core.categorize

import java.time.LocalDate

/**
 * "I pay $1,800 for rent every month": any payment out of that amount (within [toleranceCents]) gets
 * [categoryKey]. With [dayOfMonth] set, only payments within [dayWindow] days of that day count, wrapping
 * across month ends (rent due on the 1st and paid on Dec 30 still matches).
 */
data class AmountRule(
    val id: Long = 0,
    val name: String,
    /** Positive: the size of the payment. */
    val amountCents: Long,
    val categoryKey: String,
    val toleranceCents: Long = 0,
    val dayOfMonth: Int? = null,
    val dayWindow: Int = DEFAULT_DAY_WINDOW,
    /** Money coming in (a roommate's share of the rent) instead of going out. */
    val incoming: Boolean = false,
) {
    fun matches(amountCents: Long, date: LocalDate?): Boolean {
        if (incoming) { if (amountCents <= 0) return false } else if (amountCents >= 0) return false
        if (Math.abs(Math.abs(amountCents) - this.amountCents) > toleranceCents) return false
        val day = dayOfMonth ?: return true
        if (date == null) return true
        val len = date.lengthOfMonth()
        val diff = Math.abs(date.dayOfMonth - minOf(day, len))
        return minOf(diff, len - diff) <= dayWindow
    }

    /**
     * The date a payment made on [date] counts on. Rent due on the 1st but paid on Oct 31 is November's
     * rent: without this, October shows two rents and November none. Only payments that land in the wrong
     * month move, and they move to the due day; everything else keeps its own date.
     */
    fun countsOn(date: LocalDate): LocalDate {
        val day = dayOfMonth ?: return date
        val month = java.time.YearMonth.from(date)
        val due = listOf(month.minusMonths(1), month, month.plusMonths(1)).map { it.atDay(minOf(day, it.lengthOfMonth())) }
            .minByOrNull { Math.abs(java.time.temporal.ChronoUnit.DAYS.between(it, date)) }!!
        return if (java.time.YearMonth.from(due) == month) date else due
    }

    companion object {
        const val DEFAULT_DAY_WINDOW = 6
    }
}

object AmountRules {
    /**
     * The rule for this payment, if any. The closest amount wins. Payments the text rules already call a
     * transfer (a card payment, a Questrade deposit) are left alone even if the amount happens to match.
     */
    fun find(rules: List<AmountRule>, amountCents: Long, date: LocalDate?, textCategoryIsTransfer: Boolean): AmountRule? {
        if (textCategoryIsTransfer) return null
        return rules.filter { it.matches(amountCents, date) }
            .minWithOrNull(compareBy<AmountRule> { Math.abs(Math.abs(amountCents) - it.amountCents) }.thenBy { it.toleranceCents }.thenBy { it.id })
    }
}
