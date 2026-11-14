package ca.emarshall.finance.claude

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/** The coach against a local MockWebServer: request shape and response handling, no real API calls. */
class ClaudeCoachTest {
    private lateinit var server: MockWebServer
    private val context = "Today: 2026-11-14\n## Monthly income, spending and net\n| Oct 2026 | \$4,000.00 | \$3,000.00 |"

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() = server.shutdown()

    private fun coach() = ClaudeCoach("sk-ant-test-key", server.url("/").toString().trimEnd('/'))

    private fun message(text: String, stop: String = "end_turn") = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
         "content":[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))}}],
         "stop_reason":"$stop","stop_sequence":null,
         "usage":{"input_tokens":1000,"output_tokens":200,"cache_creation_input_tokens":3000,"cache_read_input_tokens":0}}
    """.trimIndent()

    @Test fun checkInSendsCachedContextSchemaAndFallbackAndParsesResult() = runBlocking {
        val result = """{"summary":"Solid month.","wins":["Saved 25%"],"concerns":["Interest on the Triangle card"],
            "actions":["Pay the card in full"],"suggested_goals":[{"type":"SPEND_LIMIT","title":"Dining under ${'$'}300","why":"Dining rose",
            "target_dollars":300,"target_percent":null,"category":"dining","months":null}]}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(message(result)))

        val (checkIn, cost) = coach().checkIn(context)

        assertEquals("Solid month.", checkIn.summary)
        assertEquals(listOf("Pay the card in full"), checkIn.actions)
        assertEquals(300L, checkIn.suggestedGoals.single().targetDollars)
        assertEquals("dining", checkIn.suggestedGoals.single().category)
        // 1000×4 + 3000×5 + 200×20 = 23,000 micro-dollars = $0.023
        assertEquals(BigDecimal("0.023000"), cost.usd)

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        // SDK 3.x sends the user's key as a bearer credential.
        assertEquals("Bearer sk-ant-test-key", req.getHeader("Authorization"))
        assertTrue(req.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val schema = body["output_config"]!!.jsonObject["format"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
        val first = body["messages"]!!.jsonArray[0].jsonObject
        val block = first["content"]!!.jsonArray[0].jsonObject
        assertTrue(block["text"]!!.jsonPrimitive.content.contains("<financial_data>"))
        assertEquals("ephemeral", block["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue((body["system"]!!.jsonArray[0] as JsonObject)["text"]!!.jsonPrimitive.content.contains("British Columbia"))
    }

    @Test fun refusalIsReportedPlainly() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(message("", stop = "refusal")))
        try {
            coach().checkIn(context); fail()
        } catch (e: ClaudeException) {
            assertTrue(e.message!!.contains("declined"))
        }
    }

    @Test fun rejectedKeyHasAHelpfulMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
        )
        try {
            coach().checkIn(context); fail()
        } catch (e: ClaudeException) {
            assertTrue(e.message!!.contains("API key was rejected"))
        }
    }

    @Test fun chatStreamsTextAndKeepsTheTurnForReplay() = runBlocking {
        val sse = listOf(
            "message_start" to """{"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":50,"output_tokens":1,"cache_read_input_tokens":3000,"cache_creation_input_tokens":0}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Put it in "}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"your TFSA."}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":12}}""",
            "message_stop" to """{"type":"message_stop"}""",
        ).joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))

        val events = coach().chat(context, emptyList(), "Where should my next \$5,000 go?").toList()
        val text = events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.delta }
        assertEquals("Put it in your TFSA.", text)
        val done = events.last() as ChatEvent.Done
        assertEquals("Where should my next \$5,000 go?", done.turn.question)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        val msgs = body["messages"]!!.jsonArray
        assertEquals(3, msgs.size) // data block, assistant acknowledgement, question
        assertEquals("user", msgs.last().jsonObject["role"]!!.jsonPrimitive.content)

        // A follow-up replays the whole first answer, then asks the new question.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        coach().chat(context, listOf(done.turn), "And after that?").toList()
        val msgs2 = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(5, msgs2.size)
        assertEquals("assistant", msgs2[3].jsonObject["role"]!!.jsonPrimitive.content)
    }
}
