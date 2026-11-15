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
