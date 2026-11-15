package ca.emarshall.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.HomeRepairService
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.ui.theme.incomeColor
import ca.emarshall.finance.ui.theme.spendColor
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The app is for one person in BC; dates are formatted the Canadian-English way. */
val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.CANADA)
val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.CANADA)
val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.CANADA)
val SHORT_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.CANADA)

fun LocalDate.pretty(): String = format(DAY_FORMAT)
fun YearMonth.pretty(): String = format(MONTH_FORMAT)

/** Money with a sign-aware colour: green for money in, red for money out. */
@Composable
fun MoneyText(
    cents: Long,
    currency: String = "CAD",
    modifier: Modifier = Modifier,
    colored: Boolean = true,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    bold: Boolean = false,
) {
    val color = when {
        !colored -> Color.Unspecified
        cents > 0 -> incomeColor()
        cents < 0 -> spendColor()
        else -> Color.Unspecified
    }
    Text(
        Money.format(cents, currency, showCurrency = true),
        modifier = modifier,
        color = color,
        style = style,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        maxLines = 1,
    )
}

/** Pushed-screen bar: back chevron, centred title (see design/Components.kt). */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    ca.emarshall.finance.ui.design.NavBar(title, onBack) { actions() }
}

/** A titled white card on the grouped background. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ca.emarshall.finance.ui.design.FinCard(modifier) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(ca.emarshall.finance.ui.theme.Fin.palette.card).padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ca.emarshall.finance.ui.theme.Fin.palette.secondaryLabel)
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) = ca.emarshall.finance.ui.design.EmptyMessage(text, modifier = modifier)

@Composable
fun ColorDot(color: Long, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).clip(CircleShape).background(Color(color.toInt())))
}

/** A labelled button that opens a menu: avoids the churn in Material's exposed-dropdown API. */
@Composable
fun <T> Picker(
    label: String,
    selected: T?,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(ca.emarshall.finance.ui.theme.Fin.palette.cardSecondary)
                .clickable(role = Role.DropdownList) { open = true }
                .semantics { contentDescription = "$label: ${selected?.let(display) ?: placeholder}" },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected?.let(display) ?: placeholder, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

/** A thin horizontal bar for category/allocation breakdowns. [fraction] is 0..1. */
@Composable
fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(ca.emarshall.finance.ui.theme.Fin.palette.cardSecondary)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = ca.emarshall.finance.ui.theme.Fin.palette.secondaryLabel)
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

fun categoryIcon(name: String?): ImageVector = when (name) {
    "shopping_basket" -> Icons.Default.ShoppingBasket
    "restaurant" -> Icons.Default.Restaurant
    "local_gas_station" -> Icons.Default.LocalGasStation
    "directions_car" -> Icons.Default.DirectionsCar
    "directions_bus" -> Icons.Default.DirectionsBus
    "shopping_bag" -> Icons.Default.ShoppingBag
    "home_repair_service" -> Icons.Default.HomeRepairService
    "receipt_long" -> Icons.Default.ReceiptLong
    "wifi" -> Icons.Default.Wifi
    "shield" -> Icons.Default.Shield
    "local_pharmacy" -> Icons.Default.LocalPharmacy
    "movie" -> Icons.Default.Movie
    "autorenew" -> Icons.Default.Autorenew
    "flight" -> Icons.Default.Flight
    "redeem" -> Icons.Default.Redeem
    "send" -> Icons.AutoMirrored.Filled.Send
    "percent" -> Icons.Default.Percent
    "local_atm" -> Icons.Default.LocalAtm
    "payments" -> Icons.Default.Payments
    "trending_up" -> Icons.AutoMirrored.Filled.TrendingUp
    "undo" -> Icons.AutoMirrored.Filled.Undo
    "savings" -> Icons.Default.Savings
    "credit_card" -> Icons.Default.CreditCard
    "swap_horiz" -> Icons.Default.SwapHoriz
    "account_balance" -> Icons.Default.AccountBalance
    "category" -> Icons.Default.Category
    "content_cut" -> Icons.Default.ContentCut
    "pets" -> Icons.Default.Pets
    "fitness_center" -> Icons.Default.FitnessCenter
    "local_bar" -> Icons.Default.LocalBar
    "school" -> Icons.Default.School
    "gavel" -> Icons.Default.Gavel
    "home" -> Icons.Default.Home
    else -> Icons.Default.Label
}

@Composable
fun VSpace(h: Int = 8) = Spacer(Modifier.height(h.dp))
