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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.NotificationsNone
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.EmptyMessage
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.MenuRow
import ca.emarshall.finance.ui.design.Notice
import ca.emarshall.finance.ui.design.NoticeTone
import ca.emarshall.finance.ui.design.SecondaryButton
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.design.SwitchRow
import ca.emarshall.finance.ui.theme.Fin
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

    val p = Fin.palette
    DetailScreen("Statement reminders", onBack = { nav.popBackStack() }) {
        if (!canNotify && (reminders.any { it.enabled } || settings?.generalReminderEnabled == true)) {
            item {
                Notice("Notifications are off, so reminders can't appear.", NoticeTone.WARNING, action = "Settings", onAction = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                })
            }
        }
        item { Footnote("A nudge a few days after each statement closes, so you remember to download it and import it.") }
        val statementAccounts = accounts.filter { it.questradeNumber == null && !it.typeEnum.isInvestment }
        if (statementAccounts.isEmpty()) item { EmptyMessage("Import a statement first; reminders follow each account's statement cycle.", Icons.Outlined.NotificationsNone) }
        statementAccounts.forEach { a ->
            item(key = a.id) {
                ReminderGroup(a, reminders.firstOrNull { it.accountId == a.id }) { day, offset, minutes, enabled ->
                    val doSave = { vm.save(a.id, day, offset, minutes, enabled) }
                    if (enabled) withPermission { doSave() } else doSave()
                }
            }
        }
        settings?.let { s ->
            item { SectionHeader("Monthly check") }
            item {
                Group {
                    SwitchRow(
                        "Remind me to import", s.generalReminderEnabled, { on ->
                            val doSave = { vm.saveGeneral(on, s.generalReminderDay, s.generalReminderMinutes) }
                            if (on) withPermission { doSave() } else doSave()
                        },
                        subtitle = "One reminder a month for all accounts", icon = Icons.Outlined.Event, iconColor = p.chart[1], divider = s.generalReminderEnabled,
                    )
                    if (s.generalReminderEnabled) {
                        MenuRow("Day of month", s.generalReminderDay, DAYS, { it.toString() }, { vm.saveGeneral(true, it, s.generalReminderMinutes) }, divider = false)
                    }
                }
            }
        }
        item { SecondaryButton("Send a test notification", { withPermission { vm.test() } }) }
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
private fun ReminderGroup(a: AccountEntity, r: ReminderEntity?, onSave: (Int, Int, Int, Boolean) -> Unit) {
    val day = r?.closingDay ?: a.closingDay ?: 1
    val offset = r?.offsetDays ?: 2
    val minutes = r?.timeOfDayMinutes ?: 9 * 60
    val enabled = r?.enabled ?: false
    val next = r?.nextFireAtMillis?.takeIf { enabled }?.let { "Next: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(a.nickname)
        Group {
            SwitchRow(
                "Remind me", enabled, { onSave(day, offset, minutes, it) }, subtitle = next ?: "Off",
                icon = if (a.typeEnum.isLiability) Icons.Outlined.CreditCard else Icons.Outlined.AccountBalance, divider = enabled,
            )
            if (enabled) {
                MenuRow("Statement closes on", day, DAYS, { if (it >= 29) "Day $it or month end" else "Day $it" }, { onSave(it, offset, minutes, enabled) })
                MenuRow("Remind me", offset, OFFSETS, { if (it == 0) "Same day" else "$it day${if (it == 1) "" else "s"} after" }, { onSave(day, it, minutes, enabled) })
                MenuRow("At", minutes, (6..21).map { it * 60 }, ::timeLabel, { onSave(day, offset, it, enabled) }, divider = false)
            }
        }
    }
}
