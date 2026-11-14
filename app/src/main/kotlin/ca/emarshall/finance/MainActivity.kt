package ca.emarshall.finance

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.security.AppLock
import ca.emarshall.finance.ui.AppRoot
import ca.emarshall.finance.ui.PendingNav
import ca.emarshall.finance.ui.theme.FinanceTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var settings: SettingsRepository

    /** Where to go once unlocked: a shared/opened file, or a notification action. */
    private val pendingNav = MutableStateFlow<PendingNav?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hide content in Recents and block screenshots before anything is drawn; the setting can relax it.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            settings.settings.collect { s ->
                if (s.blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FinanceTheme {
                AppRoot(
                    appLock = appLock,
                    pendingNav = pendingNav,
                    onUnlockRequested = ::authenticate,
                    deviceSecure = deviceCanAuthenticate(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (appLock.locked.value && deviceCanAuthenticate()) authenticate()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val nav: PendingNav? = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { PendingNav.ImportFile(it.toString()) }
            Intent.ACTION_VIEW -> intent.data?.let { PendingNav.ImportFile(it.toString()) }
            ACTION_IMPORT -> PendingNav.ImportForAccount(intent.getLongExtra(EXTRA_ACCOUNT_ID, -1))
            ACTION_QUESTRADE -> PendingNav.Questrade
            else -> null
        }
        if (nav != null) pendingNav.value = nav
    }

    private fun deviceCanAuthenticate(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private var prompting = false

    private fun authenticate() {
        if (prompting || !appLock.locked.value) return
        if (!deviceCanAuthenticate()) return
        prompting = true
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    appLock.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Finance Tracker")
                .setSubtitle("Your financial data is locked")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    companion object {
        const val ACTION_IMPORT = "ca.emarshall.finance.action.IMPORT"
        const val ACTION_QUESTRADE = "ca.emarshall.finance.action.QUESTRADE"
        const val EXTRA_ACCOUNT_ID = "account_id"
    }
}
