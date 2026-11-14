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
