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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import ca.emarshall.finance.ui.design.FinCard
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.GroupRow
import ca.emarshall.finance.ui.design.HeroCard
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.theme.Fin
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
import ca.emarshall.finance.data.QuickImportOutcome
import ca.emarshall.finance.data.db.AccountEntity
import ca.emarshall.finance.data.db.allLast4
import ca.emarshall.finance.core.importing.AccountGuesser
import ca.emarshall.finance.core.importing.AccountMatcher
import ca.emarshall.finance.core.importing.AccountRef
import ca.emarshall.finance.core.model.AccountType
import ca.emarshall.finance.core.model.Institution
import androidx.compose.material3.OutlinedTextField
import ca.emarshall.finance.ui.accounts.NewAccountsCard
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
    // Many files at once: each is imported straight away when it's clear which account it belongs to.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        when {
            uris.isEmpty() -> Unit
            uris.size == 1 && vm.preselectedAccountId != null -> vm.onFilePicked(uris.single())
            else -> vm.quickImport(uris)
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.importFolder(uri) }

    Column(Modifier.fillMaxSize().background(Fin.palette.background)) {
        BackTopBar("Import", onBack = { nav.popBackStack() }, actions = {
            if (vm.inBatch && step !is ImportStep.Batch) TextButton(onClick = vm::backToBatch) { Text("Batch") }
        })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = step) {
                ImportStep.PickFile -> PickFile(onFiles = { picker.launch(MIME_TYPES) }, onFolder = { folder.launch(null) })
                ImportStep.Working -> FinCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(); Text("Reading…")
                    }
                }
                is ImportStep.ChooseAccount -> {
                    s.reason?.let { Text("Needs a quick check: $it", color = MaterialTheme.colorScheme.error) }
                    ChooseAccount(s.detected, accounts, vm.preselectedAccountId, vm::chooseAccount, vm::createAccount)
                }
                is ImportStep.Batch -> BatchStep(s, vm, nav)
                is ImportStep.Mapping -> MappingEditor(s, vm)
                is ImportStep.Preview -> PreviewStep(s, vm)
                is ImportStep.Done -> DoneStep(s, vm, nav, onView = { nav.navigate(TransactionsRoute(accountId = s.result.accountId)) })
                is ImportStep.Failed -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.restart() }) { Text("Try another file") }
                }
            }
        }
    }
}

@Composable
private fun BatchStep(s: ImportStep.Batch, vm: ImportViewModel, nav: NavController) {
    val imported = s.imported
    val white = androidx.compose.ui.graphics.Color.White
    HeroCard(Fin.palette.heroGradient) {
        Text(if (s.working) "Importing…" else "Import complete", color = white.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        Text("${s.done} of ${s.files.size} files", color = white, style = MaterialTheme.typography.headlineMedium)
        if (s.working) {
            LinearProgressIndicator(
                progress = { s.done.toFloat() / s.files.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = white, trackColor = white.copy(alpha = 0.25f),
            )
        } else {
            Text(
                "${imported.sumOf { it.result.newCount }} new transactions" +
                    (s.transfersMatched?.takeIf { it > 0 }?.let { " · $it transfers matched" } ?: ""),
                color = white, style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    if (!s.working) NewAccountsCard(nav)
    if (s.needsReview.isNotEmpty() && !s.working) {
        SectionHeader("Needs a quick check")
        Group {
            s.needsReview.forEachIndexed { n, (i, o) ->
                GroupRow(
                    s.files[i].name, subtitle = o.reason, icon = Icons.Default.Warning, iconColor = Fin.palette.warning,
                    showChevron = true, divider = n < s.needsReview.size - 1, onClick = { vm.reviewAt(i, o) },
                )
            }
        }
    }
    SectionHeader("Files")
    Group {
        s.files.forEachIndexed { i, f ->
            val (subtitle, icon, color) = when (val o = f.outcome) {
                null -> Triple(f.error ?: "Waiting…", if (f.error != null) Icons.Default.ErrorOutline else Icons.Default.Schedule,
                    if (f.error != null) Fin.palette.negative else Fin.palette.tertiaryLabel)
                is QuickImportOutcome.Imported -> Triple(
                    "${o.result.newCount} new in ${o.accountName}" + if (o.result.duplicateCount > 0) " · ${o.result.duplicateCount} already there" else "",
                    Icons.Default.CheckCircle, Fin.palette.positive,
                )
                is QuickImportOutcome.AlreadyImported -> Triple("Already in ${o.accountName}", Icons.Default.CheckCircle, Fin.palette.tertiaryLabel)
                is QuickImportOutcome.NeedsReview -> Triple(o.reason, Icons.Default.Warning, Fin.palette.warning)
            }
            GroupRow(f.name, subtitle = subtitle, icon = icon, iconColor = color, divider = i < s.files.size - 1)
        }
    }
    if (!s.working) Button(onClick = { vm.restart() }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Import more") }
}

@Composable
private fun PickFile(onFiles: () -> Unit, onFolder: () -> Unit) {
    val white = androidx.compose.ui.graphics.Color.White
    HeroCard(Fin.palette.heroGradient) {
        Text("Import statements", color = white, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pick as many as you like. Each goes to the right account automatically, duplicates are skipped, " +
                "and anything unclear is set aside for a quick check.",
            color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
        )
    }
    Button(onClick = onFiles, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Choose files") }
    OutlinedButton(onClick = onFolder, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Import a whole folder") }
    SectionHeader("Works with")
    Group {
        GroupRow("PDF statements", subtitle = "Scotiabank chequing, Scotia Visa, Triangle Mastercard", icon = Icons.Default.Description)
        GroupRow("CSV downloads", subtitle = "Any bank; map the columns once", icon = Icons.Default.TableChart, iconColor = Fin.palette.chart[2])
        GroupRow("OFX, QFX, QBO", subtitle = "Quicken and QuickBooks downloads", icon = Icons.Default.InsertDriveFile, iconColor = Fin.palette.chart[1], divider = false)
    }
    Text(
        "Fastest of all: when your banking app shows a statement and asks which app to open it with, pick Finance Tracker.",
        style = MaterialTheme.typography.bodySmall, color = Fin.palette.secondaryLabel, modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ChooseAccount(
    d: DetectedFile,
    accounts: List<AccountEntity>,
    preselected: Long?,
    onChoose: (DetectedFile, AccountEntity) -> Unit,
    onCreate: (DetectedFile, String, Institution, AccountType) -> Unit,
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
    val fitting = candidates.filter { AccountMatcher.compatible(d.hint, AccountRef(it.id, it.institutionEnum, it.typeEnum, it.allLast4)) }
    if (selected != null) {
        Picker("Which account is this?", selected, candidates, { "${it.nickname} (${it.institutionEnum.displayName}${it.last4?.let { l -> " ••$l" } ?: ""})" }, { selected = it }, Modifier.fillMaxWidth(), "Choose an account")
        Button(onClick = { selected?.let { onChoose(d, it) } }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        TextButton(onClick = { selected = null }) { Text("It's a different account: set up a new one") }
        return
    }
    // Nothing picked for it: offer to set up the account the file describes, ready to edit.
    val guess = remember(d, accounts) { AccountGuesser.guess(d.hint, accounts.map { it.nickname }) }
    var name by remember(guess) { mutableStateOf(guess?.nickname.orEmpty()) }
    var inst by remember(guess) { mutableStateOf(guess?.institution ?: d.hint.institution ?: Institution.OTHER) }
    var type by remember(guess) { mutableStateOf(guess?.type ?: d.hint.accountType ?: AccountType.CHEQUING) }
    SectionHeader(if (guess != null) "Looks like a new account" else "New account")
    FinCard {
        Text(
            if (guess != null) "Is this right? Change anything that isn't." else "The file doesn't say which bank or account it's from. What is it?",
            style = MaterialTheme.typography.bodyMedium, color = Fin.palette.secondaryLabel,
        )
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Picker("Bank", inst, Institution.entries.filter { it != Institution.QUESTRADE }, { it.displayName }, { inst = it }, Modifier.fillMaxWidth())
        Picker("Kind of account", type, AccountType.entries.filter { !it.isInvestment }, { it.displayName }, { type = it }, Modifier.fillMaxWidth())
        d.hint.last4?.let { Text("Ending in ••$it", style = MaterialTheme.typography.bodySmall, color = Fin.palette.secondaryLabel) }
    }
    Button(onClick = { onCreate(d, name.trim(), inst, type) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(if (guess != null) "Yes, set it up" else "Set it up")
    }
    if (candidates.isNotEmpty()) {
        SectionHeader("Or add it to an account you have")
        Group {
            val list = fitting.ifEmpty { candidates }
            list.forEachIndexed { i, a ->
                GroupRow(
                    a.nickname, subtitle = listOfNotNull(a.institutionEnum.displayName, a.typeEnum.displayName, a.last4?.let { "••$it" }).joinToString(" · "),
                    showChevron = true, divider = i < list.size - 1, onClick = { onChoose(d, a) },
                )
            }
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
private fun DoneStep(s: ImportStep.Done, vm: ImportViewModel, nav: NavController, onView: () -> Unit) {
    if (s.undone) {
        Text("Import undone. Nothing from that file remains.", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { vm.restart() }) { Text("Import another file") }
        return
    }
    Text("Imported into ${s.accountName}", style = MaterialTheme.typography.titleLarge)
    NewAccountsCard(nav)
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
