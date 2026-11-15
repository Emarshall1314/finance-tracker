package ca.emarshall.finance.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One database row: column name to value (null, Long, String or ByteArray; money is always Long cents). */
typealias BackupRow = Map<String, Any?>

/** Everything a backup holds, before encryption. */
class BackupContents(
    /** [ca.emarshall.finance.data.db.FinanceDatabase] schema version the rows came from. */
    val schemaVersion: Int,
    val createdAtMillis: Long,
    /** Table name to its rows, in the order the tables should be restored. */
    val tables: Map<String, List<BackupRow>>,
    /** Original statement files by import id (empty when the backup was made without them). */
    val statementFiles: Map<Long, ByteArray>,
)

/**
 * The backup's JSON. Rows are stored by column name, not by position, so a backup made by an older version
 * still loads after the database gains columns. Binary values are wrapped as {"b64": "..."}.
 */
object BackupFormat {
    const val FORMAT = "finance-tracker-backup"
    const val FORMAT_VERSION = 1

    fun encode(c: BackupContents): ByteArray {
        val json = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("schemaVersion", c.schemaVersion)
            put("createdAtMillis", c.createdAtMillis)
            put("tables", JsonObject(c.tables.mapValues { (_, rows) -> JsonArray(rows.map { row -> JsonObject(row.mapValues { (_, v) -> value(v) }) }) }))
            put("statementFiles", JsonObject(c.statementFiles.entries.associate { (id, bytes) -> id.toString() to JsonPrimitive(b64(bytes)) }))
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): BackupContents {
        val root = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            throw BackupException("The backup file couldn't be read.")
        }
        if (root["format"]?.jsonPrimitive?.content != FORMAT) throw BackupException("This isn't a Finance Tracker backup file.")
        val version = root["formatVersion"]?.jsonPrimitive?.longOrNull ?: 0
        if (version > FORMAT_VERSION) throw BackupException("This backup was made by a newer version of the app. Update the app, then restore.")
        val tables = LinkedHashMap<String, List<BackupRow>>()
        root["tables"]?.jsonObject?.forEach { (table, rows) ->
            tables[table] = rows.jsonArray.map { row -> row.jsonObject.mapValues { (_, v) -> unvalue(v) } }
        }
        val files = root["statementFiles"]?.jsonObject?.entries?.mapNotNull { (id, v) ->
            id.toLongOrNull()?.let { it to unb64(v.jsonPrimitive.content) }
        }?.toMap().orEmpty()
        return BackupContents(
            schemaVersion = root["schemaVersion"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
            createdAtMillis = root["createdAtMillis"]?.jsonPrimitive?.longOrNull ?: 0,
            tables = tables,
            statementFiles = files,
        )
    }

    private fun value(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Long -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v.toLong())
        is String -> JsonPrimitive(v)
        is ByteArray -> buildJsonObject { put("b64", b64(v)) }
        else -> JsonPrimitive(v.toString())
    }

    private fun unvalue(v: JsonElement): Any? = when {
        v is JsonNull -> null
        v is JsonObject -> v["b64"]?.jsonPrimitive?.content?.let(::unb64)
        v is JsonPrimitive && v.isString -> v.content
        v is JsonPrimitive -> v.longOrNull ?: v.content
        else -> null
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
