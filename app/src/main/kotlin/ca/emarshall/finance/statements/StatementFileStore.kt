package ca.emarshall.finance.statements

import android.content.Context
import ca.emarshall.finance.security.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The statement library: each imported file is kept, encrypted with the app's Keystore-backed Tink key,
 * in no-backup storage. Opening one decrypts it into memory (or a private temp file for the PDF renderer).
 */
@Singleton
class StatementFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
) {
    private val dir get() = File(context.noBackupFilesDir, "statements").apply { mkdirs() }
    private fun file(batchId: Long) = File(dir, "$batchId.bin")
    private fun label(batchId: Long) = "statement:$batchId"

    suspend fun save(batchId: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "$batchId.tmp")
        tmp.writeBytes(secrets.sealBytes(label(batchId), bytes))
        if (!tmp.renameTo(file(batchId))) {
            tmp.delete()
            throw java.io.IOException("Couldn't save the statement file")
        }
    }

    suspend fun read(batchId: Long): ByteArray = withContext(Dispatchers.IO) {
        secrets.openBytes(label(batchId), file(batchId).readBytes())
    }

    /** Decrypts to a private temp file (PdfRenderer needs a seekable file). Delete it when done. */
    suspend fun decryptToTemp(batchId: Long): File = withContext(Dispatchers.IO) {
        val out = File(File(context.noBackupFilesDir, "viewer").apply { mkdirs() }, "$batchId.pdf")
        out.writeBytes(read(batchId))
        out
    }

    fun clearTemp() {
        File(context.noBackupFilesDir, "viewer").listFiles()?.forEach { it.delete() }
    }

    fun exists(batchId: Long) = file(batchId).exists()

    suspend fun delete(batchId: Long) = withContext(Dispatchers.IO) { file(batchId).delete(); Unit }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        clearTemp()
    }
}
