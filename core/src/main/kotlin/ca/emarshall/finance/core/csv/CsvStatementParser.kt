package ca.emarshall.finance.core.csv

import ca.emarshall.finance.core.model.AccountHint
import ca.emarshall.finance.core.model.FileFormat
import ca.emarshall.finance.core.model.ParsedBalance
import ca.emarshall.finance.core.model.ParsedStatement
import ca.emarshall.finance.core.model.ParsedTransaction
import ca.emarshall.finance.core.money.Money

object CsvStatementParser {

    private val DEBIT_WORDS = setOf("debit", "dr", "withdrawal", "withdrawals", "purchase", "payment out", "-")

    /** Applies [profile] to [text]. Rows that can't be read become warnings instead of failing the file. */
    fun parse(text: String, profile: CsvProfile, hint: AccountHint = AccountHint()): ParsedStatement {
        val rows = CsvReader.read(text, profile.delimiter).drop(profile.skipRows)
        val data = if (profile.hasHeader) rows.drop(1) else rows
        val txs = ArrayList<ParsedTransaction>()
        val warnings = ArrayList<String>()
        data.forEachIndexed { idx, row ->
            val lineNo = idx + 1 + profile.skipRows + (if (profile.hasHeader) 1 else 0)
            try {
                parseRow(row, profile)?.let { txs.add(it) }
            } catch (e: Exception) {
                warnings.add("Row $lineNo skipped: ${e.message}")
            }
        }
        val balances = latestBalancePerDate(txs)
        return ParsedStatement(
            format = FileFormat.CSV,
            transactions = txs,
            balances = balances,
            hint = hint.copy(currency = hint.currency ?: profile.currency),
            warnings = warnings,
        )
    }

    private fun parseRow(row: List<String>, p: CsvProfile): ParsedTransaction? {
        fun cell(i: Int?): String? = i?.let { row.getOrNull(it) }?.trim()

        val dateText = cell(p.dateColumn)
        if (dateText.isNullOrBlank()) return null // summary / footer rows
        val date = CsvDates.parse(dateText, p.dateFormat)
            ?: throw IllegalArgumentException("date '$dateText' doesn't match ${p.dateFormat}")

        val amount: Long = when (p.signConvention) {
            SignConvention.AMOUNT_AS_IS -> Money.parse(cell(p.amountColumn)) ?: throw IllegalArgumentException("no amount")
            SignConvention.AMOUNT_INVERTED -> -(Money.parse(cell(p.amountColumn)) ?: throw IllegalArgumentException("no amount"))
            SignConvention.DEBIT_CREDIT -> {
                val debit = Money.parse(cell(p.debitColumn))
                val credit = Money.parse(cell(p.creditColumn))
                if (debit == null && credit == null) throw IllegalArgumentException("no debit or credit")
                Math.abs(credit ?: 0) - Math.abs(debit ?: 0)
            }
            SignConvention.TYPE_COLUMN -> {
                val abs = Math.abs(Money.parse(cell(p.amountColumn)) ?: throw IllegalArgumentException("no amount"))
                val type = cell(p.typeColumn).orEmpty().lowercase()
                if (type in DEBIT_WORDS || type.startsWith("debit") || type.startsWith("withdraw")) -abs else abs
            }
        }

        val description = p.descriptionColumns.mapNotNull { cell(it) }
            .filter { it.isNotBlank() && it != "-" }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val balance = Money.parse(cell(p.balanceColumn))?.let { if (p.balanceIsOwed) -it else it }
        return ParsedTransaction(date, amount, description, balanceCents = balance, currency = p.currency)
    }

    /**
     * End-of-day balance for each date that has a running balance. Files list rows newest-first or
     * oldest-first, so take the row that is last in chronological order within the day: when the file is
     * newest-first, that's the first row for the date.
     */
    private fun latestBalancePerDate(txs: List<ParsedTransaction>): List<ParsedBalance> {
        val withBal = txs.filter { it.balanceCents != null }
        if (withBal.isEmpty()) return emptyList()
        val newestFirst = txs.size > 1 && txs.first().date > txs.last().date
        return withBal.groupBy { it.date }.map { (date, rows) ->
            ParsedBalance(date, (if (newestFirst) rows.first() else rows.last()).balanceCents!!)
        }.sortedBy { it.date }
    }
}
