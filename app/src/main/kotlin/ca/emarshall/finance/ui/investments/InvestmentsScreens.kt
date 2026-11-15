package ca.emarshall.finance.ui.investments

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import ca.emarshall.finance.ui.BackTopBar
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
    Column(Modifier.fillMaxSize()) {
        Text("Investments", Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineLarge)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val status = s?.questradeStatus ?: return@Column
            if (status != QuestradeStatus.CONNECTED) {
                SectionCard(if (status == QuestradeStatus.DISCONNECTED) "Questrade needs reconnecting" else "Connect Questrade") {
                    Text(s?.questradeMessage ?: "See your Questrade accounts, holdings, dividends and contributions here. Read-only: this app can never trade.")
                    Button(onClick = { nav.navigate(QuestradeRoute) }) { Text("Connect") }
                }
            }
            v?.let { InvestmentsContent(it, busy, vm::sync) }
        }
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (v.lastSyncMillis > 0) "Synced ${java.time.Instant.ofEpochMilli(v.lastSyncMillis).atZone(java.time.ZoneId.systemDefault()).format(SYNC_FORMAT)}" else "Not synced yet",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
        )
        if (busy) CircularProgressIndicator() else TextButton(onClick = onSync) { Text("Sync now") }
    }
    SectionCard("Accounts") {
        v.accounts.forEach { a ->
            Text(a.account.nickname, style = MaterialTheme.typography.titleSmall)
            LabeledRow("Equity") { MoneyText(a.equityCadCents, colored = false) }
            LabeledRow("Cash") { MoneyText(a.cashCadCents, colored = false) }
            if (a.account.typeEnum.isInvestment) LabeledRow("Deposits this year") { MoneyText(a.depositsThisYearCents, colored = false) }
        }
        Text("Contribution room isn't calculated — check it with the CRA.", style = MaterialTheme.typography.bodySmall)
    }
    if (v.holdings.isNotEmpty()) {
        SectionCard("Holdings and allocation") {
            val total = v.holdingsTotalCadCents.coerceAtLeast(1)
            v.holdings.forEach { h ->
                val pct = h.marketValueCadCents * 1000 / total // tenths of a percent, integer maths
                LabeledRow("${h.symbol} · ${h.quantity.toPlainString()}") { Text("${pct / 10}.${pct % 10}%") }
                ShareBar(pct / 1000f, MaterialTheme.colorScheme.tertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoneyText(h.marketValueCents, h.currency, colored = false, style = MaterialTheme.typography.bodySmall)
                    Text("P&L", style = MaterialTheme.typography.bodySmall)
                    MoneyText(h.openPnlCents, h.currency, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (v.holdings.any { it.currency != "CAD" }) Text("USD holdings are converted with the rate implied by Questrade's own balances.", style = MaterialTheme.typography.bodySmall)
        }
    }
    SectionCard("Dividends (CAD)") {
        LabeledRow("Last 12 months") { MoneyText(v.dividends12mCents) }
        LabeledRow("This year") { MoneyText(v.dividendsYtdCents) }
    }
    SectionCard("Recent activity") {
        if (v.recent.isEmpty()) Text("No activity in the last 12 months.")
        v.recent.take(15).forEach { a ->
            LabeledRow("${a.transactionDate.format(SHORT_DAY)} · ${a.type}${if (a.symbol.isNotBlank()) " ${a.symbol}" else ""}") {
                MoneyText(a.netAmountCents, a.currency, style = MaterialTheme.typography.bodyMedium)
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
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Questrade", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s?.questradeStatus == QuestradeStatus.CONNECTED) {
                Text("Connected (read-only).", style = MaterialTheme.typography.titleMedium)
                Text("The app syncs once a day in the background, which also keeps the connection alive.")
                Button(onClick = vm::sync, enabled = !busy) { Text("Sync now") }
                OutlinedButton(onClick = vm::disconnect, enabled = !busy) { Text("Disconnect and delete token") }
            } else {
                Text("Connect in four steps", style = MaterialTheme.typography.titleLarge)
                SectionCard("1. Turn on API access") {
                    Text("Sign in to Questrade on the web. Under your profile, open App Hub / API Centre and turn on API access if it's off.")
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://login.questrade.com/APIAccess/UserApps.aspx")))
                    }) { Text("Open the API Centre in your browser") }
                }
                SectionCard("2. Register a personal app") {
                    Text("Click Register a personal app. Tick ONLY the read permissions: Retrieve balances, positions, orders and executions (account data) and Retrieve delayed and real-time market data.")
                    Text("Leave trading permissions unticked. This app never trades.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("3. Generate a token") {
                    Text("Under your new app, click New manual authorization, then Generate new token. Copy it.")
                    Text("A token works once and expires 7 days after it's created, so paste it here straight away.", style = MaterialTheme.typography.bodySmall)
                }
                SectionCard("4. Paste it here") {
                    OutlinedTextField(
                        token, { token = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Token") }, visualTransformation = PasswordVisualTransformation(),
                    )
                    Text("The token is stored encrypted on this phone only. It never leaves the phone except to Questrade.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.connect(token); token = "" }, enabled = token.length >= 10 && !busy) { Text("Connect") }
                }
            }
            if (busy) CircularProgressIndicator()
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
