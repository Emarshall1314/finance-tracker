package ca.emarshall.finance.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ca.emarshall.finance.core.reminders.ReminderRule
import ca.emarshall.finance.core.reminders.ReminderSchedule
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.data.db.ImportDao
import ca.emarshall.finance.data.db.ReminderDao
import ca.emarshall.finance.data.db.ReminderEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One unique one-time WorkManager job per reminder, delayed until its next fire time (no exact alarms).
 * Recomputed on app start, after every import, and whenever reminder settings change.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao,
    private val importDao: ImportDao,
    private val settings: SettingsRepository,
) {
    private val wm get() = WorkManager.getInstance(context)
    private fun zone(): ZoneId = ZoneId.systemDefault()

    suspend fun rescheduleAll() {
        reminderDao.all().forEach { schedule(it) }
        scheduleGeneral()
    }

    suspend fun schedule(r: ReminderEntity, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        if (!r.enabled) {
            wm.cancelUniqueWork(name(r.id))
            reminderDao.setNextFire(r.id, null)
            return
        }
        val next = nextFor(r)
        enqueue(name(r.id), next, Data.Builder().putLong(ReminderWorker.KEY_REMINDER_ID, r.id).build(), policy)
        reminderDao.setNextFire(r.id, next.toInstant().toEpochMilli())
    }

    suspend fun nextFor(r: ReminderEntity, now: ZonedDateTime = ZonedDateTime.now(zone())): ZonedDateTime =
        ReminderSchedule.next(rule(r), now, zone(), importedThrough(r.accountId)).fireAt

    /** Whether the statement that closed on [closing] is already covered by an import. */
    suspend fun isCovered(accountId: Long, closing: LocalDate): Boolean = importedThrough(accountId)?.let { !closing.isAfter(it) } ?: false

    suspend fun importedThrough(accountId: Long): LocalDate? = importDao.importedThrough(accountId)

    fun rule(r: ReminderEntity) = ReminderRule(r.closingDay, r.offsetDays, LocalTime.of(r.timeOfDayMinutes / 60, r.timeOfDayMinutes % 60))

    suspend fun scheduleGeneral(policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val s = settings.current()
        if (!s.generalReminderEnabled) {
            wm.cancelUniqueWork(GENERAL)
            return
        }
        val next = ReminderSchedule.nextMonthly(
            s.generalReminderDay, LocalTime.of(s.generalReminderMinutes / 60, s.generalReminderMinutes % 60), ZonedDateTime.now(zone()), zone(),
        )
        enqueue(GENERAL, next, Data.Builder().putBoolean(ReminderWorker.KEY_GENERAL, true).build(), policy)
    }

    /** Re-shows a reminder in two days. */
    fun snooze(reminderId: Long) {
        val data = Data.Builder().putLong(ReminderWorker.KEY_REMINDER_ID, reminderId).putBoolean(ReminderWorker.KEY_SNOOZE, true).build()
        wm.enqueueUniqueWork(
            "$PREFIX-snooze-$reminderId", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(2, TimeUnit.DAYS).setInputData(data).addTag(TAG).build(),
        )
    }

    fun cancelAll() = wm.cancelAllWorkByTag(TAG)

    private fun enqueue(name: String, at: ZonedDateTime, data: Data, policy: ExistingWorkPolicy) {
        val delay = Duration.between(ZonedDateTime.now(zone()), at).toMillis().coerceAtLeast(0)
        wm.enqueueUniqueWork(
            name, policy,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag(TAG).build(),
        )
    }

    companion object {
        private const val PREFIX = "reminder"
        const val TAG = "reminders"
        const val GENERAL = "$PREFIX-general"
        fun name(id: Long) = "$PREFIX-$id"
    }
}
