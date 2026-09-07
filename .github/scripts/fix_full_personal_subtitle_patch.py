from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


# The rich subtitle branch depended on this small vanilla-styled text widget; the newer personal
# subtitle branch never carried it over.
write(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/widgets/TextButton.kt",
    '''package com.dreamdisplays.platform.client.ui.widgets

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
'''
)

renderer_path = "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/ScreenRenderer.kt"
renderer = read(renderer_path)

# The first patch's substring guard saw DisplayYuvRenderTypes and incorrectly assumed the RenderTypes
# import already existed. Guard the exact import instead.
exact_import = "import net.minecraft.client.renderer.rendertype.RenderTypes\n"
if exact_import not in renderer:
    anchor = "import net.minecraft.client.renderer.rendertype.RenderType\n"
    if anchor not in renderer:
        raise RuntimeError("ScreenRenderer: RenderType import anchor missing")
    renderer = renderer.replace(anchor, anchor + exact_import, 1)

# Avoid regex escaping differences after Stonecutter preprocessing; this preserves normal space/tab
# word splitting without any Kotlin string escape ambiguity.
renderer = renderer.replace(
    'raw.trim().split(Regex("\\\\s+")).filter(String::isNotEmpty)',
    "raw.trim().split(' ', '\\t').filter(String::isNotEmpty)",
)
renderer = renderer.replace(
    'raw.trim().split(Regex("\\s+")).filter(String::isNotEmpty)',
    "raw.trim().split(' ', '\\t').filter(String::isNotEmpty)",
)
write(renderer_path, renderer)

# Avoid relying on a smart cast of a mutable nullable widget property.
picker_path = "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/SubtitleColorPickerScreen.kt"
picker = read(picker_path)
picker = picker.replace(
    '''        if (off != null) {
            off!!.place(UiRect(panelX + 24, buttonY, 100, UiTheme.CONTROL_BUTTON))
            back.place(UiRect(panelX + panelW - 124, buttonY, 100, UiTheme.CONTROL_BUTTON))
        } else {
''',
    '''        val offButton = off
        if (offButton != null) {
            offButton.place(UiRect(panelX + 24, buttonY, 100, UiTheme.CONTROL_BUTTON))
            back.place(UiRect(panelX + panelW - 124, buttonY, 100, UiTheme.CONTROL_BUTTON))
        } else {
''',
)
write(picker_path, picker)

# Strict sanity checks.
if exact_import not in read(renderer_path):
    raise RuntimeError("RenderTypes import repair failed")
if "class TextButton" not in read("platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/ui/widgets/TextButton.kt"):
    raise RuntimeError("TextButton restoration failed")
if 'Regex("\\s+")' in read(renderer_path):
    raise RuntimeError("Unsupported regex escape still present")

print("Compatibility corrections applied.")
