package ca.emarshall.finance.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.QuestradeStatus
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.data.TransactionRepository
import ca.emarshall.finance.data.db.FinanceDatabase
import ca.emarshall.finance.security.SecretStore
import ca.emarshall.finance.statements.StatementFileStore
import ca.emarshall.finance.work.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a backup file holds, shown before it replaces anything. */
data class BackupSummary(val createdAtMillis: Long, val accounts: Int, val transactions: Int, val statementFiles: Int, val bytes: Int)

/**
 * A password-encrypted copy of everything in the app, for moving to a new phone or surviving a reinstall.
 * It holds every table of the database and, optionally, the original statement files. It never holds the
 * Questrade token or an AI key: those are entered again after a restore.
 *
 * The database is copied table by table and column by column rather than through the entity classes, so a
 * backup keeps working as the schema grows.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FinanceDatabase,
    private val files: StatementFileStore,
    private val categories: CategoryRepository,
    private val transactions: TransactionRepository,
    private val reminders: ReminderScheduler,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    /** Writes an encrypted backup to [uri]. */
    suspend fun create(uri: Uri, password: CharArray, includeStatements: Boolean): BackupSummary = withContext(Dispatchers.IO) {
        val sdb = db.openHelper.readableDatabase
        val tables = LinkedHashMap<String, List<BackupRow>>()
        for (table in tableNames()) {
            tables[table] = sdb.query("SELECT * FROM `$table`").use { c -> generateSequence { if (c.moveToNext()) row(c) else null }.toList() }
        }
        val statementFiles = if (!includeStatements) emptyMap() else
            db.imports().batchesWithFiles().mapNotNull { b -> runCatching { b.id to files.read(b.id) }.getOrNull() }.toMap()
        val contents = BackupContents(sdb.version, System.currentTimeMillis(), tables, statementFiles)
        val encrypted = BackupCrypto.encrypt(BackupFormat.encode(contents), password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encrypted) } ?: throw BackupException("Couldn't write to that file.")
        summary(contents, encrypted.size)
    }

    /** Opens a backup without changing anything, so the user can see what it holds first. */
    suspend fun open(uri: Uri, password: CharArray): Pair<BackupContents, BackupSummary> = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw BackupException("Couldn't open that file.")
        val contents = BackupFormat.decode(BackupCrypto.decrypt(bytes, password))
        if (contents.schemaVersion > db.openHelper.readableDatabase.version) {
            throw BackupException("This backup was made by a newer version of the app. Update the app, then restore.")
        }
        contents to summary(contents, bytes.size)
    }

    /** Replaces everything in the app with [contents]. All or nothing: a failure leaves the current data as it was. */
    suspend fun restore(contents: BackupContents) = withContext(Dispatchers.IO) {
        val w = db.openHelper.writableDatabase
        val current = tableNames()
        w.beginTransaction()
        try {
            // Rows reference each other (transactions -> accounts); check those links once, when everything is in.
            w.execSQL("PRAGMA defer_foreign_keys = ON")
            current.reversed().forEach { w.execSQL("DELETE FROM `$it`") }
            for ((table, rows) in contents.tables) {
                if (table !in current) continue // a table this version doesn't have
                val columns = w.query("PRAGMA table_info(`$table`)").use { c ->
                    val name = c.getColumnIndexOrThrow("name")
                    generateSequence { if (c.moveToNext()) c.getString(name) else null }.toSet()
                }
                for (row in rows) {
                    val cv = ContentValues()
                    for ((column, v) in row) {
                        if (column !in columns) continue
                        when (v) {
                            null -> cv.putNull(column)
                            is Long -> cv.put(column, v)
                            is String -> cv.put(column, v)
                            is ByteArray -> cv.put(column, v)
                            else -> cv.put(column, v.toString())
                        }
                    }
                    w.insert(table, SQLiteDatabase.CONFLICT_ABORT, cv)
                }
            }
            // Statement files that aren't in the backup can't be opened afterwards; say so in the library.
            if ("import_batches" in current) {
                val kept = contents.statementFiles.keys.joinToString(",")
                w.execSQL("UPDATE import_batches SET hasFile = 0" + if (kept.isEmpty()) "" else " WHERE id NOT IN ($kept)")
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        files.deleteAll()
        contents.statementFiles.forEach { (id, bytes) -> runCatching { files.save(id, bytes) } }

        // Bring an older backup up to date with this version's categories and rules.
        categories.ensureSeeded(force = true)
        categories.recleanMerchants()
        categories.recategorizeAll()
        transactions.rematchTransfers()
        reminders.rescheduleAll()
        if (secrets.load() == null) settings.setQuestradeStatus(QuestradeStatus.NOT_CONNECTED)
    }

    /** Parents before children, so a restore inserts accounts and categories before the rows that point at them. */
    private fun tableNames(): List<String> {
        val all = db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata' ORDER BY name",
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        val first = listOf("accounts", "categories").filter { it in all }
        return first + (all - first.toSet())
    }

    private fun row(c: Cursor): BackupRow {
        val out = LinkedHashMap<String, Any?>()
        for (i in 0 until c.columnCount) {
            out[c.getColumnName(i)] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                else -> c.getString(i) // text; the schema has no floating-point columns (money is whole cents)
            }
        }
        return out
    }

    private fun summary(c: BackupContents, bytes: Int) = BackupSummary(
        c.createdAtMillis, c.tables["accounts"]?.size ?: 0, c.tables["transactions"]?.size ?: 0, c.statementFiles.size, bytes,
    )
}
