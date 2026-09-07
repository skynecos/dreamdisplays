from __future__ import annotations

from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one literal match, got {count}")
    write(path, text.replace(old, new, 1))


def regex_replace_once(path: str, pattern: str, new: str) -> None:
    text = read(path)
    updated, count = re.subn(pattern, new, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one regex match, got {count}")
    write(path, updated)


# 1) Extend the persisted viewer/display subtitle model. Missing fields remain backward-compatible
# because kotlinx.serialization will use these defaults for existing JSON entries.
api_path = "api/src/main/kotlin/com/dreamdisplays/api/display/model/settings/ClientDisplaySettings.kt"
api_text = read(api_path)
if "var subtitleFont:" not in api_text:
    old = '''    /** Viewer-local vertical subtitle position, from 0.0 (bottom) to 1.0 (higher). */
    var subtitleVerticalPosition: Float = 0.15f,
) {
'''
    new = '''    /** Viewer-local vertical subtitle position, from 0.0 (bottom) to 1.0 (higher). */
    var subtitleVerticalPosition: Float = 0.15f,

    /** Viewer-local subtitle font preset token. */
    var subtitleFont: String = "default",

    /** Viewer-local opaque ARGB subtitle text color. */
    var subtitleTextColor: Int = -1,

    /** Viewer-local ARGB subtitle outline color. Alpha zero disables the outline. */
    var subtitleOutlineColor: Int = 0x00000000,

    /** Viewer-local RGB/ARGB source color used by the subtitle background plate. */
    var subtitleBackgroundColor: Int = 0xFF000000.toInt(),

    /** Viewer-local subtitle background opacity in the range 0.0..1.0. */
    var subtitleBackgroundOpacity: Float = 0.0f,
) {
'''
    replace_once(api_path, old, new)


# 2) Restore the version-aware font/style helpers from the old rich subtitle implementation.
write(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/subtitles/SubtitleStyle.kt",
    '''package com.dreamdisplays.platform.client.subtitles

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
'''
)


# 3) Restore the RGB picker, but persist through per-display viewer settings instead of global config.
write(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/SubtitleColorPickerScreen.kt",
    '''package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.widgets.TextButton
import com.dreamdisplays.platform.client.ui.widgets.ValueSlider
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/** Full-RGB picker used by subtitle text, outline and background colors. Changes are live and viewer-local. */
internal class SubtitleColorPickerScreen(
    private val parent: Screen,
    title: Component,
    private val getColor: () -> Int,
    private val setColor: (Int) -> Unit,
    private val allowOff: Boolean = false,
) : UiScreenBase(title) {

    private lateinit var red: ValueSlider
    private lateinit var green: ValueSlider
    private lateinit var blue: ValueSlider
    private lateinit var back: TextButton
    private var off: TextButton? = null

    override fun minContentSize(): Pair<Int, Int> = 440 to 300

    override fun init() {
        super.init()
        val rgb = getColor() and 0x00FFFFFF
        red = addUi(channelSlider((rgb ushr 16) and 0xFF, 16, "R"))
        green = addUi(channelSlider((rgb ushr 8) and 0xFF, 8, "G"))
        blue = addUi(channelSlider(rgb and 0xFF, 0, "B"))
        if (allowOff) {
            off = addUi(TextButton(Component.translatable("dreamdisplays.subtitle.color.off")) {
                setColor(0x00000000)
            })
        }
        back = addUi(TextButton(Component.translatable("gui.back")) { onClose() })
    }

    private fun channelSlider(initial: Int, shift: Int, prefix: String): ValueSlider = ValueSlider(
        initial = initial / 255.0,
        label = { fraction -> Component.literal("$prefix ${(fraction * 255.0).roundToInt().coerceIn(0, 255)}") },
        step = 1.0 / 255.0,
    ) { fraction ->
        val value = (fraction * 255.0).roundToInt().coerceIn(0, 255)
        val current = getColor()
        val rgb = (current and 0x00FFFFFF and (0xFF shl shift).inv()) or (value shl shift)
        setColor(0xFF000000.toInt() or rgb)
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)
        val panelW = 360
        val panelH = 238
        val panelX = (width - panelW) / 2
        val panelY = (height - panelH) / 2
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xE0181818.toInt())
        g.drawText(font, title, panelX + (panelW - font.width(title)) / 2, panelY + 14, UiTheme.TEXT_PRIMARY, false)

        val swatchX = panelX + 24
        val swatchY = panelY + 42
        val swatchW = panelW - 48
        val shown = 0xFF000000.toInt() or (getColor() and 0x00FFFFFF)
        g.fill(swatchX, swatchY, swatchX + swatchW, swatchY + 30, shown)
        val hex = if (allowOff && (getColor() ushr 24) == 0) {
            Component.translatable("dreamdisplays.subtitle.color.disabled")
        } else {
            Component.literal("#%06X".format(getColor() and 0x00FFFFFF))
        }
        g.drawText(font, hex, swatchX + (swatchW - font.width(hex)) / 2, swatchY + 10, 0xFFFFFFFF.toInt(), true)

        val sliderX = panelX + 24
        val sliderW = panelW - 48
        red.place(UiRect(sliderX, panelY + 84, sliderW, UiTheme.ROW_H))
        green.place(UiRect(sliderX, panelY + 112, sliderW, UiTheme.ROW_H))
        blue.place(UiRect(sliderX, panelY + 140, sliderW, UiTheme.ROW_H))

        val buttonY = panelY + panelH - 36
        if (off != null) {
            off!!.place(UiRect(panelX + 24, buttonY, 100, UiTheme.CONTROL_BUTTON))
            back.place(UiRect(panelX + panelW - 124, buttonY, 100, UiTheme.CONTROL_BUTTON))
        } else {
            back.place(UiRect(panelX + (panelW - 120) / 2, buttonY, 120, UiTheme.CONTROL_BUTTON))
        }

        drawChildren(g, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        MinecraftScreenUtil.setScreen(Minecraft.getInstance(), parent)
    }
}
'''
)


# 4) Replace the truncated three-row menu with the full personal appearance editor.
write(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/SubtitlePreferencesMenu.kt",
    '''package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.platform.client.subtitles.SubtitleFontPreset
import com.dreamdisplays.platform.client.subtitles.SubtitleStyleDefaults
import com.dreamdisplays.platform.client.subtitles.subtitleBackgroundArgb
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.widgets.ModeSlider
import com.dreamdisplays.platform.client.ui.widgets.TextButton
import com.dreamdisplays.platform.client.ui.widgets.ValueSlider
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * Full viewer-safe subtitle editor. Every setting remains local to this player and display and is
 * persisted in client-display-settings.json; no style value is sent to the server or other viewers.
 */
class SubtitlePreferencesMenu(
    private val displayScreen: DisplayScreen,
    private val parent: Screen? = null,
) : UiScreenBase(Component.translatable("dreamdisplays.ui.subtitle_preferences")) {
    private lateinit var enabled: ModeSlider<Boolean>
    private lateinit var size: ValueSlider
    private lateinit var backgroundOpacity: ValueSlider
    private lateinit var verticalPosition: ValueSlider
    private lateinit var fontPrev: TextButton
    private lateinit var fontNext: TextButton
    private lateinit var textColorButton: TextButton
    private lateinit var outlineColorButton: TextButton
    private lateinit var backgroundColorButton: TextButton
    private lateinit var resetButton: TextButton
    private lateinit var backButton: TextButton

    private val settings get() = ClientSettingsStore.getSettings(displayScreen.uuid)

    override fun minContentSize(): Pair<Int, Int> = 560 to 520

    override fun init() {
        super.init()

        enabled = addUi(
            ModeSlider(
                modes = listOf(false, true),
                initial = displayScreen.subtitlesEnabled,
                current = { displayScreen.subtitlesEnabled },
                enabledFor = { true },
                label = {
                    Component.translatable(
                        if (it) "dreamdisplays.button.enabled" else "dreamdisplays.button.disabled",
                    )
                },
            ) { displayScreen.subtitlesEnabled = it },
        )

        size = addUi(
            ValueSlider(
                initial = sizeToFraction(displayScreen.subtitleScale),
                label = { Component.literal("${(sizeFromFraction(it) * 100f).roundToInt()}%") },
                step = 0.01,
            ) { displayScreen.subtitleScale = sizeFromFraction(it) },
        )

        backgroundOpacity = addUi(
            ValueSlider(
                initial = settings.subtitleBackgroundOpacity.toDouble().coerceIn(0.0, 1.0),
                label = { Component.literal("${(it * 100.0).roundToInt()}%") },
                step = 0.01,
            ) {
                settings.subtitleBackgroundOpacity = it.toFloat().coerceIn(0f, 1f)
                ClientSettingsStore.save()
            },
        )

        verticalPosition = addUi(
            ValueSlider(
                initial = displayScreen.subtitleVerticalPosition.toDouble(),
                label = { Component.literal("${(it * 100.0).roundToInt()}%") },
                step = 0.01,
            ) { displayScreen.subtitleVerticalPosition = it.toFloat() },
        )

        fontPrev = addUi(TextButton(Component.literal("‹")) { cycleFont(-1) })
        fontNext = addUi(TextButton(Component.literal("›")) { cycleFont(1) })

        textColorButton = addUi(TextButton(Component.translatable("dreamdisplays.subtitle.configure")) {
            openColorPicker(
                Component.translatable("dreamdisplays.subtitle.text_color"),
                { settings.subtitleTextColor },
                {
                    settings.subtitleTextColor = it or 0xFF000000.toInt()
                    ClientSettingsStore.save()
                },
            )
        })

        outlineColorButton = addUi(TextButton(Component.translatable("dreamdisplays.subtitle.configure")) {
            openColorPicker(
                Component.translatable("dreamdisplays.subtitle.outline_color"),
                { settings.subtitleOutlineColor },
                {
                    settings.subtitleOutlineColor = it
                    ClientSettingsStore.save()
                },
                allowOff = true,
            )
        })

        backgroundColorButton = addUi(TextButton(Component.translatable("dreamdisplays.subtitle.configure")) {
            openColorPicker(
                Component.translatable("dreamdisplays.subtitle.background_color"),
                { settings.subtitleBackgroundColor },
                {
                    settings.subtitleBackgroundColor = it or 0xFF000000.toInt()
                    ClientSettingsStore.save()
                },
            )
        })

        resetButton = addUi(TextButton(Component.translatable("dreamdisplays.subtitle.reset")) {
            resetAppearance()
        })
        backButton = addUi(TextButton(Component.translatable("gui.back")) { onClose() })
    }

    private fun openColorPicker(
        title: Component,
        getColor: () -> Int,
        setColor: (Int) -> Unit,
        allowOff: Boolean = false,
    ) {
        MinecraftScreenUtil.setScreen(
            Minecraft.getInstance(),
            SubtitleColorPickerScreen(this, title, getColor, setColor, allowOff),
        )
    }

    private fun cycleFont(delta: Int) {
        val values = SubtitleFontPreset.entries
        val current = SubtitleFontPreset.fromToken(settings.subtitleFont)
        val index = values.indexOf(current)
        val next = ((index + delta) % values.size + values.size) % values.size
        settings.subtitleFont = values[next].token
        ClientSettingsStore.save()
    }

    private fun resetAppearance() {
        displayScreen.subtitleScale = SubtitleStyleDefaults.SIZE
        displayScreen.subtitleVerticalPosition = SubtitleStyleDefaults.VERTICAL_POSITION
        settings.subtitleFont = SubtitleFontPreset.DEFAULT.token
        settings.subtitleTextColor = SubtitleStyleDefaults.TEXT_COLOR
        settings.subtitleOutlineColor = SubtitleStyleDefaults.OUTLINE_COLOR
        settings.subtitleBackgroundColor = SubtitleStyleDefaults.BACKGROUND_COLOR
        settings.subtitleBackgroundOpacity = SubtitleStyleDefaults.BACKGROUND_OPACITY
        ClientSettingsStore.save()
        syncControls()
    }

    private fun syncControls() {
        size.value = sizeToFraction(displayScreen.subtitleScale)
        backgroundOpacity.value = settings.subtitleBackgroundOpacity.toDouble().coerceIn(0.0, 1.0)
        verticalPosition.value = displayScreen.subtitleVerticalPosition.toDouble().coerceIn(0.0, 1.0)
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)
        enabled.syncToCurrent()

        val panelW = 500
        val panelH = 470
        val panelX = (width - panelW) / 2
        val panelY = (height - panelH) / 2
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xE0181818.toInt())
        g.drawText(font, title, panelX + (panelW - font.width(title)) / 2, panelY + 12, UiTheme.TEXT_PRIMARY, false)

        val preview = UiRect(panelX + 20, panelY + 34, panelW - 40, 90)
        drawLivePreview(g, preview)

        val rowX = panelX + 28
        val rowW = panelW - 56
        val labelW = 155
        val controlX = rowX + labelW
        val controlW = rowW - labelW
        var y = preview.bottom + 14

        drawLabel(g, "dreamdisplays.button.subtitles", rowX, y)
        enabled.place(UiRect(controlX, y - 5, controlW, UiTheme.ROW_H))
        y += 30

        drawLabel(g, "dreamdisplays.button.subtitle_size", rowX, y)
        size.place(UiRect(controlX, y - 5, controlW, UiTheme.ROW_H))
        y += 30

        drawLabel(g, "dreamdisplays.subtitle.font", rowX, y)
        val arrowW = 30
        fontPrev.place(UiRect(controlX, y - 5, arrowW, UiTheme.ROW_H))
        fontNext.place(UiRect(controlX + controlW - arrowW, y - 5, arrowW, UiTheme.ROW_H))
        val fontName = Component.translatable(SubtitleFontPreset.fromToken(settings.subtitleFont).labelKey)
        g.drawText(font, fontName, controlX + (controlW - font.width(fontName)) / 2, y, UiTheme.TEXT_PRIMARY, false)
        y += 30

        drawColorRow(g, "dreamdisplays.subtitle.text_color", rowX, controlX, controlW, y, settings.subtitleTextColor)
        textColorButton.place(UiRect(controlX + controlW - 90, y - 5, 90, UiTheme.ROW_H))
        y += 30

        drawColorRow(g, "dreamdisplays.subtitle.outline_color", rowX, controlX, controlW, y, settings.subtitleOutlineColor)
        outlineColorButton.place(UiRect(controlX + controlW - 90, y - 5, 90, UiTheme.ROW_H))
        y += 30

        drawColorRow(g, "dreamdisplays.subtitle.background_color", rowX, controlX, controlW, y, settings.subtitleBackgroundColor)
        backgroundColorButton.place(UiRect(controlX + controlW - 90, y - 5, 90, UiTheme.ROW_H))
        y += 30

        drawLabel(g, "dreamdisplays.subtitle.background_opacity", rowX, y)
        backgroundOpacity.place(UiRect(controlX, y - 5, controlW, UiTheme.ROW_H))
        y += 30

        drawLabel(g, "dreamdisplays.button.subtitle_position", rowX, y)
        verticalPosition.place(UiRect(controlX, y - 5, controlW, UiTheme.ROW_H))

        val note = Component.translatable("dreamdisplays.ui.subtitle_local_only")
        g.drawText(font, note, panelX + 28, panelY + panelH - 58, UiTheme.TEXT_SECONDARY, false)

        val buttonY = panelY + panelH - 36
        resetButton.place(UiRect(panelX + 28, buttonY, 120, UiTheme.CONTROL_BUTTON))
        backButton.place(UiRect(panelX + panelW - 148, buttonY, 120, UiTheme.CONTROL_BUTTON))

        drawChildren(g, mouseX, mouseY, partialTick)
    }

    private fun drawLabel(g: GuiGraphicsCompat, key: String, x: Int, y: Int) {
        g.drawText(font, Component.translatable(key), x, y, UiTheme.TEXT_PRIMARY, false)
    }

    private fun drawColorRow(
        g: GuiGraphicsCompat,
        key: String,
        rowX: Int,
        controlX: Int,
        controlW: Int,
        y: Int,
        color: Int,
    ) {
        drawLabel(g, key, rowX, y)
        val swatchX = controlX + 4
        val swatchY = y - 2
        val swatchColor = 0xFF000000.toInt() or (color and 0x00FFFFFF)
        g.fill(swatchX, swatchY, swatchX + 42, swatchY + 14, swatchColor)
        val value = if ((color ushr 24) == 0) {
            Component.translatable("dreamdisplays.subtitle.color.disabled")
        } else {
            Component.literal("#%06X".format(color and 0x00FFFFFF))
        }
        g.drawText(font, value, swatchX + 48, y, UiTheme.TEXT_SECONDARY, false)
    }

    /** Live preview uses the same selected font, text/outline colors and background opacity as world rendering. */
    private fun drawLivePreview(g: GuiGraphicsCompat, area: UiRect) {
        val current = settings
        g.fill(area.x, area.y, area.right, area.bottom, 0xFF080A0F.toInt())
        g.fill(area.x + 1, area.y + 1, area.right - 1, area.bottom - 1, 0xFF151922.toInt())

        val preset = SubtitleFontPreset.fromToken(current.subtitleFont)
        val text = Component.translatable("dreamdisplays.subtitle.preview").withStyle(preset.style())
        val scale = displayScreen.subtitleScale
            .coerceIn(SubtitleStyleDefaults.MIN_SIZE, SubtitleStyleDefaults.MAX_SIZE)
        val textWidth = font.width(text)
        val textColor = current.subtitleTextColor or 0xFF000000.toInt()
        val outline = current.subtitleOutlineColor
        val background = subtitleBackgroundArgb(current.subtitleBackgroundColor, current.subtitleBackgroundOpacity)
        val marginPx = 4 + (displayScreen.subtitleVerticalPosition.coerceIn(0f, 1f) * 22f).roundToInt()
        val centerX = area.x + area.w / 2
        val baselineY = area.bottom - 12 - marginPx

        val matrices = g.pose()
        //? if >=1.21.11 {
        matrices.pushMatrix()
        matrices.translate(centerX.toFloat(), baselineY.toFloat())
        matrices.scale(scale, scale)
        //?} else
        /*matrices.pushPose()
        matrices.translate(centerX.toDouble(), baselineY.toDouble(), 0.0)
        matrices.scale(scale, scale, 1f)
        */

        val tx = -textWidth / 2
        val ty = -font.lineHeight / 2
        if ((background ushr 24) != 0) {
            g.fill(tx - 3, ty - 2, tx + textWidth + 3, ty + font.lineHeight + 2, background)
        }
        if ((outline ushr 24) != 0) {
            for ((dx, dy) in PREVIEW_OUTLINE_OFFSETS) {
                g.drawText(font, text, tx + dx, ty + dy, outline, false)
            }
        }
        g.drawText(font, text, tx, ty, textColor, false)

        //? if >=1.21.11 {
        matrices.popMatrix()
        //?} else
        /*matrices.popPose()*/

        val caption = Component.translatable("dreamdisplays.subtitle.live_preview")
        g.drawText(font, caption, area.x + 6, area.y + 6, UiTheme.TEXT_META, false)
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        ClientSettingsStore.save()
        MinecraftScreenUtil.setScreen(Minecraft.getInstance(), parent)
    }

    private fun sizeToFraction(scale: Float): Double =
        ((scale.coerceIn(SubtitleStyleDefaults.MIN_SIZE, SubtitleStyleDefaults.MAX_SIZE) - SubtitleStyleDefaults.MIN_SIZE) /
            (SubtitleStyleDefaults.MAX_SIZE - SubtitleStyleDefaults.MIN_SIZE)).toDouble()

    private fun sizeFromFraction(fraction: Double): Float =
        SubtitleStyleDefaults.MIN_SIZE + fraction.toFloat().coerceIn(0f, 1f) *
            (SubtitleStyleDefaults.MAX_SIZE - SubtitleStyleDefaults.MIN_SIZE)

    companion object {
        private val PREVIEW_OUTLINE_OFFSETS = arrayOf(
            -1 to -1, 0 to -1, 1 to -1,
            -1 to 0, 1 to 0,
            -1 to 1, 0 to 1, 1 to 1,
        )
    }
}
'''
)


# 5) Reconnect the world renderer to the persisted personal style, including font, outline and
# an independent alpha-blended background plate.
renderer_path = "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/ScreenRenderer.kt"
renderer = read(renderer_path)
if "RenderTypes" not in renderer:
    renderer = renderer.replace(
        "import net.minecraft.client.renderer.rendertype.RenderType\n",
        "import net.minecraft.client.renderer.rendertype.RenderType\nimport net.minecraft.client.renderer.rendertype.RenderTypes\n",
        1,
    )
if "ClientSettingsStore" not in renderer:
    renderer = renderer.replace(
        "import com.dreamdisplays.platform.client.displays.DisplayScreen\n",
        "import com.dreamdisplays.platform.client.displays.DisplayScreen\n"
        "import com.dreamdisplays.platform.client.storage.ClientSettingsStore\n"
        "import com.dreamdisplays.platform.client.subtitles.SubtitleFontPreset\n"
        "import com.dreamdisplays.platform.client.subtitles.SubtitleStyleDefaults\n"
        "import com.dreamdisplays.platform.client.subtitles.subtitleBackgroundArgb\n",
        1,
    )
if "import net.minecraft.network.chat.Style" not in renderer:
    renderer = renderer.replace(
        "import net.minecraft.client.gui.Font\n",
        "import net.minecraft.client.gui.Font\nimport net.minecraft.network.chat.Style\nimport net.minecraft.util.FormattedCharSequence\n",
        1,
    )

renderer = re.sub(
    r'''fun interface WorldTextSubmitter \{.*?\n\}\n\n/\*\* Renders screens''',
    '''fun interface WorldTextSubmitter {
    fun submit(
        stack: PoseStack,
        text: FormattedCharSequence,
        x: Float,
        y: Float,
        color: Int,
        outlineColor: Int,
        mode: Font.DisplayMode,
        backgroundColor: Int,
        packedLight: Int,
    )
}

/** Renders screens''',
    renderer,
    count=1,
    flags=re.S,
)
renderer = renderer.replace(
    "if (!replay) renderSubtitle(displayScreen, stack, submitText)",
    "if (!replay) renderSubtitle(displayScreen, stack, submitText, drawQuad)",
    1,
)

subtitle_block = r'''    /** Subtitles occupy at most this fraction of the video width and lower-screen height. */
    private const val SUBTITLE_WIDTH_FRACTION = 0.88f
    private const val SUBTITLE_HEIGHT_FRACTION = 0.42f
    private const val SUBTITLE_MIN_BOTTOM_MARGIN = 0.03f
    private const val SUBTITLE_MAX_BOTTOM_MARGIN = 0.33f
    private const val SUBTITLE_LIFT = 0.20f
    private const val SUBTITLE_BACKGROUND_LIFT = 0.19f
    private const val SUBTITLE_BACKGROUND_PADDING_X = 4f
    private const val SUBTITLE_BACKGROUND_PADDING_Y = 2f

    private data class SubtitleLayout(
        val lines: List<String>,
        val lineAdvancePixels: Int,
        val textScaleX: Float,
        val textScaleY: Float,
    )

    /** Draws active WebVTT cues using this viewer's persisted personal appearance settings. */
    private fun renderSubtitle(
        displayScreen: DisplayScreen,
        stack: PoseStack,
        submitText: WorldTextSubmitter?,
        drawQuad: QuadRenderer,
    ) {
        if (!displayScreen.isVideoStarted || !displayScreen.subtitlesEnabled) return
        val rawLines = displayScreen.activeSubtitleLines
        if (rawLines.isEmpty()) return

        val minecraft = Minecraft.getInstance()
        val font = minecraft.font
        val personal = ClientSettingsStore.getSettings(displayScreen.uuid)
        val preset = SubtitleFontPreset.fromToken(personal.subtitleFont)
        val style = preset.style()
        val layout = buildSubtitleLayout(
            displayScreen,
            rawLines,
            font,
            style,
            displayScreen.subtitleScale
                .coerceIn(SubtitleStyleDefaults.MIN_SIZE, SubtitleStyleDefaults.MAX_SIZE),
        ) ?: return
        //? if >=26.2 {
        val subtitleSubmitter = submitText ?: return
        //?}

        val textColor = personal.subtitleTextColor or 0xFF000000.toInt()
        val outlineColor = personal.subtitleOutlineColor
        val backgroundColor = subtitleBackgroundArgb(
            personal.subtitleBackgroundColor,
            personal.subtitleBackgroundOpacity.coerceIn(0f, 1f),
        )
        val bottomMargin = SUBTITLE_MIN_BOTTOM_MARGIN +
            (SUBTITLE_MAX_BOTTOM_MARGIN - SUBTITLE_MIN_BOTTOM_MARGIN) *
            displayScreen.subtitleVerticalPosition.coerceIn(0f, 1f)
        val totalHeight = layout.lines.size * layout.lineAdvancePixels * layout.textScaleY

        if ((backgroundColor ushr 24) != 0) {
            val maxWidthPixels = layout.lines.maxOf { line ->
                font.width(FormattedCharSequence.forward(line, style))
            }.toFloat()
            val blockHeightPixels =
                ((layout.lines.size - 1) * layout.lineAdvancePixels + font.lineHeight).toFloat()

            stack.pushPose()
            DisplayGeometry.liftTowardViewer(stack, displayScreen.facing, SUBTITLE_BACKGROUND_LIFT)
            DisplayGeometry.applyScreenTransform(
                stack,
                displayScreen.facing,
                displayScreen.width,
                displayScreen.height,
            )
            stack.translate(0.5f, bottomMargin + totalHeight, 0f)
            stack.scale(layout.textScaleX, -layout.textScaleY, 1f)

            val x0 = -maxWidthPixels / 2f - SUBTITLE_BACKGROUND_PADDING_X
            val x1 = maxWidthPixels / 2f + SUBTITLE_BACKGROUND_PADDING_X
            val y0 = -SUBTITLE_BACKGROUND_PADDING_Y
            val y1 = blockHeightPixels + SUBTITLE_BACKGROUND_PADDING_Y
            drawQuad(subtitleBackgroundRenderType()) { pose, builder ->
                appendTextBackgroundRect(pose, builder, x0, y0, x1, y1, backgroundColor, 0xF000F0)
            }
            stack.popPose()
        }

        stack.pushPose()
        DisplayGeometry.liftTowardViewer(stack, displayScreen.facing, SUBTITLE_LIFT)
        DisplayGeometry.applyScreenTransform(
            stack,
            displayScreen.facing,
            displayScreen.width,
            displayScreen.height,
        )
        stack.translate(0.5f, bottomMargin + totalHeight, 0f)
        stack.scale(layout.textScaleX, -layout.textScaleY, 1f)

        //? if <26.2 {
        val buffers = minecraft.renderBuffers().bufferSource()
        //?}
        layout.lines.forEachIndexed { index, line ->
            val formatted = FormattedCharSequence.forward(line, style)
            val x = -font.width(formatted) / 2f
            val y = (index * layout.lineAdvancePixels).toFloat()
            //? if >=26.2 {
            subtitleSubmitter.submit(
                stack,
                formatted,
                x,
                y,
                textColor,
                outlineColor,
                Font.DisplayMode.NORMAL,
                0,
                0xF000F0,
            )
            //?} else
            /*
            if ((outlineColor ushr 24) != 0) {
                font.drawInBatch8xOutline(
                    formatted, x, y, textColor, outlineColor, stack.last().pose(), buffers, 0xF000F0,
                )
            } else {
                font.drawInBatch(
                    formatted, x, y, textColor, false, stack.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, 0xF000F0,
                )
            }
            */
        }
        //? if <26.2 {
        buffers.endBatch()
        //?}
        stack.popPose()
    }

    /** Returns Minecraft's alpha-capable text-background render type for the current mappings generation. */
    private fun subtitleBackgroundRenderType(): RenderType =
        //? if >=1.21.11 {
        RenderTypes.textBackground()
        //?} else
        /*RenderType.textBackground()*/

    /** Appends one alpha-blended text-background quad using the format expected by textBackground(). */
    private fun appendTextBackgroundRect(
        pose: PoseStack.Pose,
        builder: VertexConsumer,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: Int,
        packedLight: Int,
    ) {
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF

        fun vertex(x: Float, y: Float) {
            builder.addVertex(pose, x, y, 0f)
                .setColor(r, g, b, a)
                .setLight(packedLight)
        }

        vertex(x0, y0)
        vertex(x0, y1)
        vertex(x1, y1)
        vertex(x1, y0)
    }

    /**
     * Computes subtitle wrapping and scale. Oversized cues are scaled down to fit rather than cut.
     */
    private fun buildSubtitleLayout(
        displayScreen: DisplayScreen,
        rawLines: List<String>,
        font: Font,
        style: Style,
        requestedSize: Float,
    ): SubtitleLayout? {
        val lineAdvancePixels = font.lineHeight + 2
        var desiredLineHeightBlocks =
            (displayScreen.height * 0.05f).coerceIn(0.55f, 1.6f) * requestedSize
        var result: SubtitleLayout? = null

        repeat(10) {
            val worldScalePerPixel = desiredLineHeightBlocks / lineAdvancePixels.toFloat()
            val textScaleX = worldScalePerPixel / displayScreen.width.coerceAtLeast(1).toFloat()
            val textScaleY = worldScalePerPixel / displayScreen.height.coerceAtLeast(1).toFloat()
            val wrapPixels = floor(SUBTITLE_WIDTH_FRACTION / textScaleX).toInt().coerceIn(32, 8192)
            val lines = wrapSubtitleLines(rawLines, font, style, wrapPixels)
            if (lines.isEmpty()) return null

            result = SubtitleLayout(lines, lineAdvancePixels, textScaleX, textScaleY)
            val totalHeight = lines.size * lineAdvancePixels * textScaleY
            if (totalHeight <= SUBTITLE_HEIGHT_FRACTION) return result

            val fit = (SUBTITLE_HEIGHT_FRACTION / totalHeight).coerceIn(0.25f, 0.92f)
            desiredLineHeightBlocks *= fit
        }
        return result
    }

    /** Word-wraps cue lines using the selected font's actual metrics, including long-word splitting. */
    private fun wrapSubtitleLines(rawLines: List<String>, font: Font, style: Style, maxWidth: Int): List<String> {
        val output = ArrayList<String>()
        fun width(text: String): Int = font.width(FormattedCharSequence.forward(text, style))

        for (raw in rawLines) {
            var current = ""
            for (word in raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty)) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (width(candidate) <= maxWidth) {
                    current = candidate
                    continue
                }
                if (current.isNotEmpty()) {
                    output += current
                    current = ""
                }
                if (width(word) <= maxWidth) {
                    current = word
                } else {
                    var part = ""
                    for (character in word) {
                        val next = part + character
                        if (part.isNotEmpty() && width(next) > maxWidth) {
                            output += part
                            part = character.toString()
                        } else {
                            part = next
                        }
                    }
                    current = part
                }
            }
            if (current.isNotEmpty()) output += current
        }
        return output
    }
'''

renderer, count = re.subn(
    r'''    /\*\* Subtitles occupy at most this fraction of the video width and lower-screen height\. \*/.*?(?=    /\*\*\n     \* Loading / error placeholder\.)''',
    subtitle_block,
    renderer,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError(f"{renderer_path}: subtitle renderer block match count={count}")
write(renderer_path, renderer)


# 6) 26.2 deferred text submission must retain the selected font and render an eight-direction outline.
fabric_path = "platform/client/fabric/src/main/kotlin/com/dreamdisplays/platform/client/Client.kt"
fabric = read(fabric_path)
fabric = fabric.replace("import net.minecraft.network.chat.Style\n", "")
fabric = fabric.replace("import net.minecraft.util.FormattedCharSequence\n", "")
fabric_block = '''    //? if >=26.2 {
    /** Submits subtitle glyphs and an optional eight-direction outline to the deferred level collector. */
    private fun worldTextSubmitter(submitNodeCollector: Any) = WorldTextSubmitter {
            stack, text, x, y, color, outlineColor, mode, backgroundColor, packedLight ->
        val collector = submitNodeCollector as SubmitNodeCollector
        if ((backgroundColor ushr 24) != 0) {
            collector.submitText(stack, x, y, text, false, mode, packedLight, color, backgroundColor, 0)
        }
        if ((outlineColor ushr 24) != 0) {
            for ((dx, dy) in SUBTITLE_OUTLINE_OFFSETS) {
                collector.submitText(
                    stack, x + dx, y + dy, text, false, mode, packedLight, outlineColor, 0, 0,
                )
            }
        }
        collector.submitText(stack, x, y, text, false, mode, packedLight, color, 0, 0)
    }

    private val SUBTITLE_OUTLINE_OFFSETS = arrayOf(
        -1f to -1f, 0f to -1f, 1f to -1f,
        -1f to 0f, 1f to 0f,
        -1f to 1f, 0f to 1f, 1f to 1f,
    )
    //?}
    //?}

    /** Main camera accessor. */'''
fabric, count = re.subn(
    r'''    //\? if >=26\.2 \{\n    /\*\* Submits subtitle glyphs.*?    //\?\}\n    //\?\}\n\n    /\*\* Main camera accessor\. \*/''',
    fabric_block,
    fabric,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError(f"{fabric_path}: worldTextSubmitter block match count={count}")
write(fabric_path, fabric)


neoforge_path = "platform/client/neoforge/src/main/kotlin/com/dreamdisplays/platform/client/Client.kt"
neoforge = read(neoforge_path)
neoforge = neoforge.replace("import net.minecraft.network.chat.Style\n", "")
neoforge = neoforge.replace("import net.minecraft.util.FormattedCharSequence\n", "")
neoforge_render = '''        ScreenRenderer.render(
            event.poseStack,
            camera,
            submitText = WorldTextSubmitter { stack, text, x, y, color, outlineColor, mode, backgroundColor, packedLight ->
                if ((backgroundColor ushr 24) != 0) {
                    event.submitNodeCollector.submitText(
                        stack, x, y, text, false, mode, packedLight, color, backgroundColor, 0,
                    )
                }
                if ((outlineColor ushr 24) != 0) {
                    for ((dx, dy) in SUBTITLE_OUTLINE_OFFSETS) {
                        event.submitNodeCollector.submitText(
                            stack, x + dx, y + dy, text, false, mode, packedLight, outlineColor, 0, 0,
                        )
                    }
                }
                event.submitNodeCollector.submitText(
                    stack, x, y, text, false, mode, packedLight, color, 0, 0,
                )
            },
        )
    }

    private val SUBTITLE_OUTLINE_OFFSETS = arrayOf(
        -1f to -1f, 0f to -1f, 1f to -1f,
        -1f to 0f, 1f to 0f,
        -1f to 1f, 0f to 1f, 1f to 1f,
    )
    //?} else'''
neoforge, count = re.subn(
    r'''        ScreenRenderer\.render\(\n            event\.poseStack,\n            camera,\n            submitText = WorldTextSubmitter \{.*?        \)\n    \}\n    //\?\} else''',
    neoforge_render,
    neoforge,
    count=1,
    flags=re.S,
)
if count != 1:
    raise RuntimeError(f"{neoforge_path}: 26.2 render block match count={count}")
write(neoforge_path, neoforge)


# 7) Restore all user-facing labels in both languages without disturbing unrelated translations.
translations = {
    "platform/resources/src/main/resources/assets/dreamdisplays/lang/client/en_us.json": {
        "dreamdisplays.subtitle.live_preview": "Live preview",
        "dreamdisplays.subtitle.preview": "Subtitle preview",
        "dreamdisplays.subtitle.font": "Font",
        "dreamdisplays.subtitle.font.minecraft": "Minecraft",
        "dreamdisplays.subtitle.font.uniform": "Uniform",
        "dreamdisplays.subtitle.text_color": "Text color",
        "dreamdisplays.subtitle.outline_color": "Outline color",
        "dreamdisplays.subtitle.background_color": "Background color",
        "dreamdisplays.subtitle.background_opacity": "Background opacity",
        "dreamdisplays.subtitle.configure": "Configure",
        "dreamdisplays.subtitle.reset": "Reset appearance",
        "dreamdisplays.subtitle.color.off": "Turn off",
        "dreamdisplays.subtitle.color.disabled": "Off",
    },
    "platform/resources/src/main/resources/assets/dreamdisplays/lang/client/tr_tr.json": {
        "dreamdisplays.subtitle.live_preview": "Canlı önizleme",
        "dreamdisplays.subtitle.preview": "Altyazı önizlemesi",
        "dreamdisplays.subtitle.font": "Yazı tipi",
        "dreamdisplays.subtitle.font.minecraft": "Minecraft",
        "dreamdisplays.subtitle.font.uniform": "Uniform",
        "dreamdisplays.subtitle.text_color": "Yazı rengi",
        "dreamdisplays.subtitle.outline_color": "Kenarlık rengi",
        "dreamdisplays.subtitle.background_color": "Arka plan rengi",
        "dreamdisplays.subtitle.background_opacity": "Arka plan şeffaflığı",
        "dreamdisplays.subtitle.configure": "Ayarla",
        "dreamdisplays.subtitle.reset": "Görünümü sıfırla",
        "dreamdisplays.subtitle.color.off": "Kapat",
        "dreamdisplays.subtitle.color.disabled": "Kapalı",
    },
}
for path, additions in translations.items():
    data = json.loads(read(path))
    data.update(additions)
    write(path, json.dumps(data, ensure_ascii=False, indent=2) + "\n")


# Final structural assertions: these are intentionally strict so the workflow cannot silently produce
# a half-restored UI/renderer.
checks = {
    api_path: ["subtitleFont", "subtitleTextColor", "subtitleOutlineColor", "subtitleBackgroundColor", "subtitleBackgroundOpacity"],
    renderer_path: ["ClientSettingsStore.getSettings", "subtitleBackgroundRenderType", "drawInBatch8xOutline", "SubtitleFontPreset"],
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/SubtitlePreferencesMenu.kt": [
        "backgroundOpacity", "textColorButton", "outlineColorButton", "backgroundColorButton", "drawLivePreview"
    ],
}
for path, needles in checks.items():
    text = read(path)
    missing = [needle for needle in needles if needle not in text]
    if missing:
        raise RuntimeError(f"{path}: missing expected restored features: {missing}")

print("Full personal subtitle appearance patch applied successfully.")
