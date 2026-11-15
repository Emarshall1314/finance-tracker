package ca.emarshall.finance.ui

import ca.emarshall.finance.ui.design.spreadLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartLayoutTest {
    @Test fun labelsStayBesideTheirBarsWhenThereIsRoom() {
        assertEquals(listOf(11f, 91f, 171f), spreadLabels(listOf(20f, 100f, 180f), line = 18f, height = 200f))
    }

    @Test fun crowdedLabelsAreNudgedApartInOrderAndStayInside() {
        // Three thin bars at the very bottom of a 200px chart.
        val tops = spreadLabels(listOf(10f, 190f, 194f, 198f), line = 18f, height = 200f)
        assertEquals(4, tops.size)
        for (i in 1 until tops.size) assertTrue("overlap at $i: $tops", tops[i] - tops[i - 1] >= 18f - 0.001f)
        assertTrue(tops.first() >= 0f)
        assertTrue(tops.last() <= 200f - 18f + 0.001f)
        assertEquals(1f, tops[0], 0.001f) // the one with room hasn't moved
    }
}
