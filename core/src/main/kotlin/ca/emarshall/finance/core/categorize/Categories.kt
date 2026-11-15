package ca.emarshall.finance.core.categorize

import ca.emarshall.finance.core.model.CategoryKind

/** A built-in category. [key] is stable and used by seed rules; names can be renamed by the user. */
data class CategorySeed(val key: String, val name: String, val kind: CategoryKind, val icon: String, val color: Long)

object DefaultCategories {
    // Icon names map to Material icons in the app; colours are ARGB.
    val ALL: List<CategorySeed> = listOf(
        CategorySeed("groceries", "Groceries", CategoryKind.EXPENSE, "shopping_basket", 0xFF2E7D32),
        CategorySeed("dining", "Dining & Coffee", CategoryKind.EXPENSE, "restaurant", 0xFFEF6C00),
        CategorySeed("fuel", "Fuel", CategoryKind.EXPENSE, "local_gas_station", 0xFF6D4C41),
        CategorySeed("auto", "Auto & Parts", CategoryKind.EXPENSE, "directions_car", 0xFF546E7A),
        CategorySeed("transit", "Transit & Parking", CategoryKind.EXPENSE, "directions_bus", 0xFF0277BD),
        CategorySeed("shopping", "Shopping", CategoryKind.EXPENSE, "shopping_bag", 0xFFAD1457),
        CategorySeed("home", "Home & Hardware", CategoryKind.EXPENSE, "home_repair_service", 0xFF795548),
        CategorySeed("bills", "Bills & Utilities", CategoryKind.EXPENSE, "receipt_long", 0xFF5E35B1),
        CategorySeed("phone", "Phone & Internet", CategoryKind.EXPENSE, "wifi", 0xFF3949AB),
        CategorySeed("insurance", "Insurance", CategoryKind.EXPENSE, "shield", 0xFF00838F),
        CategorySeed("health", "Health & Pharmacy", CategoryKind.EXPENSE, "local_pharmacy", 0xFFC62828),
        CategorySeed("entertainment", "Entertainment", CategoryKind.EXPENSE, "movie", 0xFF8E24AA),
        CategorySeed("subscriptions", "Subscriptions", CategoryKind.EXPENSE, "autorenew", 0xFF7B1FA2),
        CategorySeed("travel", "Travel", CategoryKind.EXPENSE, "flight", 0xFF00897B),
        CategorySeed("gifts", "Gifts & Donations", CategoryKind.EXPENSE, "redeem", 0xFFD81B60),
        CategorySeed("etransfer_sent", "e-Transfers Sent", CategoryKind.EXPENSE, "send", 0xFF455A64),
        CategorySeed("fees", "Fees & Interest", CategoryKind.EXPENSE, "percent", 0xFFB71C1C),
        CategorySeed("cash", "Cash & ATM", CategoryKind.EXPENSE, "local_atm", 0xFF558B2F),
        CategorySeed("other", "Other", CategoryKind.EXPENSE, "category", 0xFF757575),
        CategorySeed("paycheque", "Paycheque", CategoryKind.INCOME, "payments", 0xFF1B5E20),
        CategorySeed("investment_income", "Investment Income", CategoryKind.INCOME, "trending_up", 0xFF004D40),
        CategorySeed("refunds", "Refunds", CategoryKind.INCOME, "undo", 0xFF33691E),
        CategorySeed("other_income", "Other Income", CategoryKind.INCOME, "savings", 0xFF2E7D32),
        CategorySeed("card_payment", "Card Payment", CategoryKind.TRANSFER, "credit_card", 0xFF607D8B),
        CategorySeed("between_accounts", "Between My Accounts", CategoryKind.TRANSFER, "swap_horiz", 0xFF78909C),
        CategorySeed("investments_transfer", "To/From Investments", CategoryKind.TRANSFER, "account_balance", 0xFF90A4AE),
        CategorySeed(SAVINGS_TRANSFER, "To/From Savings", CategoryKind.TRANSFER, "savings", 0xFF26A69A),
    )

    /**
     * Money moved between chequing and a savings account. A transfer out of chequing that isn't matched to
     * another account the app tracks is assumed to go to savings (the savings account has no statements).
     */
    const val SAVINGS_TRANSFER = "savings_transfer"
    const val BETWEEN_ACCOUNTS = "between_accounts"
    const val INVESTMENTS_TRANSFER = "investments_transfer"

    const val FALLBACK_EXPENSE = "other"
    const val FALLBACK_INCOME = "other_income"
}
