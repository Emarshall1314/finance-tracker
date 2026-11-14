package ca.emarshall.finance.di

import android.content.Context
import ca.emarshall.finance.data.QuestradeStatus
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.data.db.FinanceDatabase
import ca.emarshall.finance.net.AllowedHostsInterceptor
import ca.emarshall.finance.net.questrade.QuestradeAuth
import ca.emarshall.finance.net.questrade.QuestradeClient
import ca.emarshall.finance.notify.Notifier
import ca.emarshall.finance.security.SecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The encrypted database. Opening it needs the passphrase from the Keystore-backed SecretStore,
     * which is a one-off ~100 ms read the first time anything touches the database.
     */
    @Provides @Singleton
    fun database(@ApplicationContext context: Context, secrets: SecretStore): FinanceDatabase =
        runBlocking(Dispatchers.IO) { FinanceDatabase.open(context, secrets.databasePassphrase()) }

    @Provides fun accountDao(db: FinanceDatabase) = db.accounts()
    @Provides fun transactionDao(db: FinanceDatabase) = db.transactions()
    @Provides fun categoryDao(db: FinanceDatabase) = db.categories()
    @Provides fun importDao(db: FinanceDatabase) = db.imports()
    @Provides fun balanceDao(db: FinanceDatabase) = db.balances()
    @Provides fun questradeDao(db: FinanceDatabase) = db.questrade()
    @Provides fun reminderDao(db: FinanceDatabase) = db.reminders()

    /** The app's single HTTP client. [AllowedHostsInterceptor] refuses every host except Questrade's. */
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AllowedHostsInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun questradeAuth(secrets: SecretStore, http: OkHttpClient, settings: SettingsRepository, notifier: Notifier): QuestradeAuth =
        QuestradeAuth(secrets, http, onDisconnected = { message ->
            settings.setQuestradeStatus(QuestradeStatus.DISCONNECTED, message)
            notifier.questradeDisconnected()
        })

    @Provides @Singleton
    fun questradeClient(auth: QuestradeAuth, http: OkHttpClient): QuestradeClient = QuestradeClient(auth, http)
}
