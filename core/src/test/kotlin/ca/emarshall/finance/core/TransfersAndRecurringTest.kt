package ca.emarshall.finance.core

import ca.emarshall.finance.core.recurring.RecurringDetector
import ca.emarshall.finance.core.recurring.RecurringInput
import ca.emarshall.finance.core.transfers.TransferCandidate
import ca.emarshall.finance.core.transfers.TransferMatcher
import ca.emarshall.finance.core.transfers.TransferPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TransfersAndRecurringTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private fun t(id: Long, acct: Long, date: LocalDate, cents: Long, desc: String = "", locked: Boolean = false) =
        TransferCandidate(id, acct, date, cents, "CAD", desc, locked)

    @Test fun pairsCardPaymentAcrossAccounts() {
        val pairs = TransferMatcher.match(
            listOf(
                t(1, acct = 10, date = d(3, 30), cents = -50000, desc = "MB-BILL PAYMENT TRIANGLE MASTERCARD"),
                t(2, acct = 20, date = d(4, 1), cents = 50000, desc = "PAYMENT - THANK YOU"),
                t(3, acct = 10, date = d(4, 1), cents = -6120, desc = "PETRO-CANADA"),
            ),
        )
        assertEquals(listOf(TransferPair(1, 2, pairs.single().score)), pairs)
    }

    @Test fun respectsWindowAccountAndCurrency() {
        val far = TransferMatcher.match(listOf(t(1, 10, d(3, 1), -100), t(2, 20, d(3, 7), 100)))
        assertTrue(far.isEmpty())
        val sameAccount = TransferMatcher.match(listOf(t(1, 10, d(3, 1), -100), t(2, 10, d(3, 1), 100)))
        assertTrue(sameAccount.isEmpty())
        val fx = TransferMatcher.match(listOf(t(1, 10, d(3, 1), -100), TransferCandidate(2, 20, d(3, 1), 100, "USD", "")))
        assertTrue(fx.isEmpty())
        val edge = TransferMatcher.match(listOf(t(1, 10, d(3, 1), -100), t(2, 20, d(3, 6), 100)))
        assertEquals(1, edge.size)
    }

    @Test fun eachTransactionPairsOnceAndClosestWins() {
        val pairs = TransferMatcher.match(
            listOf(
                t(1, 10, d(3, 1), -50000, "TRANSFER TO QUESTRADE"),
                t(2, 30, d(3, 4), 50000, "DEPOSIT"),
                t(3, 30, d(3, 2), 50000, "DEPOSIT"),
                t(4, 10, d(3, 3), -50000, "TRANSFER TO QUESTRADE"),
            ),
        )
        assertEquals(2, pairs.size)
        assertTrue(TransferPair(1, 3, pairs.first { it.outId == 1L }.score) in pairs)
        assertTrue(TransferPair(4, 2, pairs.first { it.outId == 4L }.score) in pairs)
    }

    @Test fun lockedRowsAreLeftAlone() {
        val pairs = TransferMatcher.match(listOf(t(1, 10, d(3, 1), -100, locked = true), t(2, 20, d(3, 1), 100)))
        assertTrue(pairs.isEmpty())
    }

    @Test fun equalAmountsAloneAreNotATransfer() {
        val card = ca.emarshall.finance.core.analytics.AccountKind.CARD
        fun tc(id: Long, acct: Long, cents: Long, desc: String, kind: ca.emarshall.finance.core.analytics.AccountKind = ca.emarshall.finance.core.analytics.AccountKind.BANK) =
            TransferCandidate(id, acct, d(3, 1), cents, "CAD", desc, kind = kind)
        // A friend's e-Transfer and a card purchase of the same amount.
        assertTrue(TransferMatcher.match(listOf(tc(1, 20, -5000, "PETRO-CANADA 123", card), tc(2, 10, 5000, "Deposit Interac E-Transfer"))).isEmpty())
        // An e-Transfer sent and a refund on the card.
        assertTrue(TransferMatcher.match(listOf(tc(1, 10, -5000, "Withdrawal Free Interac E-Transfer"), tc(2, 20, 5000, "AMAZON.CA", card))).isEmpty())
        // A shop purchase from chequing and a refund on the card.
        assertTrue(TransferMatcher.match(listOf(tc(1, 10, -2500, "Point of sale purchase Fpos Some Shop"), tc(2, 20, 2500, "SOME SHOP", card))).isEmpty())
        // A bill and an unrelated credit on the card: nothing says "payment".
        assertTrue(TransferMatcher.match(listOf(tc(1, 10, -8025, "Hydro bill B.C. Hydro"), tc(2, 20, 8025, "BEST BUY #12", card))).isEmpty())
        // The real thing still pairs.
        assertEquals(1, TransferMatcher.match(listOf(tc(1, 10, -30000, "Credit Card/LOC payment Main and Example"), tc(2, 20, 30000, "SCOTIABANK TRANSIT 10520", card))).size)
        assertEquals(1, TransferMatcher.match(listOf(tc(1, 10, -150000, "Misc. payment Questrade Inc"), tc(2, 30, 150000, "Deposits", ca.emarshall.finance.core.analytics.AccountKind.INVESTMENT))).size)
    }

    @Test fun billsSurviveLateStatements() {
        val through = d(4, 15) // the card's latest statement closed Apr 15
        val txs = listOf(d(1, 20), d(2, 20), d(3, 20)).mapIndexed { i, day -> RecurringInput(i.toLong(), day, -1699, "Netflix", dataThrough = through) }
        // Jun 1: 73 days since the last charge on file, but only 26 days of that are covered by statements.
        val s = RecurringDetector.detect(txs, today = d(6, 1)).single()
        assertEquals(d(6, 20), s.nextExpected) // the next one still to come, not one already past
        // Once statements run well past the last charge with nothing new, it's treated as cancelled.
        val later = txs.map { it.copy(dataThrough = d(6, 15)) }
        assertTrue(RecurringDetector.detect(later, today = d(6, 20)).isEmpty())
    }

    private fun r(id: Long, date: LocalDate, cents: Long, merchant: String) = RecurringInput(id, date, cents, merchant)

    @Test fun detectsMonthlySubscriptionWithSmallPriceChange() {
        val txs = listOf(
            r(1, d(1, 12), -1699, "Netflix"), r(2, d(2, 12), -1699, "Netflix"),
            r(3, d(3, 12), -1799, "Netflix"), r(4, d(4, 13), -1799, "Netflix"),
            r(5, d(4, 2), -345, "Tim Hortons"), r(6, d(4, 3), -345, "Tim Hortons"), r(7, d(4, 9), -510, "Tim Hortons"),
        )
        val series = RecurringDetector.detect(txs, today = d(4, 20))
        assertEquals(1, series.size)
        val n = series.single()
        assertEquals("Netflix", n.merchant)
        assertEquals(4, n.occurrences)
        assertEquals(d(5, 13), n.nextExpected)
        assertEquals(1749L, n.monthlyCostCents)
    }

    @Test fun needsThreeOccurrencesAndMonthlyRhythm() {
        val two = listOf(r(1, d(1, 1), -1000, "Gym"), r(2, d(2, 1), -1000, "Gym"))
        assertTrue(RecurringDetector.detect(two, d(2, 10)).isEmpty())
        val weekly = (0 until 6).map { r(it.toLong(), d(1, 1).plusWeeks(it.toLong()), -1000, "Gym") }
        assertTrue(RecurringDetector.detect(weekly, d(2, 10)).isEmpty())
        val bigPriceJump = listOf(r(1, d(1, 1), -1000, "Gym"), r(2, d(2, 1), -1500, "Gym"), r(3, d(3, 1), -2000, "Gym"))
        assertTrue(RecurringDetector.detect(bigPriceJump, d(3, 10)).isEmpty())
    }

    @Test fun separatesTwoPlansAtTheSameMerchant() {
        val txs = (1..4).flatMap { m -> listOf(r(m * 10L, d(m, 5), -1099, "Apple"), r(m * 10L + 1, d(m, 20), -399, "Apple")) }
        val series = RecurringDetector.detect(txs, d(4, 25))
        assertEquals(setOf(1099L, 399L), series.map { it.monthlyCostCents }.toSet())
    }

    @Test fun dropsCancelledSubscriptions() {
        val txs = listOf(r(1, d(1, 1), -1000, "Old"), r(2, d(2, 1), -1000, "Old"), r(3, d(3, 1), -1000, "Old"))
        assertTrue(RecurringDetector.detect(txs, d(6, 1)).isEmpty())
    }
}
