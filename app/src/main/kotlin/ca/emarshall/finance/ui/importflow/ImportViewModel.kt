package ca.emarshall.finance.ui.importflow

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ca.emarshall.finance.core.csv.CsvDates
import ca.emarshall.finance.core.csv.CsvProfile
import ca.emarshall.finance.core.csv.SignConvention
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.FileFormat
import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.DetectedFile
import ca.emarshall.finance.data.ImportPreview
import ca.emarshall.finance.data.ImportRepository
import ca.emarshall.finance.data.ImportResult
import ca.emarshall.finance.data.QuickImportOutcome
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.ui.ImportRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImportStep {
    data object PickFile : ImportStep
    data object Working : ImportStep
    data class ChooseAccount(val detected: DetectedFile, val reason: String? = null) : ImportStep
    data class Mapping(val detected: DetectedFile, val account: AccountEntity, val profile: CsvProfile?, val header: List<String>?, val notes: List<String>, val dateOptions: List<String>) : ImportStep
    data class Preview(val preview: ImportPreview) : ImportStep
    data class Done(val result: ImportResult, val accountName: String, val undone: Boolean = false) : ImportStep
    data class Failed(val message: String) : ImportStep

    /** Several files opened or shared at once, each imported straight away where possible. */
    data class Batch(val files: List<FileOutcome>, val working: Boolean, val transfersMatched: Int? = null) : ImportStep {
        val done get() = files.count { it.outcome != null || it.error != null }
        val imported get() = files.mapNotNull { it.outcome as? QuickImportOutcome.Imported }
        val needsReview get() = files.mapIndexedNotNull { i, f -> (f.outcome as? QuickImportOutcome.NeedsReview)?.let { i to it } }
    }
}

data class FileOutcome(val name: String, val outcome: QuickImportOutcome?, val error: String? = null)

@HiltViewModel
class ImportViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val imports: ImportRepository,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val route = savedState.toRoute<ImportRoute>()
    val preselectedAccountId = route.accountId.takeIf { it > 0 }
    val step = MutableStateFlow<ImportStep>(ImportStep.PickFile)
    val allAccounts = accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val uris = route.uris.split("\n").filter { it.isNotBlank() }.map { Uri.parse(it) }
        if (uris.isNotEmpty()) quickImport(uris)
    }

    /**
     * Files opened from a banking app (or shared from Files, Drive, Gmail) import straight away when the
     * bank and account are clear and the statement reconciles; anything else goes to the review screens.
     */
    fun quickImport(uris: List<Uri>) = run {
        if (uris.size == 1) {
            step.value = ImportStep.Working
            when (val o = imports.quickImport(uris.single())) {
                is QuickImportOutcome.Imported -> step.value = ImportStep.Done(o.result, o.accountName)
                is QuickImportOutcome.NeedsReview -> step.value = ImportStep.ChooseAccount(o.detected, o.reason)
                is QuickImportOutcome.AlreadyImported ->
                    step.value = ImportStep.Failed("Already imported: all ${o.rows} rows of this file are in ${o.accountName}.")
            }
            return@run
        }
        val results = uris.map { FileOutcome(imports.displayName(it), null) }.toMutableList()
        step.value = ImportStep.Batch(results.toList(), working = true)
        uris.forEachIndexed { i, uri ->
            results[i] = try {
                FileOutcome(results[i].name, imports.quickImport(uri, deferPostProcessing = true))
            } catch (e: Exception) {
                FileOutcome(results[i].name, null, e.message ?: "Couldn't read this file")
            }
            step.value = ImportStep.Batch(results.toList(), working = true).also { batch = it }
        }
        val matched = imports.finishBatch()
        step.value = ImportStep.Batch(results.toList(), working = false, transfersMatched = matched).also { batch = it }
    }

    /** The batch being worked through, so reviewing one file can return to the list afterwards. */
    private var batch: ImportStep.Batch? = null
    private var reviewingIndex: Int? = null

    fun importFolder(tree: Uri) = run {
        step.value = ImportStep.Working
        val files = imports.filesInFolder(tree)
        if (files.isEmpty()) step.value = ImportStep.Failed("No PDF, CSV or OFX files in that folder.") else quickImport(files)
    }

    fun backToBatch() { batch?.let { step.value = it } }
    val inBatch get() = batch != null

    fun reviewAt(index: Int, o: QuickImportOutcome.NeedsReview) {
        reviewingIndex = index
        step.value = ImportStep.ChooseAccount(o.detected, o.reason)
    }

    fun review(o: QuickImportOutcome.NeedsReview) { step.value = ImportStep.ChooseAccount(o.detected, o.reason) }

    fun onFilePicked(uri: Uri) = run {
        step.value = ImportStep.Working
        try {
            val file = imports.load(uri)
            val detected = imports.detect(file)
            step.value = ImportStep.ChooseAccount(detected)
        } catch (e: Exception) {
            step.value = ImportStep.Failed(e.message ?: "Couldn't read that file.")
        }
    }

    /** Creates an account from what the file says about itself, e.g. "Triangle Mastercard ••3432". */
    fun createAccountFromHint(detected: DetectedFile) = run {
        val h = detected.hint
        val inst = h.institution ?: Institution.OTHER
        val type = h.accountType ?: if (inst == Institution.CANADIAN_TIRE) AccountType.CREDIT_CARD else AccountType.CHEQUING
        val name = when {
            inst == Institution.CANADIAN_TIRE -> "Triangle Mastercard"
            inst == Institution.SCOTIABANK && type == AccountType.CREDIT_CARD -> "Scotia Visa"
            inst == Institution.SCOTIABANK -> "Scotiabank ${type.displayName}"
            else -> type.displayName
        }
        val id = accounts.add(name, inst, type, h.last4, h.currency ?: "CAD")
        chooseAccount(detected, accounts.get(id)!!)
    }

    fun chooseAccount(detected: DetectedFile, account: AccountEntity) = run {
        if (detected.file.format == FileFormat.CSV) {
            val inferred = imports.profileFor(detected, account)
            val needsMapping = inferred == null || inferred.dateFormatOptions.size != 1
            if (needsMapping) {
                step.value = ImportStep.Mapping(
                    detected, account, inferred?.profile, inferred?.header, inferred?.notes.orEmpty(),
                    inferred?.dateFormatOptions?.ifEmpty { null } ?: CsvDates.CANDIDATES,
                )
                return@run
            }
            step.value = ImportStep.Preview(imports.preview(detected, account, inferred!!.profile))
        } else {
            step.value = ImportStep.Preview(imports.preview(detected, account, null))
        }
    }

    fun editMapping(p: ImportPreview) {
        val text = p.detected.csvText ?: return
        val header = ca.emarshall.finance.core.csv.CsvReader.read(text, p.profile?.delimiter ?: ',').firstOrNull()
        step.value = ImportStep.Mapping(p.detected, p.account, p.profile, header, emptyList(), CsvDates.CANDIDATES)
    }

    fun applyMapping(m: ImportStep.Mapping, profile: CsvProfile) = run {
        step.value = ImportStep.Preview(imports.preview(m.detected, m.account, profile))
    }

    fun commit(p: ImportPreview) = run {
        step.value = ImportStep.Working
        val result = imports.commit(p)
        val b = batch
        val idx = reviewingIndex
        if (b != null && idx != null) {
            val files = b.files.toMutableList()
            files[idx] = files[idx].copy(outcome = QuickImportOutcome.Imported(result, p.account.nickname, p))
            reviewingIndex = null
            step.value = b.copy(files = files).also { batch = it }
        } else {
            step.value = ImportStep.Done(result, p.account.nickname)
        }
    }

    fun undo(d: ImportStep.Done) = run {
        imports.undo(d.result.batchId)
        step.value = d.copy(undone = true)
    }

    fun previewRows(detected: DetectedFile, delimiter: Char) = imports.csvPreviewRows(detected, delimiter)

    fun restart() { step.value = ImportStep.PickFile }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                step.value = ImportStep.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        val SIGN_LABELS = mapOf(
            SignConvention.AMOUNT_AS_IS to "One amount column, negative = money out",
            SignConvention.AMOUNT_INVERTED to "One amount column, positive = money out (cards)",
            SignConvention.DEBIT_CREDIT to "Separate debit and credit columns",
            SignConvention.TYPE_COLUMN to "Amount plus a Debit/Credit column",
        )
    }
}
