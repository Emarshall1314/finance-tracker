package ca.emarshall.finance.ui.reminders

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.ReminderRepository
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.db.ReminderEntity
import ca.emarshall.finance.data.typeEnum
import ca.emarshall.finance.notify.Notifier
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.EmptyState
import ca.emarshall.finance.ui.LabeledRow
import ca.emarshall.finance.ui.Picker
import ca.emarshall.finance.ui.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class RemindersViewModel @Inject constructor(
    accounts: AccountRepository,
    private val repo: ReminderRepository,
    settings: SettingsRepository,
    private val notifier: Notifier,
) : ViewModel() {
    val accounts = accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val reminders = repo.reminders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val canNotify = MutableStateFlow(notifier.canPost())

    fun refreshPermission() { canNotify.value = notifier.canPost() }

    fun save(accountId: Long, closingDay: Int, offset: Int, minutes: Int, enabled: Boolean) =
        viewModelScope.launch { repo.save(accountId, closingDay, offset, minutes, enabled) }

    fun saveGeneral(enabled: Boolean, day: Int, minutes: Int) = viewModelScope.launch { repo.saveGeneral(enabled, day, minutes) }
    fun test() = notifier.testNotification()
}

private val DAYS = (1..31).toList()
private val OFFSETS = (0..10).toList()

private fun timeLabel(m: Int) = "%d:%02d".format(m / 60, m % 60)

@Composable
fun RemindersScreen(nav: NavController, vm: RemindersViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val canNotify by vm.canNotify.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var explain by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingEnable by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshPermission()
        pendingEnable?.invoke()
        pendingEnable = null
    }

    /** Ask for POST_NOTIFICATIONS the first time a reminder is turned on, after explaining why. */
    fun withPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !vm.canNotify.value) explain = action else action()
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Statement reminders", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!canNotify && reminders.any { it.enabled }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Notifications are off, so reminders can't appear.")
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }) { Text("Open notification settings") }
                    }
                }
            }
            Text("Get a nudge a few days after each statement closes, so you remember to download it on your computer and import it.")
            val statementAccounts = accounts.filter { it.questradeNumber == null && !it.typeEnum.isInvestment }
            if (statementAccounts.isEmpty()) EmptyState("Add a bank or card account first.")
            statementAccounts.forEach { a ->
                ReminderCard(a, reminders.firstOrNull { it.accountId == a.id }) { day, offset, minutes, enabled ->
                    val doSave = { vm.save(a.id, day, offset, minutes, enabled) }
                    if (enabled) withPermission { doSave() } else doSave()
                }
            }
            settings?.let { s ->
                SectionCard("Monthly \"import your statements\" reminder") {
                    LabeledRow("On") {
                        Switch(s.generalReminderEnabled, { on ->
                            val doSave = { vm.saveGeneral(on, s.generalReminderDay, s.generalReminderMinutes) }
                            if (on) withPermission { doSave() } else doSave()
                        })
                    }
                    Picker("Day of month", s.generalReminderDay, DAYS, { it.toString() }, { vm.saveGeneral(s.generalReminderEnabled, it, s.generalReminderMinutes) }, Modifier.fillMaxWidth())
                }
            }
            Button(onClick = { withPermission { vm.test() } }, modifier = Modifier.fillMaxWidth()) { Text("Send test notification") }
        }
    }

    explain?.let { action ->
        AlertDialog(
            onDismissRequest = { explain = null },
            title = { Text("Allow notifications?") },
            text = { Text("Reminders are notifications, so Android needs your permission. They never show amounts or account numbers.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingEnable = action
                    explain = null
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explain = null; action() }) { Text("Not now") } },
        )
    }
}

@Composable
private fun ReminderCard(a: AccountEntity, r: ReminderEntity?, onSave: (Int, Int, Int, Boolean) -> Unit) {
    val day = r?.closingDay ?: a.closingDay ?: 1
    val offset = r?.offsetDays ?: 2
    val minutes = r?.timeOfDayMinutes ?: 9 * 60
    val enabled = r?.enabled ?: false
    SectionCard(a.nickname) {
        LabeledRow("Remind me") { Switch(enabled, { onSave(day, offset, minutes, it) }) }
        Picker("Statement closing day", day, DAYS, { if (it >= 29) "$it (or the month's last day)" else it.toString() }, { onSave(it, offset, minutes, enabled) }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("Days after", offset, OFFSETS, { it.toString() }, { onSave(day, it, minutes, enabled) }, Modifier.weight(1f))
            Picker("Time", minutes, (6..21).map { it * 60 }, ::timeLabel, { onSave(day, offset, it, enabled) }, Modifier.weight(1f))
        }
        r?.nextFireAtMillis?.takeIf { enabled }?.let {
            Text("Next: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
