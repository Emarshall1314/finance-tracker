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
import ca.emarshall.finance.core.money.Money
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import ca.emarshall.finance.ui.design.FinTextField
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.NavBar
import ca.emarshall.finance.ui.design.Notice
import ca.emarshall.finance.ui.design.NoticeTone
import ca.emarshall.finance.ui.design.PrimaryButton
import ca.emarshall.finance.ui.design.SecondaryButton
import ca.emarshall.finance.ui.design.ValueRow
import ca.emarshall.finance.ui.design.MenuRow
import ca.emarshall.finance.ui.design.SwitchRow
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
        NavBar("Import", onBack = { nav.popBackStack() }) {
            if (vm.inBatch && step !is ImportStep.Batch) TextButton(onClick = vm::backToBatch) { Text("All files") }
        }
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
                    s.reason?.let { Notice(it, NoticeTone.WARNING) }
                    ChooseAccount(s.detected, accounts, vm.preselectedAccountId, vm::chooseAccount, vm::createAccount)
                }
                is ImportStep.Batch -> BatchStep(s, vm, nav)
                is ImportStep.Mapping -> MappingEditor(s, vm)
                is ImportStep.Preview -> PreviewStep(s, vm)
                is ImportStep.Done -> DoneStep(s, vm, nav, onView = { nav.navigate(TransactionsRoute(accountId = s.result.accountId)) })
                is ImportStep.Failed -> {
                    Notice(s.message, NoticeTone.ERROR)
                    PrimaryButton("Try another file", { vm.restart() })
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
    if (!s.working) PrimaryButton("Import more", { vm.restart() })
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
    PrimaryButton("Choose files", onFiles)
    SecondaryButton("Import a whole folder", onFolder)
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
    SectionHeader("From the file")
    Group {
        GroupRow(d.file.fileName, subtitle = listOfNotNull(
            d.file.format.name, ImportRepository.institutionLabel(d.hint.institution), d.hint.accountType?.displayName, d.hint.last4?.let { "••$it" },
        ).joinToString(" · "), icon = Icons.Default.Description, divider = d.statement != null)
        d.statement?.let { st ->
            val period = if (st.dateFrom != null && st.dateTo != null) "${st.dateFrom!!.pretty()} – ${st.dateTo!!.pretty()}" else null
            GroupRow(period ?: "Transactions", value = "${st.transactions.size} rows", divider = false)
        }
    }
    d.previousImport?.let { Notice("You imported this exact file before. Rows already in the app will be skipped.") }
    val fitting = candidates.filter { AccountMatcher.compatible(d.hint, AccountRef(it.id, it.institutionEnum, it.typeEnum, it.allLast4)) }
    if (selected != null) {
        Picker("Which account is this?", selected, candidates, { "${it.nickname} (${it.institutionEnum.displayName}${it.last4?.let { l -> " ••$l" } ?: ""})" }, { selected = it }, Modifier.fillMaxWidth(), "Choose an account")
        PrimaryButton("Continue", { selected?.let { onChoose(d, it) } })
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
        FinTextField(name, { name = it }, "Name")
        Picker("Bank", inst, Institution.entries.filter { it != Institution.QUESTRADE }, { it.displayName }, { inst = it }, Modifier.fillMaxWidth())
        Picker("Kind of account", type, AccountType.entries.filter { !it.isInvestment }, { it.displayName }, { type = it }, Modifier.fillMaxWidth())
        d.hint.last4?.let { Text("Ending in ••$it", style = MaterialTheme.typography.bodySmall, color = Fin.palette.secondaryLabel) }
    }
    PrimaryButton(if (guess != null) "Yes, set it up" else "Set it up", { onCreate(d, name.trim(), inst, type) }, enabled = name.isNotBlank())
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

    SectionHeader("Map the columns")
    m.notes.forEach { Footnote(it) }
    FinCard {
        Text("First rows of the file", style = MaterialTheme.typography.labelMedium, color = Fin.palette.secondaryLabel)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            sampleRows.take(5).forEach { r -> Text(r.joinToString("  |  "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
    Group {
        SwitchRow("First row is a header", hasHeader, { hasHeader = it })
        MenuRow("Date", dateCol, columns, ::colName, { dateCol = it })
        MenuRow("Date format", dateFormat, m.dateOptions, { it }, { dateFormat = it })
        MenuRow("How amounts are shown", sign, SignConvention.entries, { SHORT_SIGN.getValue(it) }, { sign = it })
        when (sign) {
            SignConvention.DEBIT_CREDIT -> {
                MenuRow("Money out (debit)", debitCol, columns, ::colName, { debitCol = it })
                MenuRow("Money in (credit)", creditCol, columns, ::colName, { creditCol = it })
            }
            SignConvention.TYPE_COLUMN -> {
                MenuRow("Amount", amountCol, columns, ::colName, { amountCol = it })
                MenuRow("Debit/Credit", typeCol, columns, ::colName, { typeCol = it })
            }
            else -> MenuRow("Amount", amountCol, columns, ::colName, { amountCol = it })
        }
        MenuRow("Running balance", balanceCol, listOf<Int?>(null) + columns, { it?.let(::colName) ?: "None" }, { balanceCol = it }, divider = false)
    }
    Footnote(ImportViewModel.SIGN_LABELS.getValue(sign))
    SectionHeader("Description")
    Group {
        columns.forEachIndexed { i, c ->
            GroupRow(colName(c), divider = i < columns.size - 1, onClick = { descCols = if (c in descCols) descCols - c else descCols + c }) {
                Checkbox(c in descCols, { on -> descCols = if (on) descCols + c else descCols - c })
            }
        }
    }

    val sample = profile?.let { p -> runCatching { CsvStatementParser.parse(m.detected.csvText.orEmpty(), p) }.getOrNull() }
    SectionHeader("How it reads")
    Group {
        val rows = sample?.transactions?.take(4).orEmpty()
        if (rows.isEmpty()) GroupRow("Pick the columns above", divider = false)
        rows.forEachIndexed { i, t ->
            GroupRow(t.description, subtitle = t.date.pretty(), value = Money.format(t.amountCents, t.currency ?: m.account.currency, true),
                valueColor = if (t.amountCents > 0) Fin.palette.positive else Fin.palette.label, divider = i < rows.size - 1)
        }
    }
    if (sample != null && sample.warnings.isNotEmpty()) Notice("${sample.warnings.size} row(s) can't be read with this mapping.", NoticeTone.WARNING)
    PrimaryButton("Use this mapping", { profile?.let { vm.applyMapping(m, it) } }, enabled = profile != null && (sample?.transactions?.isNotEmpty() == true))
}

private val SHORT_SIGN = mapOf(
    SignConvention.AMOUNT_AS_IS to "One column",
    SignConvention.AMOUNT_INVERTED to "One column, inverted",
    SignConvention.DEBIT_CREDIT to "Debit and credit",
    SignConvention.TYPE_COLUMN to "Amount + type",
)

@Composable
private fun PreviewStep(s: ImportStep.Preview, vm: ImportViewModel) {
    val p = s.preview
    val st = p.statement
    SectionHeader(p.account.nickname)
    Group {
        if (st.dateFrom != null && st.dateTo != null) ValueRow("Dates", "${st.dateFrom!!.pretty()} – ${st.dateTo!!.pretty()}")
        ValueRow("New transactions", p.dedup.newRows.size.toString())
        if (p.dedup.duplicateCount > 0) ValueRow("Already in the app", p.dedup.duplicateCount.toString())
        ValueRow("Money in", Money.format(p.moneyInCents, p.account.currency, true), Fin.palette.positive)
        ValueRow("Money out", Money.format(-p.moneyOutCents, p.account.currency, true), divider = false)
    }
    st.warnings.forEach { Notice(it, NoticeTone.WARNING) }
    if (p.dedup.newRows.isNotEmpty()) {
        SectionHeader("First new rows")
        Group {
            val rows = p.dedup.newRows.take(8)
            rows.forEachIndexed { i, k ->
                GroupRow(
                    k.tx.description, subtitle = k.tx.date.pretty(), value = Money.format(k.tx.amountCents, k.tx.currency ?: p.account.currency, true),
                    valueColor = if (k.tx.amountCents > 0) Fin.palette.positive else Fin.palette.label, divider = i < rows.size - 1,
                )
            }
        }
    }
    // A statement with no new rows (a card that wasn't used) can still be recorded so its month counts as covered.
    val recordOnly = p.dedup.newRows.isEmpty() && st.periodEnd != null && p.detected.previousImport == null
    if (p.dedup.newRows.isEmpty()) {
        Notice(if (recordOnly) "No new transactions. Recording the statement marks ${st.periodEnd!!.pretty()} as covered for ${p.account.nickname}." else "Nothing new in this file.")
    }
    PrimaryButton(
        if (recordOnly) "Record this statement" else "Import ${p.dedup.newRows.size} transactions",
        { vm.commit(p) }, enabled = p.dedup.newRows.isNotEmpty() || recordOnly,
    )
    if (p.profile != null) TextButton(onClick = { vm.editMapping(p) }) { Text("Change column mapping") }
}

@Composable
private fun DoneStep(s: ImportStep.Done, vm: ImportViewModel, nav: NavController, onView: () -> Unit) {
    if (s.undone) {
        Notice("Import undone. Nothing from that file remains.", NoticeTone.SUCCESS)
        PrimaryButton("Import another file", { vm.restart() })
        return
    }
    val white = androidx.compose.ui.graphics.Color.White
    HeroCard(Fin.palette.investGradient) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = white, modifier = Modifier.size(28.dp))
        Text("Imported into ${s.accountName}", color = white, style = MaterialTheme.typography.headlineSmall)
        Text(
            "${s.result.newCount} new" + (if (s.result.duplicateCount > 0) " · ${s.result.duplicateCount} already there" else "") +
                (if (s.result.transfersMatched > 0) " · ${s.result.transfersMatched} transfers matched" else ""),
            color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
        )
    }
    NewAccountsCard(nav)
    Group {
        GroupRow("See these transactions", icon = Icons.Default.TableChart, showChevron = true, onClick = onView)
        GroupRow("Undo this import", icon = Icons.Default.ErrorOutline, iconColor = Fin.palette.negative, divider = false, onClick = { vm.undo(s) })
    }
    Footnote("Transactions were categorised automatically. Fix any in Activity and the app learns from it.")
    PrimaryButton("Import another", { vm.restart() })
}
