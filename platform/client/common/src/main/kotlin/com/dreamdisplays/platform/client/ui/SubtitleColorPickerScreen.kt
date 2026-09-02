package com.dreamdisplays.platform.client.ui

import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.ui.kit.UiRect
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase
import com.dreamdisplays.platform.client.ui.kit.UiTheme
import com.dreamdisplays.platform.client.ui.widgets.TextButton
import com.dreamdisplays.platform.client.ui.widgets.ValueSlider
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/** Full-RGB picker used by subtitle text, outline and background colors. Changes are live. */
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

    override fun onClose() {
        ClientStateManager.config.save()
        Minecraft.getInstance().setScreen(parent)
    }
}
