package ca.emarshall.finance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import ca.emarshall.finance.security.AppLock
import ca.emarshall.finance.ui.accounts.AccountEditScreen
import ca.emarshall.finance.ui.accounts.AccountsScreen
import ca.emarshall.finance.ui.bills.BillsScreen
import ca.emarshall.finance.ui.categories.CategoriesScreen
import ca.emarshall.finance.ui.importflow.ImportHistoryScreen
import ca.emarshall.finance.ui.importflow.ImportScreen
import ca.emarshall.finance.ui.investments.InvestmentsScreen
import ca.emarshall.finance.ui.investments.QuestradeConnectScreen
import ca.emarshall.finance.ui.overview.OverviewScreen
import ca.emarshall.finance.ui.reminders.RemindersScreen
import ca.emarshall.finance.ui.settings.SecurityScreen
import ca.emarshall.finance.ui.settings.SettingsScreen
import ca.emarshall.finance.ui.summary.SummaryScreen
import ca.emarshall.finance.ui.transactions.TransactionDetailScreen
import ca.emarshall.finance.ui.transactions.TransactionsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

// ---- Routes (type-safe Navigation Compose) ----
@Serializable object OverviewRoute
@Serializable data class TransactionsRoute(val accountId: Long = -1, val categoryId: Long = -1, val month: String = "")
@Serializable object InvestmentsRoute
@Serializable object SettingsRoute
@Serializable data class TransactionRoute(val id: Long)
@Serializable object AccountsRoute
@Serializable data class AccountEditRoute(val id: Long = -1)
@Serializable data class ImportRoute(val accountId: Long = -1, val uri: String = "")
@Serializable object ImportHistoryRoute
@Serializable object BillsRoute
@Serializable object QuestradeRoute
@Serializable object RemindersRoute
@Serializable object SummaryRoute
@Serializable object CategoriesRoute
@Serializable object SecurityRoute

sealed interface PendingNav {
    data class ImportFile(val uri: String) : PendingNav
    data class ImportForAccount(val accountId: Long) : PendingNav
    data object Questrade : PendingNav
}

private data class Tab(val label: String, val icon: ImageVector, val route: Any, val routeClass: KClass<*>)

private val TABS = listOf(
    Tab("Overview", Icons.Default.Dashboard, OverviewRoute, OverviewRoute::class),
    Tab("Transactions", Icons.AutoMirrored.Filled.ReceiptLong, TransactionsRoute(), TransactionsRoute::class),
    Tab("Investments", Icons.AutoMirrored.Filled.ShowChart, InvestmentsRoute, InvestmentsRoute::class),
    Tab("Settings", Icons.Default.Settings, SettingsRoute, SettingsRoute::class),
)

@Composable
fun AppRoot(
    appLock: AppLock,
    pendingNav: MutableStateFlow<PendingNav?>,
    onUnlockRequested: () -> Unit,
    deviceSecure: Boolean,
) {
    val locked by appLock.locked.collectAsStateWithLifecycle()
    if (locked) {
        LockScreen(deviceSecure, onUnlockRequested, onContinueInsecure = { appLock.unlock() })
        return
    }

    val nav = rememberNavController()
    val pending by pendingNav.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        when (val p = pending) {
            is PendingNav.ImportFile -> nav.navigate(ImportRoute(uri = p.uri))
            is PendingNav.ImportForAccount -> nav.navigate(ImportRoute(accountId = p.accountId))
            PendingNav.Questrade -> nav.navigate(QuestradeRoute)
            null -> Unit
        }
        if (pending != null) pendingNav.value = null
    }

    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val showBar = TABS.any { t -> dest?.hasRoute(t.routeClass) == true }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = dest?.hasRoute(t.routeClass) == true,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = OverviewRoute, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable<OverviewRoute> { OverviewScreen(nav) }
            composable<TransactionsRoute> { TransactionsScreen(nav, it.toRoute<TransactionsRoute>()) }
            composable<InvestmentsRoute> { InvestmentsScreen(nav) }
            composable<SettingsRoute> { SettingsScreen(nav) }
            composable<TransactionRoute> { TransactionDetailScreen(nav) }
            composable<AccountsRoute> { AccountsScreen(nav) }
            composable<AccountEditRoute> { AccountEditScreen(nav) }
            composable<ImportRoute> { ImportScreen(nav) }
            composable<ImportHistoryRoute> { ImportHistoryScreen(nav) }
            composable<BillsRoute> { BillsScreen(nav) }
            composable<QuestradeRoute> { QuestradeConnectScreen(nav) }
            composable<RemindersRoute> { RemindersScreen(nav) }
            composable<SummaryRoute> { SummaryScreen(nav) }
            composable<CategoriesRoute> { CategoriesScreen(nav) }
            composable<SecurityRoute> { SecurityScreen(nav) }
        }
    }
}

@Composable
private fun LockScreen(deviceSecure: Boolean, onUnlock: () -> Unit, onContinueInsecure: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null)
        Text("Finance Tracker is locked", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (deviceSecure) {
            Button(onClick = onUnlock) { Text("Unlock") }
        } else {
            Text(
                "Your phone has no screen lock, so the app can't lock itself. Set a PIN, pattern or fingerprint in " +
                    "your phone's settings to protect your financial data.",
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onContinueInsecure) { Text("Continue without a lock") }
        }
    }
}
