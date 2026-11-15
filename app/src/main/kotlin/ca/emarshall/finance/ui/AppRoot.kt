package ca.emarshall.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Lightbulb
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import ca.emarshall.finance.ui.insights.ClaudeChatScreen
import ca.emarshall.finance.ui.insights.ClaudeSetupScreen
import ca.emarshall.finance.ui.insights.GoalEditScreen
import ca.emarshall.finance.ui.insights.InsightsScreen
import ca.emarshall.finance.ui.statements.StatementViewerScreen
import ca.emarshall.finance.ui.statements.StatementsScreen
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
/** [uris] is newline-separated: files opened or shared from another app, imported straight away. */
@Serializable data class ImportRoute(val accountId: Long = -1, val uris: String = "")
@Serializable object ImportHistoryRoute
@Serializable object BillsRoute
@Serializable object QuestradeRoute
@Serializable object RemindersRoute
@Serializable object SummaryRoute
@Serializable object CategoriesRoute
@Serializable object SecurityRoute
@Serializable object InsightsRoute
@Serializable object StatementsRoute
@Serializable data class StatementViewerRoute(val batchId: Long, val title: String)
@Serializable data class GoalEditRoute(val id: Long = -1)
@Serializable data class ClaudeChatRoute(val prompt: String = "")
@Serializable object ClaudeSetupRoute
@Serializable data class CategoryRoute(val categoryId: Long, val month: String)
@Serializable object KnownPaymentsRoute
@Serializable object SortOtherRoute
@Serializable object BackupRoute
/** [amountCents] and [day] prefill a new one (from a transaction). */
@Serializable data class KnownPaymentEditRoute(val id: Long = -1, val amountCents: Long = 0, val day: Int = 0)

sealed interface PendingNav {
    data class ImportFiles(val uris: List<String>) : PendingNav
    data class ImportForAccount(val accountId: Long) : PendingNav
    data object Questrade : PendingNav
    data object Insights : PendingNav
}

private data class Tab(val label: String, val icon: ImageVector, val route: Any, val routeClass: KClass<*>)

private val TABS = listOf(
    Tab("Home", Icons.Default.Dashboard, OverviewRoute, OverviewRoute::class),
    Tab("Activity", Icons.AutoMirrored.Filled.ReceiptLong, TransactionsRoute(), TransactionsRoute::class),
    Tab("Insights", Icons.Default.Lightbulb, InsightsRoute, InsightsRoute::class),
    Tab("Invest", Icons.AutoMirrored.Filled.ShowChart, InvestmentsRoute, InvestmentsRoute::class),
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

    // The app's screens stay in place underneath the lock screen, so unlocking returns to where you were
    // (an import half-way through, a transaction you were looking at) instead of starting over at Home.
    val nav = rememberNavController()
    val pending by pendingNav.collectAsStateWithLifecycle()
    LaunchedEffect(pending, locked) {
        if (locked) return@LaunchedEffect // a shared file waits until the app is unlocked
        when (val p = pending) {
            is PendingNav.ImportFiles -> nav.navigate(ImportRoute(uris = p.uris.joinToString("\n")))
            is PendingNav.ImportForAccount -> nav.navigate(ImportRoute(accountId = p.accountId))
            PendingNav.Questrade -> nav.navigate(QuestradeRoute)
            PendingNav.Insights -> nav.navigate(InsightsRoute) { launchSingleTop = true }
            null -> Unit
        }
        if (pending != null) pendingNav.value = null
    }

    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val showBar = TABS.any { t -> dest?.hasRoute(t.routeClass) == true }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Scaffold(
        // Hidden from screen readers while locked; the lock screen on top also swallows every touch.
        modifier = if (locked) Modifier.clearAndSetSemantics { } else Modifier,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = ca.emarshall.finance.ui.theme.Fin.palette.card, tonalElevation = 0.dp) {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = ca.emarshall.finance.ui.theme.Fin.palette.accent,
                                selectedTextColor = ca.emarshall.finance.ui.theme.Fin.palette.accent,
                                indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unselectedIconColor = ca.emarshall.finance.ui.theme.Fin.palette.tertiaryLabel,
                                unselectedTextColor = ca.emarshall.finance.ui.theme.Fin.palette.secondaryLabel,
                            ),
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
            composable<InsightsRoute> { InsightsScreen(nav) }
            composable<StatementsRoute> { StatementsScreen(nav) }
            composable<StatementViewerRoute> { StatementViewerScreen(nav, it.toRoute<StatementViewerRoute>()) }
            composable<GoalEditRoute> { GoalEditScreen(nav) }
            composable<ClaudeChatRoute> { ClaudeChatScreen(nav) }
            composable<ClaudeSetupRoute> { ClaudeSetupScreen(nav) }
            composable<CategoryRoute> { ca.emarshall.finance.ui.categories.CategoryDetailScreen(nav) }
            composable<BackupRoute> { ca.emarshall.finance.ui.settings.BackupScreen(nav) }
            composable<SortOtherRoute> { ca.emarshall.finance.ui.categories.SortOtherScreen(nav) }
            composable<KnownPaymentsRoute> { ca.emarshall.finance.ui.categories.KnownPaymentsScreen(nav) }
            composable<KnownPaymentEditRoute> { ca.emarshall.finance.ui.categories.KnownPaymentEditScreen(nav) }
        }
    }
    if (locked) {
        val activity = androidx.activity.compose.LocalActivity.current
        // Back leaves the app rather than reaching the screens underneath.
        androidx.activity.compose.BackHandler { activity?.moveTaskToBack(true) }
        LockScreen(deviceSecure, onUnlockRequested, onContinueInsecure = { appLock.unlock() })
    }
    }
}

@Composable
private fun LockScreen(deviceSecure: Boolean, onUnlock: () -> Unit, onContinueInsecure: () -> Unit) {
    val white = androidx.compose.ui.graphics.Color.White
    Column(
        Modifier.fillMaxSize().background(ca.emarshall.finance.ui.theme.Fin.palette.heroGradient)
            .pointerInput(Unit) { detectTapGestures { } } // nothing underneath can be touched
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = white, modifier = Modifier.size(44.dp))
        Text("Finance Tracker", style = MaterialTheme.typography.headlineMedium, color = white, textAlign = TextAlign.Center)
        if (deviceSecure) {
            Button(
                onClick = onUnlock,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = white, contentColor = ca.emarshall.finance.ui.theme.Fin.palette.accent),
                modifier = Modifier.height(50.dp).fillMaxWidth(0.6f),
            ) { Text("Unlock") }
        } else {
            Text(
                "Your phone has no screen lock, so the app can't lock itself. Set a PIN, pattern or fingerprint in " +
                    "your phone's settings to protect your financial data.",
                textAlign = TextAlign.Center, color = white.copy(alpha = 0.9f),
            )
            TextButton(onClick = onContinueInsecure) { Text("Continue without a lock", color = white) }
        }
    }
}
