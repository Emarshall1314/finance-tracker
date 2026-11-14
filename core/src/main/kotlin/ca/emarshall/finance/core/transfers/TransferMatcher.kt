package ca.emarshall.finance.core.transfers

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Minimal view of a transaction for matching. */
data class TransferCandidate(
    val id: Long,
    val accountId: Long,
    val date: LocalDate,
    val amountCents: Long,
    val currency: String,
    val description: String,
    /** Already linked or unlinked by the user: leave alone. */
    val locked: Boolean = false,
)

data class TransferPair(val outId: Long, val inId: Long, val score: Int)

/**
 * Pairs money leaving one of my accounts with the same amount arriving in another of my accounts within
 * ±[windowDays] days. Each transaction joins at most one pair. Pairs are chosen best-first: closest dates,
 * then descriptions that mention payments/transfers.
 */
object TransferMatcher {

    private val KEYWORDS = listOf(
        "PAYMENT", "TRANSFER", "TFR", "QUESTRADE", "MASTERCARD", "TRIANGLE", "CANADIAN TIRE", "SCOTIA", "VISA",
        "DEPOSIT", "CONTRIBUTION", "WITHDRAWAL", "EFT", "BILL PAY",
    )

    fun match(candidates: List<TransferCandidate>, windowDays: Int = 5): List<TransferPair> {
        val open = candidates.filterNot { it.locked || it.amountCents == 0L }
        val outs = open.filter { it.amountCents < 0 }
        val insByKey = open.filter { it.amountCents > 0 }.groupBy { it.currency to it.amountCents }

        val options = ArrayList<TransferPair>()
        for (o in outs) {
            val ins = insByKey[o.currency to -o.amountCents] ?: continue
            for (i in ins) {
                if (i.accountId == o.accountId) continue
                val days = Math.abs(ChronoUnit.DAYS.between(o.date, i.date))
                if (days > windowDays) continue
                var score = 100 - days.toInt() * 10
                if (hasKeyword(o.description)) score += 15
                if (hasKeyword(i.description)) score += 15
                options.add(TransferPair(o.id, i.id, score))
            }
        }
        val used = HashSet<Long>()
        val result = ArrayList<TransferPair>()
        for (p in options.sortedWith(compareByDescending<TransferPair> { it.score }.thenBy { it.outId }.thenBy { it.inId })) {
            if (p.outId in used || p.inId in used) continue
            used.add(p.outId); used.add(p.inId)
            result.add(p)
        }
        return result
    }

    private fun hasKeyword(s: String): Boolean {
        val u = s.uppercase()
        return KEYWORDS.any { u.contains(it) }
    }
}
