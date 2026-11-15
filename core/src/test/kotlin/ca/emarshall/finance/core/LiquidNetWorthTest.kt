package ca.emarshall.finance.core

import ca.emarshall.finance.core.analytics.AccountPosition
import ca.emarshall.finance.core.analytics.LiquidNetWorth
import ca.emarshall.finance.core.analytics.LiquidityBucket
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class LiquidNetWorthTest {
    private val d = LocalDate.of(2026, 11, 1)
    private fun p(id: Long, inst: Institution, type: AccountType, cents: Long) = AccountPosition(id, "A$id", inst, type, cents, d)

    @Test fun liquidExcludesRegisteredRetirementAndSubtractsDebt() {
        val b = LiquidNetWorth.breakdown(
            listOf(
                p(1, Institution.SCOTIABANK, AccountType.CHEQUING, 5_000_00),
                p(2, Institution.SCOTIABANK, AccountType.CREDIT_CARD, -400_00),
                p(3, Institution.CANADIAN_TIRE, AccountType.CREDIT_CARD, -100_00),
                p(4, Institution.QUESTRADE, AccountType.TFSA, 20_000_00),
                p(5, Institution.QUESTRADE, AccountType.RRSP, 30_000_00),
                p(6, Institution.QUESTRADE, AccountType.FHSA, 8_000_00),
            ),
        )
        assertEquals(5_000_00L, b.cashCents)
        assertEquals(20_000_00L, b.investmentsCents)
        assertEquals(-500_00L, b.debtCents)
        assertEquals(38_000_00L, b.longTermCents)
        assertEquals(24_500_00L, b.liquidCents)
        assertEquals(62_500_00L, b.totalCents)
        assertEquals(listOf(3L, 2L).reversed(), b.bucket(LiquidityBucket.DEBT).map { it.position.accountId })
        assertEquals(58_000_00L, b.byInstitution()[Institution.QUESTRADE])
    }

    @Test fun empty() {
        assertEquals(0L, LiquidNetWorth.breakdown(emptyList()).liquidCents)
    }
}
