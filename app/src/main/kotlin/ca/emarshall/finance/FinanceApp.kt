package ca.emarshall.finance

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import ca.emarshall.finance.data.CategoryRepository
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
            } catch (e: Exception) {
                AppLog.w("FinanceApp", "Startup task failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
