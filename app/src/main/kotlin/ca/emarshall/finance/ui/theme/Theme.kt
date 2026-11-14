package ca.emarshall.finance.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(primary = Color(0xFF1B6C4E), secondary = Color(0xFF4D6357), tertiary = Color(0xFF3D6373))
private val Dark = darkColorScheme(primary = Color(0xFF8BD6B2), secondary = Color(0xFFB4CCBD), tertiary = Color(0xFFA5CCDF))

/** Money-in green and money-out red that read well in light and dark mode. */
object MoneyColors {
    val incomeLight = Color(0xFF1B7F3B)
    val incomeDark = Color(0xFF7FD99A)
    val spendLight = Color(0xFFB3261E)
    val spendDark = Color(0xFFF2B8B5)
}

@Composable
fun FinanceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun incomeColor(): Color = if (isSystemInDarkTheme()) MoneyColors.incomeDark else MoneyColors.incomeLight

@Composable
fun spendColor(): Color = if (isSystemInDarkTheme()) MoneyColors.spendDark else MoneyColors.spendLight
