package ca.emarshall.finance.core.statement

import ca.emarshall.finance.core.model.AccountHint
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.FileFormat
import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.core.model.ParsedBalance
import ca.emarshall.finance.core.model.ParsedStatement
import ca.emarshall.finance.core.model.ParsedTransaction
import ca.emarshall.finance.core.money.Money
import java.time.LocalDate
import java.time.Month

/** The statement layouts we know. See docs/IMPORT_FORMATS.md for what each looks like. */
enum class StatementLayout {
    /** Scotiabank chequing/savings ("Your Preferred Package account"): one date, amount, running balance. */
    SCOTIA_ACCOUNT,

    /** Scotiabank Visa (e.g. Scotia Momentum): REF#, transaction and posting dates, credits with a trailing minus. */
    SCOTIA_VISA,

    /** Canadian Tire Bank Triangle Mastercard: two dates, payments negative, a second text column on the right. */
    TRIANGLE,

    /** Unknown card statement: two (or one) dates, description, amount. */
    GENERIC_CARD,
}

/**
 * Parses the text of a PDF statement, as extracted in the app by PdfBox (`PDFTextStripper` with
 * `sortByPosition = true`): one text line per visual line, tokens separated by spaces, and on two-column
 * pages the right column's text appended after the transaction amount.
 *
 * Every layout reconciles itself against the statement's own totals (opening/closing balance, or
 * previous/new balance) and adds a warning when the parsed rows don't add up.
 */
object StatementTextParser {

    private const val MON = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?"

    /** A money token: optional minus or $, digits with thousands commas, exactly 2 decimals, optional trailing minus / CR. */
    private const val AMT = "-?\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?:-|\\s?CR\\b)?"
    private val AMOUNT = Regex("(?<![\\w.,$-])($AMT)(?=\\s|$)")

    private val CARD_LINE = Regex("^\\s*(?:\\d{3}\\s+)?($MON)\\s+(\\d{1,2})\\s+($MON)\\s+(\\d{1,2})\\s+(.*)$", RegexOption.IGNORE_CASE)
    private val ONE_DATE_LINE = Regex("^\\s*($MON)\\s+(\\d{1,2})\\s+(.*)$", RegexOption.IGNORE_CASE)
    /** Date, type, amount, balance; a side box's text can follow the balance on the same line. */
    private val ACCOUNT_LINE = Regex("^\\s*($MON)\\s+(\\d{1,2})\\s+(.+?)\\s+($AMT)\\s+($AMT)(?:\\s+[^\\d\\s$-].*)?$", RegexOption.IGNORE_CASE)

    /** Where a Scotiabank detail line really ends: the province+country code ("BCCA") or "E-Transfer [Rtn]". */
    private val ACCOUNT_DETAIL_END = Regex("(?:BC|AB|SK|MB|ON|QC|NB|NS|PE|NL|YT|NT|NU)CA\\b|E-Transfer(?: Rtn)?\\b", RegexOption.IGNORE_CASE)
    private val ACCOUNT_OPEN_CLOSE = Regex("^\\s*($MON)\\s+(\\d{1,2})\\s+(Opening|Closing) Balance\\s+($AMT)\\s*$", RegexOption.IGNORE_CASE)

    private const val FULL_DATE = "($MON)\\s+(\\d{1,2}),?\\s+(\\d{4})"
    private val PERIOD = Regex("$FULL_DATE\\s*(?:-|–|—|to)\\s*$FULL_DATE", RegexOption.IGNORE_CASE)
    private val STATEMENT_DATE = Regex("statement date:?\\s*$FULL_DATE", RegexOption.IGNORE_CASE)
    private val OPENING = Regex("Opening Balance on $FULL_DATE\\s+($AMT)", RegexOption.IGNORE_CASE)
    private val CLOSING = Regex("Closing Balance on $FULL_DATE\\s+($AMT)", RegexOption.IGNORE_CASE)

    private val CARD_LAST4 = Regex("(?:[*xX•]{2,}[\\s-]*){1,3}(\\d{4})\\b")
    private val ENDING_WITH = Regex("ending (?:with|in) (\\d{4})", RegexOption.IGNORE_CASE)
    private val SCOTIA_ACCOUNT_NO = Regex("^\\s*(?:[A-Z ]+\\s)?(\\d{5} \\d{5} \\d{2})\\s*$", RegexOption.MULTILINE)

    /** Headings after which a Triangle statement lists store items, not transactions. */
    private val TRIANGLE_STOP = Regex("^\\s*Details of your .*store purchases", RegexOption.IGNORE_CASE)

    private val ACCOUNT_NOISE = listOf(
        Regex("^continued on next page", RegexOption.IGNORE_CASE),
        Regex("^Page \\d+ of \\d+", RegexOption.IGNORE_CASE),
        Regex("^SBSAV"), Regex("^-{3,}"), Regex("^\\|"), Regex("^\\*?\\d{5,}\\*?$"),
        Regex("^(MR|MRS|MS|MISS|DR)\\.? [A-Z]", RegexOption.IGNORE_CASE),
        Regex("^Your .* account", RegexOption.IGNORE_CASE), Regex("^Here's what happened", RegexOption.IGNORE_CASE),
        Regex("^Amounts\\b", RegexOption.IGNORE_CASE), Regex("^Date\\s+Transactions", RegexOption.IGNORE_CASE),
    )

    private val DEPOSIT_WORDS = listOf("deposit", "payroll", "from", "error correction", "returned", "interest paid", "refund", "credit")

    fun detect(text: String): StatementLayout {
        val head = text.take(20_000)
        return when {
            head.contains("Triangle", ignoreCase = true) && (head.contains("Canadian Tire", true) || head.contains("ctfs", true)) ->
                StatementLayout.TRIANGLE
            head.contains("Scotia", ignoreCase = true) && head.contains("Visa", ignoreCase = true) && head.contains("REF.#", true) ->
                StatementLayout.SCOTIA_VISA
            head.contains("Opening Balance on", ignoreCase = true) && head.contains("withdrawn", ignoreCase = true) ->
                StatementLayout.SCOTIA_ACCOUNT
            else -> StatementLayout.GENERIC_CARD
        }
    }

    fun parse(text: String, layout: StatementLayout = detect(text)): ParsedStatement = when (layout) {
        StatementLayout.SCOTIA_ACCOUNT -> parseScotiaAccount(text)
        StatementLayout.SCOTIA_VISA, StatementLayout.TRIANGLE, StatementLayout.GENERIC_CARD -> parseCard(text, layout)
    }

    // ---------------------------------------------------------------- credit cards

    private fun parseCard(text: String, layout: StatementLayout): ParsedStatement {
        val warnings = ArrayList<String>()
        val period = PERIOD.find(text)
        val periodStart = period?.let { date(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        val periodEnd = period?.let { date(it.groupValues[4], it.groupValues[5], it.groupValues[6]) }
            ?: STATEMENT_DATE.find(text)?.let { date(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        if (periodEnd == null) warnings.add("No statement period found; dates assume the last 12 months.")
        val anchor = periodEnd ?: LocalDate.now()

        val txs = ArrayList<ParsedTransaction>()
        for (line in text.lines()) {
            if (layout == StatementLayout.TRIANGLE && TRIANGLE_STOP.containsMatchIn(line)) break
            val two = CARD_LINE.find(line)
            val one = if (two == null && layout == StatementLayout.GENERIC_CARD) ONE_DATE_LINE.find(line) else null
            val m = two ?: one ?: continue
            val rest = if (two != null) two.groupValues[5] else m.groupValues[3]
            val amt = AMOUNT.find(rest) ?: continue
            val desc = rest.substring(0, amt.range.first).replace(Regex("\\s+"), " ").trim()
            if (desc.isEmpty() || desc.startsWith("balance", ignoreCase = true)) continue
            val month = monthOf(m.groupValues[1]) ?: continue
            val d = inferYear(month, m.groupValues[2].toInt(), anchor) ?: continue
            val raw = amt.groupValues[1]
            val printed = runCatching { Money.parse(raw) }.getOrNull() ?: continue
            // Card statements print charges as plain positives and credits (payments, returns) with CR or a minus.
            val isCredit = raw.uppercase().contains("CR") || printed < 0
            txs.add(ParsedTransaction(d, if (isCredit) Math.abs(printed) else -Math.abs(printed), desc))
        }
        val previous = cardPreviousBalance(text, layout)
        val newBalance = cardNewBalance(text, layout)
        // A card that wasn't used: the balance didn't move, so no rows is the right answer, not a parsing failure.
        val quietMonth = txs.isEmpty() && previous != null && previous == newBalance
        if (txs.isEmpty() && !quietMonth) warnings.add("No transactions recognised in this PDF.")
        if (previous != null && newBalance != null) {
            val actual = txs.sumOf { it.amountCents }
            if (-(newBalance - previous) != actual) {
                warnings.add(
                    "Totals don't reconcile: the statement's balance moved by ${Money.format(newBalance - previous)}, " +
                        "but the rows found add up to ${Money.format(-actual)}. Some rows may be missing.",
                )
            }
        }
        val balances = if (newBalance != null && periodEnd != null) listOf(ParsedBalance(periodEnd, -newBalance)) else emptyList()

        val last4 = ENDING_WITH.find(text)?.groupValues?.get(1) ?: CARD_LAST4.find(text)?.groupValues?.get(1)
        val institution = when (layout) {
            StatementLayout.TRIANGLE -> Institution.CANADIAN_TIRE
            StatementLayout.SCOTIA_VISA -> Institution.SCOTIABANK
            else -> guessInstitution(text)
        }
        return ParsedStatement(
            format = FileFormat.PDF,
            transactions = txs,
            balances = balances,
            hint = AccountHint(institution, AccountType.CREDIT_CARD, last4, "CAD"),
            periodStart = periodStart,
            periodEnd = periodEnd,
            warnings = warnings,
        )
    }

    private fun cardPreviousBalance(text: String, layout: StatementLayout): Long? {
        val re = when (layout) {
            StatementLayout.TRIANGLE -> Regex("Balance from your last statement\\s+($AMT)", RegexOption.IGNORE_CASE)
            StatementLayout.SCOTIA_VISA -> Regex("Previous balance,?\\s*(?:$MON\\s*\\d{1,2}/\\d{2})?\\s+($AMT)", RegexOption.IGNORE_CASE)
            else -> Regex("Previous (?:statement )?balance[^\\d$-]{0,30}($AMT)", RegexOption.IGNORE_CASE)
        }
        return re.find(text)?.let { runCatching { Money.parse(it.groupValues[1]) }.getOrNull() }
    }

    private fun cardNewBalance(text: String, layout: StatementLayout): Long? {
        val re = when (layout) {
            StatementLayout.TRIANGLE -> Regex("Your New Balance\\s+($AMT)", RegexOption.IGNORE_CASE)
            else -> Regex("New Balance\\s*[=:]?\\s*($AMT)", RegexOption.IGNORE_CASE)
        }
        return re.find(text)?.let { runCatching { Money.parse(it.groupValues[1]) }.getOrNull() }
    }

    // ---------------------------------------------------------------- Scotiabank chequing / savings

    private fun parseScotiaAccount(text: String): ParsedStatement {
        val warnings = ArrayList<String>()
        val open = OPENING.find(text)
        val close = CLOSING.find(text)
        val periodStart = open?.let { date(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        val periodEnd = close?.let { date(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        val openingBalance = open?.let { Money.parse(it.groupValues[4]) }
        val closingBalance = close?.let { Money.parse(it.groupValues[4]) }
        if (periodEnd == null) warnings.add("No statement period found; dates assume the last 12 months.")
        val anchor = periodEnd ?: LocalDate.now()

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val txs = ArrayList<ParsedTransaction>()
        var prevBalance: Long? = openingBalance
        var mismatches = 0
        for ((i, line) in lines.withIndex()) {
            val oc = ACCOUNT_OPEN_CLOSE.find(line)
            if (oc != null) {
                if (oc.groupValues[3].equals("Opening", true) && prevBalance == null) prevBalance = Money.parse(oc.groupValues[4])
                continue
            }
            val m = ACCOUNT_LINE.find(line) ?: continue
            val month = monthOf(m.groupValues[1]) ?: continue
            val d = inferYear(month, m.groupValues[2].toInt(), anchor) ?: continue
            val type = m.groupValues[3].replace(Regex("\\s+"), " ").trim()
            val abs = Math.abs(Money.parse(m.groupValues[4]) ?: continue)
            val balance = Money.parse(m.groupValues[5]) ?: continue

            val delta = prevBalance?.let { balance - it }
            val amount = if (delta != null && Math.abs(delta) == abs) {
                delta
            } else {
                mismatches++
                if (DEPOSIT_WORDS.any { type.lowercase().contains(it) }) abs else -abs
            }
            prevBalance = balance
            if (amount == 0L) continue // e.g. "INTERAC ABM fee 0.00" (waived)

            // The merchant / payee is on the next line, unless that line is another row or page furniture.
            val detail = lines.getOrNull(i + 1)?.takeIf { n ->
                ONE_DATE_LINE.find(n) == null && ACCOUNT_NOISE.none { it.containsMatchIn(n) }
            }?.let { n -> ACCOUNT_DETAIL_END.find(n)?.let { e -> n.substring(0, e.range.last + 1) } ?: n }
            txs.add(ParsedTransaction(d, amount, if (detail != null) "$type $detail" else type, balanceCents = balance))
        }
        if (txs.isEmpty() && !(openingBalance != null && openingBalance == closingBalance)) warnings.add("No transactions recognised in this PDF.")
        if (mismatches > 0) warnings.add("$mismatches row(s) didn't match the running balance; their signs were guessed.")
        val sum = txs.sumOf { it.amountCents }
        if (openingBalance != null && closingBalance != null && openingBalance + sum != closingBalance) {
            warnings.add(
                "Totals don't reconcile: opening ${Money.format(openingBalance)} + rows ${Money.format(sum)} " +
                    "≠ closing ${Money.format(closingBalance)}. Some rows may be missing.",
            )
        }

        val balances = txs.groupBy { it.date }.map { (d, rows) -> ParsedBalance(d, rows.last().balanceCents!!) }.toMutableList()
        if (closingBalance != null && periodEnd != null) {
            balances.removeAll { it.date == periodEnd }
            balances.add(ParsedBalance(periodEnd, closingBalance))
        }
        val accountDigits = SCOTIA_ACCOUNT_NO.find(text)?.groupValues?.get(1)?.filter { it.isDigit() }
        return ParsedStatement(
            format = FileFormat.PDF,
            transactions = txs,
            balances = balances.sortedBy { it.date },
            hint = AccountHint(Institution.SCOTIABANK, AccountType.CHEQUING, accountDigits?.takeLast(4), "CAD"),
            periodStart = periodStart,
            periodEnd = periodEnd,
            warnings = warnings,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun guessInstitution(text: String): Institution? {
        val u = text.uppercase()
        return when {
            u.contains("CANADIAN TIRE") || u.contains("TRIANGLE") -> Institution.CANADIAN_TIRE
            u.contains("SCOTIA") -> Institution.SCOTIABANK
            else -> null
        }
    }

    private fun monthOf(s: String): Month? = when (s.take(3).lowercase()) {
        "jan" -> Month.JANUARY; "feb" -> Month.FEBRUARY; "mar" -> Month.MARCH; "apr" -> Month.APRIL
        "may" -> Month.MAY; "jun" -> Month.JUNE; "jul" -> Month.JULY; "aug" -> Month.AUGUST
        "sep" -> Month.SEPTEMBER; "oct" -> Month.OCTOBER; "nov" -> Month.NOVEMBER; "dec" -> Month.DECEMBER
        else -> null
    }

    private fun date(mon: String, day: String, year: String): LocalDate? =
        monthOf(mon)?.let { runCatching { LocalDate.of(year.toInt(), it, day.toInt()) }.getOrNull() }

    /** The latest date on or before [anchor] (+ a week of slack for posting) with this month and day. */
    internal fun inferYear(month: Month, day: Int, anchor: LocalDate): LocalDate? {
        for (y in listOf(anchor.year, anchor.year - 1)) {
            val d = runCatching { LocalDate.of(y, month, day) }.getOrNull() ?: continue
            if (!d.isAfter(anchor.plusDays(7))) return d
        }
        return null
    }
}
