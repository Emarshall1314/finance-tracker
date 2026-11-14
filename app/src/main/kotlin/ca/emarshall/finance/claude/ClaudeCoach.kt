package ca.emarshall.finance.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

@Serializable
data class SuggestedGoal(
    /** SAVE_AMOUNT, SPEND_LIMIT or SAVINGS_RATE. */
    val type: String,
    val title: String,
    val why: String,
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

/** One finished exchange in a chat; the assistant [Message] is kept whole so it can be replayed as-is. */
data class ChatTurn(val question: String, val answer: Message)

sealed interface ChatEvent {
    data class Text(val delta: String) : ChatEvent
    data class Done(val turn: ChatTurn, val cost: CostEstimate) : ChatEvent
}

/** Rough cost of one call, from the response's token usage and Claude Opus 5.5 list prices. */
data class CostEstimate(val inputTokens: Long, val outputTokens: Long, val usd: BigDecimal) {
    fun label() = "≈ US$${usd.setScale(3, RoundingMode.HALF_UP).toPlainString()} (${inputTokens + outputTokens} tokens)"

    companion object {
        // USD per million tokens: input 4.00, cache write (5 min) 5.00, cache read 0.20, output 20.00.
        fun of(u: Usage): CostEstimate {
            val write = u.cacheCreationInputTokens().orElse(0L)
            val read = u.cacheReadInputTokens().orElse(0L)
            val micro = BigDecimal.valueOf(u.inputTokens()).multiply(BigDecimal("4.00")) +
                BigDecimal.valueOf(write).multiply(BigDecimal("5.00")) +
                BigDecimal.valueOf(read).multiply(BigDecimal("0.20")) +
                BigDecimal.valueOf(u.outputTokens()).multiply(BigDecimal("20.00"))
            return CostEstimate(u.inputTokens() + write + read, u.outputTokens(), micro.divide(BigDecimal(1_000_000), 6, RoundingMode.HALF_UP))
        }
    }
}

class ClaudeException(message: String, val retryable: Boolean = false) : Exception(message)

/**
 * The opt-in Claude coach. It sees only [context], the privacy-filtered summary the user previewed (account
 * nicknames, no account numbers), and talks only to api.anthropic.com with the user's own API key.
 */
class ClaudeCoach(apiKey: String, baseUrl: String? = null) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()

    /** Monthly check-in: feedback plus goal suggestions, as structured JSON. */
    suspend fun checkIn(context: String): Pair<CheckIn, CostEstimate> = withContext(Dispatchers.IO) {
        val params = base(context)
            .addUserMessage(CHECK_IN_PROMPT)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).format(checkInFormat()).build())
            .build()
        val message = call { client.messages().create(params) }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val parsed = try {
            JSON.decodeFromString(CheckIn.serializer(), text)
        } catch (e: Exception) {
            throw ClaudeException("Claude's reply couldn't be read. Try again.")
        }
        parsed to CostEstimate.of(message.usage())
    }

    /** A chat answer streamed as it's written. [history] replays earlier turns unchanged. */
    fun chat(context: String, history: List<ChatTurn>, question: String): Flow<ChatEvent> = flow {
        val builder = base(context)
        history.forEach { t ->
            builder.addUserMessage(t.question)
            builder.addMessage(t.answer) // whole assistant message, thinking blocks included, unchanged
        }
        builder.addUserMessage(question)
        val params = builder.build()
        val accumulator = MessageAccumulator.create()
        try {
            client.messages().createStreaming(params).use { stream ->
                for (event in stream.stream().iterator()) {
                    accumulator.accumulate(event)
                    event.contentBlockDelta().orElse(null)?.delta()?.text()?.orElse(null)?.text()?.let { emit(ChatEvent.Text(it)) }
                }
            }
        } catch (e: Exception) {
            throw translate(e)
        }
        val message = accumulator.message()
        checkStop(message)
        emit(ChatEvent.Done(ChatTurn(question, message), CostEstimate.of(message.usage())))
    }.flowOn(Dispatchers.IO)

    /**
     * Model, system prompt and the financial data block, which is marked for prompt caching so follow-up
     * questions don't pay for it again. Server-side refusal fallback is enabled (beta header + body field).
     */
    private fun base(context: String): MessageCreateParams.Builder = MessageCreateParams.builder()
        .model(MODEL)
        .maxTokens(16_000L)
        .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(SYSTEM_PROMPT).build()))
        .addUserMessageOfContentBlockParams(
            listOf(
                ContentBlockParam.ofText(
                    TextBlockParam.builder()
                        .text("<financial_data>\n$context\n</financial_data>")
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            ),
        )
        .addAssistantMessage("I've read your financial data. What would you like to look at?")
        .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))

    private fun <T> call(block: () -> T): T = try {
        block().also { if (it is Message) checkStop(it) }
    } catch (e: ClaudeException) {
        throw e
    } catch (e: Exception) {
        throw translate(e)
    }

    private fun checkStop(m: Message) {
        when (m.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw ClaudeException("Claude declined to answer that. Try rephrasing the question.")
            StopReason.MAX_TOKENS -> throw ClaudeException("Claude's answer was cut off. Ask for a shorter answer.")
            else -> Unit
        }
    }

    private fun translate(e: Exception): Exception = when (e) {
        is ClaudeException -> e
        is UnauthorizedException -> ClaudeException("Your Anthropic API key was rejected. Check it in Settings → Claude coach.")
        is PermissionDeniedException -> ClaudeException("This API key isn't allowed to use $MODEL.")
        is RateLimitException -> ClaudeException("Too many requests right now. Wait a minute and try again.", retryable = true)
        is AnthropicServiceException -> ClaudeException("Claude is unavailable (HTTP ${e.statusCode()}). Try again later.", retryable = e.statusCode() >= 500)
        is AnthropicIoException -> ClaudeException("Couldn't reach Claude. Check your internet connection.", retryable = true)
        else -> ClaudeException(e.message ?: "Something went wrong talking to Claude.")
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        val SYSTEM_PROMPT = """
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

        private val CHECK_IN_PROMPT = """
            Give me a check-in on my finances based on the data.
            - summary: 2-3 sentences on how things are going.
            - wins: what's going well (0-4 items).
            - concerns: what needs attention, including anything unusual (0-5 items).
            - actions: specific things to do in the next month (1-4 items).
            - suggested_goals: 1-3 realistic goals I could track in the app. Use type SAVE_AMOUNT (target_dollars and
              months), SPEND_LIMIT (monthly target_dollars and category, one of the category keys listed in the data),
              or SAVINGS_RATE (target_percent). Set fields that don't apply to null. Don't repeat goals I already have.
        """.trimIndent()

        private fun obj(required: List<String>, props: Map<String, Any>) = mapOf(
            "type" to "object", "additionalProperties" to false, "required" to required, "properties" to props,
        )

        private fun strings() = mapOf("type" to "array", "items" to mapOf("type" to "string"))

        fun checkInSchema(): Map<String, Any> = obj(
            listOf("summary", "wins", "concerns", "actions", "suggested_goals"),
            mapOf(
                "summary" to mapOf("type" to "string"),
                "wins" to strings(), "concerns" to strings(), "actions" to strings(),
                "suggested_goals" to mapOf(
                    "type" to "array",
                    "items" to obj(
                        listOf("type", "title", "why", "target_dollars", "target_percent", "category", "months"),
                        mapOf(
                            "type" to mapOf("type" to "string", "enum" to listOf("SAVE_AMOUNT", "SPEND_LIMIT", "SAVINGS_RATE")),
                            "title" to mapOf("type" to "string"),
                            "why" to mapOf("type" to "string"),
                            "target_dollars" to mapOf("type" to listOf("integer", "null")),
                            "target_percent" to mapOf("type" to listOf("integer", "null")),
                            "category" to mapOf("type" to listOf("string", "null")),
                            "months" to mapOf("type" to listOf("integer", "null")),
                        ),
                    ),
                ),
            ),
        )

        private fun checkInFormat(): JsonOutputFormat = JsonOutputFormat.builder()
            .schema(
                JsonOutputFormat.Schema.builder()
                    .apply { checkInSchema().forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
                    .build(),
            )
            .build()
    }
}
