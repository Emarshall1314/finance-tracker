package ca.emarshall.finance.ui.transactions

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.TransactionRepository
import ca.emarshall.finance.data.db.CategoryEntity
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.LabeledRow
import ca.emarshall.finance.ui.MoneyText
import ca.emarshall.finance.ui.Picker
import ca.emarshall.finance.ui.SectionCard
import ca.emarshall.finance.ui.TransactionRoute
import ca.emarshall.finance.ui.pretty
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
) : ViewModel() {
    val id = savedState.toRoute<TransactionRoute>().id
    val row = transactions.row(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val allCategories = categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** After a manual recategorisation: offer "Always do this for <merchant>". */
    val offerLearn = MutableStateFlow<CategoryEntity?>(null)
    val message = MutableStateFlow<String?>(null)

    fun setCategory(c: CategoryEntity) = viewModelScope.launch {
        transactions.setCategory(id, c.id)
        offerLearn.value = c
    }

    fun learn() = viewModelScope.launch {
        val c = offerLearn.value ?: return@launch
        val merchant = row.value?.merchant ?: return@launch
        val n = categories.learn(merchant, c.id)
        offerLearn.value = null
        message.value = "Rule saved. $n transaction(s) from $merchant are now ${c.name}."
    }

    fun saveNotes(notes: String) = viewModelScope.launch { transactions.setNotes(id, notes) }
    fun confirmTransfer() = viewModelScope.launch { transactions.confirmTransfer(id); message.value = "Transfer confirmed." }
    fun unlinkTransfer() = viewModelScope.launch { transactions.unlinkTransfer(id); message.value = "Unlinked. These now count as income/spending." }
}

@Composable
fun TransactionDetailScreen(nav: NavController, vm: TransactionDetailViewModel = hiltViewModel()) {
    val t by vm.row.collectAsStateWithLifecycle()
    val cats by vm.allCategories.collectAsStateWithLifecycle()
    val learn by vm.offerLearn.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var notes by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Transaction", onBack = { nav.popBackStack() })
        val tx = t ?: return@Column
        LaunchedEffect(tx.id) { if (notes == null) notes = tx.notes.orEmpty() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tx.merchant, style = MaterialTheme.typography.headlineSmall)
            MoneyText(tx.amountCents, currency = tx.currency, style = MaterialTheme.typography.headlineMedium, bold = true)
            SectionCard(null) {
                LabeledRow("Date") { Text(tx.date.pretty()) }
                LabeledRow("Account") { Text(tx.accountNickname) }
                LabeledRow("Source") { Text(tx.source) }
                Text("Bank description", style = MaterialTheme.typography.labelMedium)
                Text(tx.rawDescription, style = MaterialTheme.typography.bodyMedium)
            }
            Picker(
                "Category", cats.firstOrNull { it.id == tx.categoryId }, cats,
                { "${it.name}${if (it.kind == "TRANSFER") " (transfer)" else ""}" }, vm::setCategory, Modifier.fillMaxWidth(),
            )
            if (tx.categoryLocked) Text("You set this category, so rules won't change it.", style = MaterialTheme.typography.bodySmall)

            if (tx.transferLinkId != null) {
                SectionCard("Transfer between my accounts") {
                    Text("Paired with a matching amount in another of your accounts. Transfers don't count as income or spending.")
                    if (tx.transferLocked) Text("Confirmed.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!tx.transferLocked) Button(onClick = vm::confirmTransfer) { Text("Confirm") }
                        OutlinedButton(onClick = vm::unlinkTransfer) { Text("Not a transfer") }
                    }
                }
            }

            OutlinedTextField(
                value = notes.orEmpty(), onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Notes") }, minLines = 2,
            )
            Button(onClick = { vm.saveNotes(notes.orEmpty()) }, enabled = notes != tx.notes.orEmpty()) { Text("Save notes") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }

        learn?.let { c ->
            AlertDialog(
                onDismissRequest = { vm.offerLearn.value = null },
                title = { Text("Always do this for ${tx.merchant}?") },
                text = { Text("Future and past transactions from ${tx.merchant} will be categorised as ${c.name}.") },
                confirmButton = { TextButton(onClick = vm::learn) { Text("Always do this") } },
                dismissButton = { TextButton(onClick = { vm.offerLearn.value = null }) { Text("Just this one") } },
            )
        }
    }
}
