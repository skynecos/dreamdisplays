package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.subtitles.SubtitleFontPreset
import com.dreamdisplays.platform.client.subtitles.SubtitleStyleDefaults
import com.dreamdisplays.platform.client.subtitles.subtitleBackgroundArgb
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.widgets.TextButton
import com.dreamdisplays.platform.client.ui.widgets.ValueSlider
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/** Anizium-style viewer-local subtitle appearance editor. Every change is reflected by the world renderer immediately. */
internal class SubtitleSettingsScreen(private val parent: Screen) :
    UiScreenBase(Component.translatable("dreamdisplays.subtitle.settings.title")) {

    private lateinit var sizeSlider: ValueSlider
    private lateinit var opacitySlider: ValueSlider
    private lateinit var marginSlider: ValueSlider
    private lateinit var fontPrev: TextButton
    private lateinit var fontNext: TextButton
    private lateinit var textColorButton: TextButton
    private lateinit var outlineColorButton: TextButton
    private lateinit var backgroundColorButton: TextButton
    private lateinit var resetButton: TextButton
    private lateinit var backButton: TextButton

    override fun minContentSize(): Pair<Int, Int> = 560 to 500

    override fun init() {
        super.init()
        val config = ClientStateManager.config

        sizeSlider = addUi(ValueSlider(
            initial = sizeToFraction(config.subtitleSize),
            label = { f -> Component.literal("${(fractionToSize(f) * 100.0).roundToInt()}%") },
            step = 0.01,
        ) { f -> config.subtitleSize = fractionToSize(f) })

        opacitySlider = addUi(ValueSlider(
            initial = config.subtitleBackgroundOpacity.coerceIn(0.0, 1.0),
            label = { f -> Component.literal("${(f * 100.0).roundToInt()}%") },
            step = 0.01,
        ) { f -> config.subtitleBackgroundOpacity = f.coerceIn(0.0, 1.0) })

        marginSlider = addUi(ValueSlider(
            initial = marginToFraction(config.subtitleBottomMargin),
            label = { f -> Component.literal("${(fractionToMargin(f) * 100.0).roundToInt()}%") },
            step = 0.01,
        ) { f -> config.subtitleBottomMargin = fractionToMargin(f) })

        fontPrev = addUi(TextButton(Component.literal("‹")) { cycleFont(-1) })
        fontNext = addUi(TextButton(Component.literal("›")) { cycleFont(1) })

        textColorButton = addUi(TextButton(Component.translatable("dreamdisplays.subtitle.configure")) {
            Minecraft.getInstance().setScreen(
                SubtitleColorPickerScreen(
                    this,
                    Component.translatable("dreamdisplays.subtitle.text_color"),
                    { config.subtitleTextColor },
                    { config.subtitleTextColor = it or 0xFF000000.toInt() },
                )
            )
        })

        outlineColorButton = addUi(TextButton(Component.translatable("dreamdisplays.subtitle.configure")) {
            Minecraft.getInstance().setScreen(
                SubtitleColorPickerScreen(
                    this,
                    Component.translatable("dreamdisplays.subtitle.outline_color"),
                    { config.subtitleOutlineColor },
                    { config.subtitleOutlineColor = it },
                    allowOff = true,
                )
            )
        })

        backgroundColorButton = addUi(TextButton(Component.translatable("dreamdisplays.subtitle.configure")) {
            Minecraft.getInstance().setScreen(
                SubtitleColorPickerScreen(
                    this,
                    Component.translatable("dreamdisplays.subtitle.background_color"),
                    { config.subtitleBackgroundColor },
                    { config.subtitleBackgroundColor = it or 0xFF000000.toInt() },
                )
            )
        })

        resetButton = addUi(TextButton(Component.translatable("dreamdisplays.subtitle.reset")) {
            config.resetSubtitleStyle()
            syncControlsFromConfig()
        })
        backButton = addUi(TextButton(Component.translatable("gui.back")) { onClose() })
    }

    private fun cycleFont(delta: Int) {
        val values = SubtitleFontPreset.entries
        val current = SubtitleFontPreset.fromToken(ClientStateManager.config.subtitleFont)
        val index = values.indexOf(current)
        val next = (index + delta).floorMod(values.size)
        ClientStateManager.config.subtitleFont = values[next].token
    }

    private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

    private fun syncControlsFromConfig() {
        val config = ClientStateManager.config
        sizeSlider.value = sizeToFraction(config.subtitleSize)
        opacitySlider.value = config.subtitleBackgroundOpacity
        marginSlider.value = marginToFraction(config.subtitleBottomMargin)
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)
        val panelW = 500
        val panelH = 450
        val panelX = (width - panelW) / 2
        val panelY = (height - panelH) / 2
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xE0181818.toInt())
        g.drawText(font, title, panelX + (panelW - font.width(title)) / 2, panelY + 12, UiTheme.TEXT_PRIMARY, false)

        val preview = UiRect(panelX + 20, panelY + 34, panelW - 40, 104)
        drawLivePreview(g, preview)

        val rowX = panelX + 28
        val rowW = panelW - 56
        val labelW = 155
        val controlX = rowX + labelW
        val controlW = rowW - labelW
        var y = preview.bottom + 14

        drawLabel(g, "dreamdisplays.subtitle.size", rowX, y)
        sizeSlider.place(UiRect(controlX, y - 5, controlW, UiTheme.ROW_H))
        y += 32

        drawLabel(g, "dreamdisplays.subtitle.font", rowX, y)
        val arrowW = 30
        fontPrev.place(UiRect(controlX, y - 5, arrowW, UiTheme.ROW_H))
        fontNext.place(UiRect(controlX + controlW - arrowW, y - 5, arrowW, UiTheme.ROW_H))
        val preset = SubtitleFontPreset.fromToken(ClientStateManager.config.subtitleFont)
        val fontName = Component.translatable(preset.labelKey)
        g.drawText(
            font,
            fontName,
            controlX + (controlW - font.width(fontName)) / 2,
            y,
            UiTheme.TEXT_PRIMARY,
            false,
        )
        y += 32

        drawColorRow(g, "dreamdisplays.subtitle.text_color", rowX, controlX, controlW, y, ClientStateManager.config.subtitleTextColor)
        textColorButton.place(UiRect(controlX + controlW - 90, y - 5, 90, UiTheme.ROW_H))
        y += 32

        drawColorRow(g, "dreamdisplays.subtitle.outline_color", rowX, controlX, controlW, y, ClientStateManager.config.subtitleOutlineColor)
        outlineColorButton.place(UiRect(controlX + controlW - 90, y - 5, 90, UiTheme.ROW_H))
        y += 32

        drawColorRow(g, "dreamdisplays.subtitle.background_color", rowX, controlX, controlW, y, ClientStateManager.config.subtitleBackgroundColor)
        backgroundColorButton.place(UiRect(controlX + controlW - 90, y - 5, 90, UiTheme.ROW_H))
        y += 32

        drawLabel(g, "dreamdisplays.subtitle.background_opacity", rowX, y)
        opacitySlider.place(UiRect(controlX, y - 5, controlW, UiTheme.ROW_H))
        y += 32

        drawLabel(g, "dreamdisplays.subtitle.bottom_margin", rowX, y)
        marginSlider.place(UiRect(controlX, y - 5, controlW, UiTheme.ROW_H))

        val buttonY = panelY + panelH - 36
        resetButton.place(UiRect(panelX + 28, buttonY, 120, UiTheme.CONTROL_BUTTON))
        backButton.place(UiRect(panelX + panelW - 148, buttonY, 120, UiTheme.CONTROL_BUTTON))

        drawChildren(g, mouseX, mouseY, partialTick)
    }

    private fun drawLabel(g: GuiGraphicsCompat, key: String, x: Int, y: Int) {
        g.drawText(font, Component.translatable(key), x, y, UiTheme.TEXT_PRIMARY, false)
    }

    private fun drawColorRow(g: GuiGraphicsCompat, key: String, rowX: Int, controlX: Int, controlW: Int, y: Int, color: Int) {
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

    /** Draws a small video-like plate whose subtitle uses the exact current font/color/background controls. */
    private fun drawLivePreview(g: GuiGraphicsCompat, area: UiRect) {
        val config = ClientStateManager.config
        g.fill(area.x, area.y, area.right, area.bottom, 0xFF080A0F.toInt())
        g.fill(area.x + 1, area.y + 1, area.right - 1, area.bottom - 1, 0xFF151922.toInt())

        val preset = SubtitleFontPreset.fromToken(config.subtitleFont)
        val text = Component.translatable("dreamdisplays.subtitle.preview").withStyle(preset.style())
        val scale = config.subtitleSize
            .coerceIn(SubtitleStyleDefaults.MIN_SIZE, SubtitleStyleDefaults.MAX_SIZE)
            .toFloat()
        val textWidth = font.width(text)
        val textColor = config.subtitleTextColor or 0xFF000000.toInt()
        val outline = config.subtitleOutlineColor
        val background = subtitleBackgroundArgb(config.subtitleBackgroundColor, config.subtitleBackgroundOpacity)
        val marginFraction = config.subtitleBottomMargin
            .coerceIn(SubtitleStyleDefaults.MIN_BOTTOM_MARGIN, SubtitleStyleDefaults.MAX_BOTTOM_MARGIN)
        val marginPx = 4 + ((marginFraction - SubtitleStyleDefaults.MIN_BOTTOM_MARGIN) /
            (SubtitleStyleDefaults.MAX_BOTTOM_MARGIN - SubtitleStyleDefaults.MIN_BOTTOM_MARGIN) * 22.0).roundToInt()
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
            val outlineColor = outline
            for ((dx, dy) in PREVIEW_OUTLINE_OFFSETS) {
                g.drawText(font, text, tx + dx, ty + dy, outlineColor, false)
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
        ClientStateManager.config.save()
        Minecraft.getInstance().setScreen(parent)
    }

    private fun sizeToFraction(size: Double): Double =
        ((size - SubtitleStyleDefaults.MIN_SIZE) / (SubtitleStyleDefaults.MAX_SIZE - SubtitleStyleDefaults.MIN_SIZE))
            .coerceIn(0.0, 1.0)

    private fun fractionToSize(fraction: Double): Double =
        SubtitleStyleDefaults.MIN_SIZE +
            fraction.coerceIn(0.0, 1.0) * (SubtitleStyleDefaults.MAX_SIZE - SubtitleStyleDefaults.MIN_SIZE)

    private fun marginToFraction(margin: Double): Double =
        ((margin - SubtitleStyleDefaults.MIN_BOTTOM_MARGIN) /
            (SubtitleStyleDefaults.MAX_BOTTOM_MARGIN - SubtitleStyleDefaults.MIN_BOTTOM_MARGIN)).coerceIn(0.0, 1.0)

    private fun fractionToMargin(fraction: Double): Double =
        SubtitleStyleDefaults.MIN_BOTTOM_MARGIN +
            fraction.coerceIn(0.0, 1.0) * (SubtitleStyleDefaults.MAX_BOTTOM_MARGIN - SubtitleStyleDefaults.MIN_BOTTOM_MARGIN)

    companion object {
        private val PREVIEW_OUTLINE_OFFSETS = arrayOf(
            -1 to -1, 0 to -1, 1 to -1,
            -1 to 0, 1 to 0,
            -1 to 1, 0 to 1, 1 to 1,
        )
    }
}
