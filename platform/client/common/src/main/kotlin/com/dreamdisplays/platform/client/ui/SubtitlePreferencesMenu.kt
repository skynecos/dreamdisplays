package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.kit.UiWidget
import com.dreamdisplays.platform.client.ui.kit.drawPanel
import com.dreamdisplays.platform.client.ui.widgets.ModeSlider
import com.dreamdisplays.platform.client.ui.widgets.ValueSlider
import com.dreamdisplays.platform.client.utils.MinecraftScreenUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * Viewer-safe menu. Every value is persisted in client-display-settings.json and never sent to the
 * server, so one viewer cannot change the video, playback state, display, or another viewer's subtitles.
 */
class SubtitlePreferencesMenu(
    private val displayScreen: DisplayScreen,
    private val parent: Screen? = null,
) : UiScreenBase(Component.translatable("dreamdisplays.ui.subtitle_preferences")) {
    private lateinit var enabled: ModeSlider<Boolean>
    private lateinit var size: ValueSlider
    private lateinit var verticalPosition: ValueSlider

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
                initial = scaleToFraction(displayScreen.subtitleScale),
                label = { Component.literal("${(scaleFromFraction(it) * 100f).roundToInt()}%") },
                live = false,
                step = 0.05,
            ) { displayScreen.subtitleScale = scaleFromFraction(it) },
        )

        verticalPosition = addUi(
            ValueSlider(
                initial = displayScreen.subtitleVerticalPosition.toDouble(),
                label = { Component.literal("${(it * 100.0).roundToInt()}%") },
                live = false,
                step = 0.05,
            ) { displayScreen.subtitleVerticalPosition = it.toFloat() },
        )
    }

    override fun drawScreen(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawScreenBackground(g)
        enabled.syncToCurrent()

        val panel = UiRect((width - PANEL_W) / 2, (height - PANEL_H) / 2, PANEL_W, PANEL_H)
        g.drawPanel(font, panel, Component.translatable("dreamdisplays.ui.subtitle_preferences").string)

        var y = panel.y + UiTheme.PANEL_PADDING_Y + font.lineHeight + 8
        drawRow(g, panel, y, Component.translatable("dreamdisplays.button.subtitles"), enabled)
        y += UiTheme.ROW_H + UiTheme.ROW_GAP
        drawRow(g, panel, y, Component.translatable("dreamdisplays.button.subtitle_size"), size)
        y += UiTheme.ROW_H + UiTheme.ROW_GAP
        drawRow(
            g,
            panel,
            y,
            Component.translatable("dreamdisplays.button.subtitle_position"),
            verticalPosition,
        )

        val note = Component.translatable("dreamdisplays.ui.subtitle_local_only")
        g.drawText(
            font,
            note,
            panel.x + UiTheme.PANEL_PADDING_X,
            panel.bottom - UiTheme.PANEL_PADDING_Y - font.lineHeight,
            UiTheme.TEXT_SECONDARY,
            false,
        )
        drawChildren(g, mouseX, mouseY, partialTick)
    }

    private fun drawRow(g: GuiGraphicsCompat, panel: UiRect, y: Int, label: Component, control: UiWidget) {
        val x = panel.x + UiTheme.PANEL_PADDING_X
        val w = panel.w - UiTheme.PANEL_PADDING_X * 2
        g.fill(x, y, x + w, y + UiTheme.ROW_H, UiTheme.ROW_BG)
        g.drawText(
            Minecraft.getInstance().font,
            label,
            x + 6,
            y + (UiTheme.ROW_H - font.lineHeight) / 2,
            UiTheme.TEXT_PRIMARY,
            false,
        )
        control.place(UiRect(x + w - CONTROL_W, y, CONTROL_W, UiTheme.ROW_H))
    }

    override fun minContentSize(): Pair<Int, Int> = MIN_W to MIN_H

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        MinecraftScreenUtil.setScreen(Minecraft.getInstance(), parent)
    }

    companion object {
        private const val PANEL_W = 400
        private const val PANEL_H = 156
        private const val CONTROL_W = 150
        private const val MIN_W = 420
        private const val MIN_H = 210

        private fun scaleToFraction(scale: Float): Double =
            ((scale.coerceIn(MIN_SCALE, MAX_SCALE) - MIN_SCALE) / (MAX_SCALE - MIN_SCALE)).toDouble()

        private fun scaleFromFraction(fraction: Double): Float =
            MIN_SCALE + fraction.toFloat().coerceIn(0f, 1f) * (MAX_SCALE - MIN_SCALE)

        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 2.0f
    }
}
