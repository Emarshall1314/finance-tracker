package ca.emarshall.finance.ai

import ca.emarshall.finance.core.goals.GoalType
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.ExportRepository
import ca.emarshall.finance.data.GoalRepository
import ca.emarshall.finance.data.InsightsRepository
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.data.StatementsRepository
import ca.emarshall.finance.data.db.GoalEntity
import ca.emarshall.finance.data.ruleKey
import ca.emarshall.finance.security.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the AI coach needs, behind the user's opt-in. Nothing is sent until the user taps a button
 * on a Claude screen; there is no background use. Chats live in memory only and vanish when the app closes.
 */
@Singleton
class AiRepository @Inject constructor(
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val export: ExportRepository,
    private val insights: InsightsRepository,
    private val goals: GoalRepository,
    private val statements: StatementsRepository,
    private val categories: CategoryRepository,
) {
    val enabled: Flow<Boolean> = settings.settings.map { it.claudeEnabled }
    val config: Flow<AiConfig> = settings.settings.map { s ->
        AiConfig(runCatching { AiKind.valueOf(s.aiKind) }.getOrDefault(AiKind.ANTHROPIC), s.aiLabel, s.aiBaseUrl, s.aiModel)
    }
    val chat = MutableStateFlow<List<AiTurn>>(emptyList())
    val lastCheckIn = MutableStateFlow<Pair<CheckIn, AiUsage>?>(null)

    suspend fun hasKey() = !secrets.aiKey().isNullOrBlank()

    /** Saves the chosen provider and key and turns the coach on. Only HTTPS endpoints are accepted. */
    suspend fun enable(config: AiConfig, apiKey: String) {
        if (config.kind == AiKind.OPENAI_COMPATIBLE) {
            val uri = runCatching { java.net.URI(config.baseUrl) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrBlank()) throw AiException("The address must start with https://")
            if (config.model.isBlank()) throw AiException("Enter the model name from ${config.label}.")
        }
        settings.setAi(config.kind.name, config.label, config.baseUrl.trim(), config.model.trim())
        secrets.setAiKey(apiKey)
        settings.setClaudeEnabled(true)
        chat.value = emptyList()
        lastCheckIn.value = null
    }

    suspend fun disable() {
        secrets.setAiKey(null)
        settings.setClaudeEnabled(false)
        chat.value = emptyList()
        lastCheckIn.value = null
    }

    private suspend fun coach(): AiProvider {
        if (!settings.current().claudeEnabled) throw AiException("Turn on the AI coach in Settings first.")
        val key = secrets.aiKey() ?: throw AiException("Add your API key in Settings → AI coach.")
        val c = config.first()
        return when (c.kind) {
            AiKind.ANTHROPIC -> AnthropicProvider(key, c)
            AiKind.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(key, c)
        }
    }

    /**
     * Exactly what Claude receives, so the consent screen can show it: the same privacy-filtered Markdown
     * summary as "Share summary" (nicknames, numbers masked), plus on-device insights, goals and missing
     * statements.
     */
    suspend fun context(months: Int = 6): String = buildString {
        appendLine("Today: ${LocalDate.now()}")
        appendLine()
        appendLine("## Category keys (for spending-limit goals)")
        categories.categories.first().filter { it.kind == "EXPENSE" }.forEach { appendLine("- ${it.ruleKey}: ${it.name}") }
        appendLine()
        appendLine("## My current goals")
        val g = goals.goals.first()
        if (g.isEmpty()) appendLine("None yet.")
        g.forEach { appendLine("- ${it.entity.title}: ${it.progress.headline} (${it.progress.status.name.lowercase().replace('_', ' ')}). ${it.progress.detail}") }
        appendLine()
        appendLine("## Unusual activity the app detected (last 30-45 days)")
        val ins = insights.visible.first()
        if (ins.isEmpty()) appendLine("Nothing flagged.")
        ins.forEach { appendLine("- [${it.severity}] ${it.title}: ${it.detail}") }
        appendLine()
        appendLine("## Statements not imported yet")
        val missing = statements.byAccount.first().flatMap { a -> a.missing.map { "${a.account.nickname}: ${it.periodStart} to ${it.periodEnd}" } }
        if (missing.isEmpty()) appendLine("None — all expected statements are imported.") else missing.forEach { appendLine("- $it") }
        appendLine()
        append(export.summaryMarkdown(months))
    }

    suspend fun runCheckIn(): Pair<CheckIn, AiUsage> = coach().checkIn(context()).also { lastCheckIn.value = it }

    suspend fun ask(question: String): Flow<AiEvent> = coach().chat(context(), chat.value, question)

    fun appendTurn(turn: AiTurn) {
        chat.value = chat.value + turn
    }

    fun newChat() {
        chat.value = emptyList()
    }

    /** Turns a suggestion into a goal the app tracks. Dollars are whole numbers, converted to cents. */
    suspend fun adopt(s: SuggestedGoal): Long {
        val type = runCatching { GoalType.valueOf(s.type) }.getOrDefault(GoalType.SAVE_AMOUNT)
        val today = LocalDate.now()
        val validCategory = s.category?.takeIf { key -> categories.categories.first().any { it.ruleKey == key } }
        return goals.save(
            GoalEntity(
                type = type.name,
                title = s.title.take(80),
                targetCents = s.targetDollars?.let { Math.multiplyExact(it, 100L) },
                targetPercent = s.targetPercent,
                categoryKey = if (type == GoalType.SPEND_LIMIT) validCategory else null,
                start = if (type == GoalType.SAVE_AMOUNT) today.withDayOfMonth(1) else today,
                deadline = s.months?.takeIf { type == GoalType.SAVE_AMOUNT && it > 0 }?.let { today.plusMonths(it.toLong()) },
                note = "Suggested by your AI coach: ${s.why}".take(500),
            ),
        )
    }
}
