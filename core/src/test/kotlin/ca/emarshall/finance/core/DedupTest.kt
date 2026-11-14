package ca.emarshall.finance.core

import ca.emarshall.finance.core.importing.Dedup
import ca.emarshall.finance.core.model.ParsedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

class DedupTest {
    private val d1 = LocalDate.of(2026, 3, 27)
    private val d2 = LocalDate.of(2026, 3, 28)
    private fun coffee(d: LocalDate = d1) = ParsedTransaction(d, -345, "POS PURCHASE TIM HORTONS #4410")

    @Test fun identicalSameDayRowsBothSurvive() {
        val plan = Dedup.plan(1, listOf(coffee(), coffee()), emptySet(), emptySet())
        assertEquals(2, plan.newRows.size)
        assertNotEquals(plan.rows[0].fingerprint, plan.rows[1].fingerprint)
    }

    @Test fun overlappingImportSkipsOnlyRowsAlreadyThere() {
        val first = Dedup.plan(1, listOf(coffee(), coffee(), ParsedTransaction(d2, -1000, "LUNCH")), emptySet(), emptySet())
        val existing = first.rows.map { it.fingerprint }.toSet()

        // Second file overlaps the 27th (same two coffees) and adds a third coffee and a new day.
        val second = Dedup.plan(
            1,
            listOf(coffee(), coffee(), coffee(), ParsedTransaction(d2, -1000, "LUNCH"), ParsedTransaction(d2, -2000, "DINNER")),
            existing, emptySet(),
        )
        assertEquals(3, second.duplicateCount)
        assertEquals(listOf(-345L, -2000L), second.newRows.map { it.tx.amountCents })
    }

    @Test fun descriptionNormalisationIgnoresSpacingAndCase() {
        val a = Dedup.plan(1, listOf(ParsedTransaction(d1, -100, "Tim  Hortons #44")), emptySet(), emptySet())
        val b = Dedup.plan(1, listOf(ParsedTransaction(d1, -100, "TIM HORTONS 44")), a.rows.map { it.fingerprint }.toSet(), emptySet())
        assertEquals(1, b.duplicateCount)
    }

    @Test fun fingerprintsArePerAccount() {
        val a = Dedup.plan(1, listOf(coffee()), emptySet(), emptySet())
        val b = Dedup.plan(2, listOf(coffee()), a.rows.map { it.fingerprint }.toSet(), emptySet())
        assertEquals(0, b.duplicateCount)
    }

    @Test fun fitIdMatchesEvenWhenDescriptionChanged() {
        val plan = Dedup.plan(
            1,
            listOf(ParsedTransaction(d1, -100, "renamed by bank", fitId = "F1"), ParsedTransaction(d1, -100, "other", fitId = "F2")),
            emptySet(), setOf("F1"),
        )
        assertEquals(listOf("F2"), plan.newRows.map { it.fitId })
    }
}
