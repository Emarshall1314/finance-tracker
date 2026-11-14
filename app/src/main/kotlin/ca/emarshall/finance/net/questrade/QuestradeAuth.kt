package ca.emarshall.finance.net.questrade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A Questrade session. Never logged, never exported; persisted only through [QuestradeTokenStore]. */
data class QuestradeSession(
    val accessToken: String,
    val refreshToken: String,
    /** Base URL from the token response, e.g. https://api01.iq.questrade.com/ */
    val apiServer: String,
    val expiresAtMillis: Long,
) {
    fun isUsable(nowMillis: Long) = accessToken.isNotEmpty() && apiServer.isNotEmpty() && nowMillis < expiresAtMillis - 60_000
    override fun toString() = "QuestradeSession(apiServer=$apiServer, expiresAt=$expiresAtMillis)"
}

interface QuestradeTokenStore {
    suspend fun load(): QuestradeSession?

    /** Must be atomic: all fields or none. */
    suspend fun save(session: QuestradeSession)
    suspend fun clear()
}

/** The refresh token was rejected: the user has to generate a new one. */
class QuestradeAuthException(message: String) : IOException(message)

/**
 * Handles Questrade's single-use refresh tokens:
 *
 * - every redeem returns a new refresh token and invalidates the old one, so the new session is saved
 *   (encrypted, atomically) **before** anyone uses the access token;
 * - a Mutex makes sure two refreshes can never run at once: a caller that waited on the lock re-reads the
 *   store and uses the session the other caller just obtained instead of spending the token again.
 */
class QuestradeAuth(
    private val store: QuestradeTokenStore,
    private val http: OkHttpClient,
    private val loginBaseUrl: HttpUrl = "https://login.questrade.com/".toHttpUrl(),
    private val now: () -> Long = System::currentTimeMillis,
    private val onDisconnected: suspend (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /** Redeems a manually generated token from the API Centre. */
    suspend fun connect(manualRefreshToken: String): QuestradeSession = mutex.withLock {
        redeem(manualRefreshToken.trim())
    }

    /** A usable session, refreshing first if the access token has expired. */
    suspend fun session(): QuestradeSession {
        val current = store.load() ?: throw QuestradeAuthException("Questrade is not connected")
        if (current.isUsable(now())) return current
        return refresh(staleAccessToken = current.accessToken)
    }

    /**
     * Refreshes unless someone else already did since [staleAccessToken] was read (then their session is
     * returned). Pass the access token that just got a 401.
     */
    suspend fun refresh(staleAccessToken: String?): QuestradeSession = mutex.withLock {
        val current = store.load() ?: throw QuestradeAuthException("Questrade is not connected")
        if (current.accessToken != staleAccessToken && current.isUsable(now())) return current
        redeem(current.refreshToken)
    }

    private suspend fun redeem(refreshToken: String): QuestradeSession = withContext(Dispatchers.IO) {
        val url = loginBaseUrl.newBuilder()
            .addPathSegments("oauth2/token")
            .addQueryParameter("grant_type", "refresh_token")
            .addQueryParameter("refresh_token", refreshToken)
            .build()
        val response = try {
            http.newCall(Request.Builder().url(url).get().build()).execute()
        } catch (e: IOException) {
            throw e // network trouble: keep the stored token, try again later
        }
        response.use { r ->
            if (r.code == 400 || r.code == 401 || r.code == 403) {
                onDisconnected("Questrade rejected the saved token (HTTP ${r.code}). Generate a new token to reconnect.")
                throw QuestradeAuthException("Questrade rejected the token (HTTP ${r.code})")
            }
            if (!r.isSuccessful) throw IOException("Questrade login server returned HTTP ${r.code}")
            val body = r.body.string()
            val t = QuestradeJson.decodeFromString(TokenResponse.serializer(), body)
            val server = if (t.apiServer.endsWith("/")) t.apiServer else t.apiServer + "/"
            val session = QuestradeSession(t.accessToken, t.refreshToken, server, now() + t.expiresIn * 1000)
            store.save(session) // before anything uses it: the old refresh token is now dead
            session
        }
    }
}
