package com.dreamdisplays.api.display.model.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitlePreferencesTest {
    @Test fun freshPreferencesKeepNativeTracksOffAndExternalVttOn() {
        val value = ClientDisplaySettings()
        assertEquals("auto", value.subtitleSource)
        assertFalse(value.subtitlesEnabled)
        assertTrue(value.externalSubtitlesEnabled)
        assertEquals(1f, value.subtitleScale)
        assertEquals(0.15f, value.subtitleVerticalPosition)
        assertEquals(0f, value.subtitleBackgroundOpacity)
        assertEquals("default", value.subtitleFont)
    }

    @Test fun corruptStyleValuesAreClampedWithoutTouchingPlayback() {
        val value = ClientDisplaySettings(volume = 0.7f, subtitleScale = Float.NaN,
            subtitleVerticalPosition = 4f, subtitleBackgroundOpacity = -1f,
            subtitleFont = "missing", subtitleTextColor = 0x123456, subtitleSource = "unsupported")
        value.normalizeSubtitlePreferences()
        assertEquals("auto", value.subtitleSource)
        assertEquals(1f, value.subtitleScale)
        assertEquals(1f, value.subtitleVerticalPosition)
        assertEquals(0f, value.subtitleBackgroundOpacity)
        assertEquals("default", value.subtitleFont)
        assertEquals(0xFF123456.toInt(), value.subtitleTextColor)
        assertEquals(0.7f, value.volume)
    }

    @Test fun acceptedLegacyAppearanceKeepsItsValues() {
        val value = ClientDisplaySettings(subtitleScale = 1.4f, subtitleVerticalPosition = 0.35f,
            subtitleBackgroundOpacity = 0.6f, subtitleFont = "uniform", subtitleTextColor = -256)
        value.normalizeSubtitlePreferences()
        assertEquals(1.4f, value.subtitleScale)
        assertEquals(0.35f, value.subtitleVerticalPosition)
        assertEquals(0.6f, value.subtitleBackgroundOpacity)
        assertEquals("uniform", value.subtitleFont)
        assertEquals(-256, value.subtitleTextColor)
    }
}
