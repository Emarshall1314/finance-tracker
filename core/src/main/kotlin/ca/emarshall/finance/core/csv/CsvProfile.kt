package ca.emarshall.finance.core.csv

import ca.emarshall.finance.core.model.Institution
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/** How the sign of each row is worked out. Whatever the file does, the result is: money out is negative. */
enum class SignConvention {
    /** One amount column, already negative for money out. */
    AMOUNT_AS_IS,

    /** One amount column, positive for money out (common in credit card exports). */
    AMOUNT_INVERTED,

    /** Separate debit (money out) and credit (money in) columns. */
    DEBIT_CREDIT,

    /** One unsigned amount column plus a column saying Debit/Credit. */
    TYPE_COLUMN,
}

/** Column mapping for one account's CSV exports. Column indexes are zero-based. */
data class CsvProfile(
    val delimiter: Char = ',',
    val hasHeader: Boolean = true,
    /** Rows to skip before the header (or before the data when there is no header). */
    val skipRows: Int = 0,
    val dateFormat: String = "yyyy-MM-dd",
    val dateColumn: Int,
    /** Joined with " " to make the raw description (e.g. Description + Sub-description). */
    val descriptionColumns: List<Int>,
    val signConvention: SignConvention,
    val amountColumn: Int? = null,
    val debitColumn: Int? = null,
    val creditColumn: Int? = null,
    val typeColumn: Int? = null,
    val balanceColumn: Int? = null,
    /** True when the balance column is positive for money owed (credit cards). */
    val balanceIsOwed: Boolean = false,
    val currency: String = "CAD",
) {
    init {
        when (signConvention) {
            SignConvention.AMOUNT_AS_IS, SignConvention.AMOUNT_INVERTED ->
                require(amountColumn != null) { "amountColumn required for $signConvention" }
            SignConvention.DEBIT_CREDIT ->
                require(debitColumn != null && creditColumn != null) { "debit and credit columns required" }
            SignConvention.TYPE_COLUMN ->
                require(amountColumn != null && typeColumn != null) { "amount and type columns required" }
        }
        require(descriptionColumns.isNotEmpty()) { "at least one description column" }
    }

    /** Short machine-friendly form for storage: key=value pairs separated by `;`. */
    fun encode(): String = buildList {
        add("delim=${delimiter.code}")
        add("header=$hasHeader")
        add("skip=$skipRows")
        add("date=$dateFormat")
        add("dateCol=$dateColumn")
        add("descCols=${descriptionColumns.joinToString(",")}")
        add("sign=$signConvention")
        amountColumn?.let { add("amount=$it") }
        debitColumn?.let { add("debit=$it") }
        creditColumn?.let { add("credit=$it") }
        typeColumn?.let { add("type=$it") }
        balanceColumn?.let { add("balance=$it") }
        add("owed=$balanceIsOwed")
        add("cur=$currency")
    }.joinToString(";")

    companion object {
        fun decode(s: String): CsvProfile {
            val m = s.split(";").filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
            return CsvProfile(
                delimiter = m.getValue("delim").toInt().toChar(),
                hasHeader = m.getValue("header").toBoolean(),
                skipRows = m["skip"]?.toInt() ?: 0,
                dateFormat = m.getValue("date"),
                dateColumn = m.getValue("dateCol").toInt(),
                descriptionColumns = m.getValue("descCols").split(",").map { it.toInt() },
                signConvention = SignConvention.valueOf(m.getValue("sign")),
                amountColumn = m["amount"]?.toInt(),
                debitColumn = m["debit"]?.toInt(),
                creditColumn = m["credit"]?.toInt(),
                typeColumn = m["type"]?.toInt(),
                balanceColumn = m["balance"]?.toInt(),
                balanceIsOwed = m["owed"]?.toBoolean() ?: false,
                currency = m["cur"] ?: "CAD",
            )
        }
    }
}

object CsvDates {
    /** Formats we try, most likely first for Canadian bank exports. */
    val CANDIDATES = listOf(
        "yyyy-MM-dd", "M/d/yyyy", "d/M/yyyy", "yyyy/MM/dd", "M/d/yy", "d/M/yy",
        "MMM d, yyyy", "d MMM yyyy", "dd-MMM-yy", "dd-MMM-yyyy", "MMM d yyyy", "yyyyMMdd",
        "M-d-yyyy", "d-M-yyyy",
    )

    private val cache = HashMap<String, DateTimeFormatter>()

    fun formatter(pattern: String): DateTimeFormatter = synchronized(cache) {
        cache.getOrPut(pattern) {
            val b = DateTimeFormatterBuilder().parseCaseInsensitive()
            if (pattern.contains("yy") && !pattern.contains("yyyy")) {
                // Two-digit years: 00-99 -> 2000-2099.
                b.appendPattern(pattern.replace("yy", "")).appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
            } else {
                b.appendPattern(pattern.replace("yyyy", "uuuu"))
            }
            b.toFormatter(Locale.ENGLISH)
        }
    }

    fun parse(text: String, pattern: String): LocalDate? = try {
        LocalDate.parse(text.trim().replace(Regex("\\s+"), " ").replace(".", ""), formatter(pattern))
    } catch (e: DateTimeParseException) {
        null
    }

    /** Patterns that parse every sample. More than one result means the dates are ambiguous (e.g. 3/4/2026). */
    fun matchingFormats(samples: List<String>): List<String> {
        val clean = samples.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return emptyList()
        return CANDIDATES.filter { p -> clean.all { parse(it, p) != null } }
    }
}

/** A guessed profile plus what we are unsure about, so the import screen can ask. */
data class InferredProfile(
    val profile: CsvProfile,
    val institution: Institution?,
    /** All date formats that fit every row. Size > 1 means the user must confirm. */
    val dateFormatOptions: List<String>,
    val header: List<String>?,
    val notes: List<String>,
)
