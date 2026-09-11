#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace(path: str, old: str, new: str, count: int = 1):
    p = ROOT / path
    text = p.read_text()
    found = text.count(old)
    if found != count:
        raise SystemExit(
            f"mobilefix1 patch anchor mismatch in {path}: expected {count}, found {found}: {old[:180]!r}"
        )
    p.write_text(text.replace(old, new, count))


# 1) Restore the same 400 ms jitter cushion used by desktop DreamDisplays.
# Android8 forced this to 0 ms. That removed the rejoin/seek cushion exactly on the path
# where the phone log shows valid seeks followed by prolonged no-presentation stalls.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePrebuffer.kt",
    '''        private val DEFAULT_PREBUFFER_MS: Long =
            if (System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true) 0L else 400L
''',
    '''        private const val DEFAULT_PREBUFFER_MS = 400L
''',
)

# 2) Undo only Android8's experimental shared-command-encoder batching for planar Y/U/V uploads.
# Keep the Android-safe command-encoder route itself; restore upstream's one fresh encoder per plane.
replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/TextureUploadUtil.kt",
    '''        // Android/OpenLTW takes this backend-neutral branch. Keep all three plane
        // writes in one command encoder so the render backend submits one batch rather
        // than constructing one encoder per YUV plane every displayed frame.
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        var offset = 0
        for (texture in arrayOf(y, u, v)) {
            val planeBytes = texture.getWidth(0) * texture.getHeight(0)
            val view = src.duplicate()
            view.position(src.position() + offset).limit(src.position() + offset + planeBytes)
            writeToTexture(
                encoder,
                texture,
                view,
                texture.getWidth(0),
                texture.getHeight(0),
                UploadPixelFormat.R8.nativeImageFormat
            )
            offset += planeBytes
        }
''',
    '''        var offset = 0
        for (texture in arrayOf(y, u, v)) {
            val planeBytes = texture.getWidth(0) * texture.getHeight(0)
            val view = src.duplicate()
            view.position(src.position() + offset).limit(src.position() + offset + planeBytes)
            writeToTexture(
                texture,
                view,
                texture.getWidth(0),
                texture.getHeight(0),
                UploadPixelFormat.R8.nativeImageFormat
            )
            offset += planeBytes
        }
''',
)

replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/TextureUploadUtil.kt",
    '''    /** Write to a Minecraft texture using a fresh command encoder. */
    private fun writeToTexture(texture: GpuTexture, pixels: ByteBuffer, w: Int, h: Int, format: NativeImage.Format) =
        writeToTexture(RenderSystem.getDevice().createCommandEncoder(), texture, pixels, w, h, format)

    /**
     * Adds one texture write to [encoder]. The Android planar path calls this three
     * times against one encoder, retaining exact pixel bytes and dimensions while
     * avoiding separate encoder construction/submission for each plane.
     */
    private fun writeToTexture(
        encoder: Any,
        texture: GpuTexture,
        pixels: ByteBuffer,
        w: Int,
        h: Int,
        format: NativeImage.Format,
    ) {
        val encoderClass = encoder.javaClass

        try {
            encoderClass
                .getMethod(
                    "writeToTexture",
                    GpuTexture::class.java,
                    ByteBuffer::class.java,
                    NativeImage.Format::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invokeOrThrowTarget(encoder, texture, pixels, format, 0, 0, 0, 0, w, h)
            return
        } catch (_: NoSuchMethodException) {
        }

        encoderClass
            .getMethod(
                "writeToTexture",
                GpuTexture::class.java,
                ByteBuffer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invokeOrThrowTarget(encoder, texture, pixels, 0, 0, 0, 0, w, h)
    }
''',
    '''    /** Write to a Minecraft texture using the `writeToTexture` method. */
    private fun writeToTexture(texture: GpuTexture, pixels: ByteBuffer, w: Int, h: Int, format: NativeImage.Format) {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val encoderClass = encoder.javaClass

        try {
            encoderClass
                .getMethod(
                    "writeToTexture",
                    GpuTexture::class.java,
                    ByteBuffer::class.java,
                    NativeImage.Format::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invokeOrThrowTarget(encoder, texture, pixels, format, 0, 0, 0, 0, w, h)
            return
        } catch (_: NoSuchMethodException) {
        }

        encoderClass
            .getMethod(
                "writeToTexture",
                GpuTexture::class.java,
                ByteBuffer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invokeOrThrowTarget(encoder, texture, pixels, 0, 0, 0, 0, w, h)
    }
''',
)

# 3) Make the Android audio master clock follow buffers actually consumed by OpenAL.
# The old implementation advanced from wall time while the SourceDataLine was marked running,
# even if the device had underrun. Desktop JavaSound reports consumed frames instead. Keep all
# OpenAL calls on the owner thread and expose an atomic consumed-frame counter to video pacing.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.kt",
    '''    private val framesWritten = AtomicLong(0)

    @Volatile private var accumulatedRunNanos = 0L
''',
    '''    private val framesWritten = AtomicLong(0)
    private val framesPlayed = AtomicLong(0)

    @Volatile private var accumulatedRunNanos = 0L
''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.kt",
    '''        framesWritten.set(0)
        accumulatedRunNanos = 0L
''',
    '''        framesWritten.set(0)
        framesPlayed.set(0)
        accumulatedRunNanos = 0L
''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.kt",
    '''            val id = AL10.alSourceUnqueueBuffers(source)
            bufferSizes.remove(id)
            free.addLast(id)
''',
    '''            val id = AL10.alSourceUnqueueBuffers(source)
            val playedBytes = bufferSizes.remove(id) ?: 0
            if (playedBytes > 0) {
                framesPlayed.addAndGet((playedBytes / fmt.frameSize.coerceAtLeast(1)).toLong())
            }
            free.addLast(id)
''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.kt",
    '''    override fun getLongFramePosition(): Long {
        val byTime = elapsedRunNanos() * fmt.sampleRate.toLong() / 1_000_000_000L
        return min(framesWritten.get(), byTime)
    }
''',
    '''    override fun getLongFramePosition(): Long = min(framesWritten.get(), framesPlayed.get())
''',
)

# Distinctive runtime marker for device logs; no behavior change.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AudioSink.kt",
    '''                    logger.info("$debugLabel Android OpenAL line opened with thread-local context.")
''',
    '''                    logger.info("$debugLabel MOBILEFIX1 Android OpenAL line opened with reclaimed-frame clock.")
''',
)

print("Applied mobilefix1 desktop-parity playback stability patch.")
