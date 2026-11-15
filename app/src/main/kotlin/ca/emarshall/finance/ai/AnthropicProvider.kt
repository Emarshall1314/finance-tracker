package ca.emarshall.finance.ai

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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * Claude through the official Anthropic SDK: schema-enforced check-ins, streamed chat, the financial data
 * block cached between turns, and server-side refusal fallback.
 */
class AnthropicProvider(apiKey: String, override val config: AiConfig, baseUrl: String? = null) : AiProvider {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()

    private val model get() = config.model.ifBlank { DEFAULT_MODEL }

    override suspend fun checkIn(context: String): Pair<CheckIn, AiUsage> = withContext(Dispatchers.IO) {
        val params = base(context)
            .addUserMessage(AiPrompts.CHECK_IN)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).format(checkInFormat()).build())
            .build()
        val message = try {
            client.messages().create(params).also(::checkStop)
        } catch (e: Exception) {
            throw translate(e)
        }
        AiPrompts.parseCheckIn(text(message)) to usage(message.usage())
    }

    override fun chat(context: String, history: List<AiTurn>, question: String): Flow<AiEvent> = flow {
        val builder = base(context)
        history.forEach { t ->
            builder.addUserMessage(t.question)
            // Replay Claude's whole earlier message (thinking blocks included) unchanged when we have it.
            val native = t.native as? Message
            if (native != null) builder.addMessage(native) else builder.addAssistantMessage(t.answer)
        }
        builder.addUserMessage(question)
        val accumulator = MessageAccumulator.create()
        try {
            client.messages().createStreaming(builder.build()).use { stream ->
                for (event in stream.stream().iterator()) {
                    accumulator.accumulate(event)
                    event.contentBlockDelta().orElse(null)?.delta()?.text()?.orElse(null)?.text()?.let { emit(AiEvent.Text(it)) }
                }
            }
        } catch (e: Exception) {
            throw translate(e)
        }
        val message = accumulator.message()
        checkStop(message)
        emit(AiEvent.Done(AiTurn(question, text(message), message), usage(message.usage())))
    }.flowOn(Dispatchers.IO)

    private fun base(context: String): MessageCreateParams.Builder = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16_000L)
        .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(AiPrompts.SYSTEM).build()))
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
        .addAssistantMessage(AiPrompts.ACK)
        .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))

    private fun text(m: Message) = m.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")

    private fun checkStop(m: Message) {
        when (m.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw AiException("Claude declined to answer that. Try rephrasing the question.")
            StopReason.MAX_TOKENS -> throw AiException("Claude's answer was cut off. Ask for a shorter answer.")
            else -> Unit
        }
    }

    private fun translate(e: Exception): Exception = when (e) {
        is AiException -> e
        is UnauthorizedException -> AiException("Your API key was rejected. Check it in Settings → AI coach.")
        is PermissionDeniedException -> AiException("This API key isn't allowed to use $model.")
        is RateLimitException -> AiException("Too many requests right now. Wait a minute and try again.", retryable = true)
        is AnthropicServiceException -> AiException("Claude is unavailable (HTTP ${e.statusCode()}). Try again later.", retryable = e.statusCode() >= 500)
        is AnthropicIoException -> AiException("Couldn't reach Claude. Check your internet connection.", retryable = true)
        else -> AiException(e.message ?: "Something went wrong talking to Claude.")
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        /** Claude Opus 5.5 list prices per million tokens: input 4.00, cache write 5.00, cache read 0.20, output 20.00. */
        fun usage(u: Usage): AiUsage {
            val write = u.cacheCreationInputTokens().orElse(0L)
            val read = u.cacheReadInputTokens().orElse(0L)
            val micro = BigDecimal.valueOf(u.inputTokens()).multiply(BigDecimal("4.00")) +
                BigDecimal.valueOf(write).multiply(BigDecimal("5.00")) +
                BigDecimal.valueOf(read).multiply(BigDecimal("0.20")) +
                BigDecimal.valueOf(u.outputTokens()).multiply(BigDecimal("20.00"))
            return AiUsage(u.inputTokens() + write + read, u.outputTokens(), micro.divide(BigDecimal(1_000_000), 6, RoundingMode.HALF_UP))
        }

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
