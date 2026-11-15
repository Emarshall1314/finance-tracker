package ca.emarshall.finance.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [ca.emarshall.finance.core.model.Institution] name. */
    val institution: String,
    val nickname: String,
    /** [ca.emarshall.finance.core.model.AccountType] name. */
    val type: String,
    val currency: String = "CAD",
    val last4: String? = null,
    /** Statement closing day, 1-31. */
    val closingDay: Int? = null,
    /** Questrade account number (needed for API calls; never exported). */
    val questradeNumber: String? = null,
    /** Last day Questrade activities were synced through. */
    val questradeSyncedThrough: LocalDate? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** False for an account the app set up from a statement until the user says the guess is right. */
    @ColumnInfo(defaultValue = "1") val confirmed: Boolean = true,
    /** Earlier last-four digits (a reissued card, or an account merged into this one), comma-separated. */
    val otherLast4: String? = null,
)

/** Every ending this account has had, for matching statements to it. */
val AccountEntity.allLast4: Set<String> get() = setOfNotNull(last4) + otherLast4.orEmpty().split(',').filter { it.isNotBlank() }

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["accountId", "fingerprint"], unique = true),
        Index("accountId"), Index("date"), Index("categoryId"), Index("importBatchId"), Index("fitId"), Index("transferLinkId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val date: LocalDate,
    /** Cents; negative = money left the account. */
    val amountCents: Long,
    val currency: String = "CAD",
    val rawDescription: String,
    val merchant: String,
    val categoryId: Long? = null,
    /** Set when the user picked the category; rules never override it. */
    val categoryLocked: Boolean = false,
    /** The other half of a transfer between my accounts. */
    val transferLinkId: Long? = null,
    /** The user confirmed or unlinked this transfer; automatic matching leaves it alone. */
    val transferLocked: Boolean = false,
    /** [ca.emarshall.finance.core.model.TxSource] name. */
    val source: String,
    val importBatchId: Long? = null,
    val fingerprint: String,
    val fitId: String? = null,
    val notes: String? = null,
)

@Entity(tableName = "categories", indices = [Index(value = ["key"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable key for built-in categories; null for the user's own. */
    val key: String? = null,
    val name: String,
    /** [ca.emarshall.finance.core.model.CategoryKind] name. */
    val kind: String,
    val icon: String,
    val color: Long,
    val builtIn: Boolean,
)

@Entity(
    tableName = "category_rules",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("categoryId")],
)
data class CategoryRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    /** [ca.emarshall.finance.core.categorize.MatchType] name. */
    val matchType: String,
    val categoryId: Long,
    val priority: Int,
    val learned: Boolean,
    /** 0 = any, 1 = money in only, -1 = money out only. */
    val amountSign: Int = 0,
)

/** "I pay $X for <name> every month": see [ca.emarshall.finance.core.categorize.AmountRule]. */
@Entity(
    tableName = "amount_rules",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("categoryId")],
)
data class AmountRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amountCents: Long,
    val toleranceCents: Long = 0,
    val categoryId: Long,
    val dayOfMonth: Int? = null,
)

@Entity(
    tableName = "import_batches",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId"), Index("sha256")],
)
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val fileName: String,
    val sha256: String,
    val format: String,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val rowsRead: Int,
    val newCount: Int,
    val duplicateCount: Int,
    val importedAtMillis: Long,
    /** The statement period printed on the file (PDF/OFX), when it has one. Used for missing-statement checks. */
    @ColumnInfo(defaultValue = "NULL") val periodStart: LocalDate? = null,
    @ColumnInfo(defaultValue = "NULL") val periodEnd: LocalDate? = null,
    /** The original file is kept, encrypted, in the statement library. */
    @ColumnInfo(defaultValue = "0") val hasFile: Boolean = false,
)

@Entity(tableName = "csv_profiles")
data class CsvProfileEntity(
    @PrimaryKey val accountId: Long,
    /** [ca.emarshall.finance.core.csv.CsvProfile.encode] output. */
    val encoded: String,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "balance_snapshots",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "date", "currency"], unique = true)],
)
data class BalanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val date: LocalDate,
    /** Positive = asset, negative = owed. */
    val balanceCents: Long,
    val currency: String = "CAD",
    val source: String,
)

/** Questrade balances, one row per currency (combined = Questrade's combined-in-CAD figures). */
@Entity(
    tableName = "qt_balances",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId")],
)
data class QtBalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val takenAtMillis: Long,
    val currency: String,
    val combined: Boolean,
    val cashCents: Long,
    val marketValueCents: Long,
    val totalEquityCents: Long,
)

@Entity(
    tableName = "qt_positions",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("accountId"), Index("takenAtMillis")],
)
data class QtPositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val takenAtMillis: Long,
    val symbol: String,
    val symbolId: Long,
    /** Exact decimal as text: quantities can be fractional. */
    val quantity: String,
    val currency: String,
    val marketValueCents: Long,
    val currentPrice: String?,
    val averageEntryPrice: String?,
    val openPnlCents: Long?,
    val totalCostCents: Long?,
)

@Entity(
    tableName = "qt_activities",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "activityKey"], unique = true), Index("transactionDate")],
)
data class QtActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val activityKey: String,
    val tradeDate: LocalDate?,
    val transactionDate: LocalDate,
    val settlementDate: LocalDate?,
    val action: String,
    val symbol: String,
    val description: String,
    val currency: String,
    val quantity: String,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val type: String,
)

@Entity(
    tableName = "reminders",
    foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId"], unique = true)],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val closingDay: Int,
    val offsetDays: Int = 2,
    /** Minutes after midnight, local time. */
    val timeOfDayMinutes: Int = 9 * 60,
    val enabled: Boolean = true,
    val nextFireAtMillis: Long? = null,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [ca.emarshall.finance.core.goals.GoalType] name. */
    val type: String,
    val title: String,
    val targetCents: Long? = null,
    val targetPercent: Int? = null,
    /** Category key (built-in) or "id:<n>" (custom) for spending limits. */
    val categoryKey: String? = null,
    val start: java.time.LocalDate,
    val deadline: java.time.LocalDate? = null,
    /** Why the goal exists, e.g. Claude's reasoning when it suggested it. */
    val note: String? = null,
    val active: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
