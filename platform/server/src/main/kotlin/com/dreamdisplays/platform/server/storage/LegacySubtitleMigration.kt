package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import com.dreamdisplays.shared.subtitle.VttSubtitleSource
import java.sql.Connection
import java.nio.file.Path

/** Imports the old fork's subtitleUrl column without replacing any new custom-data source. */
internal object LegacySubtitleMigration {
    fun validLegacySource(source: String, mediaDirectory: Path): Boolean {
        if (VttSubtitleSource.normalize(source) != null) return true
        if (!source.startsWith("ddfile:")) return false
        val raw = source.removePrefix("ddfile:").replace('\\', '/')
        val relative = runCatching { Path.of(raw).normalize() }.getOrNull() ?: return false
        if (relative.isAbsolute || relative.toString().isBlank()) return false
        val base = mediaDirectory.toAbsolutePath().normalize()
        val file = base.resolve(relative).normalize()
        return file.startsWith(base) && file.fileName.toString().endsWith(".vtt", true)
    }

    fun migrate(connection: Connection, tableName: String, validSource: (String) -> Boolean): Int {
        val exists = connection.metaData.getColumns(null, null, tableName, null).use { columns ->
            var found = false
            while (columns.next()) {
                if (columns.getString("TABLE_NAME").equals(tableName, true) &&
                    columns.getString("COLUMN_NAME").equals("subtitleUrl", true)) found = true
            }
            found
        }
        if (!exists) return 0
        val quote = connection.metaData.identifierQuoteString.trim()
        fun quoted(name: String): String = if (quote.isEmpty()) name else quote + name.replace(quote, quote + quote) + quote
        val table = quoted(tableName)
        val id = quoted("id")
        val source = quoted("subtitleUrl")
        val custom = quoted("customData")
        val rows = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT $id, $source, $custom FROM $table WHERE $source IS NOT NULL AND $source <> ''").use { results ->
                buildList {
                    while (results.next()) add(Triple(results.getBytes(1), results.getString(2), results.getString(3)))
                }
            }
        }
        var imported = 0
        connection.prepareStatement("UPDATE $table SET $custom = ?, $source = '' WHERE $id = ?").use { update ->
            for ((uuid, oldSource, oldCustom) in rows) {
                val data = CustomDataCodec.decode(oldCustom).toMutableMap()
                val bytes = oldSource.toByteArray(Charsets.UTF_8)
                // Leave invalid or oversized legacy values intact for operator inspection.
                if (VttSubtitleSource.KEY !in data) {
                    if (!validSource(oldSource) || !DisplayCustomDataLimits.accepts(data, VttSubtitleSource.KEY, bytes)) continue
                    data[VttSubtitleSource.KEY] = bytes
                    imported++
                }
                update.setString(1, CustomDataCodec.encode(data))
                update.setBytes(2, uuid)
                update.addBatch()
            }
            update.executeBatch()
        }
        return imported
    }
}
