package ca.emarshall.finance.util

import android.util.Log
import ca.emarshall.finance.BuildConfig

/**
 * The only logger in the app. Debug builds log to Logcat; release builds log nothing at all, so tokens
 * and financial data can never end up in device logs. Even in debug, never pass amounts, descriptions,
 * account numbers or tokens.
 */
object AppLog {
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, msg, t)
    }
}
