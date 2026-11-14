package ca.emarshall.finance.data

import ca.emarshall.finance.data.db.ReminderDao
import ca.emarshall.finance.data.db.ReminderEntity
import ca.emarshall.finance.work.ReminderScheduler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderRepository @Inject constructor(
    private val dao: ReminderDao,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val scheduler: ReminderScheduler,
) {
    val reminders = dao.observeAll()

    /** Saves a per-account reminder and the account's closing day, then reschedules it. */
    suspend fun save(accountId: Long, closingDay: Int, offsetDays: Int, timeOfDayMinutes: Int, enabled: Boolean) {
        val existing = dao.forAccount(accountId)
        val r = (existing ?: ReminderEntity(accountId = accountId, closingDay = closingDay)).copy(
            closingDay = closingDay.coerceIn(1, 31), offsetDays = offsetDays.coerceIn(0, 60),
            timeOfDayMinutes = timeOfDayMinutes.coerceIn(0, 24 * 60 - 1), enabled = enabled,
        )
        val id = dao.upsert(r).let { if (it == -1L) r.id else it }
        accounts.get(accountId)?.let { accounts.update(it.copy(closingDay = r.closingDay)) }
        scheduler.schedule(dao.get(if (r.id != 0L) r.id else id)!!)
    }

    suspend fun saveGeneral(enabled: Boolean, day: Int, minutes: Int) {
        settings.setGeneralReminder(enabled, day.coerceIn(1, 31), minutes)
        scheduler.scheduleGeneral()
    }
}
