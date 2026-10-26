package com.dreamdisplays.platform.client.ui

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
