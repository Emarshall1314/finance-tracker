package ca.emarshall.finance.core

import ca.emarshall.finance.core.statement.StatementTextParser
import ca.emarshall.finance.core.statements.CoveredRange
import ca.emarshall.finance.core.statements.StatementCoverage
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in check against real statements, which never enter git. Extract the PDFs to text with PdfBox
 * (sortByPosition = true; see docs/IMPORT_FORMATS.md), then run:
 *
 *     STATEMENT_TEXT_DIR=/path/to/texts ./gradlew :core:test --tests '*RealStatementsCheck*'
 *
 * It prints only counts and warnings (no amounts, merchants or account numbers) and fails if any
 * statement doesn't reconcile against its own opening/closing totals.
 */
class RealStatementsCheck {
    @Test fun everyRealStatementReconciles() {
        val dir = System.getenv("STATEMENT_TEXT_DIR")?.let(::File)
        assumeTrue("STATEMENT_TEXT_DIR not set", dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }
        var failures = 0
        for (f in files) {
            val text = f.readText()
            val layout = StatementTextParser.detect(text)
            val st = StatementTextParser.parse(text, layout)
            val ok = st.warnings.isEmpty() && st.transactions.isNotEmpty() && st.periodEnd != null
            if (!ok) failures++
            println(
                "${if (ok) "OK  " else "FAIL"} ${f.name.take(12)} $layout rows=${st.transactions.size} " +
                    "period=${st.periodStart}..${st.periodEnd} last4=${st.hint.last4 != null} " +
                    "inPeriodOrJustBefore=${st.transactions.all { t -> st.periodEnd == null || !t.date.isAfter(st.periodEnd!!.plusDays(7)) }} " +
                    st.warnings.joinToString(" | "),
            )
        }
        assertTrue("$failures of ${files.size} statements didn't parse cleanly", failures == 0)

        // Missing-statement check per account (layout + last 4), as of a date given or today.
        val today = System.getenv("STATEMENT_CHECK_DATE")?.let(LocalDate::parse) ?: LocalDate.now()
        files.map { StatementTextParser.parse(it.readText()) }
            .groupBy { "${it.hint.institution}/${it.hint.accountType}/${it.hint.last4}" }
            .forEach { (account, sts) ->
                val ranges = sts.mapNotNull { st -> if (st.periodStart != null && st.periodEnd != null) CoveredRange(st.periodStart!!, st.periodEnd!!) else null }
                val closing = StatementCoverage.inferClosingDay(ranges.map { it.end }) ?: 31
                val missing = StatementCoverage.missing(ranges, closing, today)
                println("COVERAGE ${account.substringBeforeLast('/')} statements=${sts.size} closingDay=$closing missing=${missing.map { it.periodEnd }}")
            }
    }
}
