package ca.emarshall.finance.data

import androidx.room.withTransaction
import ca.emarshall.finance.core.categorize.Categorizer
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
    fun categoryIdFor(raw: String, merchant: String, amountCents: Long): Long? =
        byKey[categorizer.categorize(raw, merchant, amountCents)]?.id
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
    private val seedLock = Mutex()

    /** Inserts the default categories and seed rules the first time. */
    suspend fun ensureSeeded() = seedLock.withLock {
        if (dao.count() > 0) return@withLock
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
        return CategorizerSnapshot(Categorizer(rules), cats.associateBy { it.ruleKey })
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

    /** Re-runs the rules over every transaction whose category the user didn't pick by hand. */
    suspend fun recategorizeAll(): Int {
        val snap = snapshot()
        val changed = txDao.all().filter { !it.categoryLocked && it.transferLinkId == null }.mapNotNull { t ->
            val id = snap.categoryIdFor(t.rawDescription, t.merchant, t.amountCents)
            if (id != null && id != t.categoryId) t.copy(categoryId = id) else null
        }
        if (changed.isNotEmpty()) txDao.updateAll(changed)
        return changed.size
    }
}
