package ca.emarshall.finance.core

import ca.emarshall.finance.core.categorize.AmountRule
import ca.emarshall.finance.core.categorize.Categorizer
import ca.emarshall.finance.core.categorize.SeedRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AmountRuleTest {
    private val rent = AmountRule(1, "Rent", 180_000, "id:99")

    @Test fun exactAmountOutMatches() {
        assertTrue(rent.matches(-180_000, LocalDate.of(2026, 10, 1)))
        assertFalse(rent.matches(-180_001, LocalDate.of(2026, 10, 1)))
        assertFalse(rent.matches(180_000, LocalDate.of(2026, 10, 1))) // money in is never rent
        assertTrue(rent.copy(toleranceCents = 500).matches(-180_400, null))
    }

    @Test fun dayOfMonthWrapsAcrossMonthEnds() {
        val first = rent.copy(dayOfMonth = 1)
        assertTrue(first.matches(-180_000, LocalDate.of(2026, 12, 30)))
        assertTrue(first.matches(-180_000, LocalDate.of(2026, 10, 5)))
        assertFalse(first.matches(-180_000, LocalDate.of(2026, 10, 15)))
        // Due on the 31st in a 30-day month means the 30th.
        assertTrue(rent.copy(dayOfMonth = 31, dayWindow = 0).matches(-180_000, LocalDate.of(2026, 11, 30)))
    }

    @Test fun beatsTextRulesButNotTransfers() {
        val c = Categorizer(SeedRules.ALL, listOf(rent))
        val day = LocalDate.of(2026, 10, 1)
        // Rent paid by e-Transfer would otherwise be "e-Transfers Sent".
        assertEquals("id:99", c.categorize("INTERAC E-TRANSFER SENT J SMITH", "J Smith", -180_000, day))
        // A Questrade deposit of the same amount stays a transfer.
        assertEquals("investments_transfer", c.categorize("PREAUTHORIZED DEBIT QUESTRADE", "Questrade", -180_000, day))
        assertNull(c.amountRuleFor("PREAUTHORIZED DEBIT QUESTRADE", "Questrade", -180_000, day))
        // Other amounts are untouched; investment accounts skip amount rules.
        assertEquals("etransfer_sent", c.categorize("INTERAC E-TRANSFER SENT J SMITH", "J Smith", -5_000, day))
        assertEquals("etransfer_sent", c.categorize("INTERAC E-TRANSFER SENT J SMITH", "J Smith", -180_000, day, useAmountRules = false))
    }

    @Test fun closestAmountWins() {
        val parking = AmountRule(2, "Parking", 180_200, "id:7", toleranceCents = 500)
        val c = Categorizer(emptyList(), listOf(parking, rent.copy(toleranceCents = 500)))
        assertEquals("id:99", c.categorize("X", "X", -180_000))
        assertEquals("id:7", c.categorize("X", "X", -180_250))
    }
}
