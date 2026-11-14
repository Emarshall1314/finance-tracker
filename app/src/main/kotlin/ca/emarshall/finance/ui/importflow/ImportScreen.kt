package ca.emarshall.finance.ui.importflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import ca.emarshall.finance.core.csv.CsvProfile
import ca.emarshall.finance.core.csv.CsvStatementParser
import ca.emarshall.finance.core.csv.SignConvention
import ca.emarshall.finance.data.DetectedFile
import ca.emarshall.finance.data.ImportRepository
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.institutionEnum
import ca.emarshall.finance.data.typeEnum
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.LabeledRow
import ca.emarshall.finance.ui.MoneyText
import ca.emarshall.finance.ui.Picker
import ca.emarshall.finance.ui.SectionCard
import ca.emarshall.finance.ui.TransactionsRoute
import ca.emarshall.finance.ui.pretty

private val MIME_TYPES = arrayOf(
    "text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/pdf",
    "application/x-ofx", "application/vnd.intu.qfx", "application/vnd.intu.qbo", "application/x-qfx", "application/octet-stream",
)

@Composable
fun ImportScreen(nav: NavController, vm: ImportViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateWithLifecycle()
    val accounts by vm.allAccounts.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.onFilePicked(uri) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Import statement", onBack = { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = step) {
                ImportStep.PickFile -> PickFile { picker.launch(MIME_TYPES) }
                ImportStep.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(); Text("Reading…")
                }
                is ImportStep.ChooseAccount -> ChooseAccount(s.detected, accounts, vm.preselectedAccountId, vm::chooseAccount, vm::createAccountFromHint)
                is ImportStep.Mapping -> MappingEditor(s, vm)
                is ImportStep.Preview -> PreviewStep(s, vm)
                is ImportStep.Done -> DoneStep(s, vm, onView = { nav.navigate(TransactionsRoute(accountId = s.result.accountId)) })
                is ImportStep.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.restart() }) { Text("Try another file") }
                }
            }
        }
    }
}

@Composable
private fun PickFile(onPick: () -> Unit) {
    Text("Import a statement or export from Scotiabank or Canadian Tire Bank.", style = MaterialTheme.typography.bodyLarge)
    SectionCard("What works") {
        Text("• PDF e-statements (Scotiabank chequing, Scotia Visa, Triangle Mastercard)")
        Text("• CSV downloads, with a column-mapping screen for new layouts")
        Text("• OFX, QFX (Quicken) and QBO (QuickBooks) downloads")
        Text("You can also share a file to this app from Files, Drive or Gmail.", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choose a file") }
}

@Composable
private fun ChooseAccount(
    d: DetectedFile,
    accounts: List<AccountEntity>,
    preselected: Long?,
    onChoose: (DetectedFile, AccountEntity) -> Unit,
    onCreate: (DetectedFile) -> Unit,
) {
    val candidates = accounts.filter { !it.typeEnum.isInvestment || it.questradeNumber == null }
    var selected by remember(d) { mutableStateOf(candidates.firstOrNull { it.id == (preselected ?: d.suggestedAccountId) }) }
    SectionCard("Detected") {
        LabeledRow("File") { Text(d.file.fileName, maxLines = 1) }
        LabeledRow("Format") { Text(d.file.format.name) }
        LabeledRow("Bank") { Text(ImportRepository.institutionLabel(d.hint.institution)) }
        d.hint.accountType?.let { LabeledRow("Account type") { Text(it.displayName) } }
        d.hint.last4?.let { LabeledRow("Ending in") { Text("••$it") } }
        d.statement?.let { st ->
            LabeledRow("Rows") { Text(st.transactions.size.toString()) }
            if (st.dateFrom != null && st.dateTo != null) LabeledRow("Period") { Text("${st.dateFrom!!.pretty()} – ${st.dateTo!!.pretty()}") }
        }
    }
    d.previousImport?.let {
        Text(
            "You imported this exact file before (${it.fileName}). Rows already in the app will be skipped.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    Picker("Which account is this?", selected, candidates, { "${it.nickname} (${it.institutionEnum.displayName}${it.last4?.let { l -> " ••$l" } ?: ""})" }, { selected = it }, Modifier.fillMaxWidth(), "Choose an account")
    Button(onClick = { selected?.let { onChoose(d, it) } }, enabled = selected != null, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    if (d.hint.institution != null) {
        OutlinedButton(onClick = { onCreate(d) }, modifier = Modifier.fillMaxWidth()) {
            Text("Create a new ${ImportRepository.institutionLabel(d.hint.institution)} account for it")
        }
    }
}

@Composable
private fun MappingEditor(m: ImportStep.Mapping, vm: ImportViewModel) {
    val start = m.profile
    val sampleRows = remember(m) { vm.previewRows(m.detected, start?.delimiter ?: ',') }
    val width = sampleRows.maxOfOrNull { it.size } ?: 0
    val columns = (0 until width).toList()
    fun colName(i: Int) = m.header?.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { "$it (col ${i + 1})" } ?: "Column ${i + 1}"

    var hasHeader by remember(m) { mutableStateOf(start?.hasHeader ?: (m.header != null)) }
    var dateCol by remember(m) { mutableIntStateOf(start?.dateColumn ?: 0) }
    var descCols by remember(m) { mutableStateOf(start?.descriptionColumns?.toSet() ?: setOf(1.coerceAtMost(width - 1))) }
    var sign by remember(m) { mutableStateOf(start?.signConvention ?: if (m.account.typeEnum.isLiability) SignConvention.AMOUNT_INVERTED else SignConvention.AMOUNT_AS_IS) }
    var amountCol by remember(m) { mutableIntStateOf(start?.amountColumn ?: (width - 1).coerceAtLeast(0)) }
    var debitCol by remember(m) { mutableIntStateOf(start?.debitColumn ?: 0) }
    var creditCol by remember(m) { mutableIntStateOf(start?.creditColumn ?: 0) }
    var typeCol by remember(m) { mutableIntStateOf(start?.typeColumn ?: 0) }
    var balanceCol by remember(m) { mutableStateOf<Int?>(start?.balanceColumn) }
    var dateFormat by remember(m) { mutableStateOf(start?.dateFormat ?: m.dateOptions.first()) }

    val profile = runCatching {
        CsvProfile(
            delimiter = start?.delimiter ?: ',', hasHeader = hasHeader, skipRows = start?.skipRows ?: 0, dateFormat = dateFormat,
            dateColumn = dateCol, descriptionColumns = descCols.sorted().ifEmpty { listOf(dateCol) }, signConvention = sign,
            amountColumn = amountCol.takeIf { sign == SignConvention.AMOUNT_AS_IS || sign == SignConvention.AMOUNT_INVERTED || sign == SignConvention.TYPE_COLUMN },
            debitColumn = debitCol.takeIf { sign == SignConvention.DEBIT_CREDIT }, creditColumn = creditCol.takeIf { sign == SignConvention.DEBIT_CREDIT },
            typeColumn = typeCol.takeIf { sign == SignConvention.TYPE_COLUMN }, balanceColumn = balanceCol,
            balanceIsOwed = m.account.typeEnum.isLiability, currency = m.account.currency,
        )
    }.getOrNull()

    Text("Map the columns", style = MaterialTheme.typography.titleLarge)
    m.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    SectionCard("First rows of the file") {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            sampleRows.take(5).forEach { r -> Text(r.joinToString("  |  "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
    LabeledRow("First row is a header") { Switch(hasHeader, { hasHeader = it }) }
    Picker("Date column", dateCol, columns, ::colName, { dateCol = it }, Modifier.fillMaxWidth())
    Picker("Date format", dateFormat, m.dateOptions, { it }, { dateFormat = it }, Modifier.fillMaxWidth())
    Text("Description column(s)", style = MaterialTheme.typography.labelLarge)
    columns.forEach { c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(c in descCols, { on -> descCols = if (on) descCols + c else descCols - c })
            Text(colName(c))
        }
    }
    Text("How amounts are shown", style = MaterialTheme.typography.labelLarge)
    SignConvention.entries.forEach { sc ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(sign == sc, { sign = sc })
            Text(ImportViewModel.SIGN_LABELS.getValue(sc))
        }
    }
    when (sign) {
        SignConvention.DEBIT_CREDIT -> {
            Picker("Debit (money out) column", debitCol, columns, ::colName, { debitCol = it }, Modifier.fillMaxWidth())
            Picker("Credit (money in) column", creditCol, columns, ::colName, { creditCol = it }, Modifier.fillMaxWidth())
        }
        SignConvention.TYPE_COLUMN -> {
            Picker("Amount column", amountCol, columns, ::colName, { amountCol = it }, Modifier.fillMaxWidth())
            Picker("Debit/Credit column", typeCol, columns, ::colName, { typeCol = it }, Modifier.fillMaxWidth())
        }
        else -> Picker("Amount column", amountCol, columns, ::colName, { amountCol = it }, Modifier.fillMaxWidth())
    }
    Picker("Running balance column (optional)", balanceCol, listOf<Int?>(null) + columns, { it?.let(::colName) ?: "None" }, { balanceCol = it }, Modifier.fillMaxWidth(), "None")

    val sample = profile?.let { p -> runCatching { CsvStatementParser.parse(m.detected.csvText.orEmpty(), p) }.getOrNull() }
    SectionCard("How it reads") {
        if (sample == null) Text("Pick the columns above.")
        sample?.transactions?.take(4)?.forEach { t -> LabeledRow("${t.date} ${t.description.take(28)}") { MoneyText(t.amountCents) } }
        if (sample != null && sample.warnings.isNotEmpty()) Text("${sample.warnings.size} row(s) can't be read with this mapping.", color = MaterialTheme.colorScheme.error)
    }
    Button(onClick = { profile?.let { vm.applyMapping(m, it) } }, enabled = profile != null && (sample?.transactions?.isNotEmpty() == true), modifier = Modifier.fillMaxWidth()) {
        Text("Use this mapping")
    }
}

@Composable
private fun PreviewStep(s: ImportStep.Preview, vm: ImportViewModel) {
    val p = s.preview
    val st = p.statement
    SectionCard("Preview — ${p.account.nickname}") {
        if (st.dateFrom != null && st.dateTo != null) LabeledRow("Dates") { Text("${st.dateFrom!!.pretty()} – ${st.dateTo!!.pretty()}") }
        LabeledRow("Rows in file") { Text(st.transactions.size.toString()) }
        LabeledRow("New") { Text(p.dedup.newRows.size.toString()) }
        LabeledRow("Already imported (skipped)") { Text(p.dedup.duplicateCount.toString()) }
        LabeledRow("Money in (new rows)") { MoneyText(p.moneyInCents) }
        LabeledRow("Money out (new rows)") { MoneyText(-p.moneyOutCents) }
        if (st.balances.isNotEmpty()) LabeledRow("Balances found") { Text(st.balances.size.toString()) }
    }
    if (st.warnings.isNotEmpty()) {
        SectionCard("Check these") { st.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) } }
    }
    SectionCard("First new rows") {
        p.dedup.newRows.take(8).forEach { k -> LabeledRow("${k.tx.date} · ${k.tx.description.take(30)}") { MoneyText(k.tx.amountCents) } }
        if (p.dedup.newRows.isEmpty()) Text("Nothing new in this file.")
    }
    Button(onClick = { vm.commit(p) }, enabled = p.dedup.newRows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text("Import ${p.dedup.newRows.size} transactions")
    }
    if (p.profile != null) TextButton(onClick = { vm.editMapping(p) }) { Text("Change column mapping") }
}

@Composable
private fun DoneStep(s: ImportStep.Done, vm: ImportViewModel, onView: () -> Unit) {
    if (s.undone) {
        Text("Import undone. Nothing from that file remains.", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { vm.restart() }) { Text("Import another file") }
        return
    }
    Text("Imported into ${s.accountName}", style = MaterialTheme.typography.titleLarge)
    SectionCard(null) {
        LabeledRow("New transactions") { Text(s.result.newCount.toString()) }
        LabeledRow("Duplicates skipped") { Text(s.result.duplicateCount.toString()) }
        LabeledRow("Transfers matched") { Text(s.result.transfersMatched.toString()) }
    }
    Text("Transactions were categorised automatically. Fix any in the Transactions tab — the app can learn from it.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.restart() }) { Text("Import another") }
        OutlinedButton(onClick = { vm.undo(s) }) { Text("Undo this import") }
    }
    TextButton(onClick = onView) { Text("See these transactions") }
}
