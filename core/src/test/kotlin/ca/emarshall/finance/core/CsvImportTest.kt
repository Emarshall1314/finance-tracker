package ca.emarshall.finance.core

import ca.emarshall.finance.core.csv.CsvDates
import ca.emarshall.finance.core.csv.CsvProfile
import ca.emarshall.finance.core.csv.CsvProfileInference
import ca.emarshall.finance.core.csv.CsvReader
import ca.emarshall.finance.core.csv.CsvStatementParser
import ca.emarshall.finance.core.csv.SignConvention
import ca.emarshall.finance.core.model.Institution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CsvImportTest {

    @Test fun readerHandlesQuotesBomAndBlankLines() {
        val rows = CsvReader.read("﻿a,\"b, with comma\",\"say \"\"hi\"\"\"\r\n\r\n1,\"multi\nline\",3\n\n\n")
        assertEquals(listOf(listOf("a", "b, with comma", "say \"hi\""), listOf("1", "multi\nline", "3")), rows)
    }

    @Test fun delimiterDetection() {
        assertEquals(';', CsvReader.detectDelimiter("a;b;c\n1;\"x;y\";3"))
        assertEquals('\t', CsvReader.detectDelimiter("a\tb\n1\t2"))
        assertEquals(',', CsvReader.detectDelimiter("a,b\n1,2"))
    }

    @Test fun scotiabankDebitCreditColumns() {
        val text = Fixtures.text("scotia_chequing_debit_credit.csv")
        val inferred = CsvProfileInference.infer(text)!!
        assertEquals(Institution.SCOTIABANK, inferred.institution)
        assertEquals(SignConvention.DEBIT_CREDIT, inferred.profile.signConvention)
        assertEquals("yyyy-MM-dd", inferred.profile.dateFormat)

        val st = CsvStatementParser.parse(text, inferred.profile)
        assertEquals(8, st.transactions.size)
        assertTrue(st.warnings.isEmpty())
        val first = st.transactions.first()
        assertEquals(LocalDate.of(2026, 3, 31), first.date)
        assertEquals(-8412L, first.amountCents)
        assertEquals("POS PURCHASE SAVE ON FOODS #2231 VANCOUVER BC", first.description)
        assertEquals(245000L, st.transactions.single { it.description.startsWith("PAYROLL") }.amountCents)
        assertEquals("PRE-AUTHORIZED PAYMENT BC HYDRO, ELECTRIC", st.transactions[5].description)
        // Newest-first file: the end-of-day balance on the 27th is the first 27th row.
        assertEquals(105000L, st.balances.single { it.date == LocalDate.of(2026, 3, 27) }.balanceCents)
        assertEquals(291588L, st.balances.last().balanceCents)
        assertEquals(LocalDate.of(2026, 3, 15), st.dateFrom)
    }

    @Test fun scotiabankTypeColumn() {
        val text = Fixtures.text("scotia_chequing_type.csv")
        val inferred = CsvProfileInference.infer(text)!!
        assertEquals(Institution.SCOTIABANK, inferred.institution)
        assertEquals(SignConvention.TYPE_COLUMN, inferred.profile.signConvention)
        val st = CsvStatementParser.parse(text, inferred.profile)
        assertEquals(listOf(-1699L, 245000L, -50000L, -6120L), st.transactions.map { it.amountCents })
        assertEquals("Point of sale purchase NETFLIX.COM, 866-579-7172", st.transactions[0].description)
        assertEquals(-122967L, st.balances.first().balanceCents) // end of Apr 1: the later (first-listed) row
    }

    @Test fun scotiabankLegacyHeaderless() {
        val text = Fixtures.text("scotia_legacy.csv")
        val inferred = CsvProfileInference.infer(text)!!
        assertEquals(Institution.SCOTIABANK, inferred.institution)
        assertEquals(false, inferred.profile.hasHeader)
        val st = CsvStatementParser.parse(text, inferred.profile)
        assertEquals(4, st.transactions.size)
        assertEquals(-4567L, st.transactions[0].amountCents)
        assertEquals("POS PURCHASE SAVE ON FOODS #2231", st.transactions[0].description)
        assertEquals(LocalDate.of(2026, 1, 15), st.transactions[0].date)
    }

    @Test fun canadianTireCardPurchasesBecomeNegative() {
        val text = Fixtures.text("ct_triangle.csv")
        val inferred = CsvProfileInference.infer(text, isCreditCard = true)!!
        assertEquals(Institution.CANADIAN_TIRE, inferred.institution)
        assertEquals(SignConvention.AMOUNT_INVERTED, inferred.profile.signConvention)
        assertEquals(listOf("M/d/yyyy"), inferred.dateFormatOptions)
        val st = CsvStatementParser.parse(text, inferred.profile)
        val byDesc = st.transactions.associateBy { it.description }
        assertEquals(-15432L, byDesc.getValue("COSTCO WHOLESALE W548, BURNABY BC").amountCents) // purchase
        assertEquals(50000L, byDesc.getValue("PAYMENT - THANK YOU").amountCents) // payment
        assertEquals(2000L, byDesc.getValue("COSTCO WHOLESALE W548 REFUND").amountCents) // refund
        assertEquals(-456L, byDesc.getValue("PURCHASE INTEREST").amountCents) // interest charge
        assertEquals(LocalDate.of(2026, 3, 2), byDesc.getValue("COSTCO WHOLESALE W548, BURNABY BC").date) // transaction, not posted, date
    }

    @Test fun messyFile() {
        val text = Fixtures.text("messy.csv")
        val inferred = CsvProfileInference.infer(text)!!
        assertEquals(';', inferred.profile.delimiter)
        assertEquals(listOf("d/M/yyyy"), inferred.dateFormatOptions)
        val st = CsvStatementParser.parse(text, inferred.profile)
        assertEquals(listOf(-123456L, 4500L, -6000L, -190000L, 42L), st.transactions.map { it.amountCents })
        assertEquals("Groceries; \"Fresh St Market\", West Van", st.transactions[0].description)
        assertEquals(LocalDate.of(2026, 4, 3), st.transactions[0].date)
    }

    @Test fun ambiguousDatesAreReported() {
        val formats = CsvDates.matchingFormats(listOf("03/04/2026", "05/06/2026"))
        assertTrue("M/d/yyyy" in formats && "d/M/yyyy" in formats)
        val inferred = CsvProfileInference.infer("Date,Description,Amount\n03/04/2026,X,-1.00\n05/06/2026,Y,-2.00\n")!!
        assertTrue(inferred.notes.any { it.contains("ambiguous") })
    }

    @Test fun twoDigitYearsAndMonthNames() {
        assertEquals(LocalDate.of(2026, 3, 4), CsvDates.parse("04-MAR-26", "dd-MMM-yy"))
        assertEquals(LocalDate.of(2026, 3, 4), CsvDates.parse("Mar 4, 2026", "MMM d, yyyy"))
    }

    @Test fun badRowsBecomeWarnings() {
        val p = CsvProfile(dateColumn = 0, descriptionColumns = listOf(1), signConvention = SignConvention.AMOUNT_AS_IS, amountColumn = 2)
        val st = CsvStatementParser.parse("Date,Desc,Amount\n2026-01-01,ok,-1.00\nnot-a-date,bad,-2.00\n2026-01-02,bad amount,abc\n", p)
        assertEquals(1, st.transactions.size)
        assertEquals(2, st.warnings.size)
    }

    @Test fun profileRoundTrips() {
        val p = CsvProfile(
            delimiter = ';', hasHeader = true, skipRows = 2, dateFormat = "d/M/yyyy", dateColumn = 1,
            descriptionColumns = listOf(2, 3), signConvention = SignConvention.DEBIT_CREDIT, debitColumn = 4,
            creditColumn = 5, balanceColumn = 6, balanceIsOwed = true,
        )
        assertEquals(p, CsvProfile.decode(p.encode()))
    }
}
