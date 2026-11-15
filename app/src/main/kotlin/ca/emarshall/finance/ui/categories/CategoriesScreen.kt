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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import ca.emarshall.finance.ui.Picker
import ca.emarshall.finance.ui.categoryIcon
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.FinTextField
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.GroupRow
import ca.emarshall.finance.ui.design.Notice
import ca.emarshall.finance.ui.design.NoticeTone
import ca.emarshall.finance.ui.design.PrimaryButton
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.design.Segmented
import ca.emarshall.finance.ui.theme.Fin
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
    val p = Fin.palette

    DetailScreen("Categories & rules", onBack = { nav.popBackStack() }) {
        message?.let { m -> item { Notice(m, NoticeTone.SUCCESS) } }
        item {
            Group {
                GroupRow("Sort out Other", subtitle = "Give the places the app didn't recognise a category", icon = Icons.Outlined.Rule, iconColor = p.chart[1],
                    showChevron = true, onClick = { nav.navigate(ca.emarshall.finance.ui.SortOtherRoute) })
                GroupRow("Known payments", subtitle = "Rent and other amounts you pay regularly", icon = Icons.Outlined.Refresh, iconColor = p.chart[0],
                    showChevron = true, divider = false, onClick = { nav.navigate(ca.emarshall.finance.ui.KnownPaymentsRoute) })
            }
        }
        CategoryKind.entries.forEach { k ->
            val inKind = cats.filter { it.kind == k.name }
            if (inKind.isEmpty()) return@forEach
            item { SectionHeader(KIND_LABELS[k] ?: k.name) }
            item {
                Group {
                    inKind.forEachIndexed { i, c ->
                        GroupRow(
                            c.name, icon = categoryIcon(c.icon), iconColor = Color(c.color.toInt()), divider = i < inKind.size - 1,
                            trailing = if (c.builtIn) null else ({
                                IconButton(onClick = { vm.deleteCategory(c) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${c.name}", tint = p.tertiaryLabel) }
                            }),
                        )
                    }
                }
            }
        }
        item { SectionHeader("New category") }
        item {
            FinCard {
                FinTextField(newName, { newName = it }, "Name")
                Segmented(CategoryKind.entries, newKind, { KIND_LABELS[it] ?: it.name }, { newKind = it })
                PrimaryButton("Add category", { vm.addCategory(newName.trim(), newKind); newName = "" }, enabled = newName.isNotBlank())
            }
        }
        item { SectionHeader("Your rules") }
        item {
            val mine = rules.filter { it.learned || it.priority >= 500 }
            if (mine.isEmpty()) {
                FinCard { Text("None yet. Recategorise a transaction and choose \"Always do this\", or add one below.", style = MaterialTheme.typography.bodyMedium, color = p.secondaryLabel) }
            } else {
                Group {
                    mine.forEachIndexed { i, r ->
                        GroupRow(
                            r.pattern, subtitle = "→ ${byId[r.categoryId]?.name ?: "?"}", divider = i < mine.size - 1,
                            trailing = { IconButton(onClick = { vm.deleteRule(r.id) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete rule ${r.pattern}", tint = p.tertiaryLabel) } },
                        )
                    }
                }
            }
        }
        item { SectionHeader("Add a rule") }
        item {
            FinCard {
                FinTextField(rulePattern, { rulePattern = it }, "Text to match", placeholder = "e.g. NETFLIX")
                Picker("Category", ruleCat, cats, { it.name }, { ruleCat = it }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Regular expression", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(ruleRegex, { ruleRegex = it })
                }
                PrimaryButton("Add rule and apply", { ruleCat?.let { vm.addRule(rulePattern.trim(), ruleRegex, it) }; rulePattern = "" }, enabled = rulePattern.isNotBlank() && ruleCat != null)
            }
        }
        item {
            Group {
                GroupRow("Re-apply rules to all transactions", icon = Icons.Outlined.Refresh, onClick = vm::reapply)
                GroupRow(if (showSeed) "Hide built-in rules" else "Show built-in rules", icon = Icons.Outlined.Rule, iconColor = p.chart[2], divider = false, onClick = { showSeed = !showSeed })
            }
        }
        if (showSeed) {
            // Built-in rules ship with the app: per category, the names and words it looks for.
            val byKey = cats.associateBy { it.key }
            val grouped = ca.emarshall.finance.core.categorize.SeedRules.ALL.groupBy { it.categoryKey }
            item { ca.emarshall.finance.ui.design.Footnote("${ca.emarshall.finance.core.categorize.SeedRules.ALL.size} built-in rules. Your own rules and known payments always win over these.") }
            grouped.forEach { (key, list) ->
                item(key = "seed-$key") {
                    FinCard {
                        Text("${byKey[key]?.name ?: key} · ${list.size}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            list.joinToString(", ") { it.pattern.lowercase() },
                            style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
                        )
                    }
                }
            }
        }
    }
}

private val KIND_LABELS = mapOf(
    CategoryKind.EXPENSE to "Spending", CategoryKind.INCOME to "Income", CategoryKind.TRANSFER to "Transfers",
)
