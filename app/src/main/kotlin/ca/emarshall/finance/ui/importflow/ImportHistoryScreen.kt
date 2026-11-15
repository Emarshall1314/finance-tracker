package ca.emarshall.finance.ui.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.ImportRepository
import ca.emarshall.finance.data.db.ImportBatchEntity
import ca.emarshall.finance.ui.ImportRoute
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.pretty
import ca.emarshall.finance.ui.theme.Fin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class ImportHistoryViewModel @Inject constructor(private val imports: ImportRepository, accounts: AccountRepository) : ViewModel() {
    val batches = imports.batches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts = accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val repairStatus = MutableStateFlow<String?>(null)

    fun undo(id: Long) = viewModelScope.launch { imports.undo(id) }

    fun repair() = viewModelScope.launch {
        repairStatus.value = "Checking every statement…"
        repairStatus.value = runCatching { imports.repairMisfiledImports() }.fold(
            onSuccess = { r ->
                buildString {
                    append(if (r.moved == 0) "Every statement is in the right account." else "Moved ${r.moved} statement${if (r.moved == 1) "" else "s"} to the right account.")
                    if (r.unreadable > 0) append(" ${r.unreadable} couldn't be re-read.")
                }
            },
            onFailure = { "The check didn't finish. Try again." },
        )
    }
}

@Composable
fun ImportHistoryScreen(nav: NavController, vm: ImportHistoryViewModel = hiltViewModel()) {
    val batches by vm.batches.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val status by vm.repairStatus.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<ImportBatchEntity?>(null) }
    val p = Fin.palette
    DetailScreen("Imports", onBack = { nav.popBackStack() }) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate(ImportRoute()) }) { Text("Import files") }
                OutlinedButton(onClick = { vm.repair() }, enabled = status != "Checking every statement…") { Text("Check accounts") }
            }
        }
        status?.let { s -> item { Text(s, style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel) } }
        if (batches.isEmpty()) {
            item { Text("Nothing imported yet.", color = p.secondaryLabel) }
        } else {
            item { SectionHeader("${batches.size} imports") }
        }
        items(batches, key = { it.id }) { b ->
            FinCard {
                val acct = accounts.firstOrNull { it.id == b.accountId }?.nickname ?: "?"
                Text(b.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$acct · ${b.format} · imported ${Instant.ofEpochMilli(b.importedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().pretty()}",
                    style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
                )
                if (b.dateFrom != null && b.dateTo != null) {
                    Text("${b.dateFrom.pretty()} – ${b.dateTo.pretty()}", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                }
                Text("${b.newCount} new, ${b.duplicateCount} duplicates skipped", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                TextButton(onClick = { confirm = b }) { Text("Undo import", color = p.negative) }
            }
        }
    }
    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Undo this import?") },
            text = { Text("This removes the ${b.newCount} transactions that came from ${b.fileName}.") },
            confirmButton = { TextButton(onClick = { vm.undo(b.id); confirm = null }) { Text("Undo import") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
