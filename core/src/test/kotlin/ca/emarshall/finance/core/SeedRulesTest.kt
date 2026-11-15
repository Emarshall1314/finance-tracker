package ca.emarshall.finance.core

import ca.emarshall.finance.core.categorize.Categorizer
import ca.emarshall.finance.core.categorize.DefaultCategories
import ca.emarshall.finance.core.categorize.MatchType
import ca.emarshall.finance.core.categorize.MerchantCleaner
import ca.emarshall.finance.core.categorize.Rule
import ca.emarshall.finance.core.categorize.SeedRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedRulesTest {
    private val cat = Categorizer(SeedRules.ALL)
    private fun c(raw: String, amount: Long = -1000) = cat.categorize(raw, MerchantCleaner.clean(raw), amount)

    private fun check(expected: String, vararg raws: String) {
        val wrong = raws.filter { c(it) != expected }.map { "$it -> ${c(it)}" }
        assertTrue("expected $expected: $wrong", wrong.isEmpty())
    }

    @Test fun wordsInsideOtherWordsDontMatch() {
        // Each of these used to land in the category of a shorter word hiding inside it.
        check("dining", "ESPRESSO BAR VANCOUVER BC", "SHAWARMA KING", "POKE TIME RICHMOND", "STEAMWORKS BREWING")
        check("shopping", "POKEMON CENTER")
        check(DefaultCategories.FALLBACK_EXPENSE, "TAIGA WORKS", "THE COMMODORE", "KINGSTON SUPPLY", "HUBERT LTD", "BROWN & SONS", "TACOMA WA")
        assertEquals(DefaultCategories.FALLBACK_INCOME, c("KINGSTON SUPPLY", 5000))
    }

    @Test fun describingWordsSayWhatAPlaceIs() {
        check(
            "dining", "Point of sale purchase Opos Golden Dragon Sushi Vancouver BCCA", "SQ *LUCKY PHO", "TST-BREKA BAKERY & VANCOUVER",
            "MAMA'S DUMPLING HOUSE", "RAIN CITY BURGERS", "THE LOCAL PUB", "SUNSET GRILLE", "BELLA TRATTORIA", "NOODLE HUT",
            "CORNER BISTRO", "Point of sale purchase Fpos Blue Kettle Coffeedelta BCCA", "TACOS EL REY", "GELATERIA DOLCE", "SZECHUAN GARDEN RESTAURANT",
        )
        check("groceries", "KIM'S SUPERMARKET", "VALLEY FARM MARKET", "CITY PRODUCE", "WINDSOR MEATS", "SUNNY GROCERY")
        check("health", "MAIN STREET DENTAL", "KITS PHYSIOTHERAPY", "DR SMITH OPTOMETRY", "WESTSIDE PHARMACY", "BROADWAY MEDICAL CLINIC", "CITY CHIROPRACTIC")
        check("personal_care", "FADE BARBERSHOP", "LUXE HAIR SALON", "HAPPY NAILS", "GLOW SPA", "QUICK DRY CLEANERS")
        check("pets", "WEST END VETERINARY", "PET FOOD N MORE", "BOSLEY'S YALETOWN")
        check("recreation", "THE HIVE BOULDERING", "ANYTIME FITNESS", "MOKSHA YOGA", "GROUSE MOUNTAIN", "FRASERVIEW GOLF COURSE", "KITSILANO COMMUNITY CENTRE")
        check("alcohol", "BC LIQUOR STORE #123", "BROADWAY LIQUOR", "CITY CANNABIS CO", "SWIRL WINE STORE")
        check("auto", "MAIN ST AUTO REPAIR", "KAL TIRE #44", "ACE COLLISION", "LORDCO PARTS")
        check("transit", "IMPARK00012345", "CITY PARKING LOT 4", "YELLOW CAB", "UBER CANADA/UBERTRIP TORONTO ON", "COMPASS VENDING")
        check("travel", "SEASIDE HOTEL", "HARBOUR AIR", "BC FERRIES TSAWWASSEN", "MOUNTAIN VIEW MOTEL")
        check("home", "VALLEY HARDWARE", "COAST FURNITURE", "HOME DEPOT #7001")
        check("entertainment", "RIO THEATRE", "CINEPLEX #1234", "STEAMGAMES.COM", "GRANVILLE BOWLING")
        check("education", "UBC TUITION", "LITTLE STARS DAYCARE")
        check("government", "CANADA REVENUE AGENCY", "SERVICE BC")
        check("gifts", "ROSE FLORIST", "CANADAHELPS")
        check("subscriptions", "GOOGLE *YOUTUBE", "APPLE.COM/BILL", "CHATGPT SUBSCRIPTION", "DISNEY PLUS")
        check("insurance", "BCAA INSURANCE", "TRAVEL INSURANCE CO")
        check("phone", "ROGERS WIRELESS", "SHAW CABLESYSTEMS", "KOODO MOBILE")
        check("fuel", "ESSO 7-ELEVEN 12345", "SHELL C01234", "HUSKY #5521", "CHEVRON 0123456")
    }

    @Test fun aNamedChainBeatsADescribingWord() {
        assertEquals("dining", c("TACO BELL #123")) // not Phone (BELL)
        assertEquals("entertainment", c("ROGERS ARENA")) // not Phone (ROGERS)
        assertEquals("home", c("KITCHEN STUFF PLUS")) // not Dining (KITCHEN)
        assertEquals("fuel", c("CANADIAN TIRE GAS BAR")) // not Dining (BAR) or Auto
        assertEquals("recreation", c("MODO YOGA")) // not Transit (MODO)
        assertEquals("transit", c("INDIGO PARK CANADA")) // not Shopping (INDIGO)
    }

    @Test fun exactMerchantRulesDontCatchLongerNames() {
        val cz = Categorizer(SeedRules.ALL + Rule(pattern = "Uber", matchType = MatchType.MERCHANT, categoryKey = "travel", priority = 1000, learned = true))
        assertEquals("travel", cz.categorize("UBER *TRIP", "Uber", -1500))
        assertEquals("dining", cz.categorize("UBER EATS", "Uber Eats", -1500))
    }

    @Test fun cleanerDropsProcessorTagsAndCutOffEndings() {
        assertEquals("Breka Bakery", MerchantCleaner.clean("Point of sale purchase Opos Tst-Breka Bakery & Vancouver BCCA"))
        assertEquals("Questrade", MerchantCleaner.clean("Customer transfer Dr. QUESTRADE INC"))
    }

    @Test fun everyRuleNamesARealCategory() {
        val keys = DefaultCategories.ALL.map { it.key }.toSet()
        val bad = SeedRules.ALL.filter { it.categoryKey !in keys }.map { it.pattern }
        assertTrue("rules with unknown categories: $bad", bad.isEmpty())
    }
}
