package ca.emarshall.finance.ui.summary

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.data.ExportRepository
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SummaryViewModel @Inject constructor(private val export: ExportRepository) : ViewModel() {
    val months = MutableStateFlow(6)
    val markdown = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun generate(m: Int = months.value) = viewModelScope.launch {
        months.value = m
        markdown.value = null
        markdown.value = export.summaryMarkdown(m)
    }

    fun exportCsv(uri: android.net.Uri) = viewModelScope.launch {
        message.value = runCatching { export.writeText(uri, export.transactionsCsv()); "Transactions exported." }.getOrElse { "Export failed: ${it.message}" }
    }

    fun exportAll(uri: android.net.Uri) = viewModelScope.launch {
        message.value = runCatching { export.writeText(uri, export.exportAllJson()); "Backup saved. Keep it somewhere private." }.getOrElse { "Export failed: ${it.message}" }
    }
}

@Composable
fun SummaryScreen(nav: NavController, vm: SummaryViewModel = hiltViewModel()) {
    val md by vm.markdown.collectAsStateWithLifecycle()
    val months by vm.months.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (vm.markdown.value == null) vm.generate() }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::exportCsv) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportAll) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Summary & export", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A Markdown summary to paste into Claude for financial advice. Account nicknames only — no account numbers.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 6, 12, 24).forEach { m -> FilterChip(months == m, { vm.generate(m) }, label = { Text("$m months") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { md?.let { share(context, it) } }, enabled = md != null) { Text("Share") }
                OutlinedButton(onClick = { md?.let { copy(context, it) } }, enabled = md != null) { Text("Copy") }
            }
            SectionCard("Preview") {
                Text(md ?: "Building…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
            SectionCard("Export") {
                OutlinedButton(onClick = { csvLauncher.launch("transactions-${LocalDate.now()}.csv") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export all transactions as CSV")
                }
                OutlinedButton(onClick = { backupLauncher.launch("finance-backup-${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export all data (backup)")
                }
                Text("Exports are not encrypted. Save them somewhere private.", style = MaterialTheme.typography.bodySmall)
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "My finances summary")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share summary"))
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("Finance summary", text).apply {
        // Android 13+: keep the summary out of the visible clipboard preview.
        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    cm.setPrimaryClip(clip)
}
