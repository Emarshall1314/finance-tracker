package ca.emarshall.finance.core.csv

import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.core.money.Money

/**
 * Guesses a [CsvProfile] from the file itself. The bank presets are header-name based, because the
 * banks have changed their layouts over the years; the column-mapping screen covers anything else.
 *
 * NOTE: Scotiabank and Canadian Tire layouts below are based on publicly reported formats and must be
 * checked against real exports (see docs/IMPORT_FORMATS.md).
 */
object CsvProfileInference {

    private val DATE = listOf("date", "transaction date", "trans date", "trans. date", "posted date", "posting date", "date posted")
    private val DESC = listOf("description", "merchant", "merchant name", "payee", "details", "transaction description", "name", "transaction")
    private val SUB_DESC = listOf("sub-description", "sub description", "memo", "additional info")
    private val AMOUNT = listOf("amount", "transaction amount", "amount ($)", "amount (cad)", "cad amount", "amount cad")
    private val DEBIT = listOf("debit", "debits", "withdrawal", "withdrawals", "money out", "withdrawals ($)", "debit amount", "charges")
    private val CREDIT = listOf("credit", "credits", "deposit", "deposits", "money in", "deposits ($)", "credit amount", "payments")
    private val TYPE = listOf("type of transaction", "transaction type", "type", "debit/credit", "dr/cr")
    private val BALANCE = listOf("balance", "running balance", "balance ($)")

    fun infer(text: String, isCreditCard: Boolean = false): InferredProfile? {
        val delimiter = CsvReader.detectDelimiter(text)
        val rows = CsvReader.read(text, delimiter)
        if (rows.isEmpty()) return null

        // Find the header: the first of the first 10 rows that contains a date-like and an amount-like name.
        val headerIdx = rows.take(10).indexOfFirst { r ->
            val n = r.map { norm(it) }
            n.any { it in DATE } && (n.any { it in AMOUNT } || n.any { it in DEBIT } || n.any { it in CREDIT })
        }
        return if (headerIdx >= 0) fromHeader(rows, headerIdx, delimiter, isCreditCard) else headerless(rows, delimiter, isCreditCard)
    }

    private fun fromHeader(rows: List<List<String>>, headerIdx: Int, delimiter: Char, isCreditCard: Boolean): InferredProfile? {
        val header = rows[headerIdx]
        val n = header.map { norm(it) }
        fun find(names: List<String>): Int? = names.firstNotNullOfOrNull { name -> n.indexOf(name).takeIf { it >= 0 } }

        // Prefer the transaction date over the posted date when both are present.
        val dateCol = find(listOf("transaction date", "trans date", "trans. date", "date")) ?: find(DATE) ?: return null
        val descCol = find(DESC) ?: return null
        val subDescCol = find(SUB_DESC)
        val amountCol = find(AMOUNT)
        val debitCol = find(DEBIT)
        val creditCol = find(CREDIT)
        val typeCol = find(TYPE)
        val balanceCol = find(BALANCE)
        val data = rows.drop(headerIdx + 1)
        val notes = ArrayList<String>()

        val sign: SignConvention = when {
            debitCol != null && creditCol != null -> SignConvention.DEBIT_CREDIT
            amountCol != null && typeCol != null && typeLooksLikeDebitCredit(data, typeCol) && !hasNegatives(data, amountCol) ->
                SignConvention.TYPE_COLUMN
            amountCol != null && isCreditCard && mostlyPositive(data, amountCol) -> {
                notes.add("Most amounts are positive, so positive is treated as a purchase (money out).")
                SignConvention.AMOUNT_INVERTED
            }
            amountCol != null -> SignConvention.AMOUNT_AS_IS
            else -> return null
        }

        val dateFormats = CsvDates.matchingFormats(data.mapNotNull { it.getOrNull(dateCol) }.take(200))
        if (dateFormats.isEmpty()) notes.add("Couldn't recognise the date format; pick it on the mapping screen.")
        if (dateFormats.size > 1) notes.add("Dates are ambiguous (${dateFormats.joinToString(" or ")}); please confirm.")

        val institution = when {
            n.contains("sub-description") || n.contains("type of transaction") -> Institution.SCOTIABANK
            n.contains("posted date") && n.contains("transaction date") && (n.contains("ref") || n.contains("reference")) -> Institution.CANADIAN_TIRE
            else -> null
        }

        val profile = CsvProfile(
            delimiter = delimiter,
            hasHeader = true,
            skipRows = headerIdx,
            dateFormat = dateFormats.firstOrNull() ?: "yyyy-MM-dd",
            dateColumn = dateCol,
            descriptionColumns = listOfNotNull(descCol, subDescCol),
            signConvention = sign,
            amountColumn = if (sign == SignConvention.DEBIT_CREDIT) null else amountCol,
            debitColumn = if (sign == SignConvention.DEBIT_CREDIT) debitCol else null,
            creditColumn = if (sign == SignConvention.DEBIT_CREDIT) creditCol else null,
            typeColumn = if (sign == SignConvention.TYPE_COLUMN) typeCol else null,
            balanceColumn = balanceCol,
            balanceIsOwed = isCreditCard,
        )
        return InferredProfile(profile, institution, dateFormats, header, notes)
    }

    /**
     * Scotiabank's older headerless layout: `date, amount, "-", type, description`, amount already signed.
     * Generic fallback for other headerless files: first date-like column, first amount-like column, longest text column.
     */
    private fun headerless(rows: List<List<String>>, delimiter: Char, isCreditCard: Boolean): InferredProfile? {
        val sample = rows.take(50)
        val width = sample.maxOf { it.size }
        val dateCol = (0 until width).firstOrNull { c -> CsvDates.matchingFormats(sample.mapNotNull { it.getOrNull(c) }).isNotEmpty() }
            ?: return null
        val amountCol = (0 until width).firstOrNull { c -> c != dateCol && sample.all { parsesAsMoney(it.getOrNull(c)) } }
            ?: return null
        val descCol = (0 until width).filter { it != dateCol && it != amountCol }
            .maxByOrNull { c -> sample.sumOf { it.getOrNull(c)?.length ?: 0 } } ?: return null

        val isScotiaLegacy = width == 5 && dateCol == 0 && amountCol == 1 && sample.all { it.getOrNull(2) == "-" }
        val descCols = if (isScotiaLegacy) listOf(3, 4) else listOf(descCol)
        val formats = CsvDates.matchingFormats(sample.map { it[dateCol] })
        val notes = ArrayList<String>()
        if (formats.size > 1) notes.add("Dates are ambiguous (${formats.joinToString(" or ")}); please confirm.")
        val sign = if (isCreditCard && mostlyPositive(rows, amountCol)) SignConvention.AMOUNT_INVERTED else SignConvention.AMOUNT_AS_IS
        val profile = CsvProfile(
            delimiter = delimiter,
            hasHeader = false,
            dateFormat = formats.first(),
            dateColumn = dateCol,
            descriptionColumns = descCols,
            signConvention = sign,
            amountColumn = amountCol,
        )
        return InferredProfile(profile, if (isScotiaLegacy) Institution.SCOTIABANK else null, formats, null, notes)
    }

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun parsesAsMoney(s: String?): Boolean =
        s != null && s.any { it.isDigit() } && runCatching { Money.parse(s) }.getOrNull() != null

    private fun hasNegatives(rows: List<List<String>>, col: Int) =
        rows.any { r -> runCatching { Money.parse(r.getOrNull(col)) }.getOrNull()?.let { it < 0 } == true }

    private fun mostlyPositive(rows: List<List<String>>, col: Int): Boolean {
        val vals = rows.mapNotNull { r -> runCatching { Money.parse(r.getOrNull(col)) }.getOrNull() }
        return vals.isNotEmpty() && vals.count { it > 0 } * 2 > vals.size
    }

    private fun typeLooksLikeDebitCredit(rows: List<List<String>>, col: Int): Boolean {
        val values = rows.mapNotNull { it.getOrNull(col)?.trim()?.lowercase() }.filter { it.isNotEmpty() }.toSet()
        return values.isNotEmpty() && values.all { it in setOf("debit", "credit", "dr", "cr", "withdrawal", "deposit") }
    }
}
