package ca.emarshall.finance.core.categorize

enum class MatchType {
    /** The text appears anywhere. */
    CONTAINS,
    REGEX,

    /** The cleaned merchant name is exactly this ("Always do this for Uber" must not catch Uber Eats). */
    MERCHANT,

    /** The text appears as whole words: ESSO matches "ESSO 123" but not "ESPRESSO". */
    WORD,

    /** The text starts a word: TACO matches "TACOS" and "TACOFINO" but not "NOTACO". */
    PREFIX,
}

/**
 * A rule maps text to a category key. Higher [priority] wins; rules learned from the user's edits are
 * given a high priority so they beat the seed rules. [amountSign] limits a rule to money in (+1) or out (-1).
 */
data class Rule(
    val id: Long = 0,
    val pattern: String,
    val matchType: MatchType = MatchType.CONTAINS,
    val categoryKey: String,
    val priority: Int = 0,
    val learned: Boolean = false,
    val amountSign: Int = 0,
)

/**
 * [amountRules] ("rent is $1,800 a month") beat text rules, except where the text says it's a transfer:
 * category keys in [transferKeys] (card payments, Questrade deposits) are never overridden by an amount.
 */
class Categorizer(
    rules: List<Rule>,
    private val amountRules: List<AmountRule> = emptyList(),
    private val transferKeys: Set<String> = DefaultCategories.ALL.filter { it.kind == ca.emarshall.finance.core.model.CategoryKind.TRANSFER }.map { it.key }.toSet(),
) {
    private val compiled = rules.sortedWith(compareByDescending<Rule> { it.priority }.thenByDescending { it.pattern.length })
        .map { r ->
            val re = when (r.matchType) {
                MatchType.REGEX -> runCatching { Regex(r.pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                else -> null
            }
            Compiled(r, re, r.pattern.uppercase())
        }

    private class Compiled(val rule: Rule, val regex: Regex?, val upper: String)

    /**
     * Returns the category key for a transaction, falling back to Other / Other Income. [date] lets amount
     * rules check the day of the month; [useAmountRules] is false for investment accounts.
     */
    fun categorize(rawDescription: String, merchant: String, amountCents: Long, date: java.time.LocalDate? = null, useAmountRules: Boolean = true): String {
        val text = match(rawDescription, merchant, amountCents)?.categoryKey
        if (useAmountRules) amountRule(text, amountCents, date)?.let { return it.categoryKey }
        return text ?: if (amountCents >= 0) DefaultCategories.FALLBACK_INCOME else DefaultCategories.FALLBACK_EXPENSE
    }

    /** The amount rule that decides this payment's category, if one does. */
    fun amountRuleFor(rawDescription: String, merchant: String, amountCents: Long, date: java.time.LocalDate?): AmountRule? =
        amountRule(match(rawDescription, merchant, amountCents)?.categoryKey, amountCents, date)

    private fun amountRule(textKey: String?, amountCents: Long, date: java.time.LocalDate?): AmountRule? =
        if (amountRules.isEmpty()) null else AmountRules.find(amountRules, amountCents, date, textKey != null && textKey in transferKeys)

    /** Statements repeat the same descriptions over and over; remember the answer for each. */
    private val memo = HashMap<String, Rule?>()

    fun match(rawDescription: String, merchant: String, amountCents: Long): Rule? {
        val key = "${java.lang.Long.signum(amountCents)}|$rawDescription|$merchant"
        synchronized(memo) { if (memo.containsKey(key)) return memo[key] }
        return find(rawDescription, merchant, amountCents).also { synchronized(memo) { if (memo.size < 20_000) memo[key] = it } }
    }

    private fun find(rawDescription: String, merchant: String, amountCents: Long): Rule? {
        val raw = rawDescription.uppercase()
        val name = merchant.uppercase()
        // The raw text, the same with glued-on cities split off ("COFFEEVANCOUVER"), and the cleaned name.
        val hay = raw + " | " + MerchantCleaner.deglue(raw) + " | " + name
        val sign = java.lang.Long.signum(amountCents)
        return compiled.firstOrNull { c ->
            val r = c.rule
            (r.amountSign == 0 || r.amountSign == sign) && when (r.matchType) {
                MatchType.CONTAINS -> hay.contains(c.upper)
                MatchType.REGEX -> c.regex?.containsMatchIn(hay) == true
                MatchType.MERCHANT -> name == c.upper
                MatchType.WORD -> containsWord(hay, c.upper, wholeWord = true)
                MatchType.PREFIX -> containsWord(hay, c.upper, wholeWord = false)
            }
        }?.rule
    }

    private fun containsWord(hay: String, word: String, wholeWord: Boolean): Boolean {
        if (word.isEmpty()) return false
        var i = hay.indexOf(word)
        while (i >= 0) {
            val before = i == 0 || !hay[i - 1].isLetterOrDigit()
            val end = i + word.length
            val after = !wholeWord || end == hay.length || !hay[end].isLetterOrDigit()
            if (before && after) return true
            i = hay.indexOf(word, i + 1)
        }
        return false
    }
}
