package com.dreamdisplays.platform.client.subtitles

import net.minecraft.network.chat.Style
//? if >=1.21.11 {
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/** Client-local subtitle appearance limits and defaults. */
internal object SubtitleStyleDefaults {
    const val SIZE = 1.0f
    const val MIN_SIZE = 0.50f
    const val MAX_SIZE = 2.00f

    const val TEXT_COLOR: Int = -1
    const val OUTLINE_COLOR: Int = 0x00000000
    const val BACKGROUND_COLOR: Int = -0x1000000
    const val BACKGROUND_OPACITY = 0.0f
    const val VERTICAL_POSITION = 0.15f
}

/** Fonts exposed by the personal subtitle appearance screen. */
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

/** Converts an RGB color and independent opacity into Minecraft ARGB. */
internal fun subtitleBackgroundArgb(rgb: Int, opacity: Float): Int {
    val alpha = (opacity.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    return (alpha shl 24) or (rgb and 0x00FFFFFF)
}
