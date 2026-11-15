package ca.emarshall.finance.core

import ca.emarshall.finance.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {
    @Test fun parsesPlainAndSigned() {
        assertEquals(123456L, Money.parse("1234.56"))
        assertEquals(-123456L, Money.parse("-1,234.56"))
        assertEquals(500L, Money.parse("+5.00"))
        assertEquals(500L, Money.parse("5"))
        assertEquals(50L, Money.parse(".5"))
    }

    @Test fun parsesCurrencySymbolsAndSeparators() {
        assertEquals(123456L, Money.parse("$1,234.56"))
        assertEquals(-500L, Money.parse("-$5"))
        assertEquals(-500L, Money.parse("$-5.00"))
        assertEquals(100000L, Money.parse("1,000.00 CAD"))
    }

    @Test fun parsesParenthesesAndCrDr() {
        assertEquals(-1234L, Money.parse("(12.34)"))
        assertEquals(-123456L, Money.parse("($1,234.56)"))
        assertEquals(1234L, Money.parse("12.34 CR"))
        assertEquals(-1234L, Money.parse("12.34DR"))
        assertEquals(-1234L, Money.parse("12.34-"))
    }

    @Test fun blanksAreNull() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("   "))
        assertNull(Money.parse(null))
        assertNull(Money.parse("-"))
    }

    @Test(expected = NumberFormatException::class)
    fun rejectsText() {
        Money.parse("twelve")
    }

    @Test fun noFloatingPointDrift() {
        // 0.1 + 0.2 style values must stay exact.
        assertEquals(30L, Money.parse("0.1")!! + Money.parse("0.2")!!)
        assertEquals(1999999999999L, Money.parse("19,999,999,999.99"))
        assertEquals(1L, Money.toCents(BigDecimal("0.005")))
    }

    @Test fun formats() {
        assertEquals("$1,234.50", Money.format(123450))
        assertEquals("-$0.05", Money.format(-5))
        assertEquals("$12.00 USD", Money.format(1200, "USD", showCurrency = true))
        assertEquals("$1,235", Money.formatWhole(123450))
    }

    @Test fun amountSearchRanges() {
        val r = ca.emarshall.finance.core.money.AmountSearch
        org.junit.Assert.assertEquals(5000L to 5099L, r.range("50"))
        org.junit.Assert.assertEquals(5020L to 5029L, r.range("50.2"))
        org.junit.Assert.assertEquals(5025L to 5025L, r.range(" $50.25 "))
        org.junit.Assert.assertEquals(180000L to 180099L, r.range("1,800"))
        org.junit.Assert.assertEquals(5000L to 5099L, r.range("50."))
        org.junit.Assert.assertNull(r.range("costco"))
        org.junit.Assert.assertNull(r.range("7-eleven"))
        org.junit.Assert.assertNull(r.range("-50"))
        org.junit.Assert.assertNull(r.range(""))
    }
}
