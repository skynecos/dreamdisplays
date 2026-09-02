package com.dreamdisplays.platform.client.ui.widgets

import com.dreamdisplays.platform.client.ui.GuiGraphicsCompat
import com.dreamdisplays.platform.client.ui.kit.UiWidget
//? if >=1.21.11 {
import com.mojang.blaze3d.platform.cursor.CursorTypes
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.WidgetSprites
//? if >=1.21.11 {
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
//?}
import net.minecraft.network.chat.Component
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/** Vanilla-styled text button used by Dream Displays' custom screens. */
class TextButton(
    message: Component,
    private val sprites: WidgetSprites = DEFAULT_SPRITES,
    private val onPress: () -> Unit,
) : UiWidget(message) {

    override fun handlesWholeWidgetCursor(): Boolean = false

    //? if >=1.21.11 {
    override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
        onPress()
        playDownSound(Minecraft.getInstance().soundManager)
    }
    //?} else
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isValidClickButton(button) || !clicked(mouseX, mouseY)) return false
        onPress()
        playDownSound(Minecraft.getInstance().soundManager)
        return true
    }*/

    override fun draw(g: GuiGraphicsCompat, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (width <= 0 || height <= 0) return
        val sprite = sprites.get(active, isHoveredOrFocused)
        //? if >=1.21.11 {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, ARGB.white(alpha))
        //?} else
        /*g.blitSprite(sprite, x, y, width, height)*/

        val color = if (active) 0xFFFFFF else 0xA0A0A0
        drawScrollingLabel(g, message.copy().withStyle { it.withColor(color) }, 4)

        //? if >=1.21.11 {
        if (isHovered) {
            g.requestCursor(if (active) CursorTypes.POINTING_HAND else CursorTypes.NOT_ALLOWED)
        }
        //?}
    }

    companion object {
        val DEFAULT_SPRITES = WidgetSprites(
            Identifier.withDefaultNamespace("widget/button"),
            Identifier.withDefaultNamespace("widget/button_disabled"),
            Identifier.withDefaultNamespace("widget/button_highlighted"),
        )
    }
}
