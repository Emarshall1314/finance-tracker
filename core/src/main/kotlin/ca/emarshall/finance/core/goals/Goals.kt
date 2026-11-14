package ca.emarshall.finance.core.goals

import ca.emarshall.finance.core.analytics.Analytics
import ca.emarshall.finance.core.analytics.TxFact
import ca.emarshall.finance.core.money.Money
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class GoalType {
    /** Save [Goal.targetCents] (income − spending) between the start date and the deadline. */
    SAVE_AMOUNT,

    /** Keep a category's spending under [Goal.targetCents] each month. */
    SPEND_LIMIT,

    /** Keep the monthly savings rate at or above [Goal.targetPercent]. */
    SAVINGS_RATE,
}

data class Goal(
    val id: Long,
    val type: GoalType,
    val title: String,
    val targetCents: Long? = null,
    val targetPercent: Int? = null,
    /** Category key for SPEND_LIMIT. */
    val categoryKey: String? = null,
    val start: LocalDate,
    val deadline: LocalDate? = null,
)

enum class GoalStatus { ON_TRACK, AT_RISK, OFF_TRACK, DONE }

data class GoalProgress(
    val goal: Goal,
    val status: GoalStatus,
    /** 0..100+ for display. */
    val percent: Int,
    val headline: String,
    val detail: String,
)

/** Progress for each goal, computed on the phone from the same numbers as the dashboard (CAD, no transfers). */
object GoalTracker {

    fun progress(goal: Goal, facts: List<TxFact>, today: LocalDate): GoalProgress {
        val a = Analytics(facts)
        return when (goal.type) {
            GoalType.SAVE_AMOUNT -> saveAmount(goal, a, today)
            GoalType.SPEND_LIMIT -> spendLimit(goal, a, today)
            GoalType.SAVINGS_RATE -> savingsRate(goal, a, today)
        }
    }

    private fun saveAmount(g: Goal, a: Analytics, today: LocalDate): GoalProgress {
        val target = (g.targetCents ?: 0).coerceAtLeast(1)
        var saved = 0L
        var m = YearMonth.from(g.start)
        while (!m.isAfter(YearMonth.from(today))) {
            saved += a.monthTotals(m).netCents
            m = m.plusMonths(1)
        }
        val pct = (saved * 100 / target).toInt()
        val status = when {
            saved >= target -> GoalStatus.DONE
            g.deadline == null -> if (saved > 0) GoalStatus.ON_TRACK else GoalStatus.AT_RISK
            !today.isBefore(g.deadline) -> GoalStatus.OFF_TRACK
            else -> {
                val total = ChronoUnit.DAYS.between(g.start, g.deadline).coerceAtLeast(1)
                val elapsed = ChronoUnit.DAYS.between(g.start, today).coerceIn(0, total)
                val expected = target * elapsed / total
                when {
                    saved >= expected -> GoalStatus.ON_TRACK
                    saved * 10 >= expected * 8 -> GoalStatus.AT_RISK
                    else -> GoalStatus.OFF_TRACK
                }
            }
        }
        val perMonth = g.deadline?.let { d ->
            val monthsLeft = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(d)).coerceAtLeast(1)
            ((target - saved).coerceAtLeast(0)) / monthsLeft
        }
        return GoalProgress(
            g, status, pct,
            "${Money.formatWhole(saved)} of ${Money.formatWhole(target)} saved",
            if (status == GoalStatus.DONE) "Reached. Nice work."
            else perMonth?.let { "About ${Money.formatWhole(it)} a month to reach it by ${g.deadline}." } ?: "No deadline set.",
        )
    }

    private fun spendLimit(g: Goal, a: Analytics, today: LocalDate): GoalProgress {
        val limit = (g.targetCents ?: 0).coerceAtLeast(1)
        val month = YearMonth.from(today)
        val spent = a.spendingByCategory(month).firstOrNull { it.categoryKey == g.categoryKey }?.cents ?: 0
        val pct = (spent * 100 / limit).toInt()
        // Pace: how much of the month has passed vs how much of the limit is used.
        val monthPct = today.dayOfMonth * 100 / month.lengthOfMonth()
        val status = when {
            spent > limit -> GoalStatus.OFF_TRACK
            pct > monthPct + 15 || pct >= 90 -> GoalStatus.AT_RISK
            else -> GoalStatus.ON_TRACK
        }
        val last = a.spendingByCategory(month.minusMonths(1)).firstOrNull { it.categoryKey == g.categoryKey }?.cents ?: 0
        return GoalProgress(
            g, status, pct,
            "${Money.formatWhole(spent)} of ${Money.formatWhole(limit)} this month",
            if (spent > limit) "Over by ${Money.formatWhole(spent - limit)}. Last month: ${Money.formatWhole(last)}."
            else "${Money.formatWhole(limit - spent)} left with ${month.lengthOfMonth() - today.dayOfMonth} days to go. Last month: ${Money.formatWhole(last)}.",
        )
    }

    private fun savingsRate(g: Goal, a: Analytics, today: LocalDate): GoalProgress {
        val target = g.targetPercent ?: 0
        val lastFull = YearMonth.from(today).minusMonths(1)
        val months = (0L until 3L).map { a.monthTotals(lastFull.minusMonths(it)) }.filter { it.incomeCents > 0 }
        val income = months.sumOf { it.incomeCents }
        val rate = if (income > 0) Math.round(months.sumOf { it.netCents } * 100.0 / income).toInt() else null
        val status = when {
            rate == null -> GoalStatus.AT_RISK
            rate >= target -> GoalStatus.ON_TRACK
            rate >= target - 5 -> GoalStatus.AT_RISK
            else -> GoalStatus.OFF_TRACK
        }
        return GoalProgress(
            g, status, if (target > 0 && rate != null) rate * 100 / target else 0,
            rate?.let { "$it% saved over the last ${months.size} months" } ?: "No income recorded yet",
            "Target: $target% of income.",
        )
    }
}
