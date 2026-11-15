package ca.emarshall.finance.core

import ca.emarshall.finance.core.analytics.AccountKind
import ca.emarshall.finance.core.analytics.Analytics
import ca.emarshall.finance.core.analytics.TxFact
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.core.statements.AccountCoverage
import ca.emarshall.finance.core.statements.MonthCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

class MoneyFlowTest {
    private fun cat(name: String, cents: Long) = ca.emarshall.finance.core.analytics.CategoryTotal(name.lowercase(), name, cents)
    private val flows = ca.emarshall.finance.core.analytics.MoneyFlows
    private fun aside(s: Long, i: Long) = ca.emarshall.finance.core.analytics.SetAsideTotals(s, i)

    @Test fun bothSidesAlwaysBalance() {
        // Earned 6,000; spent 4,000; 500 to savings, 1,000 to investments; 500 left over.
        val f = flows.build(600_000, listOf(cat("Rent", 250_000), cat("Groceries", 100_000), cat("Dining", 50_000)), aside(50_000, 100_000))!!
        assertEquals(f.sources.sumOf { it.cents }, f.targets.sumOf { it.cents })
        assertEquals(listOf("Rent", "Groceries", "Dining", "Savings", "Investments", "Left in chequing"), f.targets.map { it.label })
        assertEquals(50_000L, f.targets.last().cents)
    }

    @Test fun overspendingComesFromTheBalance() {
        val f = flows.build(100_000, listOf(cat("Travel", 180_000)), aside(0, 0))!!
        assertEquals(listOf("Income", "From your balance"), f.sources.map { it.label })
        assertEquals(80_000L, f.sources.last().cents)
        assertEquals(f.sources.sumOf { it.cents }, f.targets.sumOf { it.cents })
    }

    @Test fun moneyTakenOutOfSavingsIsASource() {
        val f = flows.build(100_000, listOf(cat("Auto", 150_000)), aside(-50_000, 0))!!
        assertEquals(listOf("Income", "From savings"), f.sources.map { it.label })
        assertEquals(listOf("Auto"), f.targets.map { it.label })
    }

    @Test fun smallCategoriesAreGrouped() {
        val cats = (1..9).map { cat("C$it", 1_000L * (10 - it)) }
        val f = flows.build(100_000, cats, aside(0, 0))!!
        assertEquals("Everything else", f.targets[6].label)
        assertEquals(6_000L, f.targets[6].cents) // 3,000 + 2,000 + 1,000
        assertNull(flows.build(0, emptyList(), aside(0, 0)))
    }

    @Test fun dailySpendingNetsRefunds() {
        var id = 0L
        fun f(day: Int, cents: Long) = TxFact(++id, 1, LocalDate.of(2026, 10, day), cents, "CAD", "shopping", "Shopping", CategoryKind.EXPENSE, "M", false, AccountKind.CARD)
        val d = Analytics(listOf(f(3, -5_000), f(3, -2_000), f(4, -1_000), f(4, 1_500), f(9, -700))).dailySpending(YearMonth.of(2026, 10))
        assertEquals(mapOf(3 to 7_000L, 9 to 700L), d) // the 4th nets to a refund, so it isn't a spending day
    }
}

class HighlightsTest {
    private var id = 0L
    private fun f(month: Int, day: Int, cents: Long, merchant: String, key: String, kind: CategoryKind = CategoryKind.EXPENSE, acct: AccountKind = AccountKind.BANK) =
        TxFact(++id, 1, LocalDate.of(2026, month, day), cents, "CAD", key, key.replaceFirstChar { it.uppercase() }, kind, merchant, false, acct)

    @Test fun aMonthInAFewNumbers() {
        val facts = ArrayList<TxFact>()
        for (m in 7..10) {
            facts += f(m, 1, 400_000, "Payroll", "paycheque", CategoryKind.INCOME)
            facts += f(m, 2, -180_000, "Landlord", "rent")
            facts += f(m, 5, if (m == 10) -60_000 else -30_000, "Sushi Place", "dining")
            repeat(5) { facts += f(m, 10 + it, -500, "Tim Hortons", "coffee") }
        }
        val h = ca.emarshall.finance.core.insights.Highlights.forMonth(facts, YearMonth.of(2026, 10), emptyList())
        val byKind = h.associateBy { it.kind }
        // October: 4,000 in; 1,800 + 600 + 25 out = 1,575 kept (39%), 300 less than the usual 1,875.
        assertEquals("39%", byKind.getValue(ca.emarshall.finance.core.insights.HighlightKind.SAVED).value)
        assertTrue(byKind.getValue(ca.emarshall.finance.core.insights.HighlightKind.SAVED).caption.contains("$300 less than your usual month"))
        assertEquals("▲ $300", byKind.getValue(ca.emarshall.finance.core.insights.HighlightKind.CATEGORY_UP).value)
        assertTrue(byKind.getValue(ca.emarshall.finance.core.insights.HighlightKind.CATEGORY_UP).caption.startsWith("Dining: $600, usually about $300"))
        assertEquals("$1,800", byKind.getValue(ca.emarshall.finance.core.insights.HighlightKind.BIGGEST_PURCHASE).value)
        assertEquals("5×", byKind.getValue(ca.emarshall.finance.core.insights.HighlightKind.MOST_VISITED).value)
        assertEquals("24", byKind.getValue(ca.emarshall.finance.core.insights.HighlightKind.QUIET_DAYS).value) // 31 days, 7 with spending
        assertTrue(ca.emarshall.finance.core.insights.Highlights.forMonth(facts, YearMonth.of(2025, 1), emptyList()).isEmpty())
    }
}
