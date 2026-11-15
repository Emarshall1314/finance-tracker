package ca.emarshall.finance.core.importing

import ca.emarshall.finance.core.model.AccountHint
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.Institution

/**
 * What the matcher needs to know about one of the user's accounts. [last4s] holds every card or account
 * ending the account has had (a reissued card gets new digits; merging keeps the old ones too).
 */
data class AccountRef(val id: Long, val institution: Institution, val type: AccountType, val last4s: Set<String> = emptySet()) {
    constructor(id: Long, institution: Institution, type: AccountType, last4: String?) : this(id, institution, type, setOfNotNull(last4))
}

/**
 * Decides which account a statement belongs to, from what the file says about itself. It never files a
 * statement into an account of a conflicting kind (a credit card statement into chequing) or with
 * different last-four digits; when nothing fits, it returns null so a new account can be made.
 */
object AccountMatcher {

    fun match(hint: AccountHint, accounts: List<AccountRef>): Long? {
        val candidates = accounts.filter { a -> compatible(hint, a) }
        return candidates.firstOrNull { hint.last4 != null && hint.last4 in it.last4s }?.id
            ?: candidates.filter { hint.accountType != null && it.type == hint.accountType }.singleOrNull()?.id
            ?: candidates.singleOrNull()?.id
    }

    /** Could this statement belong to this account at all? */
    fun compatible(hint: AccountHint, a: AccountRef): Boolean {
        if (hint.institution != null && hint.institution != a.institution) return false
        if (hint.accountType != null && hint.accountType.isLiability != a.type.isLiability) return false
        if (hint.accountType != null && hint.accountType.isInvestment != a.type.isInvestment) return false
        if (hint.last4 != null && a.last4s.isNotEmpty() && hint.last4 !in a.last4s) return false
        return true
    }

    /** A statement already filed in [a] that the matcher would now reject. */
    fun misfiled(hint: AccountHint, a: AccountRef): Boolean = !compatible(hint, a)
}

/** The account the app sets up from a statement when none of the user's accounts fits it. */
data class AccountGuess(val nickname: String, val institution: Institution, val type: AccountType, val last4: String?, val currency: String)

object AccountGuesser {
    /**
     * Best guess from what the file says, or null when the file doesn't say enough to guess the kind of
     * account (e.g. a CSV from an unknown bank). [takenNames] keeps nicknames unique.
     */
    fun guess(hint: AccountHint, takenNames: Collection<String> = emptyList()): AccountGuess? {
        val inst = hint.institution ?: return null
        val type = hint.accountType ?: when (inst) {
            Institution.CANADIAN_TIRE -> AccountType.CREDIT_CARD // Canadian Tire Bank files are Triangle cards
            else -> return null
        }
        val base = when {
            inst == Institution.CANADIAN_TIRE && type == AccountType.CREDIT_CARD -> "Triangle Mastercard"
            inst == Institution.SCOTIABANK && type == AccountType.CREDIT_CARD -> "Scotia Visa"
            inst == Institution.SCOTIABANK -> "Scotiabank ${type.displayName.lowercase()}"
            else -> "${inst.displayName} ${type.displayName.lowercase()}"
        }
        val taken = takenNames.map { it.lowercase() }.toSet()
        val name = when {
            base.lowercase() !in taken -> base
            hint.last4 != null && "$base ${hint.last4}".lowercase() !in taken -> "$base ${hint.last4}"
            else -> (2..99).map { "$base $it" }.first { it.lowercase() !in taken }
        }
        return AccountGuess(name, inst, type, hint.last4, hint.currency ?: "CAD")
    }
}
