package com.dreamdisplays.platform.client

import com.dreamdisplays.api.media.audio.model.AcousticQuality
import com.dreamdisplays.media.source.youtube.cookie.CookieSource
import com.dreamdisplays.platform.client.subtitles.SubtitleFontPreset
import com.dreamdisplays.platform.client.subtitles.SubtitleStyleDefaults
import java.io.File
import kotlin.math.roundToInt

/** Client configuration loaded from and persisted to `config.yml`. */
class Config(private val baseDir: File) {
    /** The backing `config.yml` file on disk. */
    private val file = File(baseDir, "config.yml")

    /** Whether to mute all displays while the game window is not focused. */
    var muteOnAltTab: Boolean = false

    /** Default render distance for new displays, in blocks (snapped to a multiple of 16). */
    var defaultDistance: Int = 96

    /** Default volume for new displays, in range `0.0`..`1.0`. */
    var defaultDisplayVolume: Double = 0.5

    /** Whether displays are enabled at all. */
    var displaysEnabled: Boolean = true

    /** Browser to import `yt-dlp` cookies from, or [CookieSource.NONE] to disable. */
    var ytdlpCookieSource: CookieSource = CookieSource.NONE

    /** Proxy URL passed to `yt-dlp`, or empty for a direct connection. */
    var ytdlpProxy: String = ""

    /** Whether to use hardware-accelerated video decoding. */
    var useHwAccel: Boolean = true

    var unshadedDisplays: Boolean = true

    /** 3D acoustics rendering tier applied to every display's audio (`off` / `basic` / `advanced` / `ultra`). */
    var audioAcoustics: AcousticQuality = AcousticQuality.ADVANCED

    /** Output profile for spatialized audio: `true` renders binaural for headphones, `false` a plain stereo pan for speakers. */
    var audioBinauralOutput: Boolean = true

    /** Viewer-local subtitle font preset. */
    var subtitleFont: String = SubtitleFontPreset.DEFAULT.token

    /** Subtitle size multiplier. 1.0 preserves the existing Kirazium subtitle scale. */
    var subtitleSize: Double = SubtitleStyleDefaults.SIZE

    /** Opaque ARGB subtitle text color. */
    var subtitleTextColor: Int = SubtitleStyleDefaults.TEXT_COLOR

    /** ARGB subtitle outline color. Alpha zero means outline disabled. */
    var subtitleOutlineColor: Int = SubtitleStyleDefaults.OUTLINE_COLOR

    /** RGB/ARGB source color used by the subtitle background plate. */
    var subtitleBackgroundColor: Int = SubtitleStyleDefaults.BACKGROUND_COLOR

    /** Independent background opacity; text opacity is intentionally unaffected. */
    var subtitleBackgroundOpacity: Double = SubtitleStyleDefaults.BACKGROUND_OPACITY

    /** Distance from the bottom edge as a fraction of the display height. */
    var subtitleBottomMargin: Double = SubtitleStyleDefaults.BOTTOM_MARGIN

    init {
        load()
    }

    /** Re-reads values from disk, replacing any in-memory state. */
    fun reload() = load()

    /** Resets only subtitle appearance, leaving every unrelated client preference untouched. */
    fun resetSubtitleStyle() {
        subtitleFont = SubtitleFontPreset.DEFAULT.token
        subtitleSize = SubtitleStyleDefaults.SIZE
        subtitleTextColor = SubtitleStyleDefaults.TEXT_COLOR
        subtitleOutlineColor = SubtitleStyleDefaults.OUTLINE_COLOR
        subtitleBackgroundColor = SubtitleStyleDefaults.BACKGROUND_COLOR
        subtitleBackgroundOpacity = SubtitleStyleDefaults.BACKGROUND_OPACITY
        subtitleBottomMargin = SubtitleStyleDefaults.BOTTOM_MARGIN
    }

    /** True when subtitle appearance still matches Kirazium's pre-customization rendering. */
    fun isSubtitleStyleDefault(): Boolean =
        subtitleFont == SubtitleFontPreset.DEFAULT.token &&
            kotlin.math.abs(subtitleSize - SubtitleStyleDefaults.SIZE) < 0.0001 &&
            subtitleTextColor == SubtitleStyleDefaults.TEXT_COLOR &&
            subtitleOutlineColor == SubtitleStyleDefaults.OUTLINE_COLOR &&
            subtitleBackgroundColor == SubtitleStyleDefaults.BACKGROUND_COLOR &&
            kotlin.math.abs(subtitleBackgroundOpacity - SubtitleStyleDefaults.BACKGROUND_OPACITY) < 0.0001 &&
            kotlin.math.abs(subtitleBottomMargin - SubtitleStyleDefaults.BOTTOM_MARGIN) < 0.0001

    /**
     * Loads the configuration from disk, applying default values for missing or malformed entries.
     * If the configuration file does not exist, it will be created with default values.
     */
    private fun load() {
        if (!file.exists()) {
            save(); return
        }
        val data = file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null
                else line.substring(0, colon).trim() to
                        line.substring(colon + 1).trim().removeSurrounding("'").removeSurrounding("\"")
            }
            .toMap()

        muteOnAltTab = data["mute-on-alt-tab"]?.toBooleanStrictOrNull() ?: muteOnAltTab
        val rawDistance = data["default-render-distance"]?.toIntOrNull() ?: defaultDistance
        defaultDistance = ((rawDistance / 16.0).roundToInt().coerceIn(2, 12)) * 16
        defaultDisplayVolume = data["default-default-display-volume"]?.toDoubleOrNull() ?: defaultDisplayVolume
        displaysEnabled = data["displays-enabled"]?.toBooleanStrictOrNull() ?: displaysEnabled
        ytdlpCookieSource = data["ytdlp-cookies-from-browser"]
            ?.let { CookieSource.fromConfig(it) }
            ?: ytdlpCookieSource
        ytdlpProxy = data["ytdlp-proxy"] ?: ytdlpProxy
        useHwAccel = data["use-hw-accel"]?.toBooleanStrictOrNull() ?: useHwAccel
        unshadedDisplays = data["unshaded-displays"]?.toBooleanStrictOrNull() ?: unshadedDisplays
        audioAcoustics = data["audio-acoustics"]?.let { token ->
            AcousticQuality.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
        } ?: audioAcoustics
        audioBinauralOutput = when (data["audio-output-profile"]?.lowercase()) {
            "speakers" -> false
            "headphones", "auto" -> true
            else -> audioBinauralOutput
        }

        subtitleFont = SubtitleFontPreset.fromToken(data["subtitle-font"]).token
        subtitleSize = (data["subtitle-size"]?.toDoubleOrNull() ?: subtitleSize)
            .coerceIn(SubtitleStyleDefaults.MIN_SIZE, SubtitleStyleDefaults.MAX_SIZE)
        subtitleTextColor = parseArgb(data["subtitle-text-color"], subtitleTextColor, forceOpaque = true)
        subtitleOutlineColor = parseArgb(data["subtitle-outline-color"], subtitleOutlineColor)
        subtitleBackgroundColor = parseArgb(data["subtitle-background-color"], subtitleBackgroundColor, forceOpaque = true)
        subtitleBackgroundOpacity = (data["subtitle-background-opacity"]?.toDoubleOrNull() ?: subtitleBackgroundOpacity)
            .coerceIn(0.0, 1.0)
        subtitleBottomMargin = (data["subtitle-bottom-margin"]?.toDoubleOrNull() ?: subtitleBottomMargin)
            .coerceIn(SubtitleStyleDefaults.MIN_BOTTOM_MARGIN, SubtitleStyleDefaults.MAX_BOTTOM_MARGIN)
    }

    /** Persists the current configuration values to disk. */
    fun save() {
        baseDir.mkdirs()
        file.writeText(buildString {
            appendLine("mute-on-alt-tab: $muteOnAltTab")
            appendLine("default-render-distance: $defaultDistance")
            appendLine("default-default-display-volume: $defaultDisplayVolume")
            appendLine("displays-enabled: $displaysEnabled")
            appendLine("ytdlp-cookies-from-browser: ${ytdlpCookieSource.configToken.yamlQuoted()}")
            appendLine("ytdlp-proxy: ${ytdlpProxy.yamlQuoted()}")
            appendLine("use-hw-accel: $useHwAccel")
            appendLine("unshaded-displays: $unshadedDisplays")
            appendLine("audio-acoustics: ${audioAcoustics.name.lowercase()}")
            appendLine("audio-output-profile: ${if (audioBinauralOutput) "headphones" else "speakers"}")
            appendLine("subtitle-font: ${subtitleFont.yamlQuoted()}")
            appendLine("subtitle-size: ${subtitleSize.coerceIn(SubtitleStyleDefaults.MIN_SIZE, SubtitleStyleDefaults.MAX_SIZE)}")
            appendLine("subtitle-text-color: ${subtitleTextColor.toArgbHex().yamlQuoted()}")
            appendLine("subtitle-outline-color: ${subtitleOutlineColor.toArgbHex().yamlQuoted()}")
            appendLine("subtitle-background-color: ${subtitleBackgroundColor.toArgbHex().yamlQuoted()}")
            appendLine("subtitle-background-opacity: ${subtitleBackgroundOpacity.coerceIn(0.0, 1.0)}")
            appendLine("subtitle-bottom-margin: ${subtitleBottomMargin.coerceIn(SubtitleStyleDefaults.MIN_BOTTOM_MARGIN, SubtitleStyleDefaults.MAX_BOTTOM_MARGIN)}")
        })
    }

    companion object {
        init {
            System.setProperty("file.encoding", "UTF-8")
        }

        private fun parseArgb(value: String?, fallback: Int, forceOpaque: Boolean = false): Int {
            val raw = value?.trim()?.removePrefix("#") ?: return fallback
            val parsed = raw.toLongOrNull(16) ?: return fallback
            val argb = when (raw.length) {
                6 -> (0xFF000000L or parsed).toInt()
                8 -> parsed.toInt()
                else -> return fallback
            }
            return if (forceOpaque) argb or 0xFF000000.toInt() else argb
        }

        private fun Int.toArgbHex(): String = "#%08X".format(this)

        /** Wraps the string in single quotes if it is empty or contains YAML-special characters. */
        private fun String.yamlQuoted(): String =
            if (isEmpty() || any { it in ":#{}[]|>&!*'\",\n\r\t" })
                "'${replace("'", "''")}'"
            else this
    }
}
