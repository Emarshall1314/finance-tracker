package ca.emarshall.finance.core

import ca.emarshall.finance.core.analytics.AccountBalance
import ca.emarshall.finance.core.analytics.Analytics
import ca.emarshall.finance.core.analytics.BalancePoint
import ca.emarshall.finance.core.analytics.DataFreshness
import ca.emarshall.finance.core.analytics.NetWorth
import ca.emarshall.finance.core.analytics.TxFact
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.core.recurring.RecurringSeries
import ca.emarshall.finance.core.summary.HoldingSummary
import ca.emarshall.finance.core.summary.InvestmentAccountSummary
import ca.emarshall.finance.core.summary.InvestmentSummary
import ca.emarshall.finance.core.summary.SummaryAccount
import ca.emarshall.finance.core.summary.SummaryExport
import ca.emarshall.finance.core.summary.SummaryInput
import ca.emarshall.finance.core.summary.TransactionCsvExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

class AnalyticsAndSummaryTest {

    private var nextId = 1L
    private fun f(
        date: LocalDate, cents: Long, key: String, name: String, kind: CategoryKind, merchant: String,
        account: Long = 1, transfer: Boolean = false, currency: String = "CAD",
    ) = TxFact(nextId++, account, date, cents, currency, key, name, kind, merchant, transfer)

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    private val facts by lazy {
        listOf(
            f(d(1, 15), 245000, "paycheque", "Paycheque", CategoryKind.INCOME, "Acme Widgets"),
            f(d(1, 30), 245000, "paycheque", "Paycheque", CategoryKind.INCOME, "Acme Widgets"),
            f(d(1, 5), -15432, "groceries", "Groceries", CategoryKind.EXPENSE, "Costco", account = 2),
            f(d(1, 12), -1699, "subscriptions", "Subscriptions", CategoryKind.EXPENSE, "Netflix", account = 2),
            f(d(1, 20), -190000, "bills", "Bills & Utilities", CategoryKind.EXPENSE, "Rent 4411-2233-9988"),
            f(d(1, 31), -1695, "fees", "Fees & Interest", CategoryKind.EXPENSE, "Monthly Fee"),
            // Card payment: both sides are transfers and never count.
            f(d(1, 28), -50000, "card_payment", "Card Payment", CategoryKind.TRANSFER, "Triangle Mastercard", transfer = true),
            f(d(1, 29), 50000, "card_payment", "Card Payment", CategoryKind.TRANSFER, "Payment", account = 2, transfer = true),
            // A transfer that was categorised as an expense but linked: still excluded.
            f(d(1, 29), -30000, "other", "Other", CategoryKind.EXPENSE, "Questrade", transfer = true),
            f(d(2, 15), 245000, "paycheque", "Paycheque", CategoryKind.INCOME, "Acme Widgets"),
            f(d(2, 6), -8412, "groceries", "Groceries", CategoryKind.EXPENSE, "Save-On-Foods", account = 2),
            f(d(2, 12), -1699, "subscriptions", "Subscriptions", CategoryKind.EXPENSE, "Netflix", account = 2),
            f(d(2, 14), 2000, "groceries", "Groceries", CategoryKind.EXPENSE, "Costco", account = 2), // refund reduces spending
            f(d(2, 20), -190000, "bills", "Bills & Utilities", CategoryKind.EXPENSE, "Rent 4411-2233-9988"),
            f(d(2, 25), -1701, "fees", "Fees & Interest", CategoryKind.EXPENSE, "Purchase Interest", account = 2),
            f(d(2, 26), -2500, "shopping", "Shopping", CategoryKind.EXPENSE, "Amazon", currency = "USD"),
            f(d(3, 1), 1234, "investment_income", "Investment Income", CategoryKind.INCOME, "Dividend XEQT", account = 3),
        )
    }

    @Test fun monthTotalsExcludeTransfers() {
        val a = Analytics(facts)
        val jan = a.monthTotals(YearMonth.of(2026, 1))
        assertEquals(490000L, jan.incomeCents)
        assertEquals(15432L + 1699 + 190000 + 1695, jan.spendingCents)
        assertEquals(57, jan.savingsRatePercent)
        val feb = a.monthTotals(YearMonth.of(2026, 2))
        assertEquals(8412L + 1699 - 2000 + 190000 + 1701, feb.spendingCents) // USD row excluded
        assertNull(a.monthTotals(YearMonth.of(2025, 12)).savingsRatePercent)
    }

    @Test fun categoriesMerchantsAndFees() {
        val a = Analytics(facts)
        val cats = a.spendingByCategory(d(1, 1), d(2, 28))
        assertEquals("bills", cats.first().categoryKey)
        assertEquals(15432L + 8412 - 2000, cats.single { it.categoryKey == "groceries" }.cents)
        assertFalse(cats.any { it.categoryKey == "card_payment" || it.categoryKey == "other" })
        assertEquals(3396L, a.feesAndInterest(d(1, 1), d(2, 28)))
        assertEquals(listOf("Rent 4411-2233-9988", "Costco"), a.topMerchants(d(1, 1), d(2, 28), 2).map { it.merchant })
        val series = a.monthSeries(YearMonth.of(2026, 3), 12)
        assertEquals(12, series.size)
        assertEquals(YearMonth.of(2025, 4), series.first().month)
    }

    @Test fun netWorthAndTrend() {
        val balances = listOf(
            AccountBalance(1, "Chequing", false, 291588, d(3, 31)),
            AccountBalance(2, "Triangle", true, -123456, d(3, 31)),
            AccountBalance(3, "TFSA", false, 2500000, d(3, 31)),
            AccountBalance(4, "USD", false, 99999, d(3, 31), "USD"),
        )
        assertEquals(291588L - 123456 + 2500000, NetWorth.total(balances))
        val trend = NetWorth.trend(
            listOf(BalancePoint(1, d(1, 15), 100000), BalancePoint(1, d(2, 20), 200000), BalancePoint(2, d(2, 1), -50000)),
            YearMonth.of(2026, 3), 3,
        )
        assertEquals(listOf(100000L, 150000L, 150000L), trend.map { it.second })
    }

    @Test fun freshness() {
        assertFalse(DataFreshness.of(1, d(3, 31), d(5, 5)).isStale)
        assertTrue(DataFreshness.of(1, d(3, 31), d(5, 6)).isStale)
        assertTrue(DataFreshness.of(1, null, d(5, 6)).isStale)
    }

    @Test fun summarySnapshot() {
        val input = SummaryInput(
            from = d(1, 1),
            to = d(3, 31),
            accounts = listOf(
                SummaryAccount("Everyday", "Chequing", "Scotiabank"),
                SummaryAccount("Triangle", "Credit card", "Canadian Tire Bank"),
                SummaryAccount("TFSA", "TFSA", "Questrade"),
            ),
            facts = facts,
            bills = listOf(RecurringSeries("Netflix", -1699, 1699, 3, d(3, 12), d(4, 12), listOf(1, 2, 3), "CAD")),
            balances = listOf(
                AccountBalance(1, "Everyday", false, 291588, d(3, 31)),
                AccountBalance(2, "Triangle", true, -123456, d(3, 31)),
                AccountBalance(3, "TFSA", false, 2500000, d(3, 31)),
            ),
            netWorthTrend = listOf(YearMonth.of(2026, 1) to 2400000L, YearMonth.of(2026, 2) to 2550000L, YearMonth.of(2026, 3) to 2668132L),
            investments = InvestmentSummary(
                totalEquityCents = 2500000,
                accounts = listOf(InvestmentAccountSummary("TFSA", "TFSA", 2500000, 12345)),
                holdings = listOf(
                    HoldingSummary("XEQT.TO", "500", 1600000, 120000),
                    HoldingSummary("VFV.TO", "60", 887655, -4500),
                ),
                dividendsLast12MonthsCents = 41234,
                dividendsThisYearCents = 1234,
                contributionsThisYearByType = mapOf("TFSA" to 700000L),
            ),
        )
        val md = SummaryExport.markdown(input)
        assertFalse("account numbers must be masked", md.contains("4411-2233-9988"))
        assertTrue(md.contains("British Columbia"))

        val snapshot = File("src/test/resources/snapshots/summary.md")
        if (!snapshot.exists() || System.getenv("UPDATE_SNAPSHOTS") == "true") {
            snapshot.parentFile.mkdirs()
            snapshot.writeText(md)
        }
        assertEquals(snapshot.readText(), md)
    }

    @Test fun transactionCsvMasksNumbersAndQuotes() {
        val csv = TransactionCsvExport.csv(
            listOf(
                TransactionCsvExport.Row(d(1, 2), "Everyday", "Acme, Inc", "TRANSFER TO 000099990000123", "Other", -12345, "CAD", "note \"x\""),
            ),
        )
        assertEquals(
            "date,account,merchant,description,category,amount,currency,notes\n" +
                "2026-01-02,Everyday,\"Acme, Inc\",TRANSFER TO [number],Other,-123.45,CAD,\"note \"\"x\"\"\"\n",
            csv,
        )
    }
}
