package ca.emarshall.finance.core

import ca.emarshall.finance.core.analytics.AccountBalance
import ca.emarshall.finance.core.analytics.TxFact
import ca.emarshall.finance.core.goals.Goal
import ca.emarshall.finance.core.goals.GoalStatus
import ca.emarshall.finance.core.goals.GoalTracker
import ca.emarshall.finance.core.goals.GoalType
import ca.emarshall.finance.core.insights.InsightEngine
import ca.emarshall.finance.core.insights.InsightKind
import ca.emarshall.finance.core.insights.Severity
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.core.recurring.RecurringDetector
import ca.emarshall.finance.core.recurring.RecurringInput
import ca.emarshall.finance.core.statements.CoveredRange
import ca.emarshall.finance.core.statements.StatementCoverage
import ca.emarshall.finance.core.statements.StatementStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CoverageInsightsGoalsTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    // ---------------------------------------------------------------- statement coverage

    @Test fun cardStatementsClosingOnThe5th() {
        val periods = listOf(
            CoveredRange(d(2025, 12, 6), d(2026, 1, 5)),
            CoveredRange(d(2026, 1, 6), d(2026, 2, 5)),
            // March statement (Feb 6 - Mar 5) missing
            CoveredRange(d(2026, 3, 6), d(2026, 4, 5)),
        )
        val exp = StatementCoverage.expected(periods, 5, today = d(2026, 5, 20))
        assertEquals(
            listOf(d(2026, 1, 5) to StatementStatus.IMPORTED, d(2026, 2, 5) to StatementStatus.IMPORTED,
                d(2026, 3, 5) to StatementStatus.MISSING, d(2026, 4, 5) to StatementStatus.IMPORTED,
                d(2026, 5, 5) to StatementStatus.MISSING),
            exp.map { it.periodEnd to it.status },
        )
        assertEquals(d(2026, 2, 6), exp[2].periodStart)
    }

    @Test fun latestStatementNotReportedBeforeItsAvailable() {
        val periods = listOf(CoveredRange(d(2026, 9, 16), d(2026, 10, 15)))
        // Next statement closes Nov 15; on Nov 14 (or Nov 17) it isn't "missing" yet.
        assertTrue(StatementCoverage.missing(periods, 15, d(2026, 11, 14)).isEmpty())
        assertTrue(StatementCoverage.missing(periods, 15, d(2026, 11, 17)).isEmpty())
        assertEquals(d(2026, 11, 15), StatementCoverage.missing(periods, 15, d(2026, 11, 18)).single().periodEnd)
    }

    @Test fun monthEndStatementsThatCloseEarlyStillCount() {
        // Real chequing statements sometimes close on the 29th or 30th instead of the last day.
        val periods = listOf(
            CoveredRange(d(2026, 8, 1), d(2026, 8, 31)),
            CoveredRange(d(2026, 9, 1), d(2026, 9, 29)),
            CoveredRange(d(2026, 10, 1), d(2026, 10, 31)),
        )
        assertEquals(31, StatementCoverage.inferClosingDay(periods.map { it.end }))
        assertTrue(StatementCoverage.missing(periods, 31, d(2026, 11, 14)).isEmpty())
    }

    @Test fun overlappingDownloadsCoverPeriods() {
        // Two CSV downloads with odd spans still cover Jan-Mar.
        val ranges = listOf(CoveredRange(d(2026, 1, 3), d(2026, 2, 20)), CoveredRange(d(2026, 2, 10), d(2026, 3, 29)))
        assertTrue(StatementCoverage.missing(ranges, 31, d(2026, 4, 2)).isEmpty())
        // A download that covers only a week of February does not count as February.
        val thin = listOf(CoveredRange(d(2026, 1, 1), d(2026, 1, 31)), CoveredRange(d(2026, 2, 20), d(2026, 2, 28)), CoveredRange(d(2026, 3, 1), d(2026, 3, 31)))
        assertEquals(listOf(d(2026, 2, 28)), StatementCoverage.missing(thin, 31, d(2026, 4, 10)).map { it.periodEnd })
    }

    @Test fun inferClosingDayFromPeriodEnds() {
        assertEquals(15, StatementCoverage.inferClosingDay(listOf(d(2026, 1, 15), d(2026, 2, 15), d(2026, 3, 16))))
        assertEquals(null, StatementCoverage.inferClosingDay(emptyList()))
    }

    // ---------------------------------------------------------------- insights

    private var id = 0L
    private fun f(date: LocalDate, cents: Long, merchant: String, key: String = "shopping", kind: CategoryKind = CategoryKind.EXPENSE, account: Long = 1) =
        TxFact(++id, account, date, cents, "CAD", key, key.replaceFirstChar { it.uppercase() }, kind, merchant, false)

    private fun analyze(facts: List<TxFact>, today: LocalDate, balances: List<AccountBalance> = emptyList()) =
        InsightEngine.analyze(
            facts,
            RecurringDetector.detect(facts.filter { it.kind == CategoryKind.EXPENSE }.map { RecurringInput(it.id, it.date, it.amountCents, it.merchant) }, today),
            balances, mapOf(1L to "Everyday", 2L to "Triangle"), today,
        )

    @Test fun flagsUnusuallyLargeChargeAndNewMerchant() {
        val today = d(2026, 6, 20)
        val facts = (1..5).map { f(d(2026, it, 3), -40_00, "Example Grocer", "groceries") } +
            f(d(2026, 6, 10), -180_00, "Example Grocer", "groceries") +
            (1..12).map { f(d(2026, 4, it), -15_00, "Cafe $it", "dining") } +
            f(d(2026, 6, 15), -900_00, "Brand New Store")
        val ins = analyze(facts, today)
        assertTrue(ins.any { it.kind == InsightKind.LARGE_CHARGE && it.title.contains("Example Grocer") })
        assertTrue(ins.any { it.kind == InsightKind.LARGE_CHARGE && it.detail.contains("Brand New Store") })
    }

    @Test fun flagsDoubleChargeButNotRecurringOrSmall() {
        val today = d(2026, 6, 20)
        val facts = listOf(
            f(d(2026, 6, 10), -89_99, "Example Electronics"), f(d(2026, 6, 11), -89_99, "Example Electronics"),
            f(d(2026, 6, 12), -4_50, "Cafe"), f(d(2026, 6, 12), -4_50, "Cafe"),
        )
        val dups = analyze(facts, today).filter { it.kind == InsightKind.POSSIBLE_DUPLICATE }
        assertEquals(1, dups.size)
        assertEquals(2, dups.single().transactionIds.size)
    }

    @Test fun subscriptionPriceIncreaseAndNewSubscription() {
        val today = d(2026, 6, 20)
        val facts = listOf(
            f(d(2026, 2, 12), -16_99, "Streamer", "subscriptions"), f(d(2026, 3, 12), -16_99, "Streamer", "subscriptions"),
            f(d(2026, 4, 12), -16_99, "Streamer", "subscriptions"), f(d(2026, 5, 12), -16_99, "Streamer", "subscriptions"),
            f(d(2026, 6, 12), -18_49, "Streamer", "subscriptions"),
            f(d(2026, 4, 2), -9_99, "Cloud Box", "subscriptions"), f(d(2026, 5, 2), -9_99, "Cloud Box", "subscriptions"),
            f(d(2026, 6, 2), -9_99, "Cloud Box", "subscriptions"),
        )
        val ins = analyze(facts, today)
        assertTrue(ins.any { it.kind == InsightKind.PRICE_INCREASE && it.title.startsWith("Streamer") })
        assertTrue(ins.any { it.kind == InsightKind.NEW_SUBSCRIPTION && it.title.contains("Cloud Box") })
    }

    @Test fun interestFeesOverdraftDeficitAndLatePay() {
        val today = d(2026, 6, 25)
        val facts = listOf(
            f(d(2026, 6, 5), -32_83, "Interest charges", "fees", account = 2),
            f(d(2026, 6, 6), -48_00, "NSF fee", "fees"),
            f(d(2026, 4, 3), 2000_00, "Payroll", "paycheque", CategoryKind.INCOME),
            f(d(2026, 4, 17), 2000_00, "Payroll", "paycheque", CategoryKind.INCOME),
            f(d(2026, 5, 1), 2000_00, "Payroll", "paycheque", CategoryKind.INCOME),
            f(d(2026, 5, 15), 1000_00, "Payroll", "paycheque", CategoryKind.INCOME),
            f(d(2026, 5, 20), -3500_00, "Landlord", "bills"),
        )
        val ins = analyze(facts, today, listOf(AccountBalance(1, "Everyday", false, -120_00, d(2026, 6, 20))))
        val kinds = ins.map { it.kind }.toSet()
        assertTrue(InsightKind.INTEREST_CHARGED in kinds)
        assertTrue(InsightKind.BANK_FEE in kinds)
        assertTrue(InsightKind.NEGATIVE_BALANCE in kinds)
        assertTrue(InsightKind.SPENT_MORE_THAN_EARNED in kinds) // May: 3,000 in, 3,500 out
        assertTrue(InsightKind.PAYCHEQUE_LATE in kinds) // every 14 days, last May 15
        assertEquals(Severity.ALERT, ins.first().severity)
        assertTrue(ins.first { it.kind == InsightKind.INTEREST_CHARGED }.title.contains("Triangle"))
    }

    @Test fun quietMonthHasNoInsights() {
        val today = d(2026, 6, 20)
        val facts = (1..6).flatMap { m ->
            listOf(f(d(2026, m, 1), 3000_00, "Payroll", "paycheque", CategoryKind.INCOME), f(d(2026, m, 5), -60_00, "Grocer", "groceries"))
        }
        assertTrue(analyze(facts, today).isEmpty())
    }

    @Test fun idsAreStableAcrossRuns() {
        val today = d(2026, 6, 20)
        val facts = listOf(f(d(2026, 6, 10), -89_99, "Shop"), f(d(2026, 6, 11), -89_99, "Shop"))
        assertEquals(analyze(facts, today).map { it.id }, analyze(facts, today).map { it.id })
    }

    // ---------------------------------------------------------------- goals

    @Test fun saveAmountGoal() {
        val facts = listOf(
            f(d(2026, 1, 1), 3000_00, "Payroll", "paycheque", CategoryKind.INCOME), f(d(2026, 1, 9), -2000_00, "Rent", "bills"),
            f(d(2026, 2, 1), 3000_00, "Payroll", "paycheque", CategoryKind.INCOME), f(d(2026, 2, 9), -2000_00, "Rent", "bills"),
        )
        val g = Goal(1, GoalType.SAVE_AMOUNT, "Emergency fund", targetCents = 6000_00, start = d(2026, 1, 1), deadline = d(2026, 7, 1))
        val p = GoalTracker.progress(g, facts, d(2026, 2, 28))
        assertEquals(33, p.percent)
        assertEquals(GoalStatus.ON_TRACK, p.status) // $2,000 saved, ~$1,900 expected by now
        assertTrue(p.headline.startsWith("$2,000 of $6,000"))
        assertEquals(GoalStatus.DONE, GoalTracker.progress(g.copy(targetCents = 1500_00), facts, d(2026, 2, 28)).status)
    }

    @Test fun spendLimitGoal() {
        val g = Goal(2, GoalType.SPEND_LIMIT, "Dining under $300", targetCents = 300_00, categoryKey = "dining", start = d(2026, 1, 1))
        val under = GoalTracker.progress(g, listOf(f(d(2026, 6, 3), -100_00, "Cafe", "dining")), d(2026, 6, 15))
        assertEquals(GoalStatus.ON_TRACK, under.status)
        val over = GoalTracker.progress(g, listOf(f(d(2026, 6, 3), -350_00, "Cafe", "dining")), d(2026, 6, 15))
        assertEquals(GoalStatus.OFF_TRACK, over.status)
        assertTrue(over.detail.startsWith("Over by $50"))
    }

    @Test fun monthlyGoalsFollowTheStatements() {
        // Nov 20: November has no transactions yet (its statements arrive in December). October is the latest month on file.
        val today = d(2026, 11, 20)
        val oct = java.time.YearMonth.of(2026, 10)
        val g = Goal(2, GoalType.SPEND_LIMIT, "Dining under $300", targetCents = 300_00, categoryKey = "dining", start = d(2026, 1, 1))
        val facts = listOf(f(d(2026, 10, 3), -350_00, "Cafe", "dining"), f(d(2026, 9, 3), -200_00, "Cafe", "dining"))
        val p = GoalTracker.progress(g, facts, today, oct)
        assertEquals(GoalStatus.OFF_TRACK, p.status)
        assertEquals("$350 of $300 in October", p.headline)
        assertTrue(p.detail.startsWith("Over by $50"))
        // Without statement coverage it would have looked at empty November and said all is well.
        assertEquals(GoalStatus.ON_TRACK, GoalTracker.progress(g, facts, today).status)
    }

    @Test fun paychequeAlertWaitsForStatements() {
        val today = d(2026, 11, 20)
        // Paid every two weeks; the chequing statement on file ends Oct 31, and the last pay in it is Oct 30.
        val pays = listOf(d(2026, 9, 4), d(2026, 9, 18), d(2026, 10, 2), d(2026, 10, 16), d(2026, 10, 30))
            .map { f(it, 2000_00, "Payroll", "paycheque", CategoryKind.INCOME) }
        val quiet = analyze(pays + f(d(2026, 10, 31), -20_00, "Grocer", "groceries"), today)
        assertFalse(quiet.any { it.kind == InsightKind.PAYCHEQUE_LATE })
        // A statement that runs three weeks past the last pay with no new one is worth a warning.
        val late = analyze(pays + f(d(2026, 11, 19), -20_00, "Grocer", "groceries"), today)
        assertTrue(late.any { it.kind == InsightKind.PAYCHEQUE_LATE })
    }

    @Test fun savingsRateGoal() {
        val facts = (3..5).flatMap { m -> listOf(f(d(2026, m, 1), 4000_00, "Payroll", "paycheque", CategoryKind.INCOME), f(d(2026, m, 5), -3000_00, "Rent", "bills")) }
        val g = Goal(3, GoalType.SAVINGS_RATE, "Save 20%", targetPercent = 20, start = d(2026, 1, 1))
        val p = GoalTracker.progress(g, facts, d(2026, 6, 10))
        assertEquals(GoalStatus.ON_TRACK, p.status)
        assertTrue(p.headline.startsWith("25%"))
        assertFalse(GoalTracker.progress(g.copy(targetPercent = 40), facts, d(2026, 6, 10)).status == GoalStatus.ON_TRACK)
    }
}
