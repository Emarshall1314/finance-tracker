package ca.emarshall.finance.core.analytics

import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.Institution
import java.time.LocalDate

/** Where a dollar sits, for the liquid net worth breakdown. */
enum class LiquidityBucket(val label: String) {
    /** Chequing, savings: spendable today. */
    CASH("Cash"),

    /** TFSA, margin, cash investment accounts: can be sold and withdrawn within days, no tax on the way out. */
    INVESTMENTS("Investments"),

    /** Credit cards and lines of credit: owed soon, subtracted from liquid net worth. */
    DEBT("Debt"),

    /** RRSP, FHSA: withdrawals are taxed or restricted, so they're net worth but not liquid. */
    LONG_TERM("Long-term"),
}

data class AccountPosition(
    val accountId: Long,
    val nickname: String,
    val institution: Institution,
    val type: AccountType,
    /** Positive = asset, negative = owed. CAD. */
    val balanceCents: Long,
    val asOf: LocalDate,
)

data class Segment(val position: AccountPosition, val bucket: LiquidityBucket)

data class NetWorthBreakdown(
    val segments: List<Segment>,
    val cashCents: Long,
    val investmentsCents: Long,
    val debtCents: Long,
    val longTermCents: Long,
) {
    /** Cash + liquid investments − debt. */
    val liquidCents get() = cashCents + investmentsCents + debtCents
    val totalCents get() = liquidCents + longTermCents
    fun bucket(b: LiquidityBucket) = segments.filter { it.bucket == b }.sortedByDescending { Math.abs(it.position.balanceCents) }
    fun byInstitution(): Map<Institution, Long> = segments.groupBy { it.position.institution }.mapValues { (_, l) -> l.sumOf { it.position.balanceCents } }
}

object LiquidNetWorth {
    fun bucketOf(type: AccountType): LiquidityBucket = when (type) {
        AccountType.CHEQUING, AccountType.SAVINGS, AccountType.OTHER -> LiquidityBucket.CASH
        AccountType.TFSA, AccountType.MARGIN, AccountType.CASH -> LiquidityBucket.INVESTMENTS
        AccountType.CREDIT_CARD, AccountType.LINE_OF_CREDIT -> LiquidityBucket.DEBT
        AccountType.RRSP, AccountType.FHSA -> LiquidityBucket.LONG_TERM
    }

    fun breakdown(positions: List<AccountPosition>): NetWorthBreakdown {
        val segs = positions.map { Segment(it, bucketOf(it.type)) }
        fun sum(b: LiquidityBucket) = segs.filter { it.bucket == b }.sumOf { it.position.balanceCents }
        return NetWorthBreakdown(segs, sum(LiquidityBucket.CASH), sum(LiquidityBucket.INVESTMENTS), sum(LiquidityBucket.DEBT), sum(LiquidityBucket.LONG_TERM))
    }
}
