package ca.emarshall.finance.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** A transaction joined with its account and category, for lists and analytics. */
data class TxRow(
    val id: Long,
    val accountId: Long,
    val accountNickname: String,
    val accountType: String,
    val date: LocalDate,
    val amountCents: Long,
    val currency: String,
    val rawDescription: String,
    val merchant: String,
    val categoryId: Long?,
    val categoryKey: String?,
    val categoryName: String?,
    val categoryKind: String?,
    val categoryColor: Long?,
    val categoryIcon: String?,
    val categoryLocked: Boolean,
    val transferLinkId: Long?,
    val transferLocked: Boolean,
    val source: String,
    val notes: String?,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY institution, nickname")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY institution, nickname")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE questradeNumber = :number LIMIT 1")
    suspend fun byQuestradeNumber(number: String): AccountEntity?

    @Insert
    suspend fun insert(a: AccountEntity): Long

    @Update
    suspend fun update(a: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM accounts WHERE confirmed = 0 ORDER BY createdAtMillis")
    fun observeUnconfirmed(): Flow<List<AccountEntity>>

    @Query("UPDATE accounts SET confirmed = 0 WHERE questradeNumber IS NULL")
    suspend fun markStatementAccountsUnconfirmed()

    // Merging one account into another. Rows that already exist in the target (same fingerprint or
    // date) are left behind and go when the source account is deleted.
    @Query("UPDATE OR IGNORE transactions SET accountId = :into WHERE accountId = :from")
    suspend fun moveTransactions(from: Long, into: Long)

    @Query("UPDATE transactions SET transferLinkId = NULL WHERE transferLinkId IN (SELECT id FROM transactions WHERE accountId = :from)")
    suspend fun unlinkLeftovers(from: Long)

    @Query("UPDATE import_batches SET accountId = :into WHERE accountId = :from")
    suspend fun moveBatches(from: Long, into: Long)

    @Query("UPDATE OR IGNORE balance_snapshots SET accountId = :into WHERE accountId = :from")
    suspend fun moveBalances(from: Long, into: Long)

    @Query("UPDATE OR IGNORE csv_profiles SET accountId = :into WHERE accountId = :from")
    suspend fun moveCsvProfile(from: Long, into: Long)
}

private const val TX_SELECT = """
    SELECT t.id, t.accountId, a.nickname AS accountNickname, a.type AS accountType, t.date, t.amountCents, t.currency,
           t.rawDescription, t.merchant, t.categoryId, c.`key` AS categoryKey, c.name AS categoryName, c.kind AS categoryKind,
           c.color AS categoryColor, c.icon AS categoryIcon, t.categoryLocked, t.transferLinkId, t.transferLocked, t.source, t.notes
    FROM transactions t
    JOIN accounts a ON a.id = t.accountId
    LEFT JOIN categories c ON c.id = t.categoryId
"""

@Dao
interface TransactionDao {
    @Query("$TX_SELECT ORDER BY t.date DESC, t.id DESC")
    fun observeAllRows(): Flow<List<TxRow>>

    @Query("$TX_SELECT ORDER BY t.date DESC, t.id DESC")
    suspend fun allRows(): List<TxRow>

    @Query(
        """$TX_SELECT
        WHERE (:accountId IS NULL OR t.accountId = :accountId)
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND t.date BETWEEN :from AND :to
          AND (:q = '' OR t.merchant LIKE '%' || :q || '%' OR t.rawDescription LIKE '%' || :q || '%' OR t.notes LIKE '%' || :q || '%')
        ORDER BY t.date DESC, t.id DESC
        LIMIT 20000""",
    )
    fun search(accountId: Long?, categoryId: Long?, from: LocalDate, to: LocalDate, q: String): Flow<List<TxRow>>

    @Query("$TX_SELECT WHERE t.id = :id")
    fun observeRow(id: Long): Flow<TxRow?>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT fingerprint FROM transactions WHERE accountId = :accountId")
    suspend fun fingerprints(accountId: Long): List<String>

    @Query("SELECT fitId FROM transactions WHERE accountId = :accountId AND fitId IS NOT NULL")
    suspend fun fitIds(accountId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(t: TransactionEntity)

    @Update
    suspend fun updateAll(t: List<TransactionEntity>)

    @Query("UPDATE transactions SET categoryId = :categoryId, categoryLocked = :locked WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long, locked: Boolean)

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE LOWER(merchant) = LOWER(:merchant) AND transferLinkId IS NULL")
    suspend fun setCategoryForMerchant(merchant: String, categoryId: Long): Int

    @Query("UPDATE transactions SET categoryId = NULL, categoryLocked = 0 WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Query("UPDATE transactions SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String?)

    @Query("UPDATE transactions SET transferLinkId = :linkId, transferLocked = :locked WHERE id = :id")
    suspend fun setTransferLink(id: Long, linkId: Long?, locked: Boolean)

    @Query("UPDATE transactions SET transferLinkId = NULL WHERE transferLocked = 0 AND transferLinkId IS NOT NULL")
    suspend fun unlinkUnconfirmedTransfers()

    @Query("DELETE FROM transactions WHERE importBatchId = :batchId")
    suspend fun deleteBatch(batchId: Long): Int

    @Query("UPDATE transactions SET transferLinkId = NULL WHERE transferLinkId IN (SELECT id FROM transactions WHERE importBatchId = :batchId)")
    suspend fun unlinkFromBatch(batchId: Long)

    @Query("SELECT MAX(date) FROM transactions WHERE accountId = :accountId")
    suspend fun latestDate(accountId: Long): LocalDate?

    @Query("SELECT accountId, MAX(date) AS latest FROM transactions GROUP BY accountId")
    fun observeLatestDates(): Flow<List<AccountLatest>>

    @Query("SELECT COUNT(*) FROM transactions")
    fun observeCount(): Flow<Int>
}

data class AccountLatest(val accountId: Long, val latest: LocalDate?)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY kind, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY kind, name")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE `key` = :key")
    suspend fun byKey(key: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(c: List<CategoryEntity>): List<Long>

    @Insert
    suspend fun insert(c: CategoryEntity): Long

    @Update
    suspend fun update(c: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id AND builtIn = 0")
    suspend fun deleteCustom(id: Long)

    @Query("SELECT * FROM category_rules ORDER BY priority DESC, id")
    fun observeRules(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules")
    suspend fun rules(): List<CategoryRuleEntity>

    @Query("SELECT * FROM amount_rules ORDER BY name")
    fun observeAmountRules(): Flow<List<AmountRuleEntity>>

    @Query("SELECT * FROM amount_rules")
    suspend fun amountRules(): List<AmountRuleEntity>

    @Query("SELECT * FROM amount_rules WHERE id = :id")
    suspend fun amountRule(id: Long): AmountRuleEntity?

    @androidx.room.Upsert
    suspend fun upsertAmountRule(r: AmountRuleEntity): Long

    @Query("DELETE FROM amount_rules WHERE id = :id")
    suspend fun deleteAmountRule(id: Long)

    @Insert
    suspend fun insertRule(r: CategoryRuleEntity): Long

    @Insert
    suspend fun insertRules(r: List<CategoryRuleEntity>)

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    /** Built-in rules used to be copied into this table (never learned, priority under 500); they live in code now. */
    @Query("DELETE FROM category_rules WHERE learned = 0 AND priority < 500")
    suspend fun deleteStoredSeedRules()

    @Query("UPDATE category_rules SET pattern = :to WHERE matchType = 'MERCHANT' AND LOWER(pattern) = LOWER(:from)")
    suspend fun renameMerchantRule(from: String, to: String)

    @Query("DELETE FROM category_rules WHERE learned = 1 AND LOWER(pattern) = LOWER(:merchant)")
    suspend fun deleteLearnedFor(merchant: String)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface ImportDao {
    @Insert
    suspend fun insertBatch(b: ImportBatchEntity): Long

    @Update
    suspend fun updateBatch(b: ImportBatchEntity)

    @Query("SELECT * FROM import_batches ORDER BY importedAtMillis DESC")
    fun observeBatches(): Flow<List<ImportBatchEntity>>

    @Query("SELECT * FROM import_batches WHERE sha256 = :sha LIMIT 1")
    suspend fun bySha(sha: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun batch(id: Long): ImportBatchEntity?

    @Query("DELETE FROM import_batches WHERE id = :id")
    suspend fun deleteBatch(id: Long)

    @Query("SELECT MAX(dateTo) FROM import_batches WHERE accountId = :accountId")
    suspend fun importedThrough(accountId: Long): LocalDate?

    @Query("SELECT * FROM csv_profiles WHERE accountId = :accountId")
    suspend fun profile(accountId: Long): CsvProfileEntity?

    @Upsert
    suspend fun saveProfile(p: CsvProfileEntity)

    @Query("SELECT COUNT(*) FROM import_batches")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM import_batches")
    suspend fun allBatches(): List<ImportBatchEntity>

    @Query("SELECT * FROM import_batches WHERE hasFile = 1")
    suspend fun batchesWithFiles(): List<ImportBatchEntity>
}

@Dao
interface BalanceDao {
    /**
     * One balance per account, day and currency. REPLACE, not @Upsert: an upsert only looks at the primary
     * key, so a newer balance for the same day (a second Questrade sync, an overlapping statement) was dropped.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BalanceSnapshotEntity>)

    @Query("SELECT * FROM balance_snapshots ORDER BY date")
    fun observeAll(): Flow<List<BalanceSnapshotEntity>>

    @Query("SELECT * FROM balance_snapshots ORDER BY date")
    suspend fun all(): List<BalanceSnapshotEntity>

    @Query("DELETE FROM balance_snapshots WHERE accountId = :accountId AND source = :source AND date BETWEEN :from AND :to")
    suspend fun deleteRange(accountId: Long, source: String, from: LocalDate, to: LocalDate)
}

@Dao
interface QuestradeDao {
    @Insert
    suspend fun insertBalances(rows: List<QtBalanceEntity>)

    @Insert
    suspend fun insertPositions(rows: List<QtPositionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivities(rows: List<QtActivityEntity>): List<Long>

    @Query("SELECT * FROM qt_balances WHERE takenAtMillis = (SELECT MAX(takenAtMillis) FROM qt_balances b2 WHERE b2.accountId = qt_balances.accountId)")
    fun observeLatestBalances(): Flow<List<QtBalanceEntity>>

    @Query("SELECT * FROM qt_positions WHERE takenAtMillis = (SELECT MAX(takenAtMillis) FROM qt_positions p2 WHERE p2.accountId = qt_positions.accountId)")
    fun observeLatestPositions(): Flow<List<QtPositionEntity>>

    @Query("SELECT * FROM qt_activities ORDER BY transactionDate DESC, id DESC")
    fun observeActivities(): Flow<List<QtActivityEntity>>

    @Query("DELETE FROM qt_positions WHERE accountId = :accountId AND takenAtMillis < :before")
    suspend fun prunePositions(accountId: Long, before: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    suspend fun all(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE accountId = :accountId")
    suspend fun forAccount(accountId: Long): ReminderEntity?

    @Upsert
    suspend fun upsert(r: ReminderEntity): Long

    @Query("UPDATE reminders SET nextFireAtMillis = :millis WHERE id = :id")
    suspend fun setNextFire(id: Long, millis: Long?)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE active = 1 ORDER BY createdAtMillis")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun get(id: Long): GoalEntity?

    @Upsert
    suspend fun upsert(g: GoalEntity): Long

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun delete(id: Long)
}
