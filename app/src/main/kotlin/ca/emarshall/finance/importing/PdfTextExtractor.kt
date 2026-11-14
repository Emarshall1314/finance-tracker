package ca.emarshall.finance.importing

import android.content.Context
import ca.emarshall.finance.data.ImportException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts text from a statement PDF on the phone. `sortByPosition` gives one text line per visual
 * line, which is what [ca.emarshall.finance.core.statement.StatementTextParser] expects.
 */
@Singleton
class PdfTextExtractor @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile private var initialised = false

    suspend fun extract(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (!initialised) {
            PDFBoxResourceLoader.init(context)
            initialised = true
        }
        try {
            PDDocument.load(bytes).use { doc ->
                PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }
        } catch (e: InvalidPasswordException) {
            throw ImportException("This PDF is password-protected. Download it again without a password.")
        }
    }
}
