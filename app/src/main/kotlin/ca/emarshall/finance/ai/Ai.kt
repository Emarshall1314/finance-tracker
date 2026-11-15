package ca.emarshall.finance.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode

/** Which API a provider speaks. */
enum class AiKind { ANTHROPIC, OPENAI_COMPATIBLE }

/** The user's choice of AI. The API key is stored separately, encrypted (SecretStore). */
data class AiConfig(
    val kind: AiKind,
    /** Shown in the app, e.g. "Claude" or "OpenRouter". */
    val label: String,
    /** HTTPS base URL for OpenAI-compatible providers, e.g. https://api.openai.com/v1. Unused for Anthropic. */
    val baseUrl: String,
    val model: String,
) {
    /** The one host the app may contact for this provider. */
    val host: String
        get() = if (kind == AiKind.ANTHROPIC) "api.anthropic.com" else runCatching { java.net.URI(baseUrl).host }.getOrNull().orEmpty()
}

/** Ready-made settings for popular providers; "Custom" takes any OpenAI-compatible HTTPS endpoint. */
data class AiPreset(val label: String, val kind: AiKind, val baseUrl: String, val defaultModel: String, val keyHelp: String)

object AiPresets {
    val ALL = listOf(
        AiPreset("Claude (Anthropic)", AiKind.ANTHROPIC, "https://api.anthropic.com", "claude-opus-5-5", "console.anthropic.com → API keys"),
        AiPreset("OpenAI", AiKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "", "platform.openai.com → API keys"),
        AiPreset("Google Gemini", AiKind.OPENAI_COMPATIBLE, "https://generativelanguage.googleapis.com/v1beta/openai", "", "aistudio.google.com → Get API key"),
        AiPreset("Mistral", AiKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1", "", "console.mistral.ai → API keys"),
        AiPreset("DeepSeek", AiKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", "", "platform.deepseek.com → API keys"),
        AiPreset("Groq", AiKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "", "console.groq.com → API keys"),
        AiPreset("OpenRouter (any model)", AiKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "", "openrouter.ai → Keys"),
        AiPreset("Custom (OpenAI-compatible)", AiKind.OPENAI_COMPATIBLE, "", "", "Your provider's dashboard"),
    )
}

@Serializable
data class SuggestedGoal(
    /** SAVE_AMOUNT, SPEND_LIMIT or SAVINGS_RATE. */
    val type: String,
    val title: String,
    val why: String = "",
    @SerialName("target_dollars") val targetDollars: Long? = null,
    @SerialName("target_percent") val targetPercent: Int? = null,
    /** Category key for spending limits. */
    val category: String? = null,
    /** Months from now for a save-by deadline. */
    val months: Int? = null,
)

@Serializable
data class CheckIn(
    val summary: String,
    val wins: List<String> = emptyList(),
    val concerns: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    @SerialName("suggested_goals") val suggestedGoals: List<SuggestedGoal> = emptyList(),
)

/** One finished exchange. [native] is the provider's own message object, replayed unchanged when it has one. */
data class AiTurn(val question: String, val answer: String, val native: Any? = null)

sealed interface AiEvent {
    data class Text(val delta: String) : AiEvent
    data class Done(val turn: AiTurn, val usage: AiUsage) : AiEvent
}

/** Token use for one call; [usd] only when the price is known (Claude). */
data class AiUsage(val inputTokens: Long, val outputTokens: Long, val usd: BigDecimal? = null) {
    fun label(): String {
        val tokens = "${inputTokens + outputTokens} tokens"
        return usd?.let { "≈ US$${it.setScale(3, RoundingMode.HALF_UP).toPlainString()} ($tokens)" } ?: tokens
    }
}

class AiException(message: String, val retryable: Boolean = false) : Exception(message)

/** An AI the coach can talk to. Implementations only ever receive the privacy-filtered [context]. */
interface AiProvider {
    val config: AiConfig
    suspend fun checkIn(context: String): Pair<CheckIn, AiUsage>
    fun chat(context: String, history: List<AiTurn>, question: String): Flow<AiEvent>
}

/** Prompts shared by every provider, so the coach behaves the same whichever AI is chosen. */
object AiPrompts {
    val SYSTEM = """
        You are a personal finance coach inside a private budgeting app used by one person in British Columbia, Canada.
        You receive a summary of their finances: monthly income and spending, categories, merchants, bills, fees,
        balances, Questrade holdings, detected unusual activity, their goals, and any statements not yet imported.

        How to help:
        - Ground every point in the numbers provided and cite them. If data is missing or incomplete (for example,
          statements not imported yet, or a short history), say so rather than guessing.
        - Think about the Canadian and BC context: TFSA, RRSP and FHSA rules and trade-offs, CRA benefits, BC costs.
          Don't calculate contribution room; suggest checking it in CRA My Account.
        - Be practical and specific: what to do this month, how much, and why. Prefer a few high-impact suggestions
          over many small ones.
        - Flag anything unusual or risky plainly (interest charges, fees, overdrafts, double charges, rising bills).
        - You are not a licensed financial adviser. Don't recommend specific securities to buy or sell; for tax,
          legal or investment-product decisions, suggest a fee-only planner or accountant.
        - Keep answers short: plain language, short paragraphs or bullets, amounts in CAD.
    """.trimIndent()

    val CHECK_IN = """
        Give me a check-in on my finances based on the data.
        - summary: 2-3 sentences on how things are going.
        - wins: what's going well (0-4 items).
        - concerns: what needs attention, including anything unusual (0-5 items).
        - actions: specific things to do in the next month (1-4 items).
        - suggested_goals: 1-3 realistic goals I could track in the app. Use type SAVE_AMOUNT (target_dollars and
          months), SPEND_LIMIT (monthly target_dollars and category, one of the category keys listed in the data),
          or SAVINGS_RATE (target_percent). Set fields that don't apply to null. Don't repeat goals I already have.
    """.trimIndent()

    /** For providers without schema-enforced output: spell out the JSON shape. */
    val CHECK_IN_JSON_SHAPE = """
        Reply with only a JSON object, no other text, in exactly this shape:
        {"summary": string, "wins": [string], "concerns": [string], "actions": [string],
         "suggested_goals": [{"type": "SAVE_AMOUNT"|"SPEND_LIMIT"|"SAVINGS_RATE", "title": string, "why": string,
           "target_dollars": integer|null, "target_percent": integer|null, "category": string|null, "months": integer|null}]}
    """.trimIndent()

    const val ACK = "I've read your financial data. What would you like to look at?"

    private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    /** Parses a check-in reply, tolerating code fences or text around the JSON object. */
    fun parseCheckIn(text: String): CheckIn {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("The AI's reply wasn't in the expected format. Try again.")
        return try {
            JSON.decodeFromString(CheckIn.serializer(), text.substring(start, end + 1))
        } catch (e: Exception) {
            throw AiException("The AI's reply couldn't be read. Try again.")
        }
    }
}
