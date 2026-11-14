@file:UseSerializers(BigDecimalSerializer::class)

package ca.emarshall.finance.net.questrade

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/**
 * Reads JSON numbers as BigDecimal from their literal text, so money never passes through Double.
 * `12345.67` becomes exactly 12345.67. Null becomes zero.
 */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        if (element is JsonNull) return BigDecimal.ZERO
        val text = element.jsonPrimitive.content
        return if (text.isBlank()) BigDecimal.ZERO else BigDecimal(text)
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
    }
}

val QuestradeJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("api_server") val apiServer: String,
)

@Serializable
data class AccountsResponse(val accounts: List<QtAccount> = emptyList(), val userId: Long? = null)

@Serializable
data class QtAccount(
    val type: String,
    val number: String,
    val status: String? = null,
    val isPrimary: Boolean = false,
    val isBilling: Boolean = false,
    val clientAccountType: String? = null,
)

@Serializable
data class BalancesResponse(
    val perCurrencyBalances: List<QtBalance> = emptyList(),
    val combinedBalances: List<QtBalance> = emptyList(),
)

@Serializable
data class QtBalance(
    val currency: String,
    val cash: BigDecimal = BigDecimal.ZERO,
    val marketValue: BigDecimal = BigDecimal.ZERO,
    val totalEquity: BigDecimal = BigDecimal.ZERO,
    val buyingPower: BigDecimal = BigDecimal.ZERO,
    val isRealTime: Boolean = false,
)

@Serializable
data class PositionsResponse(val positions: List<QtPosition> = emptyList())

@Serializable
data class QtPosition(
    val symbol: String,
    val symbolId: Long = 0,
    val openQuantity: BigDecimal = BigDecimal.ZERO,
    val currentMarketValue: BigDecimal = BigDecimal.ZERO,
    val currentPrice: BigDecimal = BigDecimal.ZERO,
    val averageEntryPrice: BigDecimal = BigDecimal.ZERO,
    val openPnl: BigDecimal = BigDecimal.ZERO,
    val totalCost: BigDecimal = BigDecimal.ZERO,
)

@Serializable
data class ActivitiesResponse(val activities: List<QtActivity> = emptyList())

@Serializable
data class QtActivity(
    val tradeDate: String? = null,
    val transactionDate: String,
    val settlementDate: String? = null,
    val action: String = "",
    val symbol: String = "",
    val symbolId: Long = 0,
    val description: String = "",
    val currency: String = "CAD",
    val quantity: BigDecimal = BigDecimal.ZERO,
    val price: BigDecimal = BigDecimal.ZERO,
    val grossAmount: BigDecimal = BigDecimal.ZERO,
    val commission: BigDecimal = BigDecimal.ZERO,
    val netAmount: BigDecimal = BigDecimal.ZERO,
    val type: String = "",
)
