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
import ca.emarshall.finance.ui.BackTopBar
import ca.emarshall.finance.ui.EmptyState
import ca.emarshall.finance.ui.ImportRoute
import ca.emarshall.finance.ui.SHORT_DAY
import ca.emarshall.finance.ui.SectionCard
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
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Statements", onBack = { nav.popBackStack() })
        val list = accounts ?: return@Column
        if (list.isEmpty()) {
            EmptyState("No accounts yet.")
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Every file you import is kept here, encrypted on this phone. Missing statements are worked out from " +
                    "each account's statement cycle.",
                style = MaterialTheme.typography.bodyMedium,
            )
            list.forEach { a ->
                SectionCard(a.account.nickname) {
                    a.closingDay?.let { Text("Statements close on ${if (it >= 28) "the last day of the month" else "day $it"}", style = MaterialTheme.typography.bodySmall) }
                    if (a.imported.isEmpty()) Text("Nothing imported yet.")
                    if (a.missing.isNotEmpty()) {
                        a.missing.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.navigate(ImportRoute(accountId = a.account.id)) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = "Missing", tint = MaterialTheme.colorScheme.error)
                                Text("Missing: ${m.periodStart.format(SHORT_DAY)} – ${m.periodEnd.pretty()}", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text("Download from ${whereToGet(a)}, then open it with Finance Tracker.", style = MaterialTheme.typography.bodySmall)
                    }
                    a.imported.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = r.batch.hasFile && r.batch.format == "PDF") {
                                    nav.navigate(StatementViewerRoute(r.batch.id, "${a.account.nickname} · ${r.end?.format(SHORT_DAY) ?: ""}"))
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                if (r.batch.hasFile && r.batch.format == "PDF") Icons.Default.Description else Icons.Default.CheckCircle,
                                contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(if (r.start != null && r.end != null) "${r.start.format(SHORT_DAY)} – ${r.end.pretty()}" else r.batch.fileName)
                                Text("${r.batch.format} · ${r.batch.newCount} transactions", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
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
    Column(Modifier.fillMaxSize()) {
        BackTopBar(route.title, onBack = { nav.popBackStack() })
        when {
            error != null -> Column(Modifier.padding(16.dp)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = { nav.popBackStack() }) { Text("Back") }
            }
            pages == null -> CircularProgressIndicator(Modifier.padding(32.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pages!!) { bmp ->
                    Image(
                        bmp.asImageBitmap(), contentDescription = "Statement page",
                        modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
                    )
                }
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
