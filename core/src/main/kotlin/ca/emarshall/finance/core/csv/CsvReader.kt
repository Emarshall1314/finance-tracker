package ca.emarshall.finance.core.csv

/**
 * Small RFC 4180 reader: quoted fields with delimiters, doubled quotes and line breaks inside quotes,
 * CRLF or LF, a leading BOM, and blank lines (dropped).
 */
object CsvReader {

    fun read(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val src = text.removePrefix("﻿")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var fieldWasQuoted = false
        var i = 0

        fun endField() {
            row.add(if (fieldWasQuoted) field.toString() else field.toString().trim())
            field.setLength(0)
            fieldWasQuoted = false
        }

        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) rows.add(row)
            row = ArrayList()
        }

        while (i < src.length) {
            val c = src[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < src.length && src[i + 1] == '"') {
                        field.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when {
                    c == '"' && field.isBlank() -> { field.setLength(0); inQuotes = true; fieldWasQuoted = true }
                    c == delimiter -> endField()
                    c == '\r' -> { if (i + 1 < src.length && src[i + 1] == '\n') i++; endRow() }
                    c == '\n' -> endRow()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || fieldWasQuoted) endRow()
        return rows
    }

    /** Picks the most consistent of `,`, `;` and tab across the first lines. */
    fun detectDelimiter(text: String): Char {
        val lines = text.removePrefix("﻿").lineSequence().filter { it.isNotBlank() }.take(10).toList()
        if (lines.isEmpty()) return ','
        return listOf(',', ';', '\t').maxByOrNull { d ->
            val counts = lines.map { line -> countOutsideQuotes(line, d) }
            val min = counts.minOrNull() ?: 0
            if (min == 0) 0 else min * 10 + (if (counts.distinct().size == 1) 5 else 0)
        } ?: ','
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var n = 0
        var q = false
        for (c in line) {
            if (c == '"') q = !q else if (c == d && !q) n++
        }
        return n
    }
}
