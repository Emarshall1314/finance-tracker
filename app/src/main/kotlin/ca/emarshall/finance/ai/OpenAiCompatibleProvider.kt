package ca.emarshall.finance.ai

import ca.emarshall.finance.net.AllowedHostsInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Any provider with an OpenAI-compatible Chat Completions API (OpenAI, Gemini, Mistral, DeepSeek, Groq,
 * OpenRouter, ...). Plain HTTPS through OkHttp; the client may only reach the provider's own host.
 */
class OpenAiCompatibleProvider(
    private val apiKey: String,
    override val config: AiConfig,
    http: OkHttpClient? = null,
) : AiProvider {
    private val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"

    private val client: OkHttpClient = http ?: OkHttpClient.Builder()
        .addInterceptor(AllowedHostsInterceptor(isAllowed = { it.equals(config.host, ignoreCase = true) }))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    init {
        require(config.model.isNotBlank()) { "Choose a model name for ${config.label}." }
    }

    override suspend fun checkIn(context: String): Pair<CheckIn, AiUsage> = withContext(Dispatchers.IO) {
        val messages = baseMessages(context) + msg("user", AiPrompts.CHECK_IN + "\n\n" + AiPrompts.CHECK_IN_JSON_SHAPE)
        // Ask for JSON mode where supported; some providers reject the field, so retry without it.
        val body = try {
            try {
                post(request(messages, stream = false, jsonMode = true))
            } catch (e: HttpException) {
                if (e.code == 400 || e.code == 422) post(request(messages, stream = false, jsonMode = false)) else throw e
            }
        } catch (e: HttpException) {
            throw e.toAi()
        }
        val root = JSON.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw AiException("The AI sent an empty reply.")
        if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length") throw AiException("The reply was cut off. Try again.")
        val text = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        AiPrompts.parseCheckIn(text) to usage(root)
    }

    override fun chat(context: String, history: List<AiTurn>, question: String): Flow<AiEvent> = flow {
        val messages = baseMessages(context) + history.flatMap { listOf(msg("user", it.question), msg("assistant", it.answer)) } + msg("user", question)
        val answer = StringBuilder()
        var usage = AiUsage(0, 0)
        val call = client.newCall(httpRequest(request(messages, stream = true, jsonMode = false)))
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw AiException("Couldn't reach ${config.label}. Check your internet connection.", retryable = true)
        }
        response.use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, r.body.string()).toAi()
            val source = r.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { JSON.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.get("content")
                    ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { answer.append(it); emit(AiEvent.Text(it)) }
                if (obj["usage"] is JsonObject) usage = usage(obj)
            }
        }
        emit(AiEvent.Done(AiTurn(question, answer.toString()), usage))
    }.flowOn(Dispatchers.IO)

    private fun baseMessages(context: String) = listOf(
        msg("system", AiPrompts.SYSTEM),
        msg("user", "<financial_data>\n$context\n</financial_data>"),
        msg("assistant", AiPrompts.ACK),
    )

    private fun msg(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

    private fun request(messages: List<JsonObject>, stream: Boolean, jsonMode: Boolean) = buildJsonObject {
        put("model", config.model)
        put("messages", JsonArray(messages))
        if (stream) {
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        if (jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
    }

    private fun httpRequest(body: JsonObject) = Request.Builder()
        .url(endpoint)
        .header("Authorization", "Bearer $apiKey")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    private fun post(body: JsonObject): String {
        val r: Response = try {
            client.newCall(httpRequest(body)).execute()
        } catch (e: IOException) {
            throw AiException("Couldn't reach ${config.label}. Check your internet connection.", retryable = true)
        }
        return r.use { if (it.isSuccessful) it.body.string() else throw HttpException(it.code, it.body.string()) }
    }

    private fun usage(root: JsonObject): AiUsage {
        val u = root["usage"] as? JsonObject ?: return AiUsage(0, 0)
        return AiUsage(u["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0, u["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0)
    }

    private class HttpException(val code: Int, val body: String) : IOException("HTTP $code")

    private fun HttpException.toAi() = when (code) {
        401, 403 -> AiException("Your ${config.label} API key was rejected. Check it in Settings → AI coach.")
        404 -> AiException("${config.label} doesn't recognise the model \"${config.model}\" or the address. Check them in Settings → AI coach.")
        429 -> AiException("Too many requests (or no credit left) at ${config.label}. Try again later.", retryable = true)
        in 500..599 -> AiException("${config.label} is unavailable (HTTP $code). Try again later.", retryable = true)
        else -> AiException("${config.label} returned an error (HTTP $code).")
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
