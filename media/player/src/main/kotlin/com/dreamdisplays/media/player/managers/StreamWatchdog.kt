package com.dreamdisplays.media.player.managers

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches whether frames are arriving. If no frame arrives within the applicable threshold, calls [onStall] and stops itself;
 * the caller decides how to recover.
 */
internal class StreamWatchdog(
    private val debugLabel: String,
    private val isSessionActive: () -> Boolean,
    private val getLastFrameNanos: () -> Long,
    private val getLastProgressNanos: () -> Long = getLastFrameNanos,
    private val stallThresholdMs: Long = 45_000L,
    private val startupThresholdMs: Long = 20_000L,
    private val startupHardTimeoutMs: Long = 60_000L,
    private val checkIntervalMs: Long = 1_000L,
    private val onStall: () -> Unit,
) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/StreamWatchdog")

    /** Watchdog task. */
    @Volatile
    private var job: Job? = null

    /**
     * Whether any frame has arrived since [start], and the stamp that decided it. Derived from the
     * stamp moving rather than from a callback, so it holds for every way a session can begin —
     * cold start, in-place seek, quality handoff, reappearance bridge.
     */
    private var deliveredAFrame = false
    private var lastSeenPresentedStamp = 0L
    private var lastSeenProgressStamp = 0L
    private var startedNanos = 0L

    /** Coroutine scope for the watchdog task. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("MediaPlayer-watchdog"))

    /** Start watchdog. */
    fun start() {
        stop()
        deliveredAFrame = false
        lastSeenPresentedStamp = getLastFrameNanos()
        lastSeenProgressStamp = getLastProgressNanos()
        startedNanos = System.nanoTime()
        job = scope.launch {
            delay(checkIntervalMs.milliseconds)
            // Stops at the first stall: recovery is the caller's job, and it restarts the watchdog
            while (isActive && check()) {
                delay(checkIntervalMs.milliseconds)
            }
        }
    }

    /** Stop watchdog. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One pass over [stallThresholdMs] / [startupThresholdMs]. Returns false once [onStall] has been called, which ends
     * the watchdog's polling loop.
     */
    private fun check(): Boolean {
        return runCatching {
            if (!isSessionActive()) { startedNanos = System.nanoTime(); return true }
            val now = System.nanoTime()
            val presentedStamp = getLastFrameNanos()
            if (presentedStamp != 0L && presentedStamp != lastSeenPresentedStamp) {
                lastSeenPresentedStamp = presentedStamp
                deliveredAFrame = true
            }

            val progressStamp = getLastProgressNanos()
            if (progressStamp != lastSeenProgressStamp) lastSeenProgressStamp = progressStamp

            val silenceMs: Long
            val what: String
            if (deliveredAFrame) {
                val base = if (presentedStamp > 0L) presentedStamp else startedNanos
                silenceMs = (now - base) / 1_000_000L
                if (silenceMs < stallThresholdMs) return true
                what = "No presented frames for $silenceMs ms"
            } else {
                val progressBase = if (progressStamp > 0L) progressStamp else startedNanos
                silenceMs = (now - progressBase) / 1_000_000L
                val startupElapsedMs = (now - startedNanos) / 1_000_000L
                if (silenceMs < startupThresholdMs && startupElapsedMs < startupHardTimeoutMs) return true
                what = if (startupElapsedMs >= startupHardTimeoutMs) {
                    "No first presented frame after $startupElapsedMs ms (decoder progress ${silenceMs} ms ago)"
                } else {
                    "No decoder progress for $silenceMs ms before first presentation"
                }
            }
            logger.warn("$debugLabel $what. Restarting...")
            onStall()
            false
        }.getOrDefault(true)
    }
}
