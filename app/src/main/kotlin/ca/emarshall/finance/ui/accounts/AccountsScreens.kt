package ca.emarshall.finance.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.institutionEnum
import ca.emarshall.finance.data.typeEnum
import ca.emarshall.finance.ui.AccountEditRoute
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.EmptyState
import ca.emarshall.finance.ui.Picker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(repo: AccountRepository) : ViewModel() {
    val accounts = repo.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun AccountsScreen(nav: NavController, vm: AccountsViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { BackTopBar("Accounts", onBack = { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate(AccountEditRoute()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add manually") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val list = accounts ?: return@Column
            if (list.isEmpty()) EmptyState("Import your statements and the app sets up each account from them. Questrade accounts appear when you connect.")
            list.groupBy { it.institutionEnum }.forEach { (inst, accs) ->
                ca.emarshall.finance.ui.design.SectionHeader(inst.displayName)
                ca.emarshall.finance.ui.design.Group {
                    accs.forEachIndexed { i, a ->
                        ca.emarshall.finance.ui.design.GroupRow(
                            a.nickname,
                            subtitle = listOfNotNull(a.typeEnum.displayName, a.last4?.let { "••$it" }, a.currency.takeIf { it != "CAD" },
                                a.questradeNumber?.let { "synced" }).joinToString(" · "),
                            icon = when (inst) {
                                ca.emarshall.finance.core.model.Institution.QUESTRADE -> androidx.compose.material.icons.Icons.AutoMirrored.Filled.ShowChart
                                else -> if (a.typeEnum.isLiability) androidx.compose.material.icons.Icons.Default.CreditCard else androidx.compose.material.icons.Icons.Default.AccountBalance
                            },
                            iconColor = when (inst) {
                                ca.emarshall.finance.core.model.Institution.SCOTIABANK -> androidx.compose.ui.graphics.Color(0xFFEC111A)
                                ca.emarshall.finance.core.model.Institution.CANADIAN_TIRE -> androidx.compose.ui.graphics.Color(0xFFD52B1E).copy(alpha = 0.85f)
                                ca.emarshall.finance.core.model.Institution.QUESTRADE -> androidx.compose.ui.graphics.Color(0xFF34C77B)
                                else -> androidx.compose.ui.graphics.Color(0xFF8E8E93)
                            },
                            showChevron = true, divider = i < accs.size - 1,
                            onClick = { nav.navigate(AccountEditRoute(a.id)) },
                        )
                    }
                }
            }
        }
    }
}

@HiltViewModel
class AccountEditViewModel @Inject constructor(savedState: SavedStateHandle, private val repo: AccountRepository) : ViewModel() {
    val id = savedState.toRoute<AccountEditRoute>().id.takeIf { it > 0 }
    val existing = MutableStateFlow<AccountEntity?>(null)
    val saved = MutableStateFlow(false)

    init {
        if (id != null) viewModelScope.launch { existing.value = repo.get(id) }
    }

    fun save(nickname: String, institution: Institution, type: AccountType, last4: String, currency: String) = viewModelScope.launch {
        val e = existing.value
        if (e == null) repo.add(nickname, institution, type, last4.ifBlank { null }, currency)
        else repo.update(e.copy(nickname = nickname.trim(), institution = institution.name, type = type.name, last4 = last4.filter { it.isDigit() }.takeLast(4).ifEmpty { null }, currency = currency, confirmed = true))
        saved.value = true
    }

    fun delete() = viewModelScope.launch { id?.let { repo.delete(it) }; saved.value = true }
}

@Composable
fun AccountEditScreen(nav: NavController, vm: AccountEditViewModel = hiltViewModel()) {
    val existing by vm.existing.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) nav.popBackStack() }

    var nickname by remember(existing) { mutableStateOf(existing?.nickname.orEmpty()) }
    var institution by remember(existing) { mutableStateOf(existing?.institutionEnum ?: Institution.SCOTIABANK) }
    var type by remember(existing) { mutableStateOf(existing?.typeEnum ?: AccountType.CHEQUING) }
    var last4 by remember(existing) { mutableStateOf(existing?.last4.orEmpty()) }
    var currency by remember(existing) { mutableStateOf(existing?.currency ?: "CAD") }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(if (vm.id == null) "Add account" else "Edit account", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(nickname, { nickname = it }, Modifier.fillMaxWidth(), label = { Text("Nickname (e.g. Everyday chequing)") }, singleLine = true)
            Picker("Institution", institution, Institution.entries, { it.displayName }, { institution = it }, Modifier.fillMaxWidth())
            Picker("Type", type, AccountType.entries, { it.displayName }, { type = it }, Modifier.fillMaxWidth())
            OutlinedTextField(
                last4, { v -> last4 = v.filter { it.isDigit() }.take(4) }, Modifier.fillMaxWidth(),
                label = { Text("Last 4 digits (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("Only the last 4 digits are stored, to match imported files to this account.", style = MaterialTheme.typography.bodySmall)
            Picker("Currency", currency, listOf("CAD", "USD"), { it }, { currency = it }, Modifier.fillMaxWidth())
            Button(onClick = { vm.save(nickname, institution, type, last4, currency) }, enabled = nickname.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (vm.id != null && existing?.questradeNumber == null) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete account and its transactions") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing?.nickname}?") },
            text = { Text("Its transactions, imports, balances and reminder are deleted too. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
