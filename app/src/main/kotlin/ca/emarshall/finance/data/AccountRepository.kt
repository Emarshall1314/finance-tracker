package ca.emarshall.finance.data

import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.data.db.AccountDao
import ca.emarshall.finance.data.db.AccountEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

val AccountEntity.institutionEnum: Institution get() = runCatching { Institution.valueOf(institution) }.getOrDefault(Institution.OTHER)
val AccountEntity.typeEnum: AccountType get() = runCatching { AccountType.valueOf(type) }.getOrDefault(AccountType.OTHER)

@Singleton
class AccountRepository @Inject constructor(private val dao: AccountDao) {
    val accounts: Flow<List<AccountEntity>> = dao.observeAll()

    /** Accounts set up from statements that the user hasn't checked yet. */
    val unconfirmed: Flow<List<AccountEntity>> = dao.observeUnconfirmed()

    suspend fun all() = dao.all()
    suspend fun get(id: Long) = dao.get(id)

    suspend fun add(
        nickname: String,
        institution: Institution,
        type: AccountType,
        last4: String?,
        currency: String = "CAD",
        closingDay: Int? = null,
        confirmed: Boolean = true,
    ): Long = dao.insert(
        AccountEntity(
            institution = institution.name,
            nickname = nickname.trim(),
            type = type.name,
            currency = currency,
            last4 = last4?.filter { it.isDigit() }?.takeLast(4)?.ifEmpty { null },
            closingDay = closingDay,
            confirmed = confirmed,
        ),
    )

    suspend fun confirm(id: Long) { dao.get(id)?.let { dao.update(it.copy(confirmed = true)) } }

    suspend fun markStatementAccountsUnconfirmed() = dao.markStatementAccountsUnconfirmed()

    suspend fun update(a: AccountEntity) = dao.update(a)
    suspend fun delete(id: Long) = dao.delete(id)
}
