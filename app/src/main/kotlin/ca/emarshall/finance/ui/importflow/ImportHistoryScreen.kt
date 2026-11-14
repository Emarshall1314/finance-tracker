package ca.emarshall.finance.ui.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.EmptyState
import ca.emarshall.finance.ui.ImportRoute
import ca.emarshall.finance.ui.SectionCard
import ca.emarshall.finance.ui.pretty
import dagger.hilt.android.lifecycle.HiltViewModel
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
    fun undo(id: Long) = viewModelScope.launch { imports.undo(id) }
}

@Composable
fun ImportHistoryScreen(nav: NavController, vm: ImportHistoryViewModel = hiltViewModel()) {
    val batches by vm.batches.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<ImportBatchEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Imports", onBack = { nav.popBackStack() })
        Button(onClick = { nav.navigate(ImportRoute()) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Import a file") }
        if (batches.isEmpty()) EmptyState("Nothing imported yet.")
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(batches, key = { it.id }) { b ->
                SectionCard(b.fileName) {
                    val acct = accounts.firstOrNull { it.id == b.accountId }?.nickname ?: "?"
                    Text("$acct · ${b.format} · imported ${Instant.ofEpochMilli(b.importedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate().pretty()}")
                    if (b.dateFrom != null && b.dateTo != null) Text("${b.dateFrom.pretty()} – ${b.dateTo.pretty()}", style = MaterialTheme.typography.bodySmall)
                    Text("${b.newCount} new, ${b.duplicateCount} duplicates skipped", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { confirm = b }) { Text("Undo import") }
                }
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
