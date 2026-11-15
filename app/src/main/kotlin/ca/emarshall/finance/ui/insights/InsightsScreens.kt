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
import ca.emarshall.finance.ai.AiConfig
import ca.emarshall.finance.ai.AiEvent
import ca.emarshall.finance.ai.AiException
import ca.emarshall.finance.ai.AiKind
import ca.emarshall.finance.ai.AiPresets
import ca.emarshall.finance.ai.AiRepository
import ca.emarshall.finance.ai.SuggestedGoal
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.FinTextField
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.GroupRow
import ca.emarshall.finance.ui.design.HeroCard
import ca.emarshall.finance.ui.design.IconTile
import ca.emarshall.finance.ui.design.LargeTitleScreen
import ca.emarshall.finance.ui.design.MenuRow
import ca.emarshall.finance.ui.design.NavBar
import ca.emarshall.finance.ui.design.Notice
import ca.emarshall.finance.ui.design.NoticeTone
import ca.emarshall.finance.ui.design.PrimaryButton
import ca.emarshall.finance.ui.design.SecondaryButton
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.pretty
import ca.emarshall.finance.ui.theme.Fin
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
    private val claude: AiRepository,
) : ViewModel() {
    val visible = insights.visible.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val goals = goals.goals.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val missing = statements.byAccount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val claudeEnabled = claude.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val aiConfig = claude.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val checkIn = claude.lastCheckIn
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun dismiss(i: Insight) = viewModelScope.launch { insights.dismiss(i.id) }

    fun runCheckIn() = viewModelScope.launch {
        busy.value = true
        message.value = null
        try {
            claude.runCheckIn()
        } catch (e: AiException) {
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

@Composable
fun InsightsScreen(nav: NavController, vm: InsightsViewModel = hiltViewModel()) {
    val insights by vm.visible.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val statements by vm.missing.collectAsStateWithLifecycle()
    val claudeOn by vm.claudeEnabled.collectAsStateWithLifecycle()
    val checkIn by vm.checkIn.collectAsStateWithLifecycle()
    val aiLabel = vm.aiConfig.collectAsStateWithLifecycle().value?.let { "${it.label.substringBefore(" (")} · ${it.model}" } ?: ""
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val p = Fin.palette

    LargeTitleScreen("Insights") {
        // ---- AI coach (opt-in) ----
        item {
            HeroCard(p.heroGradient, onClick = if (claudeOn) null else ({ nav.navigate(ClaudeSetupRoute) })) {
                val white = Color.White
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = white, modifier = Modifier.size(20.dp))
                    Text("AI coach", color = white, style = MaterialTheme.typography.titleMedium)
                }
                if (!claudeOn) {
                    Text("Feedback on your month, goal ideas and answers about your money, from the AI you choose. Off until you turn it on.",
                        color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                    Text("Set up ›", color = white, style = MaterialTheme.typography.labelLarge)
                } else {
                    Text(if (checkIn == null) "Get a read on how this month is going." else "Your latest check-in is below.",
                        color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroButton(if (busy) "Reading…" else "Monthly check-in", Modifier.weight(1f), enabled = !busy, onClick = vm::runCheckIn)
                        HeroButton("Ask", Modifier.weight(1f)) { nav.navigate(ClaudeChatRoute()) }
                    }
                }
            }
        }
        message?.let { m -> item { Notice(m) } }
        checkIn?.takeIf { claudeOn }?.let { (c, cost) ->
            item {
                FinCard {
                    Text(c.summary, style = MaterialTheme.typography.bodyLarge)
                    if (c.wins.isNotEmpty()) Bullets("Going well", c.wins, p.positive)
                    if (c.concerns.isNotEmpty()) Bullets("Watch out for", c.concerns, p.warning)
                    if (c.actions.isNotEmpty()) Bullets("This month", c.actions, p.accent)
                    Text("${cost.label()} · $aiLabel", style = MaterialTheme.typography.labelSmall, color = p.tertiaryLabel)
                }
            }
            if (c.suggestedGoals.isNotEmpty()) {
                item { SectionHeader("Suggested goals") }
                item {
                    Group {
                        c.suggestedGoals.forEachIndexed { i, s ->
                            GroupRow(s.title, subtitle = s.why, icon = Icons.Default.Flag, iconColor = p.chart[4], divider = i < c.suggestedGoals.size - 1,
                                trailing = { TextButton(onClick = { vm.adopt(s) }) { Text("Add") } })
                        }
                    }
                }
            }
        }

        // ---- Unusual activity (on-device) ----
        item { SectionHeader("Needs attention") }
        item {
            val list = insights
            when {
                list == null -> Unit
                list.isEmpty() -> Group { GroupRow("Nothing unusual lately", icon = Icons.Default.CheckCircle, iconColor = p.positive, divider = false) }
                else -> Group { list.forEachIndexed { i, ins -> InsightRow(ins, claudeOn, nav, divider = i < list.size - 1, onDismiss = { vm.dismiss(ins) }) } }
            }
        }
        item { Footnote("Checked on your phone after every import. Nothing is sent anywhere.") }

        // ---- Goals ----
        item { SectionHeader("Goals", "Add") { nav.navigate(GoalEditRoute()) } }
        item {
            if (goals.isEmpty()) {
                Group {
                    GroupRow("Add a goal", subtitle = "Save for something, cap a category, or hit a savings rate", icon = Icons.Default.Flag,
                        iconColor = p.chart[4], showChevron = true, divider = false, onClick = { nav.navigate(GoalEditRoute()) })
                }
            } else {
                Group {
                    goals.forEachIndexed { i, g ->
                        Column(Modifier.fillMaxWidth().clickable { nav.navigate(GoalEditRoute(g.entity.id)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.entity.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(statusLabel(g.progress.status), color = statusColor(g.progress.status), style = MaterialTheme.typography.labelMedium)
                            }
                            LinearProgressIndicator(
                                progress = { (g.progress.percent.coerceIn(0, 100)) / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                color = statusColor(g.progress.status), trackColor = p.cardSecondary, drawStopIndicator = {}, gapSize = 0.dp,
                            )
                            Text(g.progress.headline, style = MaterialTheme.typography.bodyMedium)
                            Text(g.progress.detail, style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                        }
                        if (i < goals.size - 1) HorizontalDivider(Modifier.padding(start = 16.dp), color = p.separator, thickness = 0.5.dp)
                    }
                }
            }
        }

        // ---- Missing statements ----
        val missing = statements.flatMap { a -> a.missing.map { a.account.nickname to it } }
        item { SectionHeader("Statements", "Library") { nav.navigate(StatementsRoute) } }
        item {
            Group {
                if (missing.isEmpty()) {
                    GroupRow(
                        if (statements.any { it.imported.isNotEmpty() }) "All statements are in" else "Import a statement to start tracking",
                        icon = Icons.Default.CheckCircle, iconColor = p.positive, divider = false, onClick = { nav.navigate(StatementsRoute) },
                    )
                }
                missing.take(5).forEachIndexed { i, (name, m) ->
                    GroupRow("$name", subtitle = "Statement ending ${m.periodEnd.pretty()} is missing", icon = Icons.Default.WarningAmber, iconColor = p.warning,
                        showChevron = true, divider = i < minOf(missing.size, 5) - 1, onClick = { nav.navigate(StatementsRoute) })
                }
            }
        }
        if (claudeOn) item {
            Group { GroupRow("AI settings & what's shared", icon = Icons.Default.Settings, iconColor = Color(0xFF8E8E93), showChevron = true, divider = false, onClick = { nav.navigate(ClaudeSetupRoute) }) }
        }
    }
}

/** White-on-gradient button for hero cards. */
@Composable
private fun HeroButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = if (enabled) 0.22f else 0.12f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Bullets(title: String, items: List<String>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = color)
        items.forEach { t ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(color))
                Text(t, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun InsightRow(i: Insight, claudeOn: Boolean, nav: NavController, divider: Boolean, onDismiss: () -> Unit) {
    val p = Fin.palette
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(
                when (i.severity) { Severity.ALERT -> Icons.Default.ErrorOutline; Severity.WARNING -> Icons.Default.WarningAmber; Severity.INFO -> Icons.Default.Info },
                when (i.severity) { Severity.ALERT -> p.negative; Severity.WARNING -> p.warning; Severity.INFO -> p.accent },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(i.title, style = MaterialTheme.typography.bodyLarge)
                Text(i.detail, style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 0.dp)) {
                    i.transactionIds.firstOrNull()?.let { id -> TextButton(onClick = { nav.navigate(TransactionRoute(id)) }) { Text("View") } }
                    if (claudeOn) TextButton(onClick = { nav.navigate(ClaudeChatRoute("Can you explain this and what I should do? \"${i.title}: ${i.detail}\"")) }) { Text("Ask AI") }
                    TextButton(onClick = onDismiss) { Text("Dismiss", color = p.secondaryLabel) }
                }
            }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 58.dp), color = p.separator, thickness = 0.5.dp)
    }
}

private fun statusLabel(s: GoalStatus) = when (s) {
    GoalStatus.ON_TRACK -> "On track"; GoalStatus.AT_RISK -> "At risk"; GoalStatus.OFF_TRACK -> "Off track"; GoalStatus.DONE -> "Done"
}

@Composable
private fun statusColor(s: GoalStatus) = when (s) {
    GoalStatus.ON_TRACK, GoalStatus.DONE -> Fin.palette.positive
    GoalStatus.AT_RISK -> Fin.palette.warning
    GoalStatus.OFF_TRACK -> Fin.palette.negative
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
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    DetailScreen(if (vm.id == null) "New goal" else "Edit goal", onBack = { nav.popBackStack() }) {
        item {
            Group {
                MenuRow(
                    "Kind", type, GoalType.entries,
                    { when (it) { GoalType.SAVE_AMOUNT -> "Save an amount"; GoalType.SPEND_LIMIT -> "Spending limit"; GoalType.SAVINGS_RATE -> "Savings rate" } },
                    { type = it }, divider = false,
                )
            }
        }
        item {
            FinCard {
                FinTextField(title, { title = it }, "Name", placeholder = "e.g. Emergency fund")
                when (type) {
                    GoalType.SAVE_AMOUNT -> {
                        FinTextField(amount, { amount = it }, "Amount to save ($)", keyboardOptions = number)
                        if (existing == null) FinTextField(months, { months = it.filter(Char::isDigit) }, "Months from now", keyboardOptions = number)
                    }
                    GoalType.SPEND_LIMIT -> {
                        Picker("Category", expenseCats.firstOrNull { it.ruleKey == category }, expenseCats, { it.name }, { category = it.ruleKey }, Modifier.fillMaxWidth())
                        FinTextField(amount, { amount = it }, "Monthly limit ($)", keyboardOptions = number)
                    }
                    GoalType.SAVINGS_RATE -> FinTextField(percent, { percent = it.filter(Char::isDigit).take(2) }, "Target savings rate (%)", keyboardOptions = number)
                }
            }
        }
        item {
            Footnote(
                when (type) {
                    GoalType.SAVE_AMOUNT -> "Progress counts money saved (income minus spending) from the start of this month."
                    GoalType.SPEND_LIMIT -> "Checked against each complete month's spending in that category."
                    GoalType.SAVINGS_RATE -> "The share of income left after spending, each complete month."
                } + (existing?.note?.let { " $it" } ?: ""),
            )
        }
        val cents = dollarsToCents(amount)
        val valid = title.isNotBlank() && when (type) {
            GoalType.SAVE_AMOUNT -> cents != null && cents > 0
            GoalType.SPEND_LIMIT -> cents != null && cents > 0 && category != null
            GoalType.SAVINGS_RATE -> percent.toIntOrNull() in 1..90
        }
        item {
            PrimaryButton("Save goal", {
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
            }, enabled = valid)
        }
        if (vm.id != null) item { SecondaryButton("Delete goal", vm::delete, destructive = true) }
    }
}

// ------------------------------------------------------------------ AI coach setup & consent

@HiltViewModel
class ClaudeSetupViewModel @Inject constructor(private val ai: AiRepository) : ViewModel() {
    val enabled = ai.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val config = ai.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val preview = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    fun loadPreview() = viewModelScope.launch { preview.value = ai.context() }
    fun enable(config: AiConfig, key: String) = viewModelScope.launch {
        error.value = try { ai.enable(config, key); null } catch (e: AiException) { e.message }
    }
    fun disable() = viewModelScope.launch { ai.disable() }
}

@Composable
fun ClaudeSetupScreen(nav: NavController, vm: ClaudeSetupViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var preset by remember { mutableStateOf(AiPresets.ALL.first()) }
    var baseUrl by remember(preset) { mutableStateOf(preset.baseUrl) }
    var model by remember(preset) { mutableStateOf(preset.defaultModel) }
    var key by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    val chosenHost = if (preset.kind == AiKind.ANTHROPIC) "api.anthropic.com" else runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: "your provider"
    val p = Fin.palette
    DetailScreen("AI coach", onBack = { nav.popBackStack() }) {
        val c = config
        if (enabled && c != null) {
            item {
                Group {
                    GroupRow("On · ${c.label.substringBefore(" (")}", subtitle = "${c.model} · connects to ${c.host}", icon = Icons.Default.AutoAwesome, iconColor = p.accent, divider = false)
                }
            }
            item { SecondaryButton("Turn off and delete my API key", vm::disable, destructive = true) }
            item { Footnote("To switch to a different AI, turn it off and set it up again.") }
        }
        item { SectionHeader("What it does") }
        item {
            Group {
                GroupRow("Monthly check-in", subtitle = "Feedback on your month, and goal ideas you can add with one tap", icon = Icons.Default.EventNote, iconColor = p.chart[0])
                GroupRow("Ask", subtitle = "Spending, saving, TFSA/RRSP/FHSA, or anything the app flagged", icon = Icons.Default.ChatBubbleOutline, iconColor = p.chart[2], divider = false)
            }
        }
        item { Footnote("Works with Claude, ChatGPT, Gemini, Mistral, DeepSeek, Groq, OpenRouter or any OpenAI-compatible service. Unusual-activity warnings and goals work without it.") }
        item { SectionHeader("What's shared, and when") }
        item {
            FinCard {
                Text(
                    "Only when you tap Check-in or send a question, the app sends your chosen provider a text summary: account nicknames, monthly totals, " +
                        "categories, merchants, bills, balances, holdings, flagged items and goals. Never account or card numbers. Nothing is sent in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "While it's on, the app may also reach that provider's server (HTTPS only), under its API terms. Chats are kept in memory only.",
                    style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
                )
                SecondaryButton(if (preview == null) "Show exactly what's sent" else "Refresh preview", vm::loadPreview)
                preview?.let {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
                }
            }
        }
        if (!(enabled && c != null)) {
            item { SectionHeader("Choose your AI") }
            item {
                Group { MenuRow("Provider", preset, AiPresets.ALL, { it.label }, { preset = it }, divider = false) }
            }
            item {
                FinCard {
                    if (preset.kind == AiKind.OPENAI_COMPATIBLE) FinTextField(baseUrl, { baseUrl = it.trim() }, "API address (https://…/v1)")
                    FinTextField(model, { model = it.trim() }, "Model name", placeholder = if (preset.kind == AiKind.ANTHROPIC) "claude-opus-5-5" else "Exactly as your provider lists it")
                    FinTextField(key, { key = it.trim() }, "API key", visualTransformation = PasswordVisualTransformation())
                    Text("Get a key at ${preset.keyHelp}. API use is billed by your provider (a check-in is usually a few cents); chat subscriptions don't include it.",
                        style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
                    Row(Modifier.fillMaxWidth().clickable { agreed = !agreed }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(agreed, { agreed = it })
                        Text("I understand my financial summary is sent to $chosenHost when I use these features.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            error?.let { e -> item { Notice(e, NoticeTone.ERROR) } }
            item {
                PrimaryButton(
                    "Turn on AI coach",
                    { vm.enable(AiConfig(preset.kind, preset.label, baseUrl, model.ifBlank { preset.defaultModel }), key); key = "" },
                    enabled = agreed && key.length >= 10 && (model.isNotBlank() || preset.defaultModel.isNotBlank()) &&
                        (preset.kind == AiKind.ANTHROPIC || baseUrl.startsWith("https://")),
                )
            }
            item { Footnote("The key is stored encrypted on this phone, like your Questrade token.") }
        }
    }
}

// ------------------------------------------------------------------ chat

data class ChatLine(val fromUser: Boolean, val text: String, val meta: String? = null)

@HiltViewModel
class ClaudeChatViewModel @Inject constructor(savedState: SavedStateHandle, private val claude: AiRepository) : ViewModel() {
    val initialPrompt = savedState.toRoute<ClaudeChatRoute>().prompt
    val lines = MutableStateFlow(claude.chat.value.flatMap { listOf(ChatLine(true, it.question), ChatLine(false, it.answer)) })
    val aiLabel = claude.config.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val streaming = MutableStateFlow(false)

    fun send(q: String) {
        if (q.isBlank() || streaming.value) return
        streaming.value = true
        lines.value = lines.value + ChatLine(true, q) + ChatLine(false, "")
        viewModelScope.launch {
            try {
                claude.ask(q).collect { e ->
                    when (e) {
                        is AiEvent.Text -> updateLast { it.copy(text = it.text + e.delta) }
                        is AiEvent.Done -> {
                            claude.appendTurn(e.turn)
                            updateLast { it.copy(text = e.turn.answer.ifEmpty { it.text }, meta = e.usage.label()) }
                        }
                    }
                }
            } catch (e: AiException) {
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

    val p = Fin.palette
    Column(Modifier.fillMaxSize().background(p.background).imePadding()) {
        NavBar("Ask ${vm.aiLabel.collectAsStateWithLifecycle().value?.label?.substringBefore(" (") ?: "AI"}", onBack = { nav.popBackStack() }) {
            TextButton(onClick = vm::newChat, enabled = !streaming && lines.isNotEmpty()) { Text("New chat") }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (lines.isEmpty()) {
                item { Footnote("The AI sees your privacy-filtered summary. Try one of these:") }
                item {
                    Group {
                        val ideas = listOf(
                            "Where could I cut \$200 a month without much pain?",
                            "Should my next \$5,000 go to my TFSA, RRSP or FHSA?",
                            "Are any of my subscriptions worth cancelling?",
                            "How long until I have a 3-month emergency fund?",
                        )
                        ideas.forEachIndexed { i, s -> GroupRow(s, showChevron = true, divider = i < ideas.size - 1, onClick = { vm.send(s) }) }
                    }
                }
            }
            items(lines) { l ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (l.fromUser) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.fillMaxWidth(0.86f).wrapContentWidth(if (l.fromUser) Alignment.End else Alignment.Start)
                            .clip(RoundedCornerShape(18.dp)).background(if (l.fromUser) p.accent else p.card).padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(l.text.ifEmpty { "…" }, style = MaterialTheme.typography.bodyMedium, color = if (l.fromUser) Color.White else p.label)
                        l.meta?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (l.fromUser) Color.White.copy(alpha = 0.7f) else p.tertiaryLabel) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(p.card).navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FinTextField(input, { input = it }, "Ask about your money", Modifier.weight(1f), singleLine = false)
            IconButton(onClick = { vm.send(input); input = "" }, enabled = !streaming && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (!streaming && input.isNotBlank()) p.accent else p.tertiaryLabel)
            }
        }
    }
}
