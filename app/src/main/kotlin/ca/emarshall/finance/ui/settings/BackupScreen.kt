package ca.emarshall.finance.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.backup.BackupContents
import ca.emarshall.finance.backup.BackupCrypto
import ca.emarshall.finance.backup.BackupException
import ca.emarshall.finance.backup.BackupRepository
import ca.emarshall.finance.backup.BackupSummary
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.FinTextField
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.Notice
import ca.emarshall.finance.ui.design.NoticeTone
import ca.emarshall.finance.ui.design.PrimaryButton
import ca.emarshall.finance.ui.design.SecondaryButton
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.design.SwitchRow
import ca.emarshall.finance.ui.design.ValueRow
import ca.emarshall.finance.ui.theme.Fin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(private val backup: BackupRepository) : ViewModel() {
    val busy = MutableStateFlow(false)
    val saved = MutableStateFlow<BackupSummary?>(null)
    val error = MutableStateFlow<String?>(null)

    /** A backup that has been opened with its password and is waiting for the go-ahead to replace everything. */
    val opened = MutableStateFlow<Pair<BackupContents, BackupSummary>?>(null)
    val restored = MutableStateFlow(false)

    private fun run(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        error.value = null
        try {
            block()
        } catch (e: BackupException) {
            error.value = e.message
        } catch (e: Exception) {
            error.value = "That didn't work (${e.javaClass.simpleName}). Nothing was changed."
        } finally {
            busy.value = false
        }
    }

    fun create(uri: Uri, password: String, includeStatements: Boolean) = run {
        saved.value = null
        saved.value = backup.create(uri, password.toCharArray(), includeStatements)
    }

    fun open(uri: Uri, password: String) = run { opened.value = backup.open(uri, password.toCharArray()) }

    fun restore() = run {
        val contents = opened.value?.first ?: return@run
        backup.restore(contents)
        opened.value = null
        restored.value = true
    }

    fun cancelRestore() { opened.value = null }
}

/** Starts the app again from scratch, so every screen reads the restored data. */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

private fun size(bytes: Int): String = if (bytes >= 1_000_000) "${bytes / 100_000 / 10}.${bytes / 100_000 % 10} MB" else "${(bytes / 1000).coerceAtLeast(1)} KB"

private val STAMP = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.CANADA)
private fun stamp(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(STAMP)

@Composable
fun BackupScreen(nav: NavController, vm: BackupViewModel = hiltViewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val opened by vm.opened.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val p = Fin.palette

    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var includeStatements by remember { mutableStateOf(true) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var confirmReplace by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.create(it, password, includeStatements) }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; restorePassword = "" }
    }

    DetailScreen("Backup & restore", onBack = { nav.popBackStack() }) {
        if (restored) {
            item { Notice("Everything from the backup is now on this phone. Reopen the app to see it.", NoticeTone.SUCCESS) }
            item { PrimaryButton("Reopen the app", { restartApp(context) }) }
            item { Footnote("Questrade and the AI coach need to be connected again: their keys are never part of a backup.") }
            return@DetailScreen
        }
        item {
            Footnote(
                "Your data lives only on this phone. A backup is one file, locked with a password you choose, that you can keep in " +
                    "Drive, email to yourself or copy to a computer. Use it to move to a new phone, or to get everything back after reinstalling.",
            )
        }
        error?.let { e -> item { Notice(e, NoticeTone.ERROR) } }

        item { SectionHeader("Make a backup") }
        item {
            FinCard {
                FinTextField(password, { password = it }, "Password", visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                FinTextField(again, { again = it }, "Password again", visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                val problem = when {
                    password.isEmpty() -> null
                    password.length < BackupCrypto.MIN_PASSWORD -> "At least ${BackupCrypto.MIN_PASSWORD} characters."
                    again.isNotEmpty() && again != password -> "The two passwords don't match."
                    else -> null
                }
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.warning) }
            }
        }
        item {
            Group {
                SwitchRow("Include statement files", includeStatements, { includeStatements = it },
                    subtitle = "The original PDFs. A bigger file, but nothing to re-download from the bank.", icon = Icons.Default.Description, iconColor = p.accent, divider = false)
            }
        }
        item {
            PrimaryButton(
                "Save backup file", { saveLauncher.launch("finance-tracker-${LocalDate.now()}.ftbackup") },
                enabled = !busy && password.length >= BackupCrypto.MIN_PASSWORD && password == again, loading = busy && opened == null && restoreUri == null,
            )
        }
        saved?.let { s ->
            item {
                Notice(
                    "Backup saved: ${s.accounts} accounts, ${s.transactions} transactions" +
                        (if (s.statementFiles > 0) ", ${s.statementFiles} statement files" else "") + " (${size(s.bytes)}).",
                    NoticeTone.SUCCESS,
                )
            }
        }
        item { Footnote("There is no way to recover a forgotten password: without it the file can't be opened by anyone, including you. The Questrade token and AI key are never included.") }

        item { SectionHeader("Restore from a backup") }
        val o = opened
        if (o == null) {
            item { SecondaryButton("Choose a backup file", { openLauncher.launch(arrayOf("*/*")) }, enabled = !busy) }
            item { Footnote("Restoring replaces everything in the app with what's in the file.") }
        } else {
            item {
                Group {
                    ValueRow("Made on", stamp(o.second.createdAtMillis))
                    ValueRow("Accounts", o.second.accounts.toString())
                    ValueRow("Transactions", o.second.transactions.toString())
                    ValueRow("Statement files", if (o.second.statementFiles == 0) "Not included" else o.second.statementFiles.toString(), divider = false)
                }
            }
            item { SecondaryButton("Replace everything on this phone with this backup", { confirmReplace = true }, enabled = !busy, destructive = true) }
            item { SecondaryButton("Cancel", { vm.cancelRestore() }, enabled = !busy) }
        }
    }

    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Backup password") },
            text = {
                FinTextField(restorePassword, { restorePassword = it }, "Password", visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            },
            confirmButton = { TextButton(onClick = { vm.open(uri, restorePassword); restoreUri = null }, enabled = restorePassword.isNotEmpty()) { Text("Open") } },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
        )
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace everything?") },
            text = { Text("Every account, transaction, statement, goal and rule now in the app is removed and replaced with the backup's. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmReplace = false; vm.restore() }) { Text("Replace", color = p.negative) } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
}
