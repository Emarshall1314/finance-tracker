package ca.emarshall.finance.ui.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import ca.emarshall.finance.core.model.CategoryKind
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.db.CategoryEntity
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.ColorDot
import ca.emarshall.finance.ui.Picker
import ca.emarshall.finance.ui.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(private val repo: CategoryRepository) : ViewModel() {
    val categories = repo.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rules = repo.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)

    fun addCategory(name: String, kind: CategoryKind) = viewModelScope.launch { repo.addCategory(name, kind, 0xFF607D8B) }
    fun deleteCategory(c: CategoryEntity) = viewModelScope.launch { repo.deleteCategory(c.id) }
    fun addRule(pattern: String, regex: Boolean, c: CategoryEntity) = viewModelScope.launch {
        repo.addRule(pattern, regex, c.id)
        message.value = "Rule added. ${repo.recategorizeAll()} transaction(s) updated."
    }
    fun deleteRule(id: Long) = viewModelScope.launch { repo.deleteRule(id) }
    fun reapply() = viewModelScope.launch { message.value = "${repo.recategorizeAll()} transaction(s) updated." }
}

@Composable
fun CategoriesScreen(nav: NavController, vm: CategoriesViewModel = hiltViewModel()) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(CategoryKind.EXPENSE) }
    var rulePattern by remember { mutableStateOf("") }
    var ruleRegex by remember { mutableStateOf(false) }
    var ruleCat by remember { mutableStateOf<CategoryEntity?>(null) }
    var showSeed by remember { mutableStateOf(false) }
    val byId = cats.associateBy { it.id }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Categories & rules", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Categories") {
                CategoryKind.entries.forEach { k ->
                    Text(k.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge)
                    cats.filter { it.kind == k.name }.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorDot(c.color)
                            Text(c.name, Modifier.weight(1f))
                            if (!c.builtIn) IconButton(onClick = { vm.deleteCategory(c) }) { Icon(Icons.Default.Delete, contentDescription = "Delete ${c.name}") }
                        }
                    }
                }
                OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), label = { Text("New category") }, singleLine = true)
                Picker("Kind", newKind, CategoryKind.entries, { it.name.lowercase() }, { newKind = it }, Modifier.fillMaxWidth())
                Button(onClick = { vm.addCategory(newName, newKind); newName = "" }, enabled = newName.isNotBlank()) { Text("Add category") }
            }
            SectionCard("Add a rule") {
                OutlinedTextField(rulePattern, { rulePattern = it }, Modifier.fillMaxWidth(), label = { Text("Text to match (e.g. NETFLIX)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ruleRegex, { ruleRegex = it }); Text("Regular expression") }
                Picker("Category", ruleCat, cats, { it.name }, { ruleCat = it }, Modifier.fillMaxWidth())
                Button(onClick = { ruleCat?.let { vm.addRule(rulePattern, ruleRegex, it) }; rulePattern = "" }, enabled = rulePattern.isNotBlank() && ruleCat != null) {
                    Text("Add rule and apply")
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            SectionCard("Your rules") {
                val mine = rules.filter { it.learned || it.priority >= 500 }
                if (mine.isEmpty()) Text("None yet. Recategorise a transaction and choose \"Always do this\".")
                mine.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("“${r.pattern}” → ${byId[r.categoryId]?.name ?: "?"}", Modifier.weight(1f))
                        IconButton(onClick = { vm.deleteRule(r.id) }) { Icon(Icons.Default.Delete, contentDescription = "Delete rule ${r.pattern}") }
                    }
                }
            }
            OutlinedButton(onClick = { showSeed = !showSeed }) { Text(if (showSeed) "Hide built-in rules" else "Show built-in rules") }
            if (showSeed) {
                SectionCard("Built-in rules") {
                    rules.filter { !it.learned && it.priority < 500 }.forEach { r ->
                        Text("“${r.pattern}” → ${byId[r.categoryId]?.name ?: "?"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedButton(onClick = vm::reapply) { Text("Re-apply rules to all transactions") }
        }
    }
}
