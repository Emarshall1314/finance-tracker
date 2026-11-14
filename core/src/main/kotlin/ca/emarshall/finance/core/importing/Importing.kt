package ca.emarshall.finance.core.importing

import ca.emarshall.finance.core.model.FileFormat
import ca.emarshall.finance.core.model.ParsedTransaction
import ca.emarshall.finance.core.ofx.OfxParser
import java.security.MessageDigest
import java.time.LocalDate

object Hashing {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

object FormatDetector {
    /** Decides by content first (extensions lie), then by file name. */
    fun detect(fileName: String?, head: ByteArray): FileFormat {
        if (head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()
        ) return FileFormat.PDF
        val text = String(head, Charsets.UTF_8)
        if (OfxParser.looksLikeOfx(text)) return FileFormat.OFX
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "ofx", "qfx", "qbo" -> FileFormat.OFX
            "pdf" -> FileFormat.PDF
            "csv", "txt" -> FileFormat.CSV
            else -> if (text.contains(',') || text.contains(';') || text.contains('\t')) FileFormat.CSV else FileFormat.UNKNOWN
        }
    }

    /** Text files from Windows tools are sometimes Windows-1252, not UTF-8. */
    fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('�')) String(bytes, Charsets.ISO_8859_1) else utf8
    }
}

object Fingerprints {
    /** Lower-case, digits kept, punctuation and repeated spaces removed: survives small export differences. */
    fun normalizeDescription(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun of(accountId: Long, date: LocalDate, amountCents: Long, description: String, occurrence: Int): String =
        Hashing.sha256Hex("$accountId|$date|$amountCents|${normalizeDescription(description)}|$occurrence".toByteArray())
}

/** One incoming row with the keys used to spot duplicates. */
data class KeyedRow(
    val tx: ParsedTransaction,
    val fingerprint: String,
    val fitId: String?,
    val isDuplicate: Boolean,
)

data class DedupResult(val rows: List<KeyedRow>) {
    val newRows get() = rows.filterNot { it.isDuplicate }
    val duplicateCount get() = rows.count { it.isDuplicate }
}

object Dedup {
    /**
     * Marks rows already in the database. OFX rows match on FITID. Every row also gets a fingerprint of
     * account + date + amount + normalised description + occurrence number, where occurrence counts identical
     * rows within the file, so two identical coffees on one day are both kept, and re-importing a file that
     * overlaps an earlier one skips exactly the rows already there.
     */
    fun plan(
        accountId: Long,
        rows: List<ParsedTransaction>,
        existingFingerprints: Set<String>,
        existingFitIds: Set<String>,
    ): DedupResult {
        val occurrences = HashMap<String, Int>()
        val keyed = rows.map { tx ->
            val base = "${tx.date}|${tx.amountCents}|${Fingerprints.normalizeDescription(tx.description)}"
            val occ = occurrences.merge(base, 1, Int::plus)!!
            val fp = Fingerprints.of(accountId, tx.date, tx.amountCents, tx.description, occ)
            val dup = (tx.fitId != null && tx.fitId in existingFitIds) || fp in existingFingerprints
            KeyedRow(tx, fp, tx.fitId, dup)
        }
        return DedupResult(keyed)
    }
}
