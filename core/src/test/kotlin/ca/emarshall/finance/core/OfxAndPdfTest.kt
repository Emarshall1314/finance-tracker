package ca.emarshall.finance.core

import ca.emarshall.finance.core.importing.FormatDetector
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.FileFormat
import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.core.ofx.OfxParser
import ca.emarshall.finance.core.statement.StatementLayout
import ca.emarshall.finance.core.statement.StatementTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfxAndPdfTest {

    @Test fun sgmlV1BankStatement() {
        val st = OfxParser.parse(Fixtures.text("scotia_chequing_v1.qfx"))
        assertEquals(Institution.SCOTIABANK, st.hint.institution)
        assertEquals(AccountType.CHEQUING, st.hint.accountType)
        assertEquals("0123", st.hint.last4)
        assertEquals("CAD", st.hint.currency)
        // The repeated FITID row is dropped.
        assertEquals(3, st.transactions.size)
        assertTrue(st.warnings.any { it.contains("90000000002") })
        val first = st.transactions[0]
        assertEquals(LocalDate.of(2026, 3, 5), first.date)
        assertEquals(-8412L, first.amountCents)
        assertEquals("90000000001", first.fitId)
        assertEquals("SAVE ON FOODS #2231 POS PURCHASE", first.description)
        assertEquals("MB-BILL PAYMENT TRIANGLE MASTERCARD & CO", st.transactions[2].description)
        assertEquals(LocalDate.of(2026, 3, 1), st.periodStart)
        assertEquals(LocalDate.of(2026, 3, 31), st.periodEnd)
        assertEquals(291588L, st.balances.single().balanceCents)
    }

    @Test fun xmlV2CreditCardStatement() {
        val st = OfxParser.parse(Fixtures.text("ct_card_v2.ofx"))
        assertEquals(Institution.CANADIAN_TIRE, st.hint.institution)
        assertEquals(AccountType.CREDIT_CARD, st.hint.accountType)
        assertEquals("4321", st.hint.last4)
        assertEquals(listOf(-15432L, 50000L, -1199L), st.transactions.map { it.amountCents })
        assertEquals(LocalDate.of(2026, 4, 2), st.transactions[0].date)
        assertEquals("SPOTIFY P1234ABCD STOCKHOLM", st.transactions[2].description)
        assertEquals(-123456L, st.balances.single().balanceCents) // owed
    }

    @Test fun detectsFormats() {
        assertEquals(FileFormat.OFX, FormatDetector.detect("export.csv", Fixtures.bytes("scotia_chequing_v1.qfx")))
        assertEquals(FileFormat.OFX, FormatDetector.detect(null, Fixtures.bytes("ct_card_v2.ofx")))
        assertEquals(FileFormat.CSV, FormatDetector.detect("x.csv", Fixtures.bytes("ct_triangle.csv")))
        assertEquals(FileFormat.PDF, FormatDetector.detect("statement", "%PDF-1.7\n".toByteArray()))
    }

    @Test fun scotiabankChequingStatement() {
        val text = Fixtures.text("scotia_chequing_statement.txt")
        assertEquals(StatementLayout.SCOTIA_ACCOUNT, StatementTextParser.detect(text))
        val st = StatementTextParser.parse(text)
        assertEquals(emptyList<String>(), st.warnings) // reconciles: opening + rows == closing
        assertEquals(Institution.SCOTIABANK, st.hint.institution)
        assertEquals(AccountType.CHEQUING, st.hint.accountType)
        assertEquals("0099", st.hint.last4)
        assertEquals(LocalDate.of(2026, 1, 1), st.periodStart)
        assertEquals(LocalDate.of(2026, 1, 31), st.periodEnd)
        val t = st.transactions
        assertEquals(10, t.size) // the waived 0.00 fee is skipped
        assertEquals(LocalDate.of(2025, 12, 31), t[0].date) // listed before the period start: previous year
        assertEquals(-2500L, t[0].amountCents)
        assertEquals("Point of sale purchase Fpos Save ON Foods #1111 Vancouver BCCA", t[0].description)
        assertEquals(200000L, t[2].amountCents) // payroll: sign from the running balance
        // Side-box text after the balance and after the merchant is ignored.
        assertEquals(-450L, t[3].amountCents)
        assertEquals("Point of sale purchase Fpos Tim Hortons #1234 Vancouver BCCA", t[3].description)
        assertEquals("Withdrawal 12345678 Free Interac E-Transfer", t[4].description)
        assertEquals(2000L, t[7].amountCents) // error correction is money back
        assertEquals("Hydro bill B.C. Hydro & Power Authority", t[9].description)
        assertEquals(241525L, st.balances.last().balanceCents)
        assertEquals(LocalDate.of(2026, 1, 31), st.balances.last().date)
    }

    @Test fun scotiaVisaStatement() {
        val text = Fixtures.text("scotia_visa_statement.txt")
        assertEquals(StatementLayout.SCOTIA_VISA, StatementTextParser.detect(text))
        val st = StatementTextParser.parse(text)
        assertEquals(emptyList<String>(), st.warnings)
        assertEquals(Institution.SCOTIABANK, st.hint.institution)
        assertEquals(AccountType.CREDIT_CARD, st.hint.accountType)
        assertEquals("0001", st.hint.last4)
        val t = st.transactions
        assertEquals(6, t.size)
        assertEquals(LocalDate.of(2025, 12, 15), t[0].date)
        assertEquals(-4510L, t[0].amountCents)
        assertEquals("EXAMPLE HARDWARE VANCOUVER BC", t[0].description)
        assertEquals("CITY OF NORTH VANCOUVER NORTH", t[2].description)
        assertEquals(10000L, t[3].amountCents) // trailing minus = payment (money in)
        assertEquals(LocalDate.of(2026, 1, 5), t[3].date)
        assertEquals("NETFLIX.COM 844-505-2993 BC", t[4].description)
        assertEquals(-123L, t[5].amountCents)
        assertEquals(-18566L, st.balances.single().balanceCents)
    }

    @Test fun triangleStatementIgnoresRightColumnAndStoreItems() {
        val text = Fixtures.text("triangle_statement.txt")
        assertEquals(StatementLayout.TRIANGLE, StatementTextParser.detect(text))
        val st = StatementTextParser.parse(text)
        assertEquals(emptyList<String>(), st.warnings)
        assertEquals(Institution.CANADIAN_TIRE, st.hint.institution)
        assertEquals("0002", st.hint.last4)
        assertEquals(LocalDate.of(2026, 10, 6), st.periodStart)
        assertEquals(LocalDate.of(2026, 11, 5), st.periodEnd)
        val t = st.transactions
        assertEquals(listOf(5000L, 1000L, -6000L, -389L, -488L, -2500L, -111L), t.map { it.amountCents })
        assertEquals("CTFS.COM/PAYMENTS OAKVILLE ON", t[0].description)
        assertEquals("PETRO-CANADA 12345 VANCOUVER BC", t[2].description)
        assertEquals("EXAMPLE.COM PARIS IDF", t[4].description)
        assertEquals(-8488L, st.balances.single().balanceCents)
    }

    @Test fun missingRowsAreFlagged() {
        val text = Fixtures.text("triangle_statement.txt").lines().filterNot { it.startsWith("Oct 09") }.joinToString("\n")
        val st = StatementTextParser.parse(text)
        assertTrue(st.warnings.any { it.startsWith("Totals don't reconcile") })
    }
}
