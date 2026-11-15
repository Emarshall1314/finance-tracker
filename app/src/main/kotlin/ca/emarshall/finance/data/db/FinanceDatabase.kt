package ca.emarshall.finance.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.time.LocalDate

class Converters {
    @TypeConverter fun fromDate(d: LocalDate?): Long? = d?.toEpochDay()

    @TypeConverter fun toDate(v: Long?): LocalDate? = v?.let { LocalDate.ofEpochDay(it) }
}

@Database(
    entities = [
        AccountEntity::class, TransactionEntity::class, CategoryEntity::class, CategoryRuleEntity::class,
        ImportBatchEntity::class, CsvProfileEntity::class, BalanceSnapshotEntity::class, QtBalanceEntity::class,
        QtPositionEntity::class, QtActivityEntity::class, ReminderEntity::class, GoalEntity::class, AmountRuleEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
@TypeConverters(Converters::class)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun transactions(): TransactionDao
    abstract fun categories(): CategoryDao
    abstract fun imports(): ImportDao
    abstract fun balances(): BalanceDao
    abstract fun questrade(): QuestradeDao
    abstract fun reminders(): ReminderDao
    abstract fun goals(): GoalDao

    companion object {
        const val NAME = "finance.db"

        /** Opens the SQLCipher-encrypted database. [passphrase] comes from the Keystore-protected SecretStore. */
        fun open(context: Context, passphrase: ByteArray): FinanceDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, FinanceDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
