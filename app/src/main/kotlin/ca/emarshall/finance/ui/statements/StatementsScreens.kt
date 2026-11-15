package ca.emarshall.finance.ui.statements

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import ca.emarshall.finance.core.model.Institution
import ca.emarshall.finance.data.AccountStatements
import ca.emarshall.finance.data.StatementsRepository
import ca.emarshall.finance.data.institutionEnum
import ca.emarshall.finance.data.typeEnum
import ca.emarshall.finance.statements.StatementFileStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import ca.emarshall.finance.ui.design.DetailScreen
import ca.emarshall.finance.ui.design.EmptyMessage
import ca.emarshall.finance.ui.design.Footnote
import ca.emarshall.finance.ui.design.Group
import ca.emarshall.finance.ui.design.GroupRow
import ca.emarshall.finance.ui.design.HeroCard
import ca.emarshall.finance.ui.design.Notice
import ca.emarshall.finance.ui.design.NoticeTone
import ca.emarshall.finance.ui.design.SectionHeader
import ca.emarshall.finance.ui.theme.Fin
import ca.emarshall.finance.ui.ImportRoute
import ca.emarshall.finance.ui.SHORT_DAY
import ca.emarshall.finance.ui.StatementViewerRoute
import ca.emarshall.finance.ui.pretty
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class StatementsViewModel @Inject constructor(repo: StatementsRepository, val files: StatementFileStore) : ViewModel() {
    val accounts = repo.byAccount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Where to download a missing statement, per bank. */
private fun whereToGet(a: AccountStatements): String = when {
    a.account.institutionEnum == Institution.CANADIAN_TIRE -> "Triangle app or triangle.com → Statements"
    a.account.institutionEnum == Institution.SCOTIABANK && a.account.typeEnum.isLiability -> "Scotia app or Scotia OnLine → your Visa → Statements"
    a.account.institutionEnum == Institution.SCOTIABANK -> "Scotia app or Scotia OnLine → your account → Statements"
    else -> "your bank's app or website"
}

@Composable
fun StatementsScreen(nav: NavController, vm: StatementsViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val p = Fin.palette
    DetailScreen("Statements", onBack = { nav.popBackStack() }) {
        val list = accounts ?: return@DetailScreen
        if (list.isEmpty()) {
            item { EmptyMessage("Import a statement and it's kept here, encrypted on this phone.", Icons.Outlined.Description, action = "Import statements", onAction = { nav.navigate(ImportRoute()) }) }
            return@DetailScreen
        }
        val missing = list.sumOf { it.missing.size }
        item {
            HeroCard(if (missing == 0) p.investGradient else p.heroGradient) {
                val white = Color.White
                Text(if (missing == 0) "All caught up" else "$missing missing", color = white, style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (missing == 0) "Every statement that's out has been imported." else "Download ${if (missing == 1) "it" else "them"} from your bank, then open with Finance Tracker.",
                    color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
                )
                Text("${list.sumOf { it.imported.size }} statements kept, encrypted on this phone", color = white.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            }
        }
        list.forEach { a ->
            item(key = "h${a.account.id}") { SectionHeader(a.account.nickname, "Import") { nav.navigate(ImportRoute(accountId = a.account.id)) } }
            item(key = "g${a.account.id}") {
                val imported = a.imported.sortedByDescending { it.end ?: it.start }
                Group {
                    a.missing.forEachIndexed { i, m ->
                        GroupRow(
                            "${m.periodStart.format(SHORT_DAY)} – ${m.periodEnd.pretty()}", subtitle = "Missing · get it from ${whereToGet(a)}",
                            icon = Icons.Outlined.WarningAmber, iconColor = p.warning, showChevron = true,
                            divider = i < a.missing.size - 1 || imported.isNotEmpty(),
                            onClick = { nav.navigate(ImportRoute(accountId = a.account.id)) },
                        )
                    }
                    imported.forEachIndexed { i, r ->
                        val viewable = r.batch.hasFile && r.batch.format == "PDF"
                        GroupRow(
                            if (r.start != null && r.end != null) "${r.start.format(SHORT_DAY)} – ${r.end.pretty()}" else r.batch.fileName,
                            subtitle = "${r.batch.format} · ${r.batch.newCount} transactions",
                            icon = Icons.Outlined.Description, iconColor = p.accent, showChevron = viewable, divider = i < imported.size - 1,
                            onClick = if (viewable) ({ nav.navigate(StatementViewerRoute(r.batch.id, "${a.account.nickname} · ${r.end?.format(SHORT_DAY) ?: ""}")) }) else null,
                        )
                    }
                    if (a.missing.isEmpty() && imported.isEmpty()) GroupRow("Nothing imported yet", divider = false)
                }
            }
            a.closingDay?.let { d -> item(key = "f${a.account.id}") { Footnote("Statements close on ${if (d >= 28) "the last day of the month" else "day $d"}.") } }
        }
    }
}

/**
 * Shows a stored statement inside the app. The encrypted file is decrypted to a private temp file only while
 * the screen is open (PdfRenderer needs a seekable file) and deleted when it closes. Nothing is handed to
 * another app.
 */
@Composable
fun StatementViewerScreen(nav: NavController, route: StatementViewerRoute, vm: StatementsViewModel = hiltViewModel()) {
    var pages by remember { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(route.batchId) { onDispose { vm.files.clearTemp() } }
    LaunchedEffect(route.batchId) {
        try {
            pages = withContext(Dispatchers.IO) { render(vm.files.decryptToTemp(route.batchId)) }
        } catch (e: Exception) {
            error = "Couldn't open this statement."
        }
    }
    DetailScreen(route.title, onBack = { nav.popBackStack() }) {
        when {
            error != null -> item { Notice(error!!, NoticeTone.ERROR) }
            pages == null -> item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else -> items(pages!!) { bmp ->
                Image(
                    bmp.asImageBitmap(), contentDescription = "Statement page",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

private fun render(file: File, widthPx: Int = 1400): List<Bitmap> {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
        PdfRenderer(pfd).use { r ->
            return (0 until minOf(r.pageCount, 30)).map { i ->
                r.openPage(i).use { page ->
                    val h = (widthPx.toLong() * page.height / page.width).toInt()
                    Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(AColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    } finally {
        file.delete()
    }
}
