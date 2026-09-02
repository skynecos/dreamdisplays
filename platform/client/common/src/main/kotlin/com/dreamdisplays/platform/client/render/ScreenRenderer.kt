package com.dreamdisplays.platform.client.render

//? if >=26 {
//?} else
/*import com.mojang.blaze3d.systems.RenderSystem*/
//? if >=26.2 {
//?} else
/*import com.mojang.blaze3d.vertex.Tesselator*/
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderType
//?} else
/*import net.minecraft.client.renderer.RenderType*/
import com.dreamdisplays.api.display.model.property.DisplayRotation
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.display.model.property.DisplayId
import com.dreamdisplays.api.render.backend.service.RenderContext
import com.dreamdisplays.api.render.texture.model.TextureHandle
import com.dreamdisplays.api.runtime.registry.service.getOrNull
import com.dreamdisplays.platform.client.core.DreamServices
import com.dreamdisplays.platform.client.displays.DisplayRegistry
import com.dreamdisplays.platform.client.displays.DisplayScreen
import com.dreamdisplays.platform.client.managers.ClientStateManager
import com.dreamdisplays.platform.client.render.ScreenRenderer.drawLayer
import com.dreamdisplays.platform.client.subtitles.SubtitleFontPreset
import com.dreamdisplays.platform.client.subtitles.SubtitleStyleDefaults
import com.dreamdisplays.platform.client.subtitles.subtitleBackgroundArgb
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import kotlin.math.sin

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

/** Renders screens in the world. Better not to touch this shit. */
object ScreenRenderer : ClientRenderService {
    private typealias QuadAppender = (PoseStack.Pose, VertexConsumer) -> Unit
    private typealias QuadRenderer = (RenderType, QuadAppender) -> Unit

    /**
     * Iterates all registered screens and renders each one relative to [camera]. Pass `replay = true`
     * for [UnshadedDisplayPass]'s second draw of a frame already on screen.
     */
    fun render(stack: PoseStack, camera: Camera, replay: Boolean = false) {
        render(stack, camera, replay, submitText = null) { type, appendVertices ->
            drawImmediate(stack, type, appendVertices)
        }
    }

    /** Immediate-quad render entry point with a platform-owned text submission bridge. */
    fun render(
        stack: PoseStack,
        camera: Camera,
        replay: Boolean = false,
        submitText: WorldTextSubmitter,
    ) {
        render(stack, camera, replay, submitText) { type, appendVertices ->
            drawImmediate(stack, type, appendVertices)
        }
    }

    /**
     * [ClientRenderService] render entry point. Unwraps a [MinecraftRenderContext] and delegates to
     * [render]; any other [RenderContext] type is a no-op (this renderer can only draw through a live
     * [PoseStack] / [Camera]).
     */
    override fun renderAll(context: RenderContext) {
        val ctx = context as? MinecraftRenderContext ?: return
        render(ctx.stack, ctx.camera)
    }

    /** No-op: live screens are created by [DisplayRegistry] from network packets, not flat entries. */
    override fun registerDisplay(entry: DisplayRenderEntry) = Unit

    /** Unregisters the live screen matching [displayId], delegating to [DisplayRegistry]. */
    override fun unregisterDisplay(displayId: DisplayId) {
        DisplayRegistry.getScreens()
            .firstOrNull { it.uuid == displayId.uuid }
            ?.let { DisplayRegistry.unregisterScreen(it) }
    }

    /** No-op: each [DisplayScreen] owns its own GPU texture lifecycle in the current model. */
    override fun updateTexture(displayId: DisplayId, handle: TextureHandle) = Unit

    /** Number of live screens with an uploaded texture. Those this renderer will actually draw. */
    override val registeredCount: Int; get() = DisplayRegistry.getScreens().count { it.hasTexture }

    /** Iterates all registered screens and lets the caller submit quads through the active renderer. */
    fun render(
        stack: PoseStack,
        camera: Camera,
        replay: Boolean = false,
        submitText: WorldTextSubmitter? = null,
        drawQuad: QuadRenderer,
    ) {
        val cameraPos =
            //? if >=1.21.11 {
            camera.position()
        //?} else
        /*camera.getPosition()*/
        for (displayScreen in DisplayRegistry.getScreens()) {
            if (displayScreen.isDormant || !displayScreen.hasTexture) continue

            stack.pushPose()

            val pos = displayScreen.pos
            val screenCenter = Vec3.atLowerCornerOf(pos)
            val relativePos = screenCenter.subtract(cameraPos)
            stack.translate(relativePos.x, relativePos.y, relativePos.z)

            renderScreenTexture(displayScreen, stack, replay, submitText, drawQuad)

            stack.popPose()
        }

        // The registered RenderHook extends the world pass after the mod's own screens.
        // ClientRenderModule installs the default hook for API-registered surfaces.
        // The world render hooks do not surface a partial tick, hence the 0f tickDelta.
        // Only on the level-pass draw: the replay would run every surface a second time.
        if (!replay) DreamServices.registry.getOrNull<RenderHook>()
            ?.onRender(MinecraftRenderContext(stack, camera, 0f))
    }

    /** Distance the [UnshadedDisplayPass] replay is lifted off the level-pass quad it repeats, in blocks. */
    private const val REPLAY_LIFT = 0.01f

    /** Translates and rotates the pose for [displayScreen]'s facing direction, then renders the video or fallback color. */
    private fun renderScreenTexture(
        displayScreen: DisplayScreen,
        stack: PoseStack,
        replay: Boolean,
        submitText: WorldTextSubmitter?,
        drawQuad: QuadRenderer,
    ) {
        if (!replay) displayScreen.fitTexture()

        val facing = displayScreen.facing
        val w = displayScreen.width
        val h = displayScreen.height
        val lift = if (replay) REPLAY_LIFT else 0f

        if (displayScreen.isVideoStarted && displayScreen.hasTexture && displayScreen.renderType != null) {
            drawLayer(stack, facing, w, h, lift) {
                renderGpuTexture(drawQuad, displayScreen)
            }
        } else {
            renderPlaceholder(
                stack,
                drawQuad,
                DisplayYuvRenderTypes.solidColorType(),
                facing,
                w,
                h,
                displayScreen.errored,
                lift,
            )
        }
        if (!replay) renderSubtitle(displayScreen, stack, submitText)
    }

    /** Draws a unit quad using the screen's GPU texture, ramping up the first-appear fade. */
    private fun renderGpuTexture(drawQuad: QuadRenderer, displayScreen: DisplayScreen) {
        val appear = displayScreen.appearProgress()
        val base = if (displayScreen.isYuvTexture) displayScreen.brightness.coerceIn(0f, 1f) * 255f else 255f
        val c = (base * appear).toInt().coerceIn(0, 255)
        drawQuad(displayScreen.renderType!!) { pose, builder ->
            appendQuad(pose, builder, c, c, c, displayScreen.rotation)
        }
    }

    /** Depth-layer spacing between placeholder elements, in blocks toward the viewer (see [drawLayer]). */
    private const val OVERLAY_LIFT = 0.01f

    /** Subtitles occupy at most this fraction of the video width and lower-screen height. */
    private const val SUBTITLE_WIDTH_FRACTION = 0.88f
    private const val SUBTITLE_HEIGHT_FRACTION = 0.42f
    private const val SUBTITLE_LIFT = 0.20f

    private data class SubtitleLayout(
        val lines: List<String>,
        val lineAdvancePixels: Int,
        val textScaleX: Float,
        val textScaleY: Float,
    )

    /** Draws active WebVTT cues in world space, slightly in front of the video plane. */
    private fun renderSubtitle(
        displayScreen: DisplayScreen,
        stack: PoseStack,
        submitText: WorldTextSubmitter?,
    ) {
        if (!displayScreen.isVideoStarted) return
        val rawLines = displayScreen.activeSubtitleLines
        if (rawLines.isEmpty()) return

        val minecraft = Minecraft.getInstance()
        val font = minecraft.font
        val config = ClientStateManager.config
        val preset = SubtitleFontPreset.fromToken(config.subtitleFont)
        val style = preset.style()
        val layout = buildSubtitleLayout(
            displayScreen,
            rawLines,
            font,
            style,
            config.subtitleSize.coerceIn(SubtitleStyleDefaults.MIN_SIZE, SubtitleStyleDefaults.MAX_SIZE).toFloat(),
        ) ?: return
        //? if >=26.2 {
        val subtitleSubmitter = submitText ?: return
        //?}

        val textColor = config.subtitleTextColor or 0xFF000000.toInt()
        val outlineColor = config.subtitleOutlineColor
        val backgroundColor = subtitleBackgroundArgb(config.subtitleBackgroundColor, config.subtitleBackgroundOpacity)
        val bottomMargin = config.subtitleBottomMargin
            .coerceIn(SubtitleStyleDefaults.MIN_BOTTOM_MARGIN, SubtitleStyleDefaults.MAX_BOTTOM_MARGIN)
            .toFloat()
        val totalHeight = layout.lines.size * layout.lineAdvancePixels * layout.textScaleY

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
                backgroundColor,
                0xF000F0,
            )
            //?} else
            /*
            if ((outlineColor ushr 24) != 0) {
                if ((backgroundColor ushr 24) != 0) {
                    font.drawInBatch(
                        formatted, x, y, textColor, false, stack.last().pose(), buffers,
                        Font.DisplayMode.NORMAL, backgroundColor, 0xF000F0,
                    )
                }
                font.drawInBatch8xOutline(
                    formatted, x, y, textColor, outlineColor, stack.last().pose(), buffers, 0xF000F0,
                )
            } else {
                font.drawInBatch(
                    formatted, x, y, textColor, false, stack.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, backgroundColor, 0xF000F0,
                )
            }
            */
        }
        //? if <26.2 {
        buffers.endBatch()
        //?}
        stack.popPose()
    }

    /**
     * Computes subtitle wrapping and scale. If a cue would exceed the allotted lower-screen height,
     * the glyph scale is reduced and wrapping is recalculated instead of silently cutting lines.
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

    /** Word-wraps cue lines using the selected font's actual metrics, including safe splitting of long words. */
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

    /**
     * Loading / error placeholder. Loading is a faintly breathing dark backdrop with an indeterminate progress bar;
     * error swaps in a static red tint.
     */
    private fun renderPlaceholder(
        stack: PoseStack, drawQuad: QuadRenderer, type: RenderType,
        facing: DisplayFacing, w: Int, h: Int, error: Boolean, lift: Float,
    ) {
        // Backdrop on the screen plane
        drawLayer(stack, facing, w, h, lift) {
            val (r, g, b) = if (error) {
                Triple(28, 6, 6)
            } else {
                val breathe = (sin(System.nanoTime() / 2_000_000_000.0 * 2.0 * Math.PI).toFloat() + 1f) * 0.5f
                val v = (8 + breathe * 6f).toInt()
                Triple(v, v, v)
            }
            drawQuad(type) { pose, vb -> appendRect(pose, vb, 0f, 0f, 1f, 1f, r, g, b) }
        }

        val y0 = 0.045f
        val y1 = 0.075f
        val x0 = 0.06f
        val x1 = 0.94f

        // Bar track, lifted off the backdrop.
        drawLayer(stack, facing, w, h, lift + OVERLAY_LIFT) {
            val (r, g, b) = if (error) Triple(120, 30, 30) else Triple(22, 24, 34)
            drawQuad(type) { pose, vb -> appendRect(pose, vb, x0, y0, x1, y1, r, g, b) }
        }
        if (error) return

        // Accent segment sweeping across the track, clipped at the ends so it grows in and shrinks out,
        // lifted again so it never fights the track.
        val period = 1_300_000_000L
        val phase = (System.nanoTime() % period).toFloat() / period
        val segW = 0.28f
        val travel = (x1 - x0) + segW
        val segStart = x0 - segW + travel * phase
        val sx0 = segStart.coerceIn(x0, x1)
        val sx1 = (segStart + segW).coerceIn(x0, x1)
        if (sx1 > sx0) drawLayer(stack, facing, w, h, lift + OVERLAY_LIFT * 2f) {
            drawQuad(type) { pose, vb -> appendRect(pose, vb, sx0, y0, sx1, y1, 40, 110, 255) }
        }
    }

    /**
     * Runs [body] with the screen transform applied and the layer lifted [lift] blocks toward the
     * viewer (in world space, before the transform's z-flattening scale) so stacked overlay quads
     * occupy distinct depths.
     */
    private inline fun drawLayer(
        stack: PoseStack, facing: DisplayFacing, w: Int, h: Int, lift: Float, body: () -> Unit,
    ) {
        stack.pushPose()
        if (lift != 0f) DisplayGeometry.liftTowardViewer(stack, facing, lift)
        DisplayGeometry.applyScreenTransform(stack, facing, w, h)
        body()
        stack.popPose()
    }

    /**
     * Appends a solid-color rectangle spanning [[x0], [y0] and [x1],[y1]] in unit-quad space (UV is ignored: the
     * overlay type samples a 1x1 white texture). Wound CCW to match the video quad.
     */
    private fun appendRect(
        pose: PoseStack.Pose, builder: VertexConsumer,
        x0: Float, y0: Float, x1: Float, y1: Float, r: Int, g: Int, b: Int,
    ) {
        addVertex(pose, builder, x0, y0, 0f, r, g, b, 0f, 0f)
        addVertex(pose, builder, x1, y0, 0f, r, g, b, 0f, 0f)
        addVertex(pose, builder, x1, y1, 0f, r, g, b, 0f, 0f)
        addVertex(pose, builder, x0, y1, 0f, r, g, b, 0f, 0f)
    }

    /** Texture corners in vertex order; rotating the list by [rotation] spins the image. */
    private val baseUv = arrayOf(0f to 1f, 1f to 1f, 1f to 0f, 0f to 0f)

    /** Appends a quad with the given [rotation] and color [[r], [g], [b] (0..255). */
    private fun appendQuad(
        pose: PoseStack.Pose,
        builder: VertexConsumer,
        r: Int,
        g: Int,
        b: Int,
        rotation: DisplayRotation,
    ) {
        val rot = rotation.quarterTurns
        val uv = Array(4) { baseUv[(it + rot) % 4] }
        addVertex(pose, builder, 0f, 0f, 0f, r, g, b, uv[0].first, uv[0].second)
        addVertex(pose, builder, 1f, 0f, 0f, r, g, b, uv[1].first, uv[1].second)
        addVertex(pose, builder, 1f, 1f, 0f, r, g, b, uv[2].first, uv[2].second)
        addVertex(pose, builder, 0f, 1f, 0f, r, g, b, uv[3].first, uv[3].second)
    }

    /** Adds a vertex with the given [r,g,b] (0..255) and [u,v] (0..1) coordinates. */
    private fun addVertex(
        pose: PoseStack.Pose,
        builder: VertexConsumer,
        x: Float,
        y: Float,
        z: Float,
        r: Int,
        g: Int,
        b: Int,
        u: Float,
        v: Float,
    ) {
        builder.addVertex(pose, x, y, z).setUv(u, v).setColor(r, g, b, 255)
    }

    /** Draws a quad using the given [type] and [appendVertices] function. A bit of a hack. */
    private fun drawImmediate(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
        ImmediateRenderCompat.draw(stack, type, appendVertices)
    }

    /** Compatibility layer for the new immediate mode API. */
    private object ImmediateRenderCompat {
        fun draw(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
            //? if >=26.2 {
            draw262(stack, type, appendVertices)
            //?} else
            /*run {
                //? if <1.21.11 {
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                //?}
                try {
                    val builder = Tesselator.getInstance().begin(type.mode(), type.format())
                    appendVertices(stack.last(), builder)
                    type.draw(builder.buildOrThrow())
                } finally {
                    //? if <1.21.11 {
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
                    //?}
                }
            }*/
        }

        //? if >=26.2 {
        /** Staged-buffer class and constructor for the 26.2+ draw path, resolved once. */
        private val stagedClass: Class<*> by lazy { Class.forName("net.minecraft.client.renderer.StagedVertexBuffer") }
        private val stagedCtor by lazy {
            stagedClass.getConstructor(java.util.function.Supplier::class.java, Int::class.javaPrimitiveType)
        }

        /** Per-class method cache so the staged draw resolves each method once, not per quad per frame. */
        private val methodCache = java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method>()

        /** Looks up a public method on [owner] through the cache. */
        private fun method(owner: Class<*>, name: String, vararg params: Class<*>): java.lang.reflect.Method =
            methodCache.computeIfAbsent("${owner.name}#$name") { owner.getMethod(name, *params) }

        private fun draw262(stack: PoseStack, type: RenderType, appendVertices: QuadAppender) {
            val staged = stagedCtor.newInstance(java.util.function.Supplier { "dream-displays-immediate" }, 1536)
            try {
                val primitiveTopology = method(type.javaClass, "primitiveTopology").invoke(type)
                val draw = method(stagedClass, "appendDraw", VertexFormat::class.java, primitiveTopology.javaClass)
                    .invoke(staged, type.format(), primitiveTopology)
                val builder = method(stagedClass, "getVertexBuilder", draw.javaClass)
                    .invoke(staged, draw) as VertexConsumer
                appendVertices(stack.last(), builder)
                method(stagedClass, "upload").invoke(staged)
                val executeInfo = method(stagedClass, "getExecuteInfo", draw.javaClass).invoke(staged, draw) ?: return
                val prepared = method(type.javaClass, "prepare").invoke(type)
                method(prepared.javaClass, "drawFromBuffer", executeInfo.javaClass).invoke(prepared, executeInfo)
            } finally {
                (staged as AutoCloseable).close()
            }
        }
        //?}
    }
}
