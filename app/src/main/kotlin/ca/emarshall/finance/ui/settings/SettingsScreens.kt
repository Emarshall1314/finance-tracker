package ca.emarshall.finance.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.BuildConfig
import ca.emarshall.finance.data.ExportRepository
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.ui.AccountsRoute
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.BillsRoute
import ca.emarshall.finance.ui.CategoriesRoute
import ca.emarshall.finance.ui.ClaudeSetupRoute
import ca.emarshall.finance.ui.StatementsRoute
import ca.emarshall.finance.ui.ImportHistoryRoute
import ca.emarshall.finance.ui.LabeledRow
import ca.emarshall.finance.ui.Picker
import ca.emarshall.finance.ui.QuestradeRoute
import ca.emarshall.finance.ui.RemindersRoute
import ca.emarshall.finance.ui.SectionCard
import ca.emarshall.finance.ui.SecurityRoute
import ca.emarshall.finance.ui.SummaryRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settingsRepo: SettingsRepository, private val export: ExportRepository) : ViewModel() {
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val deleted = MutableStateFlow(false)
    fun setTimeout(m: Int) = viewModelScope.launch { settingsRepo.setLockTimeout(m) }
    fun setBlockScreenshots(on: Boolean) = viewModelScope.launch { settingsRepo.setBlockScreenshots(on) }
    fun deleteAll() = viewModelScope.launch { export.deleteAll(); deleted.value = true }
}

@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val p = ca.emarshall.finance.ui.theme.Fin.palette
    ca.emarshall.finance.ui.design.LargeTitleScreen("Settings") {
        item {
            ca.emarshall.finance.ui.design.Group {
                Row("Accounts", Icons.Default.AccountBalance, p.chart[0]) { nav.navigate(AccountsRoute) }
                Row("Statements", Icons.Default.Description, Color(0xFF0A84FF), "Library and missing statements") { nav.navigate(StatementsRoute) }
                Row("Import history", Icons.Default.FileUpload, p.chart[4], "Undo an import", last = true) { nav.navigate(ImportHistoryRoute) }
            }
        }
        item {
            ca.emarshall.finance.ui.design.Group {
                Row("Categories & rules", Icons.Default.Category, p.chart[1]) { nav.navigate(CategoriesRoute) }
                Row("Bills & subscriptions", Icons.Default.Autorenew, p.chart[3]) { nav.navigate(BillsRoute) }
                Row("Statement reminders", Icons.Default.Notifications, Color(0xFFFF3B30), last = true) { nav.navigate(RemindersRoute) }
            }
        }
        item {
            ca.emarshall.finance.ui.design.Group {
                Row("Questrade", Icons.AutoMirrored.Filled.ShowChart, p.chart[2], "Read-only") { nav.navigate(QuestradeRoute) }
                Row("AI coach", Icons.Default.AutoAwesome, p.chart[5], "Optional", last = true) { nav.navigate(ClaudeSetupRoute) }
            }
        }
        item {
            ca.emarshall.finance.ui.design.Group {
                Row("Summary & export", Icons.Default.Share, Color(0xFF8E8E93)) { nav.navigate(SummaryRoute) }
                Row("App lock & privacy", Icons.Default.Lock, Color(0xFF8E8E93), last = true) { nav.navigate(SecurityRoute) }
            }
        }
        item {
            ca.emarshall.finance.ui.design.Group {
                ca.emarshall.finance.ui.design.GroupRow("Delete all data", icon = Icons.Default.DeleteForever, iconColor = p.negative, divider = false, onClick = { confirm = true })
            }
        }
        item {
            Text(
                "Finance Tracker ${BuildConfig.VERSION_NAME} · Everything stays on this phone, encrypted. Connections go only to " +
                    "Questrade (read-only) and, if you turn on the AI coach, to the AI provider you chose.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
            )
            if (deleted) Text("All data deleted.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete all data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This deletes every account, transaction, statement, goal, reminder, the Questrade token and the Claude API key from this phone. It can't be undone. Type DELETE to confirm.")
                    OutlinedTextField(typed, { typed = it }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.deleteAll(); confirm = false; typed = "" }, enabled = typed == "DELETE") { Text("Delete everything") } },
            dismissButton = { TextButton(onClick = { confirm = false; typed = "" }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Row(title: String, icon: ImageVector, color: Color, subtitle: String? = null, last: Boolean = false, onClick: () -> Unit) =
    ca.emarshall.finance.ui.design.GroupRow(title, subtitle = subtitle, icon = icon, iconColor = color, showChevron = true, divider = !last, onClick = onClick)

@Composable
private fun Item(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, contentDescription = null) }, modifier = Modifier.clickable(onClick = onClick))
    HorizontalDivider()
}

private val TIMEOUTS = listOf(0, 1, 5, 15, 30, 60)

@Composable
fun SecurityScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopBar("App lock & privacy", onBack = { nav.popBackStack() })
        val settings = s ?: return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("App lock") {
                Text("The app asks for your fingerprint, face or screen lock when it opens and after it's been in the background.")
                Picker(
                    "Lock after", settings.lockTimeoutMinutes, TIMEOUTS,
                    { if (it == 0) "Immediately" else if (it == 1) "1 minute" else "$it minutes" }, vm::setTimeout, Modifier.fillMaxWidth(),
                )
            }
            SectionCard("Screen privacy") {
                LabeledRow("Hide in recent apps and block screenshots") { Switch(settings.blockScreenshots, vm::setBlockScreenshots) }
            }
            SectionCard("Where your data lives") {
                Text("• Everything is in an encrypted database on this phone (SQLCipher). The key is protected by Android's Keystore.")
                Text("• No cloud, no analytics, no ads. Backups and device-to-device transfer are turned off for this app.")
                Text("• Internet connections go only to Questrade's login and API servers (read-only), plus your chosen AI provider if you turn on the optional AI coach.")
                Text("• Bank data only comes in as files you import. The app never asks for bank passwords.")
            }
        }
    }
}
