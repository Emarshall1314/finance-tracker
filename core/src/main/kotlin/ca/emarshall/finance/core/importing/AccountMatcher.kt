package ca.emarshall.finance.core.importing

import ca.emarshall.finance.core.model.AccountHint
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.Institution

/** What the matcher needs to know about one of the user's accounts. */
data class AccountRef(val id: Long, val institution: Institution, val type: AccountType, val last4: String?)

/**
 * Decides which account a statement belongs to, from what the file says about itself. It never files a
 * statement into an account of a conflicting kind (a credit card statement into chequing) or with
 * different last-four digits; when nothing fits, it returns null so a new account can be made.
 */
object AccountMatcher {

    fun match(hint: AccountHint, accounts: List<AccountRef>): Long? {
        val candidates = accounts.filter { a -> compatible(hint, a) }
        return candidates.firstOrNull { hint.last4 != null && it.last4 == hint.last4 }?.id
            ?: candidates.filter { hint.accountType != null && it.type == hint.accountType }.singleOrNull()?.id
            ?: candidates.singleOrNull()?.id
    }

    /** Could this statement belong to this account at all? */
    fun compatible(hint: AccountHint, a: AccountRef): Boolean {
        if (hint.institution != null && hint.institution != a.institution) return false
        if (hint.accountType != null && hint.accountType.isLiability != a.type.isLiability) return false
        if (hint.accountType != null && hint.accountType.isInvestment != a.type.isInvestment) return false
        if (hint.last4 != null && a.last4 != null && hint.last4 != a.last4) return false
        return true
    }

    /** A statement already filed in [a] that the matcher would now reject. */
    fun misfiled(hint: AccountHint, a: AccountRef): Boolean = !compatible(hint, a)
}
