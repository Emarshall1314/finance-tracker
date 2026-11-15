package ca.emarshall.finance.core.categorize

/**
 * Turns raw bank descriptions into readable merchant names. Shapes seen in real exports:
 *
 * - Scotiabank chequing PDF: `Point of sale purchase Fpos Costco Wholesale W54Burnaby BCCA`
 *   (transaction type, then `Fpos`/`Opos`, a 20-character name, a city glued on, province + country)
 * - Scotiabank Visa PDF: `UBER CANADA/UBERTRIP     TORONTO      ON`
 * - Triangle Mastercard PDF: `PETRO-CANADA 92580 VANCOUVER BC`, `CDN TIRE STORE #00605 VANCOUVER`
 *
 * Results: `Costco`, `Uber`, `Petro-Canada`, `Canadian Tire`.
 */
object MerchantCleaner {

    /** Whole-description shapes that are better named by what they are than by the text after them. */
    private val KIND_NAMES = listOf(
        Regex("^CREDIT CARD/LOC PAYMENT") to "Credit card payment",
        Regex("^IO TRANSFER TO INV") to "Transfer to investments",
        Regex("^(IO|MB|PC)[- ]TRANSFER (TO|FROM)") to "Transfer between my accounts",
        Regex("^WITHDRAWAL\\b.*INTERAC E-TRANSFER") to "Interac e-Transfer sent",
        Regex("^DEPOSIT\\b.*INTERAC E-TRANSFER RTN") to "Interac e-Transfer returned",
        Regex("^DEPOSIT\\b.*INTERAC E-TRANSFER") to "Interac e-Transfer received",
        Regex("^SCOTIABANK TRANSIT") to "Payment received",
        Regex("^CTFS\\.COM/PAYMENTS") to "Payment received",
        Regex("^PAYMENT[ -]+THANK YOU") to "Payment received",
        Regex("^INTEREST CHARGES?") to "Interest charges",
        Regex("^NSF CHEQUE CHARGE") to "NSF fee",
        Regex("^RETURNED NSF CHEQUE") to "Returned payment",
        Regex("^(SHARED )?ABM WITHDRAWAL") to "Cash withdrawal",
        Regex("^SERVICE CHARGE") to "Service charge",
    )

    private val PREFIXES = listOf(
        "POINT OF SALE PURCHASE", "POINT OF SALE", "POS PURCHASE", "POS", "OPOS", "APOS", "FPOS", "INTERAC PURCHASE",
        "DEBIT PURCHASE", "VISA DEBIT PURCHASE", "VISA DEBIT", "MASTERCARD PURCHASE", "PURCHASE", "PRE-AUTHORIZED PAYMENT",
        "PRE-AUTH PAYMENT", "PREAUTHORIZED DEBIT", "PAD", "BILL PAYMENT", "MB-BILL PAYMENT", "PC-BILL PAYMENT",
        "MB BILL PAYMENT", "PC BILL PAYMENT", "MISC. PAYMENT", "MISC PAYMENT", "AUTO INSURANCE", "HYDRO BILL",
        "PAYROLL DEP.", "PAYROLL DEPOSIT", "ERROR CORRECTION", "CONTACTLESS", "RECURRING PAYMENT",
        "CUSTOMER TRANSFER DR.", "CUSTOMER TRANSFER CR.", "TELEPHONE BILL", "CABLE BILL", "UTILITY BILL", "GAS BILL",
        "MORTGAGE PAYMENT", "LOAN PAYMENT", "TAX PAYMENT", "BUSINESS TAXES", "RENT/LEASES",
        // Payment processors that put their own tag in front of the shop's name.
        "SQ *", "SQ*", "TST*", "TST-", "PAYPAL *", "PP*", "SP *", "SP*", "IC*", "DD *", "DD*", "CKO*", "WWW ", "LS ", "BT*", "CLV*",
        "ZSK*", "OLO*", "WPY*", "PY *", "IN *", "FS *", "FSP*", "EB *", "SQSP*", "GLF*", "PAR*", "TCB*", "NYX*", "SPI*", "CTLP*", "PPL*",
    ).sortedByDescending { it.length }

    /** Word prefixes need a space after them; tags ending in punctuation ("SQ *", "TST-") don't. */
    private fun String.startsWithPrefix(p: String) =
        if (p.last().isLetterOrDigit() || p.last() == '.') startsWith("$p ") else startsWith(p)

    private const val PROVINCES = "BC|AB|SK|MB|ON|QC|NB|NS|PE|NL|YT|NT|NU"
    private const val COUNTRIES = "CA|CAN|US|USA|IRL|GBR|FRA|DEU|ESP|ITA|NLD|PRT|JPN|MEX|AUS|NZL|CHE"

    /** Cities that exports glue onto the merchant name without a space ("COFFEEVANCOUVER"). */
    private val CITIES = listOf(
        "NORTH VANCOUVER", "WEST VANCOUVER", "VANCOUVER", "BURNABY", "RICHMOND", "SURREY", "COQUITLAM", "PORT COQUITLAM",
        "PORT MOODY", "NEW WESTMINSTER", "DELTA", "LANGLEY", "ABBOTSFORD", "MAPLE RIDGE", "PITT MEADOWS", "MISSION",
        "CHILLIWACK", "SQUAMISH", "WHISTLER", "PEMBERTON", "VICTORIA", "NANAIMO", "KELOWNA", "KAMLOOPS", "REVELSTOKE",
        "GIBSONS", "SECHELT", "PRINCETON", "LILLOOET", "HOPE", "WHITE ROCK", "TORONTO", "CALGARY", "EDMONTON", "MONTREAL",
    ).sortedByDescending { it.length }

    /** Only distinctive names are split when glued; short ones (HOPE, DELTA, MISSION) would break words like COMMISSION. */
    private val GLUED_CITY = Regex(
        "([A-Z0-9'&.])(" + CITIES.filter { it.length >= 7 && it != "MISSION" }.joinToString("|") { Regex.escape(it) } + ")\\b",
    )

    /** Known merchants whose names otherwise come out mangled. Matched on whole words of the cleaned text. */
    private val ALIASES = listOf(
        "SAVE ON FOODS" to "Save-On-Foods", "SAVE-ON-FOODS" to "Save-On-Foods", "SAVEONFOODS" to "Save-On-Foods",
        "REAL CDN SUPERSTORE" to "Real Canadian Superstore", "REAL CANADIAN SUPERSTORE" to "Real Canadian Superstore",
        "SUPERSTORE" to "Real Canadian Superstore", "TIM HORTONS" to "Tim Hortons", "TIMHORTONS" to "Tim Hortons",
        "STARBUCKS" to "Starbucks", "COSTCO GAS" to "Costco Gas", "COSTCO" to "Costco", "PETRO-CANADA" to "Petro-Canada",
        "PETRO CANADA" to "Petro-Canada", "PETROCAN" to "Petro-Canada", "SHOPPERS DRUG MART" to "Shoppers Drug Mart",
        "LONDON DRUGS" to "London Drugs", "BC HYDRO" to "BC Hydro", "B.C. HYDRO" to "BC Hydro", "FORTISBC" to "FortisBC",
        "FORTIS BC" to "FortisBC", "INSURANCE CORPORATION OF BC" to "ICBC", "ICBC" to "ICBC",
        "AMZN MKTP" to "Amazon", "AMAZON.CA" to "Amazon", "AMAZON" to "Amazon", "NETFLIX" to "Netflix",
        "SPOTIFY" to "Spotify", "CANADIAN TIRE GAS" to "Canadian Tire Gas", "CANADIAN TIRE" to "Canadian Tire",
        "CDN TIRE" to "Canadian Tire", "MARK'S" to "Mark's", "MCDONALD'S" to "McDonald's", "MCDONALDS" to "McDonald's",
        "UBER CANADA/UBEREATS" to "Uber Eats", "UBER EATS" to "Uber Eats", "UBEREATS" to "Uber Eats", "UBER" to "Uber",
        "COMPASS" to "Compass (TransLink)", "TRANSLINK" to "Compass (TransLink)", "PAYBYPHONE" to "PayByPhone",
        "TELUS" to "Telus", "ROGERS" to "Rogers", "FREEDOM MOBILE" to "Freedom Mobile", "SHAW" to "Shaw",
        "SAFEWAY" to "Safeway", "WALMART" to "Walmart", "WAL-MART" to "Walmart", "HOME DEPOT" to "Home Depot",
        "NOFRILLS" to "No Frills", "NO FRILLS" to "No Frills", "SHELL" to "Shell", "ESSO" to "Esso",
        "CHEVRON" to "Chevron", "APPLE.COM/BILL" to "Apple", "DISNEY PLUS" to "Disney+", "WHITE SPOT" to "White Spot",
        "A&W" to "A&W", "A & W" to "A&W", "BEST BUY" to "Best Buy", "BESTBUY" to "Best Buy", "IKEA" to "IKEA",
        "WINNERS" to "Winners", "T&T SUPERMARKET" to "T&T Supermarket", "DOLLARAMA" to "Dollarama", "SUBWAY" to "Subway",
        "7-ELEVEN" to "7-Eleven", "7 ELEVEN" to "7-Eleven", "BC LIQUOR" to "BC Liquor", "BCF" to "BC Ferries",
        "BC FERRIES" to "BC Ferries", "FAMOUS PLAYER" to "Cineplex", "CINEPLEX" to "Cineplex", "MUJI" to "Muji",
        "ZARA" to "Zara", "DENNY'S" to "Denny's", "SUPER VALU" to "Super Valu", "BUY LOW FOODS" to "Buy-Low Foods",
        "WHOLE FOODS" to "Whole Foods", "QUESTRADE" to "Questrade", "PAYROLL" to "Payroll",
        "DOORDASH" to "DoorDash", "SKIPTHEDISHES" to "SkipTheDishes", "SKIP THE DISHES" to "SkipTheDishes", "MCDONALD" to "McDonald's",
        "WENDY'S" to "Wendy's", "WENDYS" to "Wendy's", "DAIRY QUEEN" to "Dairy Queen", "BOSTON PIZZA" to "Boston Pizza",
        "CACTUS CLUB" to "Cactus Club", "PHARMASAVE" to "Pharmasave", "LIFELABS" to "LifeLabs", "PETSMART" to "PetSmart",
        "SPORT CHEK" to "Sport Chek", "SPORTCHEK" to "Sport Chek", "LULULEMON" to "Lululemon", "UNIQLO" to "Uniqlo",
        "HOMESENSE" to "HomeSense", "STAPLES" to "Staples", "IMPARK" to "Impark", "EASYPARK" to "EasyPark", "LYFT" to "Lyft",
        "AIR CANADA" to "Air Canada", "WESTJET" to "WestJet", "AIRBNB" to "Airbnb", "YOUTUBE" to "YouTube", "OPENAI" to "OpenAI",
        "GOOGLE ONE" to "Google One", "MICROSOFT" to "Microsoft", "KOODO" to "Koodo", "FIDO" to "Fido", "BELL MOBILITY" to "Bell",
        "BELL CANADA" to "Bell", "PUBLIC MOBILE" to "Public Mobile", "BCAA" to "BCAA", "GOODLIFE" to "GoodLife Fitness",
        "FRESHCO" to "FreshCo", "THRIFTY FOODS" to "Thrifty Foods", "H MART" to "H Mart", "HMART" to "H Mart", "KFC" to "KFC",
        "POPEYES" to "Popeyes", "BURGER KING" to "Burger King", "FIVE GUYS" to "Five Guys", "CHIPOTLE" to "Chipotle",
        "PANAGO" to "Panago", "PIZZA HUT" to "Pizza Hut", "BOOSTER JUICE" to "Booster Juice", "CIRCLE K" to "Circle K",
        "HUSKY" to "Husky", "MOBIL" to "Mobil", "LORDCO" to "Lordco", "KAL TIRE" to "Kal Tire", "EVO CAR SHARE" to "Evo",
        "MARSHALLS" to "Marshalls", "SEPHORA" to "Sephora", "ETSY" to "Etsy",
        "EBAY" to "eBay", "TICKETMASTER" to "Ticketmaster", "PLAYSTATION" to "PlayStation", "NINTENDO" to "Nintendo",
        "PATREON" to "Patreon", "DROPBOX" to "Dropbox", "ADOBE" to "Adobe", "AUDIBLE" to "Audible",
        "EXPEDIA" to "Expedia", "BOOKING.COM" to "Booking.com", "CANADA POST" to "Canada Post", "RONA" to "Rona", "LOWE'S" to "Lowe's",
    ).map { (k, v) -> Regex("(^|[^A-Z0-9])" + Regex.escape(k) + "($|[^A-Z0-9])") to v }

    fun clean(raw: String): String {
        var s = raw.uppercase().replace(Regex("\\s+"), " ").trim()
        KIND_NAMES.firstOrNull { (re, _) -> re.containsMatchIn(s) }?.let { return it.second }

        // Bank prefixes can stack: "POINT OF SALE PURCHASE FPOS SQ *..."
        repeat(4) {
            val p = PREFIXES.firstOrNull { p -> s.startsWithPrefix(p) } ?: return@repeat
            s = s.removePrefix(p).trim()
        }
        // Province + country glued at the end ("VANCOUVER BCCA", "RIDGEBCCA"), then glued cities.
        s = s.replace(Regex("($PROVINCES)CA$"), " $1").replace(GLUED_CITY, "$1 $2")
        ALIASES.firstOrNull { (re, _) -> re.containsMatchIn(s) }?.let { return it.second }

        s = s
            .replace(Regex("^#\\s?\\d+\\s+"), "") // "#229 MARK'S"
            .replace(Regex("\\s+#\\s?\\d+.*$"), "") // store numbers and everything after: "#2231 VANCOUVER BC"
            .replace(Regex("\\s+(STORE|STR|NO\\.?)\\s?#?\\d+.*$"), "")
            .replace(Regex("\\s+\\+?\\d[\\d-]{2,}.*$"), "") // trailing terminal ids / phone numbers
            .replace(Regex("\\s+($COUNTRIES)$"), "")
            .replace(Regex("\\s+(" + CITIES.joinToString("|") { Regex.escape(it) } + ")(\\s+($PROVINCES))?$"), "")
            .replace(Regex("\\s+($PROVINCES)$"), "")
            .replace(Regex("[*#]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            // Names cut off at the bank's column width end mid-phrase: "BREKA BAKERY &", "JOE'S BAR AND".
            .replace(Regex("(\\s+(&|AND|-|/|\\+))+$"), "")
            .trim()
        if (s.isEmpty()) s = raw.trim()
        return titleCase(s)
    }

    private val ANY_GLUED_CITY = Regex("([A-Z0-9'&.])(" + CITIES.joinToString("|") { Regex.escape(it) } + ")\\b")
    private val GLUED_PROVINCE = Regex("($PROVINCES)CA\\b")

    /**
     * For matching only: [upper] with province codes and every known city split off the word they were
     * glued to ("COFFEEDELTA BCCA" -> "COFFEE DELTA BC"). Unlike [clean] it splits short city names too,
     * because a wrong split here can only add a word to match, never spoil a displayed name.
     */
    fun deglue(upper: String): String = upper.replace(GLUED_PROVINCE, " $1").replace(ANY_GLUED_CITY, "$1 $2")

    private fun titleCase(s: String): String = s.lowercase().split(" ").joinToString(" ") { w ->
        if (w.uppercase() in setOf("BC", "ICBC", "ATM", "IGA", "USA", "UBC", "SFU")) w.uppercase()
        else w.replaceFirstChar { it.titlecase() }
    }
}
