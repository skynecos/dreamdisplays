package com.dreamdisplays.media.player.pipeline

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10
import org.lwjgl.openal.EXTThreadLocalContext
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.*
import kotlin.math.min

/**
 * Android replacement for JavaSound's SourceDataLine.
 *
 * The Android OpenJDK runtime used by Kirazium has javax.sound.sampled classes but
 * no libjsound.so provider. Minecraft itself already ships LWJGL OpenAL and the
 * launcher routes OpenAL-Soft to Oboe, so we stream the same 44.1 kHz stereo PCM
 * through a private OpenAL context instead.
 */
internal class AndroidOpenALLine : SourceDataLine {
    companion object {
        private const val BUFFER_COUNT = 8
        private const val DEFAULT_RATE = 44100
        private const val DEFAULT_FRAME_SIZE = 4
    }

    private var fmt = AudioFormat(DEFAULT_RATE.toFloat(), 16, 2, true, false)
    private var requestedBufferBytes = DEFAULT_RATE * DEFAULT_FRAME_SIZE * 2 / 5
    private var chunkBytes = requestedBufferBytes / BUFFER_COUNT

    private var owner: Thread? = null
    private var device = 0L
    private var context = 0L
    private var source = 0
    private val buffers = IntArray(BUFFER_COUNT)
    private val free = ArrayDeque<Int>()
    private val bufferSizes = HashMap<Int, Int>()
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(chunkBytes.coerceAtLeast(4096))

    private val opened = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val availableBytes = AtomicLong(0)
    private val framesWritten = AtomicLong(0)
    private val framesPlayed = AtomicLong(0)

    @Volatile private var accumulatedRunNanos = 0L
    @Volatile private var runStartedNanos = 0L

    override fun open(format: AudioFormat, bufferSize: Int) {
        if (opened.get()) return
        require(format.channels == 2 && format.sampleSizeInBits == 16 && !format.isBigEndian) {
            "Android OpenAL line requires stereo signed 16-bit little-endian PCM"
        }
        fmt = format
        requestedBufferBytes = bufferSize.coerceAtLeast(8192)
        chunkBytes = (requestedBufferBytes / BUFFER_COUNT).coerceAtLeast(4096)
        scratch = ByteBuffer.allocateDirect(chunkBytes)
        owner = Thread.currentThread()
        closeRequested.set(false)

        val d = ALC10.alcOpenDevice(null as ByteBuffer?)
        if (d == 0L) throw LineUnavailableException("OpenAL default device could not be opened")
        device = d
        val alcCaps = ALC.createCapabilities(d)
        val c = ALC10.alcCreateContext(d, null as IntBuffer?)
        if (c == 0L) {
            ALC10.alcCloseDevice(d)
            device = 0L
            throw LineUnavailableException("OpenAL context could not be created")
        }
        context = c
        if (!alcCaps.ALC_EXT_thread_local_context) {
            ALC10.alcDestroyContext(c)
            ALC10.alcCloseDevice(d)
            context = 0L
            device = 0L
            throw LineUnavailableException("OpenAL thread-local context extension is unavailable")
        }
        if (!EXTThreadLocalContext.alcSetThreadContext(c)) {
            ALC10.alcDestroyContext(c)
            ALC10.alcCloseDevice(d)
            context = 0L
            device = 0L
            throw LineUnavailableException("OpenAL thread-local context could not be made current")
        }
        AL.createCapabilities(alcCaps)

        source = AL10.alGenSources()
        free.clear()
        bufferSizes.clear()
        for (i in 0 until BUFFER_COUNT) {
            buffers[i] = AL10.alGenBuffers()
            free.addLast(buffers[i])
        }
        framesWritten.set(0)
        framesPlayed.set(0)
        accumulatedRunNanos = 0L
        runStartedNanos = 0L
        availableBytes.set((BUFFER_COUNT * chunkBytes).toLong())
        opened.set(true)
    }

    override fun open(format: AudioFormat) = open(format, requestedBufferBytes)
    override fun open() = open(fmt, requestedBufferBytes)

    private fun onOwnerThread(): Boolean = Thread.currentThread() === owner

    /** Reclaims buffers the audio device has finished playing. Owner thread only. */
    private fun reclaimProcessed() {
        if (!onOwnerThread() || source == 0) return
        var processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
        while (processed-- > 0) {
            val id = AL10.alSourceUnqueueBuffers(source)
            val playedBytes = bufferSizes.remove(id) ?: 0
            if (playedBytes > 0) {
                framesPlayed.addAndGet((playedBytes / fmt.frameSize.coerceAtLeast(1)).toLong())
            }
            free.addLast(id)
        }
        availableBytes.set((free.size * chunkBytes).toLong())
    }

    private fun ensurePlaying() {
        if (!running.get() || source == 0) return
        if (AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) <= 0) return
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(source)
        }
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int {
        if (!opened.get() || closeRequested.get() || !onOwnerThread()) return 0
        var off = offset
        var remaining = length
        var total = 0
        while (remaining > 0 && opened.get() && !closeRequested.get()) {
            reclaimProcessed()
            while (free.isEmpty() && opened.get() && !closeRequested.get()) {
                ensurePlaying()
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return total
                }
                reclaimProcessed()
            }
            if (free.isEmpty()) break

            val n = min(remaining, chunkBytes)
            val id = free.removeFirst()
            scratch.clear()
            scratch.put(data, off, n)
            scratch.flip()
            AL10.alBufferData(id, AL10.AL_FORMAT_STEREO16, scratch, fmt.sampleRate.toInt())
            AL10.alSourceQueueBuffers(source, id)
            bufferSizes[id] = n
            availableBytes.set((free.size * chunkBytes).toLong())
            framesWritten.addAndGet((n / fmt.frameSize.coerceAtLeast(1)).toLong())
            off += n
            remaining -= n
            total += n
            ensurePlaying()
        }
        return total
    }

    override fun start() {
        if (!opened.get()) return
        if (running.compareAndSet(false, true)) runStartedNanos = System.nanoTime()
        if (onOwnerThread()) ensurePlaying()
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) {
            accumulatedRunNanos += (System.nanoTime() - runStartedNanos).coerceAtLeast(0L)
        }
        if (onOwnerThread() && source != 0) AL10.alSourcePause(source)
    }

    override fun drain() {
        if (!onOwnerThread()) return
        while (opened.get() && !closeRequested.get()) {
            reclaimProcessed()
            if (AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) == 0) return
            ensurePlaying()
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }
        }
    }

    override fun flush() {
        if (!onOwnerThread() || source == 0) return
        AL10.alSourceStop(source)
        var queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
        while (queued-- > 0) {
            val id = AL10.alSourceUnqueueBuffers(source)
            bufferSizes.remove(id)
            if (!free.contains(id)) free.addLast(id)
        }
        availableBytes.set((free.size * chunkBytes).toLong())
    }

    override fun close() {
        closeRequested.set(true)
        stop()
        opened.set(false)
        if (!onOwnerThread()) return
        cleanupOwnerThread()
    }

    private fun cleanupOwnerThread() {
        if (source != 0) {
            runCatching { AL10.alSourceStop(source) }
            runCatching {
                var queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
                while (queued-- > 0) AL10.alSourceUnqueueBuffers(source)
            }
            runCatching { AL10.alDeleteSources(source) }
            source = 0
        }
        for (id in buffers) if (id != 0) runCatching { AL10.alDeleteBuffers(id) }
        free.clear()
        bufferSizes.clear()
        if (context != 0L) {
            runCatching {
                EXTThreadLocalContext.alcSetThreadContext(0L)
                AL.setCurrentThread(null)
            }
            runCatching { ALC10.alcDestroyContext(context) }
            context = 0L
        }
        if (device != 0L) {
            runCatching { ALC10.alcCloseDevice(device) }
            device = 0L
        }
    }

    override fun isOpen(): Boolean = opened.get()
    override fun isRunning(): Boolean = running.get() && opened.get()
    override fun isActive(): Boolean = isRunning
    override fun getFormat(): AudioFormat = fmt
    override fun getBufferSize(): Int = requestedBufferBytes
    override fun available(): Int {
        if (opened.get() && onOwnerThread()) {
            reclaimProcessed()
            ensurePlaying()
        }
        return availableBytes.get().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun elapsedRunNanos(): Long = accumulatedRunNanos +
        if (running.get()) (System.nanoTime() - runStartedNanos).coerceAtLeast(0L) else 0L

    override fun getLongFramePosition(): Long = min(framesWritten.get(), framesPlayed.get())

    override fun getFramePosition(): Int = getLongFramePosition().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    override fun getMicrosecondPosition(): Long = getLongFramePosition() * 1_000_000L / fmt.sampleRate.toLong().coerceAtLeast(1L)
    override fun getLevel(): Float = -1f
    override fun getLineInfo(): Line.Info = DataLine.Info(SourceDataLine::class.java, fmt)
    override fun getControls(): Array<Control> = emptyArray()
    override fun isControlSupported(control: Control.Type): Boolean = false
    override fun getControl(control: Control.Type): Control = throw IllegalArgumentException("Unsupported control: $control")
    override fun addLineListener(listener: LineListener) = Unit
    override fun removeLineListener(listener: LineListener) = Unit
}
