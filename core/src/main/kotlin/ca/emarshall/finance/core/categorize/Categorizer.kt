package ca.emarshall.finance.core.categorize

enum class MatchType { CONTAINS, REGEX }

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

class Categorizer(rules: List<Rule>) {
    private val compiled = rules.sortedWith(compareByDescending<Rule> { it.priority }.thenByDescending { it.pattern.length })
        .map { r ->
            val re = when (r.matchType) {
                MatchType.CONTAINS -> null
                MatchType.REGEX -> runCatching { Regex(r.pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            }
            r to re
        }

    /** Returns the category key for a transaction, falling back to Other / Other Income. */
    fun categorize(rawDescription: String, merchant: String, amountCents: Long): String =
        match(rawDescription, merchant, amountCents)?.categoryKey
            ?: if (amountCents >= 0) DefaultCategories.FALLBACK_INCOME else DefaultCategories.FALLBACK_EXPENSE

    fun match(rawDescription: String, merchant: String, amountCents: Long): Rule? {
        val hay = (rawDescription + " | " + merchant).uppercase()
        val sign = java.lang.Long.signum(amountCents)
        return compiled.firstOrNull { (r, re) ->
            (r.amountSign == 0 || r.amountSign == sign) && when (r.matchType) {
                MatchType.CONTAINS -> hay.contains(r.pattern.uppercase())
                MatchType.REGEX -> re?.containsMatchIn(hay) == true
            }
        }?.first
    }
}

object SeedRules {
    private fun c(pattern: String, key: String, priority: Int = 10, sign: Int = 0) =
        Rule(pattern = pattern, categoryKey = key, priority = priority, amountSign = sign)

    private fun r(pattern: String, key: String, priority: Int = 10, sign: Int = 0) =
        Rule(pattern = pattern, matchType = MatchType.REGEX, categoryKey = key, priority = priority, amountSign = sign)

    val ALL: List<Rule> = listOf(
        // Groceries
        c("SAVE ON FOODS", "groceries"), c("SAVE-ON-FOODS", "groceries"), c("SUPERSTORE", "groceries"),
        c("SAFEWAY", "groceries"), c("NO FRILLS", "groceries"), c("NOFRILLS", "groceries"), c("WHOLE FOODS", "groceries"),
        c("T&T SUPERMARKET", "groceries"), c("NESTERS", "groceries"), c("FRESH ST", "groceries"), c("COOPERS FOODS", "groceries"),
        c("QUALITY FOODS", "groceries"), c("URBAN FARE", "groceries"), c("IGA ", "groceries"), c("H MART", "groceries"),
        c("FRESHCO", "groceries"), c("WALMART SUPERCENTER", "groceries"),
        r("\\bCOSTCO\\b(?!.*GAS)", "groceries"),
        // Dining & coffee
        c("TIM HORTONS", "dining"), c("STARBUCKS", "dining"), c("MCDONALD", "dining"), c("A&W", "dining"),
        c("WHITE SPOT", "dining"), c("BLENZ", "dining"), c("JJ BEAN", "dining"), c("WENDY", "dining"), c("SUBWAY", "dining"),
        c("UBER EATS", "dining", priority = 20), c("UBEREATS", "dining", priority = 20), c("DOORDASH", "dining"),
        c("SKIPTHEDISHES", "dining"), c("SKIP THE DISHES", "dining"), c("BOSTON PIZZA", "dining"), c("DOMINO", "dining"),
        c("PIZZA", "dining", priority = 5), c("SUSHI", "dining", priority = 5), c("RESTAURANT", "dining", priority = 5),
        c("CAFE", "dining", priority = 4), c("COFFEE", "dining", priority = 4), c("TST*", "dining", priority = 3),
        // Fuel
        c("PETRO-CANADA", "fuel"), c("PETRO CANADA", "fuel"), c("PETROCAN", "fuel"), c("SHELL", "fuel"), c("ESSO", "fuel"),
        c("CHEVRON", "fuel"), c("HUSKY", "fuel"), r("\\bMOBIL\\b", "fuel"), c("PIONEER", "fuel"), c("COSTCO GAS", "fuel", priority = 20),
        c("CANADIAN TIRE GAS", "fuel", priority = 20), c("GAS BAR", "fuel", priority = 20), c("PARKLAND", "fuel"),
        // Auto
        c("ICBC", "insurance"), c("CANADIAN TIRE", "auto", priority = 5), c("KAL TIRE", "auto"), c("MIDAS", "auto"),
        c("JIFFY LUBE", "auto"), c("NAPA", "auto"), c("CAR WASH", "auto"), c("PARTSOURCE", "auto"),
        // Transit & parking
        c("COMPASS", "transit"), c("TRANSLINK", "transit"), c("BC FERRIES", "travel"), c("EVO CAR SHARE", "transit"),
        c("MODO", "transit"), c("IMPARK", "transit"), c("EASYPARK", "transit"), c("PAYBYPHONE", "transit"),
        c("UBER", "transit", priority = 5), c("LYFT", "transit"), c("PARKING", "transit", priority = 5),
        // Shopping
        c("AMAZON", "shopping"), c("AMZN", "shopping"), c("WALMART", "shopping", priority = 5), c("WINNERS", "shopping"),
        c("BEST BUY", "shopping"), c("IKEA", "home"), c("HUDSON'S BAY", "shopping"), c("SPORT CHEK", "shopping"),
        c("MEC ", "shopping"), c("DOLLARAMA", "shopping"), c("LONDON DRUGS", "health", priority = 5),
        // Home
        c("HOME DEPOT", "home"), r("\\bRONA\\b", "home"), c("LOWE'S", "home"), c("HOME HARDWARE", "home"),
        // Bills & utilities
        c("BC HYDRO", "bills"), c("FORTISBC", "bills"), c("FORTIS BC", "bills"), c("CITY OF ", "bills"),
        c("PROPERTY TAX", "bills"), c("STRATA", "bills"), r("\\bRENT\\b", "bills", priority = 2, sign = -1),
        // Phone & internet
        c("TELUS", "phone"), c("ROGERS", "phone"), c("BELL CANADA", "phone"), c("BELL MOBILITY", "phone"),
        c("FREEDOM MOBILE", "phone"), c("FIDO", "phone"), c("KOODO", "phone"), c("VIRGIN PLUS", "phone"),
        c("PUBLIC MOBILE", "phone"), c("SHAW", "phone"), c("NOVUS", "phone"),
        // Insurance
        c("INSURANCE", "insurance", priority = 5), c("BCAA", "insurance"), c("SQUARE ONE", "insurance"),
        c("MANULIFE", "insurance"), c("SUN LIFE", "insurance"), c("PACIFIC BLUE CROSS", "insurance"),
        // Health
        c("SHOPPERS DRUG MART", "health"), c("PHARMA", "health", priority = 4), c("REXALL", "health"),
        c("DENTAL", "health"), c("PHYSIO", "health"), c("OPTOMETR", "health"), c("CLINIC", "health", priority = 4),
        // Entertainment
        c("CINEPLEX", "entertainment"), c("TICKETMASTER", "entertainment"), c("STEAM", "entertainment"),
        c("PLAYSTATION", "entertainment"), c("NINTENDO", "entertainment"), c("XBOX", "entertainment"),
        // Subscriptions
        c("NETFLIX", "subscriptions"), c("SPOTIFY", "subscriptions"), c("DISNEY", "subscriptions"),
        c("CRAVE", "subscriptions"), c("APPLE.COM/BILL", "subscriptions"), c("YOUTUBE", "subscriptions"),
        c("GOOGLE *", "subscriptions"), c("PRIME VIDEO", "subscriptions"), c("AMAZON PRIME", "subscriptions", priority = 20),
        c("AMZN PRIME", "subscriptions", priority = 20), c("PATREON", "subscriptions"), c("OPENAI", "subscriptions"),
        c("ANTHROPIC", "subscriptions"), c("CLAUDE.AI", "subscriptions"), c("MICROSOFT", "subscriptions", priority = 4),
        c("ADOBE", "subscriptions"), c("DROPBOX", "subscriptions"), c("GYM", "subscriptions", priority = 3),
        // Travel
        c("AIR CANADA", "travel"), c("WESTJET", "travel"), c("FLAIR", "travel"), c("AIRBNB", "travel"),
        c("EXPEDIA", "travel"), c("HOTEL", "travel", priority = 4), c("BOOKING.COM", "travel"),
        // Gifts & donations
        c("CANADAHELPS", "gifts"), c("DONATION", "gifts"), c("RED CROSS", "gifts"),
        // Cash & ATM
        r("\\bATM\\b", "cash", priority = 15), c("ABM WITHDRAWAL", "cash", priority = 15),
        c("CASH WITHDRAWAL", "cash", priority = 15),
        // Fees & interest (charges are money out)
        c("INTEREST CHARGE", "fees", priority = 30, sign = -1), c("PURCHASE INTEREST", "fees", priority = 30, sign = -1),
        c("CASH ADVANCE INTEREST", "fees", priority = 30, sign = -1), c("INTEREST ON", "fees", priority = 25, sign = -1),
        c("ANNUAL FEE", "fees", priority = 30), c("SERVICE CHARGE", "fees", priority = 30), c("MONTHLY FEE", "fees", priority = 30),
        c("ACCOUNT FEE", "fees", priority = 30), c("OVERDRAFT", "fees", priority = 30), r("\\bNSF\\b", "fees", priority = 30),
        c("FOREIGN TRANSACTION FEE", "fees", priority = 30), c("FX FEE", "fees", priority = 30), c("INTERAC E-TRANSFER FEE", "fees", priority = 35),
        c("LATE PAYMENT FEE", "fees", priority = 30), c(" FEE", "fees", priority = 1, sign = -1),
        // e-Transfers
        c("E-TRANSFER", "etransfer_sent", priority = 8, sign = -1), c("ETRANSFER", "etransfer_sent", priority = 8, sign = -1),
        c("INTERAC E-TRF", "etransfer_sent", priority = 8, sign = -1), c("SEND E-TFR", "etransfer_sent", priority = 8, sign = -1),
        // Income
        c("PAYROLL", "paycheque", priority = 20, sign = 1), c("PAY DEPOSIT", "paycheque", priority = 20, sign = 1),
        c("DIRECT DEPOSIT", "paycheque", priority = 15, sign = 1), c("SALARY", "paycheque", priority = 20, sign = 1),
        c("CANADA FED", "other_income", priority = 15, sign = 1), c("CRA ", "other_income", priority = 10, sign = 1),
        c("GST", "other_income", priority = 10, sign = 1), c("CCB", "other_income", priority = 10, sign = 1),
        c("BC CLIMATE", "other_income", priority = 10, sign = 1),
        c("INTEREST PAID", "investment_income", priority = 20, sign = 1), c("INTEREST EARNED", "investment_income", priority = 20, sign = 1),
        c("DIVIDEND", "investment_income", priority = 20, sign = 1),
        c("REFUND", "refunds", priority = 15, sign = 1), c("RETURN", "refunds", priority = 4, sign = 1),
        c("E-TRANSFER", "other_income", priority = 8, sign = 1), c("INTERAC E-TRF", "other_income", priority = 8, sign = 1),
        // Transfers (the transfer matcher confirms these by pairing; the rules make unpaired ones obvious)
        c("PAYMENT - THANK YOU", "card_payment", priority = 40, sign = 1), c("PAYMENT THANK YOU", "card_payment", priority = 40, sign = 1),
        c("PAYMENT RECEIVED", "card_payment", priority = 40, sign = 1), c("TRIANGLE MASTERCARD", "card_payment", priority = 40, sign = -1),
        c("CANADIAN TIRE MC", "card_payment", priority = 40, sign = -1), c("CTFS", "card_payment", priority = 40, sign = -1),
        c("CANADIAN TIRE BANK", "card_payment", priority = 40, sign = -1), c("SCOTIA VISA", "card_payment", priority = 40, sign = -1),
        c("SCOTIABANK MASTERCARD", "card_payment", priority = 40, sign = -1),
        c("QUESTRADE", "investments_transfer", priority = 40),
        r("(?<!E-)\\bTRANSFER (TO|FROM)\\b", "between_accounts", priority = 12), c("TFR-TO", "between_accounts", priority = 12),
        c("TFR-FR", "between_accounts", priority = 12), c("MB-TRANSFER", "between_accounts", priority = 12),
        c("PC-TRANSFER", "between_accounts", priority = 12),

        // Shapes seen in real Scotiabank / Triangle statement exports (see docs/IMPORT_FORMATS.md)
        c("CREDIT CARD/LOC PAYMENT", "card_payment", priority = 40, sign = -1),
        c("IO TRANSFER TO INV", "investments_transfer", priority = 40),
        c("INTERAC E-TRANSFER RTN", "etransfer_sent", priority = 30),
        c("HYDRO BILL", "bills", priority = 15), c("B.C. HYDRO", "bills"), c("INSURANCE CORPORATION OF BC", "insurance"),
        c("CDN TIRE", "auto", priority = 5), c("MARK'S", "shopping"), c("BESTBUY", "shopping"), c("MUJI", "shopping"),
        r("\\bZARA\\b", "shopping"), c("UNIQLO", "shopping"), c("LULULEMON", "shopping"), c("SPORTCHEK", "shopping"),
        c("CENTEX", "fuel"), c("CANCO PETROLEUM", "fuel"), r("\\bCHV\\s?\\d", "fuel"), c("7-ELEVEN", "dining", priority = 3),
        c("SUPER VALU", "groceries"), c("BUY LOW FOODS", "groceries"), c("BUY-LOW", "groceries"),
        c("FARMERS MARKET", "groceries"), c("COUNTRY MARKET", "groceries"), c("FOODS", "groceries", priority = 3),
        r("\\bBCF\\b", "travel"), c("FAMOUS PLAYER", "entertainment"), c("CLIMBING", "entertainment", priority = 4),
        c("LIQUOR", "dining", priority = 4), c("RAMEN", "dining", priority = 4), c("POKE", "dining", priority = 4),
        c("BAKERY", "dining", priority = 4), r("\\bPUB\\b", "dining", priority = 4), c("TAVERN", "dining", priority = 4),
        c("BREWHOUSE", "dining", priority = 4), c("BREWING", "dining", priority = 4), c("BISTRO", "dining", priority = 4),
        c("GRILL", "dining", priority = 4), c("KITCHEN", "dining", priority = 3), c("CHICKEN", "dining", priority = 4),
        c("DINER", "dining", priority = 4), c("DENNY'S", "dining"), c("NOODLE", "dining", priority = 4),
        c("TANDOORI", "dining", priority = 4), c("CURRY", "dining", priority = 4), c("TACO", "dining", priority = 4),
        c("BURGER", "dining", priority = 4), c("CATERING", "dining", priority = 4), c("BAGUETTE", "dining", priority = 4),
        r("\\bCOFF", "dining", priority = 4),
        r("\\bTLNK\\b", "transit"), r("\\bRESTA", "dining", priority = 4),
    )
}
