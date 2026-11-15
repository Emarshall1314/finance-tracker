package ca.emarshall.finance.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.ImportRepository
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.institutionEnum
import ca.emarshall.finance.data.typeEnum
import ca.emarshall.finance.ui.AccountEditRoute
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.IconTile
import ca.emarshall.finance.ui.theme.Fin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewAccount(val account: AccountEntity, val statements: Int, val mergeTargets: List<AccountEntity>)

@HiltViewModel
class NewAccountsViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val imports: ImportRepository,
) : ViewModel() {
    val pending = combine(accounts.unconfirmed, accounts.accounts, imports.batches) { unconfirmed, all, batches ->
        val perAccount = batches.groupingBy { it.accountId }.eachCount()
        unconfirmed.map { a ->
            NewAccount(
                a, perAccount[a.id] ?: 0,
                // Only an account of the same bank and kind can be "the same account" (e.g. a reissued card).
                all.filter { it.id != a.id && it.questradeNumber == null && it.institutionEnum == a.institutionEnum && it.typeEnum.isLiability == a.typeEnum.isLiability },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun confirm(id: Long) = viewModelScope.launch { accounts.confirm(id) }

    fun merge(from: Long, into: Long) = viewModelScope.launch { imports.mergeAccounts(from, into) }
}

/**
 * "Is this right?" for each account the app set up from a statement. Shows nothing once every account
 * has been checked.
 */
@Composable
fun NewAccountsCard(nav: NavController, modifier: Modifier = Modifier, vm: NewAccountsViewModel = hiltViewModel()) {
    val pending by vm.pending.collectAsStateWithLifecycle()
    if (pending.isEmpty()) return
    var merging by remember { mutableStateOf<NewAccount?>(null) }
    val p = Fin.palette
    FinCard(modifier) {
        Text(
            if (pending.size == 1) "New account from your statements" else "${pending.size} new accounts from your statements",
            style = MaterialTheme.typography.titleMedium,
        )
        Text("The app set these up from what the statements say. Is each one right?", style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
        pending.forEachIndexed { i, n ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = p.separator)
            val a = n.account
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(if (a.typeEnum.isLiability) Icons.Default.CreditCard else Icons.Default.AccountBalance, p.accent)
                Column(Modifier.weight(1f)) {
                    Text(a.nickname, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            a.institutionEnum.displayName, a.typeEnum.displayName, a.last4?.let { "••$it" },
                            n.statements.takeIf { it > 0 }?.let { "$it statement${if (it == 1) "" else "s"}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { vm.confirm(a.id) }) { Text("Looks right") }
                TextButton(onClick = { nav.navigate(AccountEditRoute(a.id)) }) { Text("Change") }
                if (n.mergeTargets.isNotEmpty()) TextButton(onClick = { merging = n }) { Text("Same as…") }
            }
        }
    }
    merging?.let { n ->
        var target by remember(n) { mutableStateOf(n.mergeTargets.singleOrNull()) }
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Which account is ${n.account.nickname}?") },
            text = {
                Column {
                    Text(
                        "Its transactions and statements move into the account you pick, and that account will match this card number from now on.",
                        style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
                    )
                    n.mergeTargets.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { target = t }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(target?.id == t.id, { target = t })
                            Text(t.nickname + (t.last4?.let { " ••$it" } ?: ""))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { target?.let { vm.merge(n.account.id, it.id) }; merging = null }, enabled = target != null) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } },
        )
    }
}
