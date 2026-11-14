package ca.emarshall.finance.security

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ca.emarshall.finance.net.questrade.QuestradeSession
import ca.emarshall.finance.net.questrade.QuestradeTokenStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

/**
 * Secrets at rest: values are encrypted with Tink AEAD (AES-256-GCM) before they go into DataStore.
 * Tink's keyset is itself encrypted by a master key that lives in the Android Keystore and never leaves
 * it. Each value is bound to its key name as associated data, so ciphertexts can't be swapped around.
 *
 * Holds the database passphrase and the Questrade session. Backups are disabled for the whole app, so
 * none of this leaves the phone.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext private val context: Context) : QuestradeTokenStore {

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val store get() = context.secretsDataStore

    private fun encrypt(name: String, plain: String): String =
        Base64.encodeToString(aead.encrypt(plain.toByteArray(Charsets.UTF_8), name.toByteArray()), Base64.NO_WRAP)

    private fun decrypt(name: String, cipher: String?): String? = cipher?.let {
        String(aead.decrypt(Base64.decode(it, Base64.NO_WRAP), name.toByteArray()), Charsets.UTF_8)
    }

    /** Random 32-byte database passphrase, created on first use. */
    suspend fun databasePassphrase(): ByteArray {
        val existing = decrypt(K_DB.name, store.data.first()[K_DB])
        if (existing != null) return Base64.decode(existing, Base64.NO_WRAP)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        store.edit { it[K_DB] = encrypt(K_DB.name, encoded) }
        return bytes
    }

    override suspend fun load(): QuestradeSession? {
        val p = store.data.first()
        val refresh = decrypt(K_QT_REFRESH.name, p[K_QT_REFRESH]) ?: return null
        return QuestradeSession(
            accessToken = decrypt(K_QT_ACCESS.name, p[K_QT_ACCESS]).orEmpty(),
            refreshToken = refresh,
            apiServer = decrypt(K_QT_SERVER.name, p[K_QT_SERVER]).orEmpty(),
            expiresAtMillis = decrypt(K_QT_EXPIRES.name, p[K_QT_EXPIRES])?.toLongOrNull() ?: 0L,
        )
    }

    /** All four values are written in one DataStore transaction: either the new session is saved, or nothing is. */
    override suspend fun save(session: QuestradeSession) {
        store.edit {
            it[K_QT_ACCESS] = encrypt(K_QT_ACCESS.name, session.accessToken)
            it[K_QT_REFRESH] = encrypt(K_QT_REFRESH.name, session.refreshToken)
            it[K_QT_SERVER] = encrypt(K_QT_SERVER.name, session.apiServer)
            it[K_QT_EXPIRES] = encrypt(K_QT_EXPIRES.name, session.expiresAtMillis.toString())
        }
    }

    /** Encrypts a file's bytes, bound to [label] (e.g. "statement:42") so files can't be swapped. */
    fun sealBytes(label: String, plain: ByteArray): ByteArray = aead.encrypt(plain, label.toByteArray())

    fun openBytes(label: String, cipher: ByteArray): ByteArray = aead.decrypt(cipher, label.toByteArray())

    /** The user's own Anthropic API key for the opt-in Claude coach. Never logged or exported. */
    suspend fun anthropicKey(): String? = decrypt(K_ANTHROPIC.name, store.data.first()[K_ANTHROPIC])

    suspend fun setAnthropicKey(key: String?) {
        store.edit { if (key.isNullOrBlank()) it.remove(K_ANTHROPIC) else it[K_ANTHROPIC] = encrypt(K_ANTHROPIC.name, key.trim()) }
    }

    /** Removes every secret except the database passphrase (used by "Delete all data"). */
    suspend fun clearAll() {
        clear()
        setAnthropicKey(null)
    }

    override suspend fun clear() {
        store.edit {
            it.remove(K_QT_ACCESS); it.remove(K_QT_REFRESH); it.remove(K_QT_SERVER); it.remove(K_QT_EXPIRES)
        }
    }

    companion object {
        private const val KEYSET_NAME = "finance_keyset"
        private const val KEYSET_PREFS = "finance_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://finance_master_key"
        private val K_DB = stringPreferencesKey("db_passphrase")
        private val K_QT_ACCESS = stringPreferencesKey("qt_access")
        private val K_QT_REFRESH = stringPreferencesKey("qt_refresh")
        private val K_QT_SERVER = stringPreferencesKey("qt_server")
        private val K_QT_EXPIRES = stringPreferencesKey("qt_expires")
        private val K_ANTHROPIC = stringPreferencesKey("anthropic_api_key")
    }
}
