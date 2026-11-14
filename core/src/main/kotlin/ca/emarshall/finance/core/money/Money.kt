package ca.emarshall.finance.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/** Money is always Long cents. Parsing goes through BigDecimal; Double is never used. */
object Money {

    private val NUMBER = Regex("""\d+(\.\d+)?|\.\d+|\d+\.""")

    /** Dollars -> cents, rounding half-up. */
    fun toCents(value: BigDecimal): Long =
        value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()

    fun fromCents(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

    /**
     * Parses amounts the way banks print them: `1234.56`, `-1,234.56`, `$1,234.56`, `($12.34)`,
     * `12.34 CR`, `12.34DR`, `-$5`, `$-5`, `+5.00`, `5.00-`.
     * CR is a credit (positive), DR a debit (negative), parentheses negative.
     * Returns null for blank cells. Throws [NumberFormatException] for anything else.
     */
    fun parse(raw: String?): Long? {
        if (raw == null) return null
        var s = raw.replace(' ', ' ').trim()
        if (s.isEmpty() || s == "-" || s == "--") return null
        var negative = false
        val upper = s.uppercase()
        if (upper.endsWith("CR")) {
            s = s.dropLast(2).trim()
        } else if (upper.endsWith("DR")) {
            s = s.dropLast(2).trim(); negative = !negative
        }
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = !negative
            s = s.substring(1, s.length - 1).trim()
        }
        s = s.replace("CAD", "", ignoreCase = true).replace("USD", "", ignoreCase = true).trim()
        if (s.endsWith("-")) {
            negative = !negative; s = s.dropLast(1).trim()
        }
        s = s.replace("$", "").replace(",", "").replace(" ", "")
        if (s.startsWith("+")) s = s.substring(1)
        if (s.startsWith("-")) {
            negative = !negative; s = s.substring(1)
        }
        if (s.isEmpty()) return null
        if (!NUMBER.matches(s)) throw NumberFormatException("Not an amount: '$raw'")
        val cents = toCents(BigDecimal(s))
        return if (negative) -cents else cents
    }

    /** `-$1,234.50`; adds the currency code when it isn't CAD and [showCurrency] is set. */
    fun format(cents: Long, currency: String = "CAD", showCurrency: Boolean = false): String {
        val abs = Math.abs(cents)
        val base = (if (cents < 0) "-" else "") + "$" + group(abs / 100) + "." +
            (abs % 100).toString().padStart(2, '0')
        return if (showCurrency && currency != "CAD") "$base $currency" else base
    }

    /** Whole dollars for compact tiles: `$1,235`. */
    fun formatWhole(cents: Long): String {
        val rounded = BigDecimal.valueOf(cents, 2).setScale(0, RoundingMode.HALF_UP).toLong()
        return (if (rounded < 0) "-" else "") + "$" + group(Math.abs(rounded))
    }

    private fun group(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
}
