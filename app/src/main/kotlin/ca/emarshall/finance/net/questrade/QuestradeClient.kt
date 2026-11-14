package ca.emarshall.finance.net.questrade

import ca.emarshall.finance.core.util.DateWindows
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Read-only endpoints. There is deliberately nothing here that can place, change or cancel orders. */
interface QuestradeApi {
    @GET("v1/accounts")
    suspend fun accounts(@Header("Authorization") auth: String): Response<AccountsResponse>

    @GET("v1/accounts/{id}/balances")
    suspend fun balances(@Header("Authorization") auth: String, @Path("id") id: String): Response<BalancesResponse>

    @GET("v1/accounts/{id}/positions")
    suspend fun positions(@Header("Authorization") auth: String, @Path("id") id: String): Response<PositionsResponse>

    @GET("v1/accounts/{id}/activities")
    suspend fun activities(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Query("startTime") startTime: String,
        @Query("endTime") endTime: String,
    ): Response<ActivitiesResponse>
}

class QuestradeHttpException(val code: Int) : IOException("Questrade returned HTTP $code")

/**
 * Calls the API with the current session. On a 401 it refreshes once and retries; on a 429 it waits for
 * the rate-limit reset (X-RateLimit-Reset, epoch seconds) or backs off exponentially; when
 * X-RateLimit-Remaining hits 0 it waits for the reset before the next call.
 */
class QuestradeClient(
    private val auth: QuestradeAuth,
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxRateLimitRetries: Int = 5,
) {
    private val apis = ConcurrentHashMap<String, QuestradeApi>()
    @Volatile private var blockedUntilMillis = 0L

    private fun api(baseUrl: String): QuestradeApi = apis.getOrPut(baseUrl) {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(QuestradeJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(QuestradeApi::class.java)
    }

    suspend fun accounts(): List<QtAccount> = call { a, h -> a.accounts(h) }.accounts

    suspend fun balances(accountNumber: String): BalancesResponse = call { a, h -> a.balances(h, accountNumber) }

    suspend fun positions(accountNumber: String): List<QtPosition> = call { a, h -> a.positions(h, accountNumber) }.positions

    /** Activities between [start] and [end], fetched in 30-day windows (the API allows at most 31 days). */
    suspend fun activities(accountNumber: String, start: ZonedDateTime, end: ZonedDateTime): List<QtActivity> =
        DateWindows.chunk(start, end, 30).flatMap { w ->
            call { a, h -> a.activities(h, accountNumber, ISO.format(w.start), ISO.format(w.end)) }.activities
        }

    private suspend fun <T> call(block: suspend (QuestradeApi, String) -> Response<T>): T {
        var session = auth.session()
        var refreshed = false
        var rateRetries = 0
        while (true) {
            val wait = blockedUntilMillis - now()
            if (wait > 0) sleep(minOf(wait, MAX_WAIT_MILLIS))

            val r = block(api(session.apiServer), "Bearer ${session.accessToken}")
            noteRateLimit(r)
            when {
                r.isSuccessful -> return r.body() ?: throw IOException("Empty response from Questrade")
                r.code() == 401 && !refreshed -> {
                    refreshed = true
                    session = auth.refresh(staleAccessToken = session.accessToken)
                }
                r.code() == 401 -> throw QuestradeAuthException("Questrade refused the refreshed token")
                r.code() == 429 && rateRetries < maxRateLimitRetries -> {
                    rateRetries++
                    val resetWait = resetMillis(r)?.let { it - now() }?.takeIf { it > 0 }
                    sleep(minOf(resetWait ?: (1000L shl (rateRetries - 1)), MAX_WAIT_MILLIS))
                }
                else -> throw QuestradeHttpException(r.code())
            }
        }
    }

    private fun noteRateLimit(r: Response<*>) {
        val remaining = r.headers()["X-RateLimit-Remaining"]?.toLongOrNull()
        val reset = resetMillis(r)
        if (remaining != null && remaining <= 0 && reset != null) blockedUntilMillis = reset
    }

    private fun resetMillis(r: Response<*>): Long? = r.headers()["X-RateLimit-Reset"]?.toLongOrNull()?.let { it * 1000 }

    companion object {
        private const val MAX_WAIT_MILLIS = 60_000L
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}
