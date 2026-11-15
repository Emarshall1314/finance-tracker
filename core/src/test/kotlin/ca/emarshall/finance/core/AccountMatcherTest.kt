package ca.emarshall.finance.core

import ca.emarshall.finance.core.importing.AccountMatcher
import ca.emarshall.finance.core.importing.AccountRef
import ca.emarshall.finance.core.model.AccountHint
import ca.emarshall.finance.core.model.AccountType.CHEQUING
import ca.emarshall.finance.core.model.AccountType.CREDIT_CARD
import ca.emarshall.finance.core.model.AccountType.SAVINGS
import ca.emarshall.finance.core.model.Institution.CANADIAN_TIRE
import ca.emarshall.finance.core.model.Institution.SCOTIABANK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMatcherTest {
    private val chequing = AccountRef(1, SCOTIABANK, CHEQUING, "0099")
    private val triangle = AccountRef(2, CANADIAN_TIRE, CREDIT_CARD, "0002")

    @Test fun cardStatementNeverLandsInTheOnlyChequingAccount() {
        // The bug: the Visa statement went into chequing because it was the only Scotiabank account.
        assertNull(AccountMatcher.match(AccountHint(SCOTIABANK, CREDIT_CARD, "0001"), listOf(chequing, triangle)))
    }

    @Test fun matchesByLast4ThenType() {
        val visa = AccountRef(3, SCOTIABANK, CREDIT_CARD, "0001")
        val all = listOf(chequing, triangle, visa)
        assertEquals(3L, AccountMatcher.match(AccountHint(SCOTIABANK, CREDIT_CARD, "0001"), all))
        assertEquals(1L, AccountMatcher.match(AccountHint(SCOTIABANK, CHEQUING, "0099"), all))
        // Account without last4 yet: matched by type.
        val visaNoDigits = visa.copy(last4s = emptySet())
        assertEquals(3L, AccountMatcher.match(AccountHint(SCOTIABANK, CREDIT_CARD, "0001"), listOf(chequing, visaNoDigits)))
        // Different last4 on the same kind of account: not this one.
        assertNull(AccountMatcher.match(AccountHint(SCOTIABANK, CREDIT_CARD, "7777"), listOf(chequing, visa)))
    }

    @Test fun ambiguousSameTypeAccountsNeedTheUser() {
        val a = AccountRef(4, SCOTIABANK, CHEQUING, null)
        val b = AccountRef(5, SCOTIABANK, SAVINGS, null)
        assertNull(AccountMatcher.match(AccountHint(SCOTIABANK, null, null), listOf(a, b)))
        assertEquals(4L, AccountMatcher.match(AccountHint(SCOTIABANK, CHEQUING, null), listOf(a, b)))
    }

    @Test fun detectsMisfiledStatements() {
        assertTrue(AccountMatcher.misfiled(AccountHint(SCOTIABANK, CREDIT_CARD, "0001"), chequing))
        assertFalse(AccountMatcher.misfiled(AccountHint(SCOTIABANK, CHEQUING, "0099"), chequing))
        assertFalse(AccountMatcher.misfiled(AccountHint(SCOTIABANK, null, null), chequing))
    }
}

class AccountGuesserTest {
    @Test fun guessesFromTheStatement() {
        val visa = ca.emarshall.finance.core.importing.AccountGuesser.guess(AccountHint(SCOTIABANK, CREDIT_CARD, "0001"))!!
        assertEquals("Scotia Visa", visa.nickname)
        assertEquals(CREDIT_CARD, visa.type)
        assertEquals("0001", visa.last4)
        assertEquals("Scotiabank chequing", ca.emarshall.finance.core.importing.AccountGuesser.guess(AccountHint(SCOTIABANK, CHEQUING, null))!!.nickname)
        // Canadian Tire files are Triangle cards even when the file doesn't say so (CSV).
        assertEquals(CREDIT_CARD, ca.emarshall.finance.core.importing.AccountGuesser.guess(AccountHint(CANADIAN_TIRE))!!.type)
        // Not enough to go on.
        assertNull(ca.emarshall.finance.core.importing.AccountGuesser.guess(AccountHint(SCOTIABANK)))
        assertNull(ca.emarshall.finance.core.importing.AccountGuesser.guess(AccountHint()))
    }

    @Test fun keepsNamesUnique() {
        val g = ca.emarshall.finance.core.importing.AccountGuesser
        assertEquals("Scotia Visa 0002", g.guess(AccountHint(SCOTIABANK, CREDIT_CARD, "0002"), listOf("Scotia Visa"))!!.nickname)
        assertEquals("Scotia Visa 2", g.guess(AccountHint(SCOTIABANK, CREDIT_CARD, null), listOf("scotia visa"))!!.nickname)
    }

    @Test fun reissuedCardKeepsMatchingOldDigits() {
        val merged = AccountRef(9, SCOTIABANK, CREDIT_CARD, setOf("0001", "0003"))
        assertEquals(9L, AccountMatcher.match(AccountHint(SCOTIABANK, CREDIT_CARD, "0001"), listOf(merged)))
        assertEquals(9L, AccountMatcher.match(AccountHint(SCOTIABANK, CREDIT_CARD, "0003"), listOf(merged)))
        assertNull(AccountMatcher.match(AccountHint(SCOTIABANK, CREDIT_CARD, "0004"), listOf(merged)))
    }
}
