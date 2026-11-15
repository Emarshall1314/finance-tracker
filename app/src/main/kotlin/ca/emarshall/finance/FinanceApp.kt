package ca.emarshall.finance

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.CategoryRepository
import ca.emarshall.finance.data.ImportRepository
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.data.QuestradeRepository
import ca.emarshall.finance.notify.Notifier
import ca.emarshall.finance.security.AppLock
import ca.emarshall.finance.util.AppLog
import ca.emarshall.finance.work.QuestradeSyncWorker
import ca.emarshall.finance.work.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FinanceApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var categories: dagger.Lazy<CategoryRepository>
    @Inject lateinit var reminders: dagger.Lazy<ReminderScheduler>
    @Inject lateinit var questrade: dagger.Lazy<QuestradeRepository>
    @Inject lateinit var imports: dagger.Lazy<ImportRepository>
    @Inject lateinit var settings: dagger.Lazy<SettingsRepository>
    @Inject lateinit var accounts: dagger.Lazy<AccountRepository>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appLock.install()
        notifier.createChannels()
        // Off the main thread: opening the encrypted database reads the Keystore-protected passphrase.
        scope.launch {
            try {
                categories.get().ensureSeeded()
                reminders.get().rescheduleAll()
                if (questrade.get().isConnected()) QuestradeSyncWorker.schedule(this@FinanceApp)
                // Once: move statements that older versions filed in the wrong account (Visa into chequing).
                val done = settings.get().repairVersion()
                if (done < 1) imports.get().repairMisfiledImports()
                // Accounts set up from statements before the app asked: ask once now.
                if (done < 2) accounts.get().markStatementAccountsUnconfirmed()
                if (done < 2) settings.get().setRepairVersion(2)
            } catch (e: Exception) {
                AppLog.w("FinanceApp", "Startup task failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
