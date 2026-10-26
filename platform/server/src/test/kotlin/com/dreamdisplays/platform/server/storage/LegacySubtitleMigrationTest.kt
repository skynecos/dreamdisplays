@file:OptIn(com.dreamdisplays.api.Unstable::class)

package com.dreamdisplays.platform.server.storage

import com.dreamdisplays.shared.subtitle.VttSubtitleSource
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class LegacySubtitleMigrationTest {
    @Test
    fun legacyUrlMigratesOnceWithoutReplacingNewSourceOrOtherMetadata() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.execute("CREATE TABLE displays (id BLOB PRIMARY KEY, subtitleUrl TEXT NOT NULL DEFAULT '', customData TEXT)") }
            fun row(id: Int, old: String, data: Map<String, ByteArray> = emptyMap()) {
                connection.prepareStatement("INSERT INTO displays VALUES (?, ?, ?)").use {
                    it.setBytes(1, byteArrayOf(id.toByte())); it.setString(2, old)
                    it.setString(3, CustomDataCodec.encode(data)); it.executeUpdate()
                }
            }
            row(1, "https://example.test/old.vtt", mapOf("other:data" to byteArrayOf(42)))
            row(2, "https://example.test/stale.vtt", mapOf(VttSubtitleSource.KEY to "https://example.test/new.vtt".toByteArray()))
            row(3, "ddfile:subtitles/yerel.vtt")
            row(4, "file:///outside.vtt")
            val valid: (String) -> Boolean = { LegacySubtitleMigration.validLegacySource(it, Path.of("media")) }
            assertEquals(2, LegacySubtitleMigration.migrate(connection, "displays", valid))
            assertEquals(0, LegacySubtitleMigration.migrate(connection, "displays", valid))
            connection.createStatement().executeQuery("SELECT subtitleUrl, customData FROM displays ORDER BY id").use {
                assertTrue(it.next()); assertEquals("", it.getString(1))
                val first = CustomDataCodec.decode(it.getString(2))
                assertEquals("https://example.test/old.vtt", VttSubtitleSource.read(first))
                assertContentEquals(byteArrayOf(42), first["other:data"])
                assertTrue(it.next()); assertEquals("https://example.test/new.vtt", VttSubtitleSource.read(CustomDataCodec.decode(it.getString(2))))
                assertTrue(it.next()); assertEquals("ddfile:subtitles/yerel.vtt", CustomDataCodec.decode(it.getString(2))[VttSubtitleSource.KEY]?.toString(Charsets.UTF_8))
                assertTrue(it.next()); assertEquals("file:///outside.vtt", it.getString(1))
            }
        }
    }

    @Test
    fun ordinaryUpstreamDatabaseNeedsNoLegacyColumn() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().execute("CREATE TABLE displays (id BLOB PRIMARY KEY, customData TEXT)")
            assertEquals(0, LegacySubtitleMigration.migrate(connection, "displays") { true })
        }
    }
}
