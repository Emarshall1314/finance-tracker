package ca.emarshall.finance.data

import androidx.room.withTransaction
import ca.emarshall.finance.core.categorize.AmountRule
import ca.emarshall.finance.core.categorize.Categorizer
import ca.emarshall.finance.data.db.AmountRuleEntity
import ca.emarshall.finance.core.categorize.DefaultCategories
import ca.emarshall.finance.core.categorize.MatchType
import ca.emarshall.finance.core.categorize.Rule
import ca.emarshall.finance.core.categorize.SeedRules
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.data.db.CategoryDao
import ca.emarshall.finance.data.db.CategoryEntity
import ca.emarshall.finance.data.db.CategoryRuleEntity
import ca.emarshall.finance.data.db.FinanceDatabase
import ca.emarshall.finance.data.db.TransactionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** A category's key for the core categorizer: built-ins use their stable key, custom ones "id:<id>". */
val CategoryEntity.ruleKey: String get() = key ?: "id:$id"
val CategoryEntity.kindEnum: CategoryKind get() = runCatching { CategoryKind.valueOf(kind) }.getOrDefault(CategoryKind.EXPENSE)

/** Categorizer plus lookups between category keys and database ids. */
class CategorizerSnapshot(val categorizer: Categorizer, private val byKey: Map<String, CategoryEntity>) {
    /** [useAmountRules] is false for investment accounts: "rent is $1,800" never applies inside Questrade. */
    fun categoryIdFor(
        raw: String, merchant: String, amountCents: Long, date: java.time.LocalDate? = null, useAmountRules: Boolean = true,
        /** In a chequing account, a transfer between "my accounts" is a move to or from savings. */
        chequing: Boolean = false,
    ): Long? {
        val key = categorizer.categorize(raw, merchant, amountCents, date, useAmountRules)
        val adjusted = if (chequing && key == DefaultCategories.BETWEEN_ACCOUNTS && DefaultCategories.SAVINGS_TRANSFER in byKey) DefaultCategories.SAVINGS_TRANSFER else key
        return byKey[adjusted]?.id
    }
    fun idOf(key: String): Long? = byKey[key]?.id
}

@Singleton
class CategoryRepository @Inject constructor(
    private val db: FinanceDatabase,
    private val dao: CategoryDao,
    private val txDao: TransactionDao,
) {
    val categories: Flow<List<CategoryEntity>> = dao.observeAll()
    val rules: Flow<List<CategoryRuleEntity>> = dao.observeRules()
    val amountRules: Flow<List<AmountRuleEntity>> = dao.observeAmountRules()
    private val seedLock = Mutex()

    @Volatile private var builtInsChecked = false

    /** Inserts the default categories and seed rules the first time, and built-in categories added in later versions. */
    suspend fun ensureSeeded() = seedLock.withLock {
        if (dao.count() > 0) {
            if (!builtInsChecked) {
                // Unique key: existing ones are ignored, new built-ins (e.g. To/From Savings) are added.
                dao.insertAll(DefaultCategories.ALL.map { CategoryEntity(key = it.key, name = it.name, kind = it.kind.name, icon = it.icon, color = it.color, builtIn = true) })
                builtInsChecked = true
            }
            return@withLock
        }
        db.withTransaction {
            dao.insertAll(DefaultCategories.ALL.map { CategoryEntity(key = it.key, name = it.name, kind = it.kind.name, icon = it.icon, color = it.color, builtIn = true) })
            val ids = dao.all().associate { (it.key ?: "") to it.id }
            dao.insertRules(
                SeedRules.ALL.mapNotNull { r ->
                    ids[r.categoryKey]?.let { cid ->
                        CategoryRuleEntity(pattern = r.pattern, matchType = r.matchType.name, categoryId = cid, priority = r.priority, learned = false, amountSign = r.amountSign)
                    }
                },
            )
        }
    }

    suspend fun snapshot(): CategorizerSnapshot {
        ensureSeeded()
        val cats = dao.all()
        val byId = cats.associateBy { it.id }
        val rules = dao.rules().mapNotNull { r ->
            val cat = byId[r.categoryId] ?: return@mapNotNull null
            Rule(r.id, r.pattern, runCatching { MatchType.valueOf(r.matchType) }.getOrDefault(MatchType.CONTAINS), cat.ruleKey, r.priority, r.learned, r.amountSign)
        }
        val amountRules = dao.amountRules().mapNotNull { r ->
            val cat = byId[r.categoryId] ?: return@mapNotNull null
            AmountRule(r.id, r.name, r.amountCents, cat.ruleKey, r.toleranceCents, r.dayOfMonth)
        }
        val transferKeys = cats.filter { it.kindEnum == CategoryKind.TRANSFER }.map { it.ruleKey }.toSet()
        return CategorizerSnapshot(Categorizer(rules, amountRules, transferKeys), cats.associateBy { it.ruleKey })
    }

    /**
     * "Always do this for <merchant>": a learned rule that beats the seed rules, applied to past
     * transactions from that merchant too. Returns how many transactions changed.
     */
    suspend fun learn(merchant: String, categoryId: Long): Int = db.withTransaction {
        dao.insertRule(CategoryRuleEntity(pattern = merchant, matchType = MatchType.CONTAINS.name, categoryId = categoryId, priority = 1000, learned = true))
        txDao.setCategoryForMerchant(merchant, categoryId)
    }

    suspend fun addCategory(name: String, kind: CategoryKind, color: Long): Long =
        dao.insert(CategoryEntity(name = name.trim(), kind = kind.name, icon = "label", color = color, builtIn = false))

    suspend fun rename(c: CategoryEntity, name: String) = dao.update(c.copy(name = name.trim()))
    suspend fun deleteCategory(id: Long) = dao.deleteCustom(id)

    suspend fun addRule(pattern: String, regex: Boolean, categoryId: Long, priority: Int = 500) =
        dao.insertRule(CategoryRuleEntity(pattern = pattern, matchType = (if (regex) MatchType.REGEX else MatchType.CONTAINS).name, categoryId = categoryId, priority = priority, learned = false))

    suspend fun deleteRule(id: Long) = dao.deleteRule(id)

    /**
     * Saves a known payment ("Rent, $1,800, around the 1st") and applies it to past transactions. With
     * [newCategoryName] a new spending category of that name is made for it. Returns how many past payments match.
     */
    suspend fun saveAmountRule(rule: AmountRuleEntity, newCategoryName: String?): Int {
        val categoryId = newCategoryName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            dao.all().firstOrNull { it.name.equals(name, ignoreCase = true) && it.kindEnum == CategoryKind.EXPENSE }?.id
                ?: addCategory(name, CategoryKind.EXPENSE, NEW_CATEGORY_COLORS[(name.lowercase().hashCode() and 0x7fffffff) % NEW_CATEGORY_COLORS.size])
        } ?: rule.categoryId
        val id = dao.upsertAmountRule(rule.copy(categoryId = categoryId)).let { if (it == -1L) rule.id else it }
        recategorizeAll()
        val snap = snapshot()
        val investment = investmentAccountIds()
        return txDao.all().count { t ->
            t.accountId !in investment && !t.categoryLocked && t.transferLinkId == null &&
                snap.categorizer.amountRuleFor(t.rawDescription, t.merchant, t.amountCents, t.date)?.id == id
        }
    }

    suspend fun amountRule(id: Long): AmountRuleEntity? = dao.amountRule(id)

    /** Removes a known payment; its transactions go back to what the text rules say. */
    suspend fun deleteAmountRule(id: Long) {
        dao.deleteAmountRule(id)
        recategorizeAll()
    }

    private suspend fun investmentAccountIds(): Set<Long> =
        db.accounts().all().filter { a -> runCatching { ca.emarshall.finance.core.model.AccountType.valueOf(a.type) }.getOrNull()?.isInvestment == true }
            .map { it.id }.toSet()

    /** Re-runs the rules over every transaction whose category the user didn't pick by hand. */
    suspend fun recategorizeAll(): Int {
        val snap = snapshot()
        val investment = investmentAccountIds()
        val chequing = db.accounts().all().filter { it.type == ca.emarshall.finance.core.model.AccountType.CHEQUING.name }.map { it.id }.toSet()
        val changed = txDao.all().filter { !it.categoryLocked && it.transferLinkId == null }.mapNotNull { t ->
            val id = snap.categoryIdFor(t.rawDescription, t.merchant, t.amountCents, t.date, useAmountRules = t.accountId !in investment, chequing = t.accountId in chequing)
            if (id != null && id != t.categoryId) t.copy(categoryId = id) else null
        }
        if (changed.isNotEmpty()) txDao.updateAll(changed)
        return changed.size
    }
}

private val NEW_CATEGORY_COLORS = listOf(0xFF6D4C41, 0xFF00796B, 0xFF5D4037, 0xFF3949AB, 0xFFAD1457, 0xFF00838F)
