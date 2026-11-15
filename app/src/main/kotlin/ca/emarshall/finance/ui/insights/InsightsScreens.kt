package ca.emarshall.finance.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import ca.emarshall.finance.claude.ChatEvent
import ca.emarshall.finance.claude.ClaudeCoach
import ca.emarshall.finance.claude.ClaudeException
import ca.emarshall.finance.claude.ClaudeRepository
import ca.emarshall.finance.claude.SuggestedGoal
import ca.emarshall.finance.core.goals.GoalStatus
import ca.emarshall.finance.core.goals.GoalType
import ca.emarshall.finance.core.insights.Insight
import ca.emarshall.finance.core.insights.Severity
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.GoalRepository
import ca.emarshall.finance.data.InsightsRepository
import ca.emarshall.finance.data.StatementsRepository
import ca.emarshall.finance.data.db.GoalEntity
import ca.emarshall.finance.data.ruleKey
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.ClaudeChatRoute
import ca.emarshall.finance.ui.ClaudeSetupRoute
import ca.emarshall.finance.ui.GoalEditRoute
import ca.emarshall.finance.ui.Picker
import ca.emarshall.finance.ui.SectionCard
import ca.emarshall.finance.ui.StatementsRoute
import ca.emarshall.finance.ui.TransactionRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val insights: InsightsRepository,
    goals: GoalRepository,
    statements: StatementsRepository,
    private val claude: ClaudeRepository,
) : ViewModel() {
    val visible = insights.visible.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val goals = goals.goals.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val missing = statements.byAccount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val claudeEnabled = claude.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val checkIn = claude.lastCheckIn
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun dismiss(i: Insight) = viewModelScope.launch { insights.dismiss(i.id) }

    fun runCheckIn() = viewModelScope.launch {
        busy.value = true
        message.value = null
        try {
            claude.runCheckIn()
        } catch (e: ClaudeException) {
            message.value = e.message
        } finally {
            busy.value = false
        }
    }

    fun adopt(s: SuggestedGoal) = viewModelScope.launch {
        claude.adopt(s)
        message.value = "Goal added: ${s.title}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(nav: NavController, vm: InsightsViewModel = hiltViewModel()) {
    val insights by vm.visible.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val statements by vm.missing.collectAsStateWithLifecycle()
    val claudeOn by vm.claudeEnabled.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Text("Insights", Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineLarge)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- Unusual activity (on-device) ----
            SectionCard("Needs attention") {
                val list = insights
                when {
                    list == null -> Unit
                    list.isEmpty() -> Text("Nothing unusual in the last few weeks.")
                    else -> list.forEach { i -> InsightRow(i, claudeOn, nav, onDismiss = { vm.dismiss(i) }) }
                }
                Text("Checked on your phone after every import. Nothing is sent anywhere.", style = MaterialTheme.typography.bodySmall)
            }

            // ---- Missing statements ----
            val missing = statements.flatMap { a -> a.missing.map { a.account.nickname to it } }
            SectionCard("Statements") {
                if (missing.isEmpty()) Text(if (statements.any { it.imported.isNotEmpty() }) "All expected statements are imported." else "Import a statement to start tracking.")
                missing.take(5).forEach { (name, m) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text("$name: statement ending ${m.periodEnd} is missing")
                    }
                }
                TextButton(onClick = { nav.navigate(StatementsRoute) }) { Text("Open statement library") }
            }

            // ---- Goals ----
            SectionCard("Goals") {
                if (goals.isEmpty()) Text("No goals yet. Add one, or ask Claude to suggest some.")
                goals.forEach { g ->
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate(GoalEditRoute(g.entity.id)) }.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.entity.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(statusLabel(g.progress.status), color = statusColor(g.progress.status), style = MaterialTheme.typography.labelLarge)
                        }
                        LinearProgressIndicator(
                            progress = { (g.progress.percent.coerceIn(0, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = statusColor(g.progress.status),
                        )
                        Text(g.progress.headline, style = MaterialTheme.typography.bodyMedium)
                        Text(g.progress.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { nav.navigate(GoalEditRoute()) }) { Text("Add a goal") }
            }

            // ---- Claude coach (opt-in) ----
            SectionCard("Claude coach") {
                if (!claudeOn) {
                    Text("Get feedback on your month, goal suggestions, and answers to questions about your money from Claude. Off until you turn it on.")
                    Button(onClick = { nav.navigate(ClaudeSetupRoute) }) { Text("Set up Claude coach") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::runCheckIn, enabled = !busy) { Text("Monthly check-in") }
                        OutlinedButton(onClick = { nav.navigate(ClaudeChatRoute()) }) { Text("Ask Claude") }
                    }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(); Text("Claude is reading your summary…")
                    }
                    checkIn?.let { (c, cost) ->
                        Text(c.summary, style = MaterialTheme.typography.bodyLarge)
                        if (c.wins.isNotEmpty()) Bullets("Going well", c.wins)
                        if (c.concerns.isNotEmpty()) Bullets("Watch out for", c.concerns)
                        if (c.actions.isNotEmpty()) Bullets("This month", c.actions)
                        c.suggestedGoals.forEach { s ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Suggested goal: ${s.title}", style = MaterialTheme.typography.titleSmall)
                                    Text(s.why, style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { vm.adopt(s) }) { Text("Add this goal") }
                                }
                            }
                        }
                        Text("${cost.label()} · ${ClaudeCoach.MODEL}", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { nav.navigate(ClaudeSetupRoute) }) { Text("Claude settings & what's shared") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun Bullets(title: String, items: List<String>) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun InsightRow(i: Insight, claudeOn: Boolean, nav: NavController, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                when (i.severity) { Severity.ALERT -> Icons.Default.ErrorOutline; Severity.WARNING -> Icons.Default.Warning; Severity.INFO -> Icons.Default.Info },
                contentDescription = i.severity.name.lowercase(),
                tint = when (i.severity) { Severity.ALERT -> MaterialTheme.colorScheme.error; Severity.WARNING -> Color(0xFFE6A100); Severity.INFO -> MaterialTheme.colorScheme.primary },
            )
            Text(i.title, style = MaterialTheme.typography.titleSmall)
        }
        Text(i.detail, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            i.transactionIds.firstOrNull()?.let { id -> TextButton(onClick = { nav.navigate(TransactionRoute(id)) }) { Text("View") } }
            if (claudeOn) TextButton(onClick = { nav.navigate(ClaudeChatRoute("Can you explain this and what I should do? \"${i.title}: ${i.detail}\"")) }) { Text("Ask Claude") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private fun statusLabel(s: GoalStatus) = when (s) {
    GoalStatus.ON_TRACK -> "On track"; GoalStatus.AT_RISK -> "At risk"; GoalStatus.OFF_TRACK -> "Off track"; GoalStatus.DONE -> "Done"
}

@Composable
private fun statusColor(s: GoalStatus) = when (s) {
    GoalStatus.ON_TRACK, GoalStatus.DONE -> MaterialTheme.colorScheme.primary
    GoalStatus.AT_RISK -> Color(0xFFE6A100)
    GoalStatus.OFF_TRACK -> MaterialTheme.colorScheme.error
}

// ------------------------------------------------------------------ goal editor

@HiltViewModel
class GoalEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val goals: GoalRepository,
    categories: CategoryRepository,
) : ViewModel() {
    val id = savedState.toRoute<GoalEditRoute>().id.takeIf { it > 0 }
    val existing = MutableStateFlow<GoalEntity?>(null)
    val categories = categories.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val done = MutableStateFlow(false)

    init {
        if (id != null) viewModelScope.launch { existing.value = goals.get(id) }
    }

    fun save(g: GoalEntity) = viewModelScope.launch { goals.save(g); done.value = true }
    fun delete() = viewModelScope.launch { id?.let { goals.delete(it) }; done.value = true }
}

/** Whole dollars typed by the user -> cents, without floating point. */
private fun dollarsToCents(text: String): Long? =
    text.replace(",", "").replace("$", "").trim().takeIf { it.isNotEmpty() }?.let { runCatching { Money.toCents(BigDecimal(it)) }.getOrNull() }

@Composable
fun GoalEditScreen(nav: NavController, vm: GoalEditViewModel = hiltViewModel()) {
    val existing by vm.existing.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    LaunchedEffect(done) { if (done) nav.popBackStack() }

    var type by remember(existing) { mutableStateOf(existing?.type?.let { runCatching { GoalType.valueOf(it) }.getOrNull() } ?: GoalType.SAVE_AMOUNT) }
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var amount by remember(existing) { mutableStateOf(existing?.targetCents?.let { (it / 100).toString() }.orEmpty()) }
    var percent by remember(existing) { mutableStateOf(existing?.targetPercent?.toString().orEmpty()) }
    var months by remember(existing) { mutableStateOf("12") }
    var category by remember(existing) { mutableStateOf(existing?.categoryKey) }

    val expenseCats = cats.filter { it.kind == "EXPENSE" }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(if (vm.id == null) "New goal" else "Edit goal", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Picker(
                "Kind of goal", type, GoalType.entries,
                { when (it) { GoalType.SAVE_AMOUNT -> "Save an amount by a date"; GoalType.SPEND_LIMIT -> "Monthly spending limit for a category"; GoalType.SAVINGS_RATE -> "Savings rate (% of income)" } },
                { type = it }, Modifier.fillMaxWidth(),
            )
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Name (e.g. Emergency fund)") }, singleLine = true)
            when (type) {
                GoalType.SAVE_AMOUNT -> {
                    OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Amount to save ($)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (existing == null) OutlinedTextField(months, { months = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Months from now") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("Progress counts money saved (income minus spending) from the start of this month.", style = MaterialTheme.typography.bodySmall)
                }
                GoalType.SPEND_LIMIT -> {
                    Picker("Category", expenseCats.firstOrNull { it.ruleKey == category }, expenseCats, { it.name }, { category = it.ruleKey }, Modifier.fillMaxWidth())
                    OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Monthly limit ($)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                GoalType.SAVINGS_RATE -> OutlinedTextField(percent, { percent = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(),
                    label = { Text("Target savings rate (%)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            existing?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val cents = dollarsToCents(amount)
            val valid = title.isNotBlank() && when (type) {
                GoalType.SAVE_AMOUNT -> cents != null && cents > 0
                GoalType.SPEND_LIMIT -> cents != null && cents > 0 && category != null
                GoalType.SAVINGS_RATE -> percent.toIntOrNull() in 1..90
            }
            Button(
                onClick = {
                    val today = LocalDate.now()
                    vm.save(
                        (existing ?: GoalEntity(type = type.name, title = title, start = today.withDayOfMonth(1))).copy(
                            type = type.name, title = title.trim(),
                            targetCents = if (type == GoalType.SAVINGS_RATE) null else cents,
                            targetPercent = if (type == GoalType.SAVINGS_RATE) percent.toIntOrNull() else null,
                            categoryKey = if (type == GoalType.SPEND_LIMIT) category else null,
                            deadline = if (type == GoalType.SAVE_AMOUNT) existing?.deadline ?: today.plusMonths((months.toLongOrNull() ?: 12).coerceIn(1, 600)) else null,
                        ),
                    )
                },
                enabled = valid, modifier = Modifier.fillMaxWidth(),
            ) { Text("Save goal") }
            if (vm.id != null) OutlinedButton(onClick = vm::delete, modifier = Modifier.fillMaxWidth()) { Text("Delete goal") }
        }
    }
}

// ------------------------------------------------------------------ Claude setup & consent

@HiltViewModel
class ClaudeSetupViewModel @Inject constructor(private val claude: ClaudeRepository) : ViewModel() {
    val enabled = claude.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val preview = MutableStateFlow<String?>(null)

    fun loadPreview() = viewModelScope.launch { preview.value = claude.context() }
    fun enable(key: String) = viewModelScope.launch { claude.enable(key) }
    fun disable() = viewModelScope.launch { claude.disable() }
}

@Composable
fun ClaudeSetupScreen(nav: NavController, vm: ClaudeSetupViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Claude coach", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("What it does") {
                Text("• Monthly check-in: feedback on how your month went, and goal suggestions you can add with one tap.")
                Text("• Ask Claude: questions about your spending, saving, TFSA/RRSP/FHSA, or anything the app flagged.")
                Text("Unusual-activity warnings and goal tracking work without Claude — they run on your phone.", style = MaterialTheme.typography.bodySmall)
            }
            SectionCard("What's shared, and when") {
                Text("Only when you tap Check-in or send a question, the app sends Anthropic a text summary of your finances: " +
                    "account nicknames, monthly totals, categories, merchants, bills, balances, holdings, flagged items and goals. " +
                    "Account and card numbers are never included. Nothing is sent in the background.")
                Text("This is an exception to the app's \"only Questrade\" network rule, and only applies while this is turned on. " +
                    "Data goes to api.anthropic.com under Anthropic's API terms. Chats are kept in memory only and disappear when you close the app.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::loadPreview) { Text(if (preview == null) "Show exactly what's sent" else "Refresh preview") }
                preview?.let {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
            }
            if (enabled) {
                Text("Claude coach is on (${ClaudeCoach.MODEL}).", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = vm::disable, modifier = Modifier.fillMaxWidth()) { Text("Turn off and delete my API key") }
            } else {
                SectionCard("Turn it on") {
                    Text("You need your own Anthropic API key: console.anthropic.com → API keys. Usage is billed to that account " +
                        "(a check-in is typically a few cents). A claude.ai subscription doesn't include API use.")
                    OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text("API key (sk-ant-…)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(agreed, { agreed = it })
                        Text("I understand my financial summary is sent to Anthropic when I use these features.")
                    }
                    Button(onClick = { vm.enable(key); key = "" }, enabled = agreed && key.startsWith("sk-ant-") && key.length > 20, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn on Claude coach")
                    }
                    Text("The key is stored encrypted on this phone, like your Questrade token.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ chat

data class ChatLine(val fromUser: Boolean, val text: String, val meta: String? = null)

@HiltViewModel
class ClaudeChatViewModel @Inject constructor(savedState: SavedStateHandle, private val claude: ClaudeRepository) : ViewModel() {
    val initialPrompt = savedState.toRoute<ClaudeChatRoute>().prompt
    val lines = MutableStateFlow(claude.chat.value.flatMap { listOf(ChatLine(true, it.question), ChatLine(false, answerText(it.answer))) })
    val streaming = MutableStateFlow(false)

    private fun answerText(m: com.anthropic.models.messages.Message) = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")

    fun send(q: String) {
        if (q.isBlank() || streaming.value) return
        streaming.value = true
        lines.value = lines.value + ChatLine(true, q) + ChatLine(false, "")
        viewModelScope.launch {
            try {
                claude.ask(q).collect { e ->
                    when (e) {
                        is ChatEvent.Text -> updateLast { it.copy(text = it.text + e.delta) }
                        is ChatEvent.Done -> {
                            claude.appendTurn(e.turn)
                            updateLast { it.copy(text = answerText(e.turn.answer).ifEmpty { it.text }, meta = e.cost.label()) }
                        }
                    }
                }
            } catch (e: ClaudeException) {
                updateLast { it.copy(text = e.message ?: "Something went wrong.", meta = "error") }
            } finally {
                streaming.value = false
            }
        }
    }

    fun newChat() {
        claude.newChat()
        lines.value = emptyList()
    }

    private fun updateLast(f: (ChatLine) -> ChatLine) {
        val l = lines.value
        if (l.isNotEmpty()) lines.value = l.dropLast(1) + f(l.last())
    }
}

@Composable
fun ClaudeChatScreen(nav: NavController, vm: ClaudeChatViewModel = hiltViewModel()) {
    val lines by vm.lines.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf(vm.initialPrompt) }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackTopBar("Ask Claude", onBack = { nav.popBackStack() }, actions = { TextButton(onClick = vm::newChat, enabled = !streaming) { Text("New chat") } })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lines.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Claude sees your privacy-filtered summary. Try:", style = MaterialTheme.typography.bodyMedium)
                    listOf(
                        "Where could I cut \$200 a month without much pain?",
                        "Should my next \$5,000 go to my TFSA, RRSP or FHSA?",
                        "Are any of my subscriptions worth cancelling?",
                        "How long until I have a 3-month emergency fund?",
                    ).forEach { s -> TextButton(onClick = { vm.send(s) }) { Text(s) } }
                }
            }
            items(lines) { l ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (l.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (l.fromUser) "You" else "Claude", style = MaterialTheme.typography.labelMedium)
                        Text(l.text.ifEmpty { "…" }, style = MaterialTheme.typography.bodyMedium)
                        l.meta?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask about your money") }, maxLines = 4)
            Button(onClick = { vm.send(input); input = "" }, enabled = !streaming && input.isNotBlank()) { Text("Send") }
        }
    }
}
