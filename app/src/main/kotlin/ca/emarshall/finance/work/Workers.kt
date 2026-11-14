package ca.emarshall.finance.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.emarshall.finance.core.reminders.ReminderSchedule
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.QuestradeRepository
import ca.emarshall.finance.data.db.ReminderDao
import ca.emarshall.finance.data.institutionEnum
import ca.emarshall.finance.net.questrade.QuestradeAuthException
import ca.emarshall.finance.notify.Notifier
import ca.emarshall.finance.util.AppLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Posts a statement reminder, then schedules the next one. */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reminderDao: ReminderDao,
    private val accounts: AccountRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_GENERAL, false)) {
            notifier.statementReady(GENERAL_ID, null, "Import your statements", "")
            scheduler.scheduleGeneral(ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }
        val id = inputData.getLong(KEY_REMINDER_ID, -1)
        val r = reminderDao.get(id) ?: return Result.success()
        val account = accounts.get(r.accountId) ?: return Result.success()
        val snooze = inputData.getBoolean(KEY_SNOOZE, false)
        if (r.enabled) {
            // Skip the reminder if an import already covers the statement that just closed.
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val closing = ReminderSchedule.next(scheduler.rule(r), now.minusDays(r.offsetDays + 1L), zone).closingDate
            if (!scheduler.isCovered(account.id, closing)) {
                notifier.statementReady(r.id, account.id, "${account.nickname} statement", account.institutionEnum.displayName)
            }
            if (!snooze) scheduler.schedule(r, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    companion object {
        const val KEY_REMINDER_ID = "reminder_id"
        const val KEY_GENERAL = "general"
        const val KEY_SNOOZE = "snooze"
        const val GENERAL_ID = 998L
    }
}

/** "Snooze 2 days" from the notification. */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, -1)
        if (id < 0) return
        NotificationManagerCompat.from(context).cancel(1000 + id.toInt())
        val data = androidx.work.Data.Builder().putLong(ReminderWorker.KEY_REMINDER_ID, id).putBoolean(ReminderWorker.KEY_SNOOZE, true).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "reminder-snooze-$id", ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(2, TimeUnit.DAYS).setInputData(data)
                .addTag(ReminderScheduler.TAG).build(),
        )
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
    }
}

/** Daily Questrade sync so the single-use refresh token never goes stale. */
@HiltWorker
class QuestradeSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val questrade: QuestradeRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        if (questrade.isConnected()) questrade.sync()
        Result.success()
    } catch (e: QuestradeAuthException) {
        AppLog.w(TAG, "Questrade token rejected")
        Result.failure() // the repository already marked Questrade disconnected and notified
    } catch (e: Exception) {
        AppLog.w(TAG, "Questrade sync failed: ${e.javaClass.simpleName}")
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val TAG = "QuestradeSync"
        private const val NAME = "questrade-daily-sync"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<QuestradeSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
