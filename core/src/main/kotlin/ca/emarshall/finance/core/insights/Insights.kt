package ca.emarshall.finance.core.insights

import ca.emarshall.finance.core.analytics.AccountBalance
import ca.emarshall.finance.core.analytics.Analytics
import ca.emarshall.finance.core.analytics.TxFact
import ca.emarshall.finance.core.analytics.forCashFlow
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.core.recurring.RecurringSeries
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class Severity { INFO, WARNING, ALERT }

enum class InsightKind {
    LARGE_CHARGE, POSSIBLE_DUPLICATE, PRICE_INCREASE, NEW_SUBSCRIPTION, CATEGORY_SPIKE,
    INTEREST_CHARGED, BANK_FEE, NEGATIVE_BALANCE, SPENT_MORE_THAN_EARNED, PAYCHEQUE_LATE,
}

/**
 * Something worth the user's attention. [id] is stable across recomputation so a dismissal sticks.
 * Text never contains account numbers; amounts are formatted for display.
 */
data class Insight(
    val id: String,
    val kind: InsightKind,
    val severity: Severity,
    val title: String,
    val detail: String,
    val date: LocalDate?,
    val transactionIds: List<Long> = emptyList(),
)

/**
 * On-device checks for unusual activity. Deterministic and offline: no network, no AI. Each rule is
 * deliberately conservative (thresholds in cents) so the list stays short enough to read.
 */
object InsightEngine {

    fun analyze(
        facts: List<TxFact>,
        bills: List<RecurringSeries>,
        balances: List<AccountBalance>,
        accountNames: Map<Long, String>,
        today: LocalDate,
        /** Latest month whose statements are all in; month-level checks wait for it. */
        completeThrough: YearMonth? = YearMonth.from(today).minusMonths(1),
    ): List<Insight> {
        val home = facts.mapNotNull { it.forCashFlow() }.filter { it.currency == "CAD" }
        val spend = home.filter { it.kind == CategoryKind.EXPENSE && !it.countsAsTransfer && it.amountCents < 0 }
        val out = ArrayList<Insight>()
        out += largeCharges(spend, today)
        out += duplicates(spend, bills, today)
        out += priceIncreases(home, bills, today)
        out += newSubscriptions(bills, today)
        out += categorySpikes(spend, today)
        out += feesAndInterest(home, accountNames, today)
        out += negativeBalances(balances, today)
        if (completeThrough != null) out += spentMoreThanEarned(home, completeThrough)
        out += paychequeLate(home, today)
        return out.sortedWith(compareByDescending<Insight> { it.severity }.thenByDescending { it.date })
    }

    private fun m(c: Long) = Money.format(c)
    private fun recent(d: LocalDate, today: LocalDate, days: Long) = !d.isBefore(today.minusDays(days)) && !d.isAfter(today)

    /** Much bigger than usual for that merchant, or big and from a merchant never seen before. */
    private fun largeCharges(spend: List<TxFact>, today: LocalDate): List<Insight> {
        val byMerchant = spend.groupBy { it.merchant.lowercase() }
        val recentAll = spend.filter { recent(it.date, today, 120) }.map { -it.amountCents }.sorted()
        val p90 = if (recentAll.size >= 10) recentAll[(recentAll.size * 9) / 10] else Long.MAX_VALUE
        return spend.filter { recent(it.date, today, 30) && -it.amountCents >= 100_00 }.mapNotNull { t ->
            val prior = byMerchant[t.merchant.lowercase()].orEmpty().filter { it.date.isBefore(t.date) }.map { -it.amountCents }.sorted()
            val amount = -t.amountCents
            when {
                prior.size >= 3 && amount >= prior[prior.size / 2] * 3 -> Insight(
                    "large:${t.id}", InsightKind.LARGE_CHARGE, Severity.WARNING,
                    "Unusually large charge at ${t.merchant}",
                    "${m(amount)} on ${t.date}, about ${amount / maxOf(prior[prior.size / 2], 1)}× what you usually spend there (${m(prior[prior.size / 2])}).",
                    t.date, listOf(t.id),
                )
                prior.isEmpty() && amount >= 200_00 && amount >= p90 * 2 -> Insight(
                    "large:${t.id}", InsightKind.LARGE_CHARGE, Severity.INFO,
                    "Large charge from a new merchant",
                    "${m(amount)} at ${t.merchant} on ${t.date}. First time you've paid them — check it's yours.",
                    t.date, listOf(t.id),
                )
                else -> null
            }
        }
    }

    /** Same merchant, same amount, same account, within two days — and not a known recurring charge. */
    private fun duplicates(spend: List<TxFact>, bills: List<RecurringSeries>, today: LocalDate): List<Insight> {
        val recurringIds = bills.flatMap { it.transactionIds }.toSet()
        return spend.filter { recent(it.date, today, 45) && -it.amountCents >= 20_00 && it.id !in recurringIds }
            .groupBy { Triple(it.accountId, it.merchant.lowercase(), it.amountCents) }
            .values.flatMap { group ->
                group.sortedBy { it.date }.zipWithNext().filter { (a, b) -> ChronoUnit.DAYS.between(a.date, b.date) <= 2 }
                    .map { (a, b) ->
                        Insight(
                            "dup:${a.id}:${b.id}", InsightKind.POSSIBLE_DUPLICATE, Severity.WARNING,
                            "Possible double charge at ${a.merchant}",
                            "${m(-a.amountCents)} was charged on ${a.date} and again on ${b.date}. If you only bought once, contact the merchant or your bank.",
                            b.date, listOf(a.id, b.id),
                        )
                    }
            }
    }

    /** A subscription's latest charge is at least 5% (and $1) above its usual price. */
    private fun priceIncreases(facts: List<TxFact>, bills: List<RecurringSeries>, today: LocalDate): List<Insight> {
        val byId = facts.associateBy { it.id }
        return bills.mapNotNull { b ->
            val charges = b.transactionIds.mapNotNull { byId[it] }.sortedBy { it.date }
            if (charges.size < 3) return@mapNotNull null
            val last = charges.last()
            val before = charges.dropLast(1).map { -it.amountCents }.sorted()
            val usual = before[before.size / 2]
            val now = -last.amountCents
            if (!recent(last.date, today, 45) || now - usual < 100 || (now - usual) * 20 < usual) return@mapNotNull null
            Insight(
                "price:${last.id}", InsightKind.PRICE_INCREASE, Severity.INFO,
                "${b.merchant} went up",
                "Now ${m(now)} a month, up from ${m(usual)} (${m((now - usual) * 12)} more a year).",
                last.date, listOf(last.id),
            )
        }
    }

    private fun newSubscriptions(bills: List<RecurringSeries>, today: LocalDate) = bills
        .filter { it.occurrences == 3 && recent(it.lastDate, today, 40) }
        .map { b ->
            Insight(
                "newsub:${b.merchant.lowercase()}:${b.transactionIds.first()}", InsightKind.NEW_SUBSCRIPTION, Severity.INFO,
                "New recurring charge: ${b.merchant}",
                "${m(b.monthlyCostCents)} a month for the last 3 months (${m(b.monthlyCostCents * 12)} a year). Keep it?",
                b.lastDate, b.transactionIds,
            )
        }

    /** Last 30 days in a category vs the 30-day average of the 90 days before. */
    private fun categorySpikes(spend: List<TxFact>, today: LocalDate): List<Insight> {
        val last = spend.filter { recent(it.date, today, 29) }
        val prior = spend.filter { it.date.isBefore(today.minusDays(29)) && !it.date.isBefore(today.minusDays(119)) }
        if (prior.isEmpty()) return emptyList()
        val priorByCat = prior.groupBy { it.categoryKey }.mapValues { (_, l) -> -l.sumOf { it.amountCents } / 3 }
        return last.groupBy { it.categoryKey }.mapNotNull { (key, l) ->
            val now = -l.sumOf { it.amountCents }
            val usual = priorByCat[key] ?: return@mapNotNull null
            if (usual <= 0 || now - usual < 150_00 || now * 2 < usual * 3) return@mapNotNull null
            Insight(
                "spike:$key:${YearMonth.from(today)}", InsightKind.CATEGORY_SPIKE, Severity.WARNING,
                "${l.first().categoryName} is up",
                "${m(now)} in the last 30 days vs about ${m(usual)} in a typical month.",
                today, emptyList(),
            )
        }
    }

    private fun feesAndInterest(facts: List<TxFact>, accountNames: Map<Long, String>, today: LocalDate): List<Insight> =
        facts.filter { it.categoryKey == "fees" && it.amountCents < 0 && recent(it.date, today, 45) }
            .groupBy { it.accountId to it.merchant.contains("interest", ignoreCase = true) }
            .map { (k, l) ->
                val (accountId, interest) = k
                val total = -l.sumOf { it.amountCents }
                val name = accountNames[accountId] ?: "an account"
                if (interest) {
                    Insight(
                        "interest:$accountId:${l.maxOf { it.date }}", InsightKind.INTEREST_CHARGED, Severity.ALERT,
                        "You paid interest on $name",
                        "${m(total)} in interest recently. Paying the full statement balance by the due date avoids interest on purchases.",
                        l.maxOf { it.date }, l.map { it.id },
                    )
                } else {
                    Insight(
                        "fee:$accountId:${l.maxOf { it.date }}", InsightKind.BANK_FEE, Severity.WARNING,
                        "Fees on $name",
                        "${m(total)} in fees recently (${l.joinToString { it.merchant }.take(80)}). Some can be waived — ask the bank.",
                        l.maxOf { it.date }, l.map { it.id },
                    )
                }
            }

    private fun negativeBalances(balances: List<AccountBalance>, today: LocalDate) = balances
        .filter { !it.isLiability && it.balanceCents < 0 && it.currency == "CAD" && recent(it.asOf, today, 20) }
        .map {
            Insight(
                "neg:${it.accountId}:${it.asOf}", InsightKind.NEGATIVE_BALANCE, Severity.ALERT,
                "${it.nickname} is overdrawn",
                "Balance was ${m(it.balanceCents)} on ${it.asOf}. Overdrafts usually mean fees and interest.",
                it.asOf,
            )
        }

    /** Last complete month: spending above income. */
    private fun spentMoreThanEarned(facts: List<TxFact>, month: YearMonth): List<Insight> {
        val t = Analytics(facts).monthTotals(month)
        if (t.incomeCents <= 0 || t.spendingCents <= t.incomeCents) return emptyList()
        return listOf(
            Insight(
                "deficit:$month", InsightKind.SPENT_MORE_THAN_EARNED, Severity.WARNING,
                "You spent more than you earned in ${month.month.name.lowercase().replaceFirstChar { it.uppercase() }}",
                "Spending ${m(t.spendingCents)} vs income ${m(t.incomeCents)}: ${m(t.spendingCents - t.incomeCents)} short.",
                month.atEndOfMonth(),
            ),
        )
    }

    /** Paycheques arrive on a rhythm; flag when the next one is more than 5 days overdue. */
    private fun paychequeLate(facts: List<TxFact>, today: LocalDate): List<Insight> {
        val pays = facts.filter { it.categoryKey == "paycheque" && it.amountCents > 0 }.map { it.date }.distinct().sorted()
        if (pays.size < 3) return emptyList()
        val gaps = pays.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.sorted()
        val usual = gaps[gaps.size / 2]
        if (usual !in 6..35) return emptyList()
        val last = pays.last()
        val since = ChronoUnit.DAYS.between(last, today)
        if (since <= usual + 5 || since > usual * 3) return emptyList()
        return listOf(
            Insight(
                "paylate:$last", InsightKind.PAYCHEQUE_LATE, Severity.WARNING,
                "No paycheque since ${last}",
                "You're usually paid every $usual days; it's been $since. If you haven't imported recent statements, that may be why.",
                today,
            ),
        )
    }
}
