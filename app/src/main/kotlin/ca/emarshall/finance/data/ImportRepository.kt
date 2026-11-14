package ca.emarshall.finance.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import ca.emarshall.finance.core.categorize.MerchantCleaner
import ca.emarshall.finance.core.csv.CsvProfile
import ca.emarshall.finance.core.csv.CsvProfileInference
import ca.emarshall.finance.core.csv.CsvReader
import ca.emarshall.finance.core.csv.CsvStatementParser
import ca.emarshall.finance.core.csv.InferredProfile
import ca.emarshall.finance.core.importing.Dedup
import ca.emarshall.finance.core.importing.DedupResult
import ca.emarshall.finance.core.importing.FormatDetector
import ca.emarshall.finance.core.importing.Hashing
import ca.emarshall.finance.core.model.AccountHint
import ca.emarshall.finance.core.model.FileFormat
import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.core.model.ParsedStatement
import ca.emarshall.finance.core.model.TxSource
import ca.emarshall.finance.core.ofx.OfxParser
import ca.emarshall.finance.core.statement.StatementTextParser
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.db.BalanceDao
import ca.emarshall.finance.data.db.BalanceSnapshotEntity
import ca.emarshall.finance.data.db.CsvProfileEntity
import ca.emarshall.finance.data.db.FinanceDatabase
import ca.emarshall.finance.data.db.ImportBatchEntity
import ca.emarshall.finance.data.db.ImportDao
import ca.emarshall.finance.data.db.TransactionDao
import ca.emarshall.finance.data.db.TransactionEntity
import ca.emarshall.finance.importing.PdfTextExtractor
import ca.emarshall.finance.work.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A file the user picked or shared, read into memory. */
class LoadedFile(val fileName: String, val bytes: ByteArray, val sha256: String, val format: FileFormat)

/** What we learned from the file before an account is chosen. */
data class DetectedFile(
    val file: LoadedFile,
    val hint: AccountHint,
    /** For OFX and PDF the statement is fully parsed already; CSV waits for the account (sign conventions). */
    val statement: ParsedStatement?,
    val csvText: String?,
    val csvInference: InferredProfile?,
    val suggestedAccountId: Long?,
    val previousImport: ImportBatchEntity?,
)

data class ImportPreview(
    val detected: DetectedFile,
    val account: AccountEntity,
    val statement: ParsedStatement,
    val dedup: DedupResult,
    val profile: CsvProfile?,
) {
    val moneyInCents get() = dedup.newRows.filter { it.tx.amountCents > 0 }.sumOf { it.tx.amountCents }
    val moneyOutCents get() = -dedup.newRows.filter { it.tx.amountCents < 0 }.sumOf { it.tx.amountCents }
}

data class ImportResult(val batchId: Long, val accountId: Long, val newCount: Int, val duplicateCount: Int, val transfersMatched: Int)

class ImportException(message: String) : IOException(message)

@Singleton
class ImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FinanceDatabase,
    private val importDao: ImportDao,
    private val txDao: TransactionDao,
    private val balanceDao: BalanceDao,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val transactions: TransactionRepository,
    private val pdf: PdfTextExtractor,
    private val reminders: ReminderScheduler,
) {
    val batches = importDao.observeBatches()

    suspend fun load(uri: Uri): LoadedFile = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "statement"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw ImportException("That file is larger than 25 MB; statements are much smaller.")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw ImportException("Couldn't open that file.")
        LoadedFile(name, bytes, Hashing.sha256Hex(bytes), FormatDetector.detect(name, bytes.copyOf(minOf(bytes.size, 8192))))
    }

    suspend fun detect(file: LoadedFile): DetectedFile = withContext(Dispatchers.Default) {
        var statement: ParsedStatement? = null
        var csvText: String? = null
        var inference: InferredProfile? = null
        val hint: AccountHint = when (file.format) {
            FileFormat.OFX -> OfxParser.parse(FormatDetector.decodeText(file.bytes)).also { statement = it }.hint
            FileFormat.PDF -> {
                val text = pdf.extract(file.bytes)
                StatementTextParser.parse(text).also { statement = it }.hint
            }
            FileFormat.CSV -> {
                csvText = FormatDetector.decodeText(file.bytes)
                inference = CsvProfileInference.infer(csvText!!)
                AccountHint(institution = inference?.institution)
            }
            FileFormat.UNKNOWN -> throw ImportException("This doesn't look like a CSV, OFX/QFX/QBO or PDF statement.")
        }
        DetectedFile(file, hint, statement, csvText, inference, suggestAccount(hint, accounts.all()), importDao.bySha(file.sha256))
    }

    /** Best guess at which of my accounts a file belongs to. */
    fun suggestAccount(hint: AccountHint, all: List<AccountEntity>): Long? {
        val sameBank = all.filter { hint.institution == null || it.institutionEnum == hint.institution }
        return sameBank.firstOrNull { hint.last4 != null && it.last4 == hint.last4 }?.id
            ?: sameBank.filter { hint.accountType != null && it.typeEnum == hint.accountType }.singleOrNull()?.id
            ?: sameBank.singleOrNull()?.id
    }

    /** CSV profile to use for this account: the saved one, else inferred with the account's sign convention. */
    suspend fun profileFor(detected: DetectedFile, account: AccountEntity): InferredProfile? {
        val text = detected.csvText ?: return null
        val saved = importDao.profile(account.id)?.let { runCatching { CsvProfile.decode(it.encoded) }.getOrNull() }
        val inferred = CsvProfileInference.infer(text, isCreditCard = account.typeEnum.isLiability)
        return when {
            saved != null && (inferred == null || saved.delimiter == inferred.profile.delimiter) ->
                InferredProfile(saved, inferred?.institution, listOf(saved.dateFormat), inferred?.header, listOf("Using the column mapping saved for this account."))
            else -> inferred
        }
    }

    fun csvPreviewRows(detected: DetectedFile, delimiter: Char, limit: Int = 8): List<List<String>> =
        detected.csvText?.let { CsvReader.read(it, delimiter).take(limit) } ?: emptyList()

    suspend fun preview(detected: DetectedFile, account: AccountEntity, profile: CsvProfile?): ImportPreview = withContext(Dispatchers.Default) {
        val statement = when (detected.file.format) {
            FileFormat.CSV -> {
                val p = profile ?: throw ImportException("Choose which columns hold the date, description and amount.")
                CsvStatementParser.parse(detected.csvText!!, p, detected.hint)
            }
            else -> detected.statement!!
        }
        val plan = Dedup.plan(account.id, statement.transactions, txDao.fingerprints(account.id).toHashSet(), txDao.fitIds(account.id).toHashSet())
        ImportPreview(detected, account, statement, plan, profile)
    }

    suspend fun commit(p: ImportPreview): ImportResult {
        val snap = categories.snapshot()
        val source = when (p.detected.file.format) {
            FileFormat.OFX -> TxSource.OFX
            FileFormat.PDF -> TxSource.PDF
            else -> TxSource.CSV
        }
        val result = db.withTransaction {
            val batchId = importDao.insertBatch(
                ImportBatchEntity(
                    accountId = p.account.id, fileName = p.detected.file.fileName, sha256 = p.detected.file.sha256,
                    format = p.detected.file.format.name, dateFrom = p.statement.dateFrom, dateTo = p.statement.dateTo,
                    rowsRead = p.statement.transactions.size, newCount = 0, duplicateCount = 0, importedAtMillis = System.currentTimeMillis(),
                ),
            )
            val rows = p.dedup.newRows.map { k ->
                val merchant = MerchantCleaner.clean(k.tx.description)
                TransactionEntity(
                    accountId = p.account.id, date = k.tx.date, amountCents = k.tx.amountCents,
                    currency = k.tx.currency ?: p.account.currency, rawDescription = k.tx.description, merchant = merchant,
                    categoryId = snap.categoryIdFor(k.tx.description, merchant, k.tx.amountCents),
                    source = source.name, importBatchId = batchId, fingerprint = k.fingerprint, fitId = k.fitId,
                )
            }
            val newCount = txDao.insertAll(rows).count { it != -1L }
            importDao.updateBatch(
                importDao.batch(batchId)!!.copy(newCount = newCount, duplicateCount = p.statement.transactions.size - newCount),
            )
            balanceDao.upsertAll(
                p.statement.balances.map {
                    BalanceSnapshotEntity(accountId = p.account.id, date = it.date, balanceCents = it.balanceCents, currency = p.account.currency, source = source.name)
                },
            )
            if (p.profile != null) importDao.saveProfile(CsvProfileEntity(p.account.id, p.profile.encode(), System.currentTimeMillis()))
            batchId to newCount
        }
        val matched = transactions.rematchTransfers()
        reminders.rescheduleAll()
        return ImportResult(result.first, p.account.id, result.second, p.statement.transactions.size - result.second, matched)
    }

    /** Removes a whole import: its transactions, their transfer links, and the balances it brought in. */
    suspend fun undo(batchId: Long) {
        db.withTransaction {
            val b = importDao.batch(batchId) ?: return@withTransaction
            txDao.unlinkFromBatch(batchId)
            txDao.deleteBatch(batchId)
            if (b.dateFrom != null && b.dateTo != null) {
                val source = if (b.format == FileFormat.OFX.name) TxSource.OFX else if (b.format == FileFormat.PDF.name) TxSource.PDF else TxSource.CSV
                balanceDao.deleteRange(b.accountId, source.name, b.dateFrom, b.dateTo)
            }
            importDao.deleteBatch(batchId)
        }
        reminders.rescheduleAll()
    }

    companion object {
        private const val MAX_BYTES = 25 * 1024 * 1024
        fun institutionLabel(i: Institution?) = i?.displayName ?: "Unknown bank"
    }
}
