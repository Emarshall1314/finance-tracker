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

class CategorizeTest {
    private val cat = Categorizer(SeedRules.ALL)
    private fun c(raw: String, amount: Long = -1000) = cat.categorize(raw, MerchantCleaner.clean(raw), amount)

    @Test fun seedRulesCoverCommonBcMerchants() {
        assertEquals("groceries", c("POS PURCHASE SAVE ON FOODS #2231 VANCOUVER BC"))
        assertEquals("groceries", c("REAL CDN SUPERSTORE #1520"))
        assertEquals("groceries", c("COSTCO WHOLESALE W548"))
        assertEquals("fuel", c("COSTCO GAS W548"))
        assertEquals("fuel", c("PETRO-CANADA 98765"))
        assertEquals("fuel", c("CANADIAN TIRE GAS BAR #123"))
        assertEquals("auto", c("CANADIAN TIRE #0123 BURNABY BC"))
        assertEquals("dining", c("TIM HORTONS #4410"))
        assertEquals("dining", c("UBER EATS"))
        assertEquals("transit", c("UBER *TRIP"))
        assertEquals("insurance", c("ICBC AUTOPLAN"))
        assertEquals("bills", c("BC HYDRO, ELECTRIC"))
        assertEquals("bills", c("FORTISBC GAS"))
        assertEquals("phone", c("TELUS MOBILITY"))
        assertEquals("subscriptions", c("NETFLIX.COM"))
        assertEquals("subscriptions", c("SPOTIFY P1234ABCD"))
        assertEquals("health", c("SHOPPERS DRUG MART #2212"))
        assertEquals("health", c("LONDON DRUGS 12"))
        assertEquals("home", c("CORONA HOME RONA #44"))
    }

    @Test fun feesInterestAndIncome() {
        assertEquals("fees", c("PURCHASE INTEREST", -1701))
        assertEquals("fees", c("SERVICE CHARGE MONTHLY FEE", -1695))
        assertEquals("fees", c("ANNUAL FEE", -12000))
        assertEquals("paycheque", c("PAYROLL DEPOSIT ACME WIDGETS INC", 245000))
        assertEquals("investment_income", c("INTEREST EARNED", 42))
        assertEquals("refunds", c("COSTCO WHOLESALE REFUND", 2000))
        assertEquals(DefaultCategories.FALLBACK_INCOME, c("MYSTERY CREDIT", 1000))
        assertEquals(DefaultCategories.FALLBACK_EXPENSE, c("MYSTERY SHOP", -1000))
    }

    @Test fun transfers() {
        assertEquals("card_payment", c("PAYMENT - THANK YOU", 50000))
        assertEquals("card_payment", c("MB-BILL PAYMENT TRIANGLE MASTERCARD", -50000))
        assertEquals("investments_transfer", c("Customer transfer Dr. QUESTRADE INC", -50000))
        assertEquals("etransfer_sent", c("INTERAC E-TRANSFER TO J SMITH", -4000))
        assertEquals("other_income", c("INTERAC E-TRANSFER FROM J SMITH", 4000))
    }

    @Test fun realStatementShapes() {
        assertEquals("card_payment", c("Credit Card/LOC payment Main and Example Vancouver British Columbia", -30000))
        assertEquals("card_payment", c("SCOTIABANK TRANSIT 10520 VANCOUVER BC", 10000))
        assertEquals("card_payment", c("CTFS.COM/PAYMENTS OAKVILLE ON", 5000))
        assertEquals("investments_transfer", c("IO Transfer to Inv 000000999999999", -20000))
        assertEquals("investments_transfer", c("Misc. payment Questrade Inc", -20000))
        assertEquals("etransfer_sent", c("Withdrawal 12345678 Free Interac E-Transfer", -5000))
        assertEquals("etransfer_sent", c("Deposit 12345678 Interac E-Transfer Rtn", 5000))
        assertEquals("paycheque", c("Payroll dep. Example Employer Ltd", 200000))
        assertEquals("bills", c("Hydro bill B.C. Hydro & Power Authority", -8025))
        assertEquals("insurance", c("Auto insurance Insurance Corporation of BC", -36570))
        assertEquals("fees", c("NSF cheque charge", -4800))
        assertEquals("fees", c("INTEREST CHARGES-PURCHASE", -123))
        assertEquals("fees", c("INTEREST CHARGES", -111))
        assertEquals("fuel", c("Point of sale purchase Fpos Chv12345 Canyon", -2000))
        assertEquals("fuel", c("Error correction Fpos Chv12345 Canyon Chevnorth Vancobcca", 2000))
        assertEquals("groceries", c("Point of sale purchase Fpos Costco Wholesale W54Burnaby BCCA", -5436))
        assertEquals("cash", c("Shared ABM withdrawal Interac", -20400))
        assertEquals("auto", c("CDN TIRE STORE #00001 VANCOUVER", -2500))
    }

    @Test fun learnedRulesWin() {
        val learned = Rule(pattern = "Tim Hortons", categoryKey = "gifts", priority = 1000, learned = true)
        val cz = Categorizer(SeedRules.ALL + learned)
        assertEquals("gifts", cz.categorize("TIM HORTONS #4410", "Tim Hortons", -345))
    }

    @Test fun regexRules() {
        val cz = Categorizer(listOf(Rule(pattern = "^ACME\\b", matchType = MatchType.REGEX, categoryKey = "shopping")))
        assertEquals("shopping", cz.categorize("ACME STORE", "Acme Store", -100))
        assertEquals(DefaultCategories.FALLBACK_EXPENSE, cz.categorize("NOT ACME", "Not Acme", -100))
    }

    @Test fun invalidRegexIsIgnored() {
        val cz = Categorizer(listOf(Rule(pattern = "([", matchType = MatchType.REGEX, categoryKey = "shopping")))
        assertEquals(DefaultCategories.FALLBACK_EXPENSE, cz.categorize("anything", "anything", -1))
    }

    @Test fun merchantCleaning() {
        assertEquals("Save-On-Foods", MerchantCleaner.clean("POS PURCHASE SAVE ON FOODS #2231 VANCOUVER BC"))
        assertEquals("Tim Hortons", MerchantCleaner.clean("TIM HORTONS #4410"))
        assertEquals("Petro-Canada", MerchantCleaner.clean("PETRO-CANADA 98765, NORTH VAN BC"))
        assertEquals("Joe's Pizza", MerchantCleaner.clean("SQ *JOE'S PIZZA VANCOUVER BC"))
        assertEquals("Blue Fin Sushi", MerchantCleaner.clean("BLUE FIN SUSHI 000123456"))
        assertEquals("Amazon", MerchantCleaner.clean("AMZN MKTP CA*2K3J45"))
        // Scotiabank chequing PDF shapes: stacked prefixes, glued cities, province+country suffix.
        assertEquals("Costco", MerchantCleaner.clean("Point of sale purchase Fpos Costco Wholesale W54Burnaby BCCA"))
        assertEquals("Blue Kettle Coffee", MerchantCleaner.clean("Point of sale purchase Fpos Blue Kettle Coffeevancouver BCCA"))
        assertEquals("Example Poke", MerchantCleaner.clean("Point of sale purchase Fpos Sq *Example Poke Richmond BCCA"))
        assertEquals("Credit card payment", MerchantCleaner.clean("Credit Card/LOC payment Main and Example Vancouver British Columbia"))
        assertEquals("Interac e-Transfer sent", MerchantCleaner.clean("Withdrawal 12345678 Free Interac E-Transfer"))
        assertEquals("Interac e-Transfer received", MerchantCleaner.clean("Deposit 87654321 Free Interac E-Transfer"))
        assertEquals("Payment received", MerchantCleaner.clean("SCOTIABANK TRANSIT 10520 VANCOUVER BC"))
        assertEquals("Canadian Tire", MerchantCleaner.clean("CDN TIRE STORE #00001 VANCOUVER"))
        assertEquals("Uber", MerchantCleaner.clean("UBER CANADA/UBERTRIP     TORONTO      ON"))
        assertEquals("Commission Shop", MerchantCleaner.clean("COMMISSION SHOP VANCOUVER BC"))
        // A short alias must not match inside other words.
        assertTrue(MerchantCleaner.clean("BIGAL DELI") != "IGA")
    }

    @Test fun defaultCategoryKeysAreUnique() {
        val keys = DefaultCategories.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val seedKeys = SeedRules.ALL.map { it.categoryKey }.toSet()
        assertTrue("every seed rule points at a real category", keys.containsAll(seedKeys))
    }
}
