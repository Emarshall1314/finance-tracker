package ca.emarshall.finance.core

import ca.emarshall.finance.core.analytics.AccountKind
import ca.emarshall.finance.core.analytics.Analytics
import ca.emarshall.finance.core.analytics.TxFact
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.core.statements.AccountCoverage
import ca.emarshall.finance.core.statements.MonthCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CompletenessAndIncomeTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)

    @Test fun monthIsCompleteOnlyWhenEveryAccountCoversIt() {
        val accounts = listOf(
            AccountCoverage(1, "Chequing", d(10, 31)), // through October
            AccountCoverage(2, "Visa", d(11, 15)), // October fully, November half
            AccountCoverage(3, "Triangle", d(11, 5)), // October fully
        )
        assertEquals(YearMonth.of(2026, 10), MonthCompleteness.latestComplete(accounts))
        // Chequing's October statement not in yet (only through September): October waits.
        val late = accounts.map { if (it.accountId == 1L) it.copy(coveredThrough = d(9, 29)) else it }
        assertEquals(YearMonth.of(2026, 9), MonthCompleteness.latestComplete(late)) // Sep 29 counts as month end
        assertEquals(listOf("Chequing"), MonthCompleteness.waitingOn(late, YearMonth.of(2026, 10)).map { it.name })
        // A card closing on the 15th covers October only once the Nov 15 statement is in.
        assertEquals(YearMonth.of(2026, 9), MonthCompleteness.lastFullMonth(d(10, 15)))
        assertNull(MonthCompleteness.latestComplete(listOf(AccountCoverage(1, "New", null))))
    }

    private var id = 0L
    private fun f(cents: Long, key: String, kind: CategoryKind, acct: AccountKind) =
        TxFact(++id, 1, d(10, 10), cents, "CAD", key, key, kind, "M", false, acct)

    @Test fun incomeOnlyFromBankAccounts() {
        val a = Analytics(
            listOf(
                f(3000_00, "paycheque", CategoryKind.INCOME, AccountKind.BANK),
                f(-500_00, "groceries", CategoryKind.EXPENSE, AccountKind.CARD),
                f(50_00, "other_income", CategoryKind.INCOME, AccountKind.CARD), // card return: reduces spending
                f(20_00, "groceries", CategoryKind.EXPENSE, AccountKind.CARD), // refund already in a spending category
                f(120_00, "investment_income", CategoryKind.INCOME, AccountKind.INVESTMENT), // dividend stays in the account
            ),
        )
        val t = a.monthTotals(YearMonth.of(2026, 10))
        assertEquals(3000_00L, t.incomeCents)
        assertEquals(500_00L - 50_00 - 20_00, t.spendingCents)
        assertEquals(listOf("groceries"), a.spendingByCategory(YearMonth.of(2026, 10)).map { it.categoryKey })
    }
}

class UnlockedMonthTest {
    private val chequing = ca.emarshall.finance.core.statements.AccountCoverage(1, "Chequing", java.time.LocalDate.of(2026, 10, 31), drivesMonths = true, closingDay = 31)
    private val visa = ca.emarshall.finance.core.statements.AccountCoverage(2, "Scotia Visa", java.time.LocalDate.of(2026, 10, 15), drivesMonths = false, closingDay = 15)
    private val triangle = ca.emarshall.finance.core.statements.AccountCoverage(3, "Triangle", java.time.LocalDate.of(2026, 11, 5), drivesMonths = false, closingDay = 5)
    private val mc = ca.emarshall.finance.core.statements.MonthCompleteness

    @org.junit.Test fun chequingUnlocksTheMonthCardsOnlyMakeItFinal() {
        val all = listOf(chequing, visa, triangle)
        org.junit.Assert.assertEquals(java.time.YearMonth.of(2026, 10), mc.latestUnlocked(all))
        org.junit.Assert.assertEquals(java.time.YearMonth.of(2026, 9), mc.latestComplete(all))
        org.junit.Assert.assertEquals(listOf("Scotia Visa"), mc.waitingOn(all, java.time.YearMonth.of(2026, 10)).map { it.name })
    }

    @org.junit.Test fun withoutBankStatementsEveryAccountCounts() {
        org.junit.Assert.assertEquals(java.time.YearMonth.of(2026, 9), mc.latestUnlocked(listOf(visa, triangle)))
    }

    @org.junit.Test fun nextCloseFollowsTheCycle() {
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 11, 15), mc.nextClose(visa))
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 12, 5), mc.nextClose(triangle))
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2026, 11, 30), mc.nextClose(chequing))
        org.junit.Assert.assertEquals(java.time.LocalDate.of(2027, 2, 28), mc.nextClose(chequing.copy(coveredThrough = java.time.LocalDate.of(2027, 1, 31))))
    }
}

class InvestedAndOverallTest {
    private var id = 0L
    private val oct = LocalDate.of(2026, 10, 10)
    private fun f(cents: Long, key: String, kind: CategoryKind, acct: AccountKind, link: Boolean = false, toInv: Boolean = false, toSavings: Boolean = false) =
        TxFact(++id, 1, oct, cents, "CAD", key, key, kind, "M", link, acct,
            if (toInv) ca.emarshall.finance.core.analytics.SetAside.INVESTMENTS else if (toSavings) ca.emarshall.finance.core.analytics.SetAside.SAVINGS else null)

    @Test fun investedCountsTheBankSideOnly() {
        val facts = listOf(
            f(-150_000, "investments_transfer", CategoryKind.TRANSFER, AccountKind.BANK, link = true, toInv = true), // chequing -> Questrade
            f(150_000, "investments_transfer", CategoryKind.TRANSFER, AccountKind.INVESTMENT, link = true, toInv = true), // Questrade side
            f(20_000, "investments_transfer", CategoryKind.TRANSFER, AccountKind.BANK, toInv = true), // withdrawal back
            f(-5_000, "groceries", CategoryKind.EXPENSE, AccountKind.BANK),
            f(-50_000, "savings_transfer", CategoryKind.TRANSFER, AccountKind.BANK, toSavings = true), // auto-transfer to savings
        )
        val t = Analytics(facts).setAsideIn(YearMonth.of(2026, 10))
        assertEquals(130_000L, t.investmentsCents)
        assertEquals(50_000L, t.savingsCents)
        assertEquals(180_000L, t.totalCents)
    }

    @Test fun overallLeavesOutTransfersAndInvestmentActivity() {
        val facts = listOf(
            f(300_000, "salary", CategoryKind.INCOME, AccountKind.BANK),
            f(-50_000, "card_payment", CategoryKind.TRANSFER, AccountKind.BANK, link = true), // chequing pays the card
            f(50_000, "card_payment", CategoryKind.TRANSFER, AccountKind.CARD, link = true),
            f(-8_000, "dining", CategoryKind.EXPENSE, AccountKind.CARD),
            f(1_000, "refund", CategoryKind.INCOME, AccountKind.CARD), // a refund lowers spending
            f(-400_000, "other", CategoryKind.EXPENSE, AccountKind.INVESTMENT), // a stock buy
            f(500_000, "other_income", CategoryKind.INCOME, AccountKind.INVESTMENT), // a stock sale
        )
        val t = Analytics(facts).overall()
        assertEquals(300_000L, t.incomeCents)
        assertEquals(7_000L, t.spendingCents)
    }
}
