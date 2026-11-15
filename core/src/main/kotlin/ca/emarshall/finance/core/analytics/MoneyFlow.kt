package ca.emarshall.finance.core.analytics

enum class FlowKind {
    INCOME, FROM_SAVINGS, FROM_INVESTMENTS,

    /** Spent or put away more than came in: the difference came out of the chequing balance. */
    FROM_BALANCE,
    CATEGORY, OTHER_SPENDING, TO_SAVINGS, TO_INVESTMENTS,

    /** Came in and wasn't spent or moved: still sitting in chequing. */
    LEFT_OVER,
}

data class FlowPart(val kind: FlowKind, val label: String, val cents: Long, val categoryKey: String? = null)

/** Where a month's money came from and where it went. Both sides always add up to the same total. */
data class MoneyFlow(val sources: List<FlowPart>, val targets: List<FlowPart>) {
    val totalCents get() = sources.sumOf { it.cents }
}

object MoneyFlows {
    /**
     * Income (plus anything taken back out of savings or investments) flows into the biggest spending
     * categories, the rest of spending, savings and investments. What's left over stays in chequing; if more
     * went out than came in, the gap is shown as coming from the balance.
     */
    fun build(income: Long, categories: List<CategoryTotal>, aside: SetAsideTotals, maxCategories: Int = 6): MoneyFlow? {
        val spending = categories.filter { it.cents > 0 }.sortedByDescending { it.cents }
        val sources = ArrayList<FlowPart>()
        val targets = ArrayList<FlowPart>()
        if (income > 0) sources += FlowPart(FlowKind.INCOME, "Income", income)
        if (aside.savingsCents < 0) sources += FlowPart(FlowKind.FROM_SAVINGS, "From savings", -aside.savingsCents)
        if (aside.investmentsCents < 0) sources += FlowPart(FlowKind.FROM_INVESTMENTS, "From investments", -aside.investmentsCents)

        spending.take(maxCategories).forEach { targets += FlowPart(FlowKind.CATEGORY, it.categoryName, it.cents, it.categoryKey) }
        val rest = spending.drop(maxCategories).sumOf { it.cents }
        if (rest > 0) targets += FlowPart(FlowKind.OTHER_SPENDING, "Everything else", rest)
        if (aside.savingsCents > 0) targets += FlowPart(FlowKind.TO_SAVINGS, "Savings", aside.savingsCents)
        if (aside.investmentsCents > 0) targets += FlowPart(FlowKind.TO_INVESTMENTS, "Investments", aside.investmentsCents)

        val inTotal = sources.sumOf { it.cents }
        val outTotal = targets.sumOf { it.cents }
        if (inTotal == 0L && outTotal == 0L) return null
        if (inTotal > outTotal) targets += FlowPart(FlowKind.LEFT_OVER, "Left in chequing", inTotal - outTotal)
        if (outTotal > inTotal) sources += FlowPart(FlowKind.FROM_BALANCE, "From your balance", outTotal - inTotal)
        return MoneyFlow(sources, targets)
    }
}
