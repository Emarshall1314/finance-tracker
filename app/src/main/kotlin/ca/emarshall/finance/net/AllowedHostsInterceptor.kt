package ca.emarshall.finance.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * The app's only network rule: HTTPS to Questrade's login server and its API servers, nothing else.
 * Every OkHttpClient in the app carries this interceptor; a refused request never leaves the phone.
 *
 * [isAllowed] and [allowCleartext] exist only so unit tests can point the client at a local MockWebServer.
 */
class AllowedHostsInterceptor(
    private val isAllowed: (String) -> Boolean = ::isQuestradeHost,
    private val allowCleartext: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !allowCleartext) throw BlockedHostException(url.host)
        if (!isAllowed(url.host)) throw BlockedHostException(url.host)
        return chain.proceed(chain.request())
    }

    companion object {
        private val API_HOST = Regex("api\\d{1,3}\\.iq\\.questrade\\.com")

        /** login.questrade.com and the per-user API servers such as api01.iq.questrade.com. */
        fun isQuestradeHost(host: String): Boolean {
            val h = host.lowercase()
            return h == "login.questrade.com" || API_HOST.matches(h)
        }
    }
}

class BlockedHostException(host: String) : IOException("Network access to $host is not allowed")
