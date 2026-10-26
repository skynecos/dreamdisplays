@file:OptIn(com.dreamdisplays.api.Unstable::class)

package com.dreamdisplays.shared.subtitle

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import kotlin.test.*

class VttSubtitleSourceTest {
    @Test
    fun sourceSurvivesExistingMetadataAndCanBeCleared() {
        val data = mutableMapOf("other:data" to byteArrayOf(42))
        assertTrue(VttSubtitleSource.write(data, "https://example.test/altyazı.vtt?episode=1"))
        assertEquals("https://example.test/altyazı.vtt?episode=1", VttSubtitleSource.read(data))
        assertFalse(VttSubtitleSource.write(data, "https://example.test/altyazı.vtt?episode=1"))
        assertTrue(VttSubtitleSource.write(data, ""))
        assertEquals("", VttSubtitleSource.read(data))
        assertContentEquals(byteArrayOf(42), data["other:data"])
    }

    @Test
    fun fileReferencesAndInvalidUrlsNeverReachClientLoader() {
        for (source in listOf("ddfile:subtitles/test.vtt", "file:///etc/passwd", "javascript:alert(1)", "https:///missing-host")) {
            assertEquals("", VttSubtitleSource.read(mapOf(VttSubtitleSource.KEY to source.toByteArray())))
        }
    }

    @Test
    fun fullMapIsNotOverwritten() {
        val data = (1..DisplayCustomDataLimits.MAX_ENTRIES).associate { "other:key$it" to byteArrayOf(1) }.toMutableMap()
        assertFalse(VttSubtitleSource.write(data, "https://example.test/subtitle.vtt"))
        assertEquals(DisplayCustomDataLimits.MAX_ENTRIES, data.size)
    }
}
