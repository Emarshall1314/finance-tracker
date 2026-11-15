package ca.emarshall.finance.ai

import ca.emarshall.finance.net.AllowedHostsInterceptor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Any OpenAI-compatible provider, against a local MockWebServer. */
class OpenAiCompatibleProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        http = OkHttpClient.Builder().addInterceptor(AllowedHostsInterceptor(isAllowed = { it == server.hostName }, allowCleartext = true)).build()
    }

    @After fun tearDown() = server.shutdown()

    private fun provider(model: String = "some-model") =
        OpenAiCompatibleProvider("key-123", AiConfig(AiKind.OPENAI_COMPATIBLE, "TestAI", server.url("/v1").toString(), model), http)

    private fun completion(content: String, finish: String = "stop") = """
        {"id":"x","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(content))}},"finish_reason":"$finish"}],
         "usage":{"prompt_tokens":900,"completion_tokens":150,"total_tokens":1050}}
    """.trimIndent()

    @Test fun checkInParsesJsonEvenInsideCodeFences() = runBlocking {
        val json = """{"summary":"OK month.","wins":[],"concerns":["Fees"],"actions":["Call the bank"],"suggested_goals":[{"type":"SAVINGS_RATE","title":"Save 20%","why":"x","target_dollars":null,"target_percent":20,"category":null,"months":null}]}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(completion("```json\n$json\n```")))
        val (c, usage) = provider().checkIn("DATA")
        assertEquals("OK month.", c.summary)
        assertEquals(20, c.suggestedGoals.single().targetPercent)
        assertEquals(1050L, usage.inputTokens + usage.outputTokens)
        assertNull(usage.usd)

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer key-123", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("some-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val msgs = body["messages"]!!.jsonArray
        assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(msgs[1].jsonObject["content"]!!.jsonPrimitive.content.contains("<financial_data>\nDATA"))
    }

    @Test fun retriesWithoutJsonModeWhenProviderRejectsIt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"response_format not supported"}}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(completion("""{"summary":"Fine."}""")))
        assertEquals("Fine.", provider().checkIn("DATA").first.summary)
        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull(second["response_format"])
    }

    @Test fun badKeyAndUnknownModelHaveHelpfulMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try { provider().checkIn("D"); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("API key was rejected")) }
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        try { provider("nope").checkIn("D"); fail() } catch (e: AiException) { assertTrue(e.message!!.contains("\"nope\"")) }
    }

    @Test fun chatStreamsSseAndReplaysHistory() = runBlocking {
        val sse = listOf(
            """{"choices":[{"delta":{"role":"assistant"}}]}""",
            """{"choices":[{"delta":{"content":"Pay the "}}]}""",
            """{"choices":[{"delta":{"content":"card first."}}]}""",
            """{"choices":[],"usage":{"prompt_tokens":40,"completion_tokens":5}}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val history = listOf(AiTurn("Earlier?", "Earlier answer."))
        val events = provider().chat("DATA", history, "What first?").toList()
        assertEquals("Pay the card first.", events.filterIsInstance<AiEvent.Text>().joinToString("") { it.delta })
        val done = events.last() as AiEvent.Done
        assertEquals("Pay the card first.", done.turn.answer)
        assertEquals(45L, done.usage.inputTokens + done.usage.outputTokens)
        val msgs = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "user", "assistant", "user"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("Earlier answer.", msgs[4].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun defaultClientOnlyReachesTheChosenHost() {
        val cfg = AiConfig(AiKind.OPENAI_COMPATIBLE, "X", "https://api.example-ai.com/v1", "m")
        assertEquals("api.example-ai.com", cfg.host)
        assertEquals("api.anthropic.com", AiConfig(AiKind.ANTHROPIC, "Claude", "", "m").host)
    }
}
