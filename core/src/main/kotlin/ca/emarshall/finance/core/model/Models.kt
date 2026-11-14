package ca.emarshall.finance.core.model

import java.time.LocalDate

enum class Institution(val displayName: String) {
    SCOTIABANK("Scotiabank"),
    CANADIAN_TIRE("Canadian Tire Bank"),
    QUESTRADE("Questrade"),
    OTHER("Other"),
}

enum class AccountType(
    val displayName: String,
    val isLiability: Boolean = false,
    val isInvestment: Boolean = false,
) {
    CHEQUING("Chequing"),
    SAVINGS("Savings"),
    CREDIT_CARD("Credit card", isLiability = true),
    LINE_OF_CREDIT("Line of credit", isLiability = true),
    TFSA("TFSA", isInvestment = true),
    RRSP("RRSP", isInvestment = true),
    FHSA("FHSA", isInvestment = true),
    MARGIN("Margin", isInvestment = true),
    CASH("Cash (investment)", isInvestment = true),
    OTHER("Other"),
}

enum class TxSource { CSV, OFX, PDF, QUESTRADE, MANUAL }

enum class CategoryKind { EXPENSE, INCOME, TRANSFER }

enum class FileFormat { CSV, OFX, PDF, UNKNOWN }

/** One transaction as read from a file, before it is matched against the database. */
data class ParsedTransaction(
    val date: LocalDate,
    /** Cents. Negative means money left the account. */
    val amountCents: Long,
    val description: String,
    val fitId: String? = null,
    /** Running balance after this row, if the file has one (cents; positive = asset, negative = owed). */
    val balanceCents: Long? = null,
    val currency: String? = null,
)

data class ParsedBalance(val date: LocalDate, val balanceCents: Long)

/** What a file says about its own account. Used to suggest the account; never exported. */
data class AccountHint(
    val institution: Institution? = null,
    val accountType: AccountType? = null,
    val last4: String? = null,
    val currency: String? = null,
)

data class ParsedStatement(
    val format: FileFormat,
    val transactions: List<ParsedTransaction>,
    val balances: List<ParsedBalance> = emptyList(),
    val hint: AccountHint = AccountHint(),
    /** Statement period when the file states one (OFX DTSTART/DTEND, PDF statement period). */
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val warnings: List<String> = emptyList(),
) {
    val dateFrom: LocalDate? get() = periodStart ?: transactions.minOfOrNull { it.date }
    val dateTo: LocalDate? get() = periodEnd ?: transactions.maxOfOrNull { it.date }
}
