package ca.emarshall.finance.core.transfers

import ca.emarshall.finance.core.analytics.AccountKind
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
    val kind: AccountKind = AccountKind.BANK,
)

data class TransferPair(val outId: Long, val inId: Long, val score: Int)

/**
 * Pairs money leaving one of my accounts with the same amount arriving in another of my accounts within
 * ±[windowDays] days. Each transaction joins at most one pair. Pairs are chosen best-first: closest dates,
 * then descriptions that mention payments/transfers.
 *
 * The same amount on the same day is not enough: a $50 e-Transfer from a friend and a $50 purchase on a
 * card are not a transfer. So a pair must also make sense: money never moves from a card to a bank
 * account, neither side may be a shop purchase, an e-Transfer, a cash withdrawal or a paycheque, and a
 * payment into a card must say so on one side or the other.
 */
object TransferMatcher {

    private val KEYWORDS = listOf(
        "PAYMENT", "TRANSFER", "TFR", "QUESTRADE", "MASTERCARD", "TRIANGLE", "CANADIAN TIRE", "SCOTIA", "VISA",
        "DEPOSIT", "CONTRIBUTION", "WITHDRAWAL", "EFT", "BILL PAY",
    )

    /** How far apart an e-Transfer and the card payment it made can be dated (statements date them a day or two apart). */
    private const val ETRANSFER_PAYMENT_DAYS = 2L

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
                if (days > windowDays || !plausible(o, i, days)) continue
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

    private val NEVER = listOf("POINT OF SALE", "POS PURCHASE", "E-TRANSFER", "ETRANSFER", "E-TRF", "E-TFR", "ABM", "ATM", "PAYROLL", "REFUND")
    private val CARD_PAYMENT = listOf("PAYMENT", "THANK YOU", "SCOTIABANK TRANSIT", "CTFS", "TRANSFER", "TFR", "MASTERCARD", "VISA", "TRIANGLE")

    private val ETRANSFER = listOf("E-TRANSFER", "ETRANSFER", "E-TRF", "E-TFR")
    private val PAYMENT_ON_CARD = listOf("PAYMENT", "PYMT", "THANK YOU", "SCOTIABANK TRANSIT", "CTFS")

    private fun plausible(out: TransferCandidate, inn: TransferCandidate, days: Long): Boolean {
        if (out.kind == AccountKind.CARD) return false // purchases leave a card; money doesn't move from it to a bank account
        val o = out.description.uppercase()
        val i = inn.description.uppercase()
        // A card can be paid by e-Transfer: an e-Transfer out of the bank and a payment of the same amount
        // arriving on a card within a day or so are one payment. The card side must say it's a payment,
        // so a shop's refund of the same amount is never mistaken for one.
        if (inn.kind == AccountKind.CARD && days <= ETRANSFER_PAYMENT_DAYS && ETRANSFER.any { o.contains(it) } &&
            PAYMENT_ON_CARD.any { i.contains(it) } && NEVER.none { i.contains(it) }
        ) return true
        if (NEVER.any { o.contains(it) || i.contains(it) }) return false
        if (inn.kind == AccountKind.CARD && CARD_PAYMENT.none { o.contains(it) || i.contains(it) }) return false
        return true
    }

    private fun hasKeyword(s: String): Boolean {
        val u = s.uppercase()
        return KEYWORDS.any { u.contains(it) }
    }
}
