package ca.emarshall.finance.net

import ca.emarshall.finance.net.questrade.QuestradeAuth
import ca.emarshall.finance.net.questrade.QuestradeAuthException
import ca.emarshall.finance.net.questrade.QuestradeClient
import ca.emarshall.finance.net.questrade.QuestradeHttpException
import ca.emarshall.finance.net.questrade.QuestradeJson
import ca.emarshall.finance.net.questrade.QuestradeSession
import ca.emarshall.finance.net.questrade.QuestradeTokenStore
import ca.emarshall.finance.net.questrade.BalancesResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Every request goes to a local MockWebServer; no real Questrade token is ever involved. */
class QuestradeClientTest {

    private class MemoryStore(var session: QuestradeSession? = null) : QuestradeTokenStore {
        val saves = Collections.synchronizedList(ArrayList<QuestradeSession>())
        private val lock = Mutex()
        override suspend fun load() = lock.withLock { session }
        override suspend fun save(session: QuestradeSession) = lock.withLock { this.session = session; saves.add(session); Unit }
        override suspend fun clear() = lock.withLock { session = null }
    }

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private val tokenCalls = AtomicInteger()
    private val refreshTokensSeen = Collections.synchronizedList(ArrayList<String>())
    private var clock = 1_000_000_000_000L
    private val sleeps = Collections.synchronizedList(ArrayList<Long>())

    /** Programmable API behaviour per path. */
    private val api = HashMap<String, (RecordedRequest) -> MockResponse>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/oauth2/token") {
                    val n = tokenCalls.incrementAndGet()
                    val rt = request.requestUrl!!.queryParameter("refresh_token")!!
                    refreshTokensSeen.add(rt)
                    if (rt == "revoked") return MockResponse().setResponseCode(400).setBody("""{"error":"invalid"}""")
                    Thread.sleep(50) // widen the race window for the concurrency test
                    return MockResponse().setBody(
                        """{"access_token":"access-$n","token_type":"Bearer","expires_in":1800,
                           "refresh_token":"refresh-$n","api_server":"${server.url("/")}"}""",
                    )
                }
                return api[path]?.invoke(request) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        http = OkHttpClient.Builder()
            .addInterceptor(AllowedHostsInterceptor(isAllowed = { it == server.hostName }, allowCleartext = true))
            .build()
    }

    @After fun tearDown() = server.shutdown()

    private fun auth(store: MemoryStore, onDisconnected: suspend (String) -> Unit = {}) =
        QuestradeAuth(store, http, server.url("/"), now = { clock }, onDisconnected = onDisconnected)

    private fun client(store: MemoryStore) = QuestradeClient(auth(store), http, now = { clock }, sleep = { sleeps.add(it) })

    private fun ok(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test fun connectRedeemsTokenAndSavesRotatedTokenBeforeUse() = runBlocking {
        val store = MemoryStore()
        val s = auth(store).connect("  manual-token  ")
        assertEquals(listOf("manual-token"), refreshTokensSeen)
        assertEquals("access-1", s.accessToken)
        assertEquals("refresh-1", store.session!!.refreshToken)
        assertEquals(server.url("/").toString(), s.apiServer)
        assertEquals(clock + 1_800_000, s.expiresAtMillis) // expires_in, not a hard-coded lifetime
    }

    @Test fun refreshTokensAreSingleUseAndRotate() = runBlocking {
        val store = MemoryStore()
        val a = auth(store)
        a.connect("manual")
        clock += 2_000_000 // past expiry
        a.session()
        clock += 2_000_000
        a.session()
        assertEquals(listOf("manual", "refresh-1", "refresh-2"), refreshTokensSeen)
        assertEquals("refresh-3", store.session!!.refreshToken)
    }

    @Test fun concurrentRefreshesSpendTheTokenOnce() = runBlocking {
        val store = MemoryStore(QuestradeSession("stale", "refresh-0", server.url("/").toString(), 0))
        val a = auth(store)
        val sessions = (1..8).map { async(kotlinx.coroutines.Dispatchers.IO) { a.refresh(staleAccessToken = "stale") } }.awaitAll()
        assertEquals(1, tokenCalls.get())
        assertEquals(listOf("refresh-0"), refreshTokensSeen)
        assertTrue(sessions.all { it.accessToken == "access-1" })
    }

    @Test fun retriesOnceAfter401WithFreshToken() = runBlocking {
        val store = MemoryStore()
        auth(store).connect("manual")
        val calls = AtomicInteger()
        api["/v1/accounts"] = { req ->
            if (calls.incrementAndGet() == 1) MockResponse().setResponseCode(401)
            else {
                assertEquals("Bearer access-2", req.getHeader("Authorization"))
                ok("""{"accounts":[{"type":"TFSA","number":"11112222","status":"Active"}],"userId":1}""")
            }
        }
        val accounts = client(store).accounts()
        assertEquals("TFSA", accounts.single().type)
        assertEquals(2, calls.get())
        assertEquals(2, tokenCalls.get())
    }

    @Test fun secondConsecutive401Fails() = runBlocking {
        val store = MemoryStore()
        auth(store).connect("manual")
        api["/v1/accounts"] = { MockResponse().setResponseCode(401) }
        try {
            client(store).accounts(); fail()
        } catch (e: QuestradeAuthException) {
            // expected
        }
        Unit
    }

    @Test fun rejectedRefreshMarksDisconnected() = runBlocking {
        val store = MemoryStore(QuestradeSession("old", "revoked", server.url("/").toString(), 0))
        var message: String? = null
        try {
            auth(store) { message = it }.session(); fail()
        } catch (e: QuestradeAuthException) {
            // expected
        }
        assertTrue(message!!.contains("new token"))
        assertEquals("revoked", store.session!!.refreshToken) // nothing overwritten
    }

    @Test fun backsOffOn429UsingResetHeader() = runBlocking {
        val store = MemoryStore()
        auth(store).connect("manual")
        val calls = AtomicInteger()
        api["/v1/accounts"] = {
            if (calls.incrementAndGet() <= 2) {
                MockResponse().setResponseCode(429).setHeader("X-RateLimit-Reset", ((clock + 3_000) / 1000).toString())
            } else {
                ok("""{"accounts":[]}""")
            }
        }
        client(store).accounts()
        assertEquals(3, calls.get())
        assertEquals(listOf(3_000L, 3_000L), sleeps)
    }

    @Test fun backsOffExponentiallyWithoutResetHeaderAndGivesUp() = runBlocking {
        val store = MemoryStore()
        auth(store).connect("manual")
        api["/v1/accounts"] = { MockResponse().setResponseCode(429) }
        try {
            client(store).accounts(); fail()
        } catch (e: QuestradeHttpException) {
            assertEquals(429, e.code)
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), sleeps)
    }

    @Test fun waitsWhenRemainingHitsZero() = runBlocking {
        val store = MemoryStore()
        auth(store).connect("manual")
        api["/v1/accounts"] = {
            ok("""{"accounts":[]}""").setHeader("X-RateLimit-Remaining", "0").setHeader("X-RateLimit-Reset", ((clock + 5_000) / 1000).toString())
        }
        val c = client(store)
        c.accounts()
        assertTrue(sleeps.isEmpty())
        c.accounts()
        assertEquals(listOf(5_000L), sleeps)
    }

    @Test fun activitiesAreChunkedInto30DayWindows() = runBlocking {
        val store = MemoryStore()
        auth(store).connect("manual")
        val windows = Collections.synchronizedList(ArrayList<Pair<String, String>>())
        api["/v1/accounts/11112222/activities"] = { req ->
            windows.add(req.requestUrl!!.queryParameter("startTime")!! to req.requestUrl!!.queryParameter("endTime")!!)
            ok("""{"activities":[{"transactionDate":"2026-01-15T00:00:00.000000-05:00","type":"Dividends","currency":"CAD","netAmount":12.34,"description":"X"}]}""")
        }
        val zone = ZoneId.of("America/Vancouver")
        val end = ZonedDateTime.of(2026, 11, 14, 0, 0, 0, 0, zone)
        val acts = client(store).activities("11112222", end.minusMonths(12), end)
        assertEquals(13, windows.size)
        assertEquals(13, acts.size)
        windows.forEach { (s, e) ->
            val days = java.time.Duration.between(ZonedDateTime.parse(s), ZonedDateTime.parse(e)).toDays()
            assertTrue("window $s..$e is $days days", days <= 31)
        }
        assertEquals("2025-11-14T00:00:00-08:00", windows.first().first)
        assertEquals(windows[0].second, windows[1].first) // contiguous
    }

    @Test fun moneyIsParsedWithoutDouble() {
        val json = """{"perCurrencyBalances":[{"currency":"CAD","cash":0.1,"marketValue":1234567890.12,"totalEquity":0.30000000000000004}],
                       "combinedBalances":[]}"""
        val b = QuestradeJson.decodeFromString(BalancesResponse.serializer(), json).perCurrencyBalances.single()
        assertEquals(BigDecimal("0.1"), b.cash)
        assertEquals(BigDecimal("1234567890.12"), b.marketValue)
        assertEquals(BigDecimal("0.30000000000000004"), b.totalEquity) // exact literal, not a binary double
    }

    @Test fun onlyQuestradeHostsAreAllowed() {
        assertTrue(AllowedHostsInterceptor.isQuestradeHost("login.questrade.com"))
        assertTrue(AllowedHostsInterceptor.isQuestradeHost("api01.iq.questrade.com"))
        assertTrue(AllowedHostsInterceptor.isQuestradeHost("API07.IQ.QUESTRADE.COM"))
        assertFalse(AllowedHostsInterceptor.isQuestradeHost("questrade.com.evil.example"))
        assertFalse(AllowedHostsInterceptor.isQuestradeHost("api01.iq.questrade.com.evil.example"))
        assertFalse(AllowedHostsInterceptor.isQuestradeHost("www.google.com"))
        assertFalse(AllowedHostsInterceptor.isQuestradeHost("evil.iq.questrade.com"))

        val strict = OkHttpClient.Builder().addInterceptor(AllowedHostsInterceptor()).build()
        try {
            strict.newCall(Request.Builder().url(server.url("/v1/accounts")).build()).execute(); fail()
        } catch (e: BlockedHostException) {
            assertEquals(0, server.requestCount)
        }
        assertNull(null)
    }
}
