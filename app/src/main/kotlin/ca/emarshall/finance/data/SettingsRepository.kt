package ca.emarshall.finance.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class QuestradeStatus { NOT_CONNECTED, CONNECTED, DISCONNECTED }

data class AppSettings(
    val lockTimeoutMinutes: Int = 5,
    val blockScreenshots: Boolean = true,
    val questradeStatus: QuestradeStatus = QuestradeStatus.NOT_CONNECTED,
    val questradeLastSyncMillis: Long = 0L,
    val questradeMessage: String? = null,
    val generalReminderEnabled: Boolean = false,
    val generalReminderDay: Int = 3,
    val generalReminderMinutes: Int = 9 * 60,
    val checklistDismissed: Boolean = false,
    /** Insight ids the user dismissed. */
    val dismissedInsights: Set<String> = emptySet(),
    /** Insight ids already sent as a notification. */
    val notifiedInsights: Set<String> = emptySet(),
    /** The opt-in Claude coach: off until the user enables it and accepts what's shared. */
    val claudeEnabled: Boolean = false,
    /** Which AI the coach uses (see ai/AiPresets). */
    val aiKind: String = "ANTHROPIC",
    val aiLabel: String = "Claude (Anthropic)",
    val aiBaseUrl: String = "https://api.anthropic.com",
    val aiModel: String = "claude-opus-5-5",
)

/** Non-secret preferences. Secrets live in [ca.emarshall.finance.security.SecretStore]. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val ds get() = context.settingsDataStore

    val settings: Flow<AppSettings> = ds.data.map { p ->
        AppSettings(
            lockTimeoutMinutes = p[LOCK_TIMEOUT] ?: 5,
            blockScreenshots = p[BLOCK_SCREENSHOTS] ?: true,
            questradeStatus = p[QT_STATUS]?.let { runCatching { QuestradeStatus.valueOf(it) }.getOrNull() } ?: QuestradeStatus.NOT_CONNECTED,
            questradeLastSyncMillis = p[QT_LAST_SYNC] ?: 0L,
            questradeMessage = p[QT_MESSAGE],
            generalReminderEnabled = p[GEN_ENABLED] ?: false,
            generalReminderDay = p[GEN_DAY] ?: 3,
            generalReminderMinutes = p[GEN_MINUTES] ?: 9 * 60,
            checklistDismissed = p[CHECKLIST_DISMISSED] ?: false,
            dismissedInsights = p[DISMISSED_INSIGHTS] ?: emptySet(),
            notifiedInsights = p[NOTIFIED_INSIGHTS] ?: emptySet(),
            claudeEnabled = p[CLAUDE_ENABLED] ?: false,
            aiKind = p[AI_KIND] ?: "ANTHROPIC",
            aiLabel = p[AI_LABEL] ?: "Claude (Anthropic)",
            aiBaseUrl = p[AI_BASE_URL] ?: "https://api.anthropic.com",
            aiModel = p[AI_MODEL] ?: "claude-opus-5-5",
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLockTimeout(minutes: Int) = ds.edit { it[LOCK_TIMEOUT] = minutes }
    suspend fun setBlockScreenshots(on: Boolean) = ds.edit { it[BLOCK_SCREENSHOTS] = on }
    suspend fun setChecklistDismissed(on: Boolean) = ds.edit { it[CHECKLIST_DISMISSED] = on }

    suspend fun setQuestradeStatus(status: QuestradeStatus, message: String? = null) = ds.edit {
        it[QT_STATUS] = status.name
        if (message == null) it.remove(QT_MESSAGE) else it[QT_MESSAGE] = message
    }

    suspend fun setQuestradeLastSync(millis: Long) = ds.edit { it[QT_LAST_SYNC] = millis }

    suspend fun setGeneralReminder(enabled: Boolean, day: Int, minutes: Int) = ds.edit {
        it[GEN_ENABLED] = enabled; it[GEN_DAY] = day; it[GEN_MINUTES] = minutes
    }

    suspend fun dismissInsight(id: String) = ds.edit { it[DISMISSED_INSIGHTS] = (it[DISMISSED_INSIGHTS] ?: emptySet()) + id }
    suspend fun restoreInsights() = ds.edit { it.remove(DISMISSED_INSIGHTS) }

    /** Remembers notified ids, keeping the set small (ids for old dates fall out of the insight window anyway). */
    suspend fun markNotified(ids: Collection<String>) = ds.edit {
        it[NOTIFIED_INSIGHTS] = ((it[NOTIFIED_INSIGHTS] ?: emptySet()) + ids).toList().takeLast(500).toSet()
    }

    suspend fun setClaudeEnabled(on: Boolean) = ds.edit { it[CLAUDE_ENABLED] = on }

    suspend fun setAi(kind: String, label: String, baseUrl: String, model: String) = ds.edit {
        it[AI_KIND] = kind; it[AI_LABEL] = label; it[AI_BASE_URL] = baseUrl; it[AI_MODEL] = model
    }

    /** Version of the one-time data repairs already run (see FinanceApp). */
    suspend fun repairVersion(): Int = ds.data.first()[REPAIR_VERSION] ?: 0
    suspend fun setRepairVersion(v: Int) = ds.edit { it[REPAIR_VERSION] = v }

    suspend fun clearAll() = ds.edit { it.clear() }

    private companion object {
        val LOCK_TIMEOUT = intPreferencesKey("lock_timeout_minutes")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
        val QT_STATUS = stringPreferencesKey("questrade_status")
        val QT_LAST_SYNC = longPreferencesKey("questrade_last_sync")
        val QT_MESSAGE = stringPreferencesKey("questrade_message")
        val GEN_ENABLED = booleanPreferencesKey("general_reminder_enabled")
        val GEN_DAY = intPreferencesKey("general_reminder_day")
        val GEN_MINUTES = intPreferencesKey("general_reminder_minutes")
        val CHECKLIST_DISMISSED = booleanPreferencesKey("checklist_dismissed")
        val DISMISSED_INSIGHTS = stringSetPreferencesKey("dismissed_insights")
        val NOTIFIED_INSIGHTS = stringSetPreferencesKey("notified_insights")
        val CLAUDE_ENABLED = booleanPreferencesKey("claude_enabled")
        val AI_KIND = stringPreferencesKey("ai_kind")
        val AI_LABEL = stringPreferencesKey("ai_label")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val REPAIR_VERSION = intPreferencesKey("repair_version")
    }
}
