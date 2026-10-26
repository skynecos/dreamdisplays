package com.dreamdisplays.platform.client.render

//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.storage.ClientSettingsStore
import com.dreamdisplays.platform.client.subtitles.SubtitleFontPreset
import com.dreamdisplays.platform.client.subtitles.SubtitleStyleDefaults
import com.dreamdisplays.platform.client.subtitles.subtitleBackgroundArgb
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
//? if >=26.2 {
import net.minecraft.client.renderer.SubmitNodeCollector
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import kotlin.math.floor

/** Version-neutral bridge for submitting world-space text through the active level renderer. */
fun interface WorldTextSubmitter {
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

/** One glyph layer for provider captions and externally supplied WebVTT. */
internal object PersonalSubtitleRenderer {
    private typealias QuadRenderer = (RenderType, (PoseStack.Pose, VertexConsumer) -> Unit) -> Unit
    /** Subtitles occupy at most this fraction of the video width and lower-screen height. */
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
    fun render(
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
            DisplayPoseStack.liftTowardViewer(stack, displayScreen.facing, SUBTITLE_BACKGROUND_LIFT)
            DisplayPoseStack.applyScreenTransform(
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
        DisplayPoseStack.liftTowardViewer(stack, displayScreen.facing, SUBTITLE_LIFT)
        DisplayPoseStack.applyScreenTransform(
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
            for (word in raw.trim().split(' ', '\t').filter(String::isNotEmpty)) {
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
}

//? if >=26.2 {
/** Uses the active level collector so glyphs participate in the same render frame as the display. */
fun subtitleTextSubmitter(collector: SubmitNodeCollector) = WorldTextSubmitter {
        stack, text, x, y, color, outlineColor, mode, backgroundColor, packedLight ->
    if ((backgroundColor ushr 24) != 0) {
        collector.submitText(stack, x, y, text, false, mode, packedLight, color, backgroundColor, 0)
    }
    if ((outlineColor ushr 24) != 0) {
        for ((dx, dy) in arrayOf(-1f to -1f, 0f to -1f, 1f to -1f, -1f to 0f, 1f to 0f, -1f to 1f, 0f to 1f, 1f to 1f)) {
            collector.submitText(stack, x + dx, y + dy, text, false, mode, packedLight, outlineColor, 0, 0)
        }
    }
    collector.submitText(stack, x, y, text, false, mode, packedLight, color, 0, 0)
}
//?}
