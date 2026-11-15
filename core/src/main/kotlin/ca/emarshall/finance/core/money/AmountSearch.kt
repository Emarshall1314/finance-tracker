package ca.emarshall.finance.core.money

/** Turns what someone typed in a search box into a range of cents, when it looks like an amount. */
object AmountSearch {
    private val AMOUNT = Regex("^\\$?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{0,2}))?$")

    /**
     * "50" covers $50.00-$50.99, "50.2" covers $50.20-$50.29, "50.25" is exactly $50.25. Thousands commas
     * and a dollar sign are fine. Returns null for anything that isn't a plain positive amount.
     */
    fun range(query: String): Pair<Long, Long>? {
        val m = AMOUNT.find(query.trim()) ?: return null
        val dollars = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        if (dollars > 1_000_000_000L) return null
        val decimals = m.groupValues[2]
        val base = dollars * 100
        return when (decimals.length) {
            0 -> base to base + 99
            1 -> (base + decimals.toLong() * 10).let { it to it + 9 }
            else -> (base + decimals.toLong()).let { it to it }
        }
    }
}
