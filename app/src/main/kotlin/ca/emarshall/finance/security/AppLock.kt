package ca.emarshall.finance.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ca.emarshall.finance.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locks the app on launch and after it has been in the background for longer than the timeout
 * (5 minutes by default, adjustable in Settings). Unlocking happens through BiometricPrompt in MainActivity.
 */
@Singleton
class AppLock @Inject constructor(private val settings: SettingsRepository) : DefaultLifecycleObserver {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt: Long? = null
    @Volatile private var timeoutMinutes = 5
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch { settings.settings.collect { timeoutMinutes = it.lockTimeoutMinutes } }
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAt ?: return
        if (SystemClock.elapsedRealtime() - since >= timeoutMinutes * 60_000L) _locked.value = true
        backgroundedAt = null
    }

    fun unlock() {
        _locked.value = false
    }

    fun lockNow() {
        _locked.value = true
    }
}
