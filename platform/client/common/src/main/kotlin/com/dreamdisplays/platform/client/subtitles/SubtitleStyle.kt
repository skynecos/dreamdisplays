package com.dreamdisplays.platform.client.subtitles

import net.minecraft.network.chat.Style
//? if >=1.21.11 {
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/** Client-local subtitle appearance limits and defaults. */
internal object SubtitleStyleDefaults {
    const val SIZE = 1.0
    const val MIN_SIZE = 0.50
    const val MAX_SIZE = 2.00

    const val TEXT_COLOR: Int = -1 // opaque white
    const val OUTLINE_COLOR: Int = 0x00000000 // transparent = disabled, preserves Kirazium's current clean look
    const val BACKGROUND_COLOR: Int = 0xFF000000.toInt()
    const val BACKGROUND_OPACITY = 0.0

    const val BOTTOM_MARGIN = 0.075
    const val MIN_BOTTOM_MARGIN = 0.02
    const val MAX_BOTTOM_MARGIN = 0.30
}

/** Fonts exposed by the subtitle appearance screen. Uses only fonts guaranteed to exist in Minecraft itself. */
internal enum class SubtitleFontPreset(
    val token: String,
    val labelKey: String,
    private val path: String,
) {
    DEFAULT("default", "dreamdisplays.subtitle.font.minecraft", "default"),
    UNIFORM("uniform", "dreamdisplays.subtitle.font.uniform", "uniform"),
    ;

    val id: Identifier
        get() = Identifier.withDefaultNamespace(path)

    /** Style carrying this preset's font through Minecraft's normal glyph pipeline. */
    fun style(): Style {
        // FontDescription.Resource replaced the raw Identifier/ResourceLocation style field in 1.21.11.
        //? if >=1.21.11 {
        return Style.EMPTY.withFont(FontDescription.Resource(id))
        //?} else
        /*return Style.EMPTY.withFont(id)*/
    }

    companion object {
        fun fromToken(token: String?): SubtitleFontPreset =
            entries.firstOrNull { it.token.equals(token, ignoreCase = true) } ?: DEFAULT
    }
}

/** Converts an RGB color and independent opacity into the ARGB value expected by Minecraft text rendering. */
internal fun subtitleBackgroundArgb(rgb: Int, opacity: Double): Int {
    val alpha = (opacity.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt().coerceIn(0, 255)
    return (alpha shl 24) or (rgb and 0x00FFFFFF)
}
