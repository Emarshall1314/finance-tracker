package ca.emarshall.finance.ui.investments

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.core.money.Money
import ca.emarshall.finance.data.AccountRepository
import ca.emarshall.finance.data.InvestmentsCalculator
import ca.emarshall.finance.data.InvestmentsView
import ca.emarshall.finance.data.QuestradeRepository
import ca.emarshall.finance.data.QuestradeStatus
import ca.emarshall.finance.data.SettingsRepository
import ca.emarshall.finance.data.typeEnum
import ca.emarshall.finance.net.questrade.QuestradeAuthException
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.FinTextField
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.GroupRow
import ca.emarshall.finance.ui.design.HeroCard
import ca.emarshall.finance.ui.design.LargeTitleScreen
import ca.emarshall.finance.ui.design.Notice
import ca.emarshall.finance.ui.design.PrimaryButton
import ca.emarshall.finance.ui.design.SecondaryButton
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.theme.Fin
import ca.emarshall.finance.ui.LabeledRow
import ca.emarshall.finance.ui.MoneyText
import ca.emarshall.finance.ui.QuestradeRoute
import ca.emarshall.finance.ui.SHORT_DAY
import ca.emarshall.finance.ui.SectionCard
import ca.emarshall.finance.ui.ShareBar
import ca.emarshall.finance.ui.StatTile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

private val SYNC_FORMAT = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)

@HiltViewModel
class InvestmentsViewModel @Inject constructor(
    private val questrade: QuestradeRepository,
    accounts: AccountRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val view = combine(accounts.accounts, questrade.latestBalances, questrade.latestPositions, questrade.activities, settings.settings) { a, b, p, act, s ->
        InvestmentsCalculator.build(a, b, p, act, LocalDate.now(), s.questradeLastSyncMillis)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun connect(token: String) = work("Connected. Your accounts are synced.") { questrade.connect(token) }
    fun sync() = work("Synced.") { questrade.sync() }
    fun disconnect() = work("Disconnected. Your token was deleted from this phone.") { questrade.disconnect() }

    private fun work(done: String, block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        message.value = null
        message.value = try {
            block(); done
        } catch (e: QuestradeAuthException) {
            "Questrade didn't accept that token. Tokens work once and expire after 7 days — generate a new one and paste it here."
        } catch (e: Exception) {
            "Couldn't reach Questrade (${e.message ?: e.javaClass.simpleName}). Check your connection and try again."
        } finally {
            busy.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestmentsScreen(nav: NavController, vm: InvestmentsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val v by vm.view.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LargeTitleScreen("Investments") {
        val status = s?.questradeStatus ?: return@LargeTitleScreen
        if (status != QuestradeStatus.CONNECTED) {
            item {
                HeroCard(Fin.palette.investGradient, onClick = { nav.navigate(QuestradeRoute) }) {
                    val white = Color.White
                    Text(if (status == QuestradeStatus.DISCONNECTED) "Reconnect Questrade" else "Connect Questrade", color = white, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        s?.questradeMessage ?: "See your accounts, holdings, dividends and contributions. Read-only: this app can never trade.",
                        color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Set up ›", color = white, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        v?.let { view -> item { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { InvestmentsContent(view, busy, vm::sync) } } }
    }
}

@Composable
private fun InvestmentsContent(v: InvestmentsView, busy: Boolean, onSync: () -> Unit) {
    ca.emarshall.finance.ui.design.HeroCard(ca.emarshall.finance.ui.theme.Fin.palette.investGradient) {
        val white = androidx.compose.ui.graphics.Color.White
        Text("Total equity", color = white.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        Text(Money.formatWhole(v.totalEquityCadCents), color = white, style = MaterialTheme.typography.headlineLarge)
        Text("Dividends this year ${Money.formatWhole(v.dividendsYtdCents)} · CAD, all accounts", color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
    }
    val p = ca.emarshall.finance.ui.theme.Fin.palette
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (v.lastSyncMillis > 0) "Synced ${java.time.Instant.ofEpochMilli(v.lastSyncMillis).atZone(java.time.ZoneId.systemDefault()).format(SYNC_FORMAT)}" else "Not synced yet",
            Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel,
        )
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else TextButton(onClick = onSync) { Text("Sync now") }
    }

    if (v.holdings.isNotEmpty()) {
        ca.emarshall.finance.ui.design.SectionHeader("Allocation")
        val total = v.holdingsTotalCadCents.coerceAtLeast(1)
        var sel by androidx.compose.runtime.remember(v.holdings) { androidx.compose.runtime.mutableStateOf<Int?>(null) }
        ca.emarshall.finance.ui.design.FinCard {
            // Same colour per holding as the list below (index in v.holdings); tap a segment for its numbers.
            ca.emarshall.finance.ui.design.SegmentedBar(
                v.holdings.mapIndexed { i, h -> ca.emarshall.finance.ui.design.ChartSlice(h.symbol, h.marketValueCadCents, p.chart[i % p.chart.size]) },
                sel, { sel = it },
            )
            val h = sel?.let { v.holdings.getOrNull(it) }
            Text(
                if (h == null) "Tap the bar to see a holding" else {
                    val pct = h.marketValueCadCents * 1000 / total
                    "${h.symbol} · ${Money.formatWhole(h.marketValueCadCents)} CAD · ${pct / 10}.${pct % 10}% · P&L ${Money.format(h.openPnlCents, h.currency, true)}"
                },
                style = MaterialTheme.typography.bodySmall, color = if (h == null) p.tertiaryLabel else p.label,
            )
        }
        ca.emarshall.finance.ui.design.Group {
            v.holdings.forEachIndexed { i, h ->
                val pct = h.marketValueCadCents * 1000 / total // tenths of a percent, integer maths
                ca.emarshall.finance.ui.design.GroupRow(
                    h.symbol,
                    subtitle = "${h.quantity.toPlainString()} units · P&L ${Money.format(h.openPnlCents, h.currency, true)}",
                    value = "${pct / 10}.${pct % 10}%",
                    divider = i < v.holdings.size - 1,
                    trailing = { androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(p.chart[i % p.chart.size])) },
                )
            }
        }
        if (v.holdings.any { it.currency != "CAD" }) {
            Text("USD holdings are converted with the rate implied by Questrade's own balances.", Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
        }
    }

    ca.emarshall.finance.ui.design.SectionHeader("Accounts")
    ca.emarshall.finance.ui.design.Group {
        v.accounts.forEachIndexed { i, a ->
            ca.emarshall.finance.ui.design.GroupRow(
                a.account.nickname,
                subtitle = "Cash ${Money.formatWhole(a.cashCadCents)}" +
                    if (a.depositsThisYearCents != 0L) " · deposited ${Money.formatWhole(a.depositsThisYearCents)} this year" else "",
                value = Money.formatWhole(a.equityCadCents), valueColor = p.label, divider = i < v.accounts.size - 1,
            )
        }
    }
    Text("Contribution room isn't calculated; check it in CRA My Account.", Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)

    ca.emarshall.finance.ui.design.SectionHeader("Dividends")
    ca.emarshall.finance.ui.design.Group {
        ca.emarshall.finance.ui.design.GroupRow("Last 12 months", value = Money.format(v.dividends12mCents))
        ca.emarshall.finance.ui.design.GroupRow("This year", value = Money.format(v.dividendsYtdCents), divider = false)
    }

    if (v.recent.isNotEmpty()) {
        ca.emarshall.finance.ui.design.SectionHeader("Recent activity")
        ca.emarshall.finance.ui.design.Group {
            val shown = v.recent.take(12)
            shown.forEachIndexed { i, a ->
                ca.emarshall.finance.ui.design.GroupRow(
                    a.type + if (a.symbol.isNotBlank()) " · ${a.symbol}" else "",
                    subtitle = a.transactionDate.format(SHORT_DAY),
                    value = (if (a.netAmountCents > 0) "+" else "") + Money.format(a.netAmountCents, a.currency, true),
                    valueColor = if (a.netAmountCents > 0) p.positive else p.label,
                    divider = i < shown.size - 1,
                )
            }
        }
    }
}

@Composable
fun QuestradeConnectScreen(nav: NavController, vm: InvestmentsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }
    val context = LocalContext.current
    val p = Fin.palette
    DetailScreen("Questrade", onBack = { nav.popBackStack() }) {
        message?.let { m -> item { Notice(m) } }
        if (s?.questradeStatus == QuestradeStatus.CONNECTED) {
            item {
                Group {
                    GroupRow("Connected", subtitle = "Read-only · syncs once a day, which keeps the connection alive",
                        icon = Icons.AutoMirrored.Filled.ShowChart, iconColor = Color(0xFF34C77B), divider = false)
                }
            }
            item { PrimaryButton("Sync now", vm::sync, loading = busy) }
            item { SecondaryButton("Disconnect and delete token", vm::disconnect, enabled = !busy, destructive = true) }
        } else {
            item { SectionHeader("Connect in four steps") }
            item {
                Group {
                    Step(1, "Turn on API access", "Sign in to Questrade on the web. Under your profile, open App Hub / API Centre and turn on API access if it's off.")
                    GroupRow("Open the API Centre", icon = Icons.AutoMirrored.Filled.OpenInNew, iconColor = p.accent, showChevron = true, onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://login.questrade.com/APIAccess/UserApps.aspx")))
                    })
                    Step(2, "Register a personal app", "Tick ONLY the read permissions: account data (balances, positions, orders, executions) and market data. Leave trading unticked; this app never trades.")
                    Step(3, "Generate a token", "Under your new app: New manual authorization, then Generate new token. Copy it. It works once and expires after 7 days.", divider = false)
                }
            }
            item { SectionHeader("Paste it here") }
            item {
                FinCard {
                    FinTextField(token, { token = it.trim() }, "Token", visualTransformation = PasswordVisualTransformation())
                    PrimaryButton("Connect", { vm.connect(token); token = "" }, enabled = token.length >= 10, loading = busy)
                }
            }
            item { Footnote("The token is stored encrypted on this phone and only ever sent to Questrade.") }
        }
    }
}

/** A numbered instruction inside a [Group]. */
@Composable
private fun Step(n: Int, title: String, body: String, divider: Boolean = true) {
    val p = Fin.palette
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(p.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Text("$n", color = p.accent, style = MaterialTheme.typography.labelLarge)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = p.secondaryLabel)
            }
        }
        if (divider) androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 56.dp), color = p.separator, thickness = 0.5.dp)
    }
}
