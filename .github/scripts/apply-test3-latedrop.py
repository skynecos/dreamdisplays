#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace_once(path: str, old: str, new: str):
    p = ROOT / path
    text = p.read_text()
    found = text.count(old)
    if found != 1:
        raise SystemExit(
            f"TEST3 late-drop anchor mismatch in {path}: expected 1, found {found}: {old[:180]!r}"
        )
    p.write_text(text.replace(old, new, 1))


PREBUFFER = "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePrebuffer.kt"

# Android-only smart catch-up. The existing FramePacing logic already drops a frame that is >80 ms
# late when a fresher decoded frame is waiting. TEST3 makes recovery from a queued backlog cheaper:
# once regular playout has begun and the head is badly late, collapse several obsolete queued frames
# in one bounded pass instead of pacing them one-by-one. This does not touch startup/seek gating,
# does not refresh presentation/watchdog timestamps, and cannot hide a decoder that produces no frames.
replace_once(
    PREBUFFER,
    '''import java.util.concurrent.atomic.AtomicInteger\nimport java.util.concurrent.atomic.AtomicReference\n''',
    '''import java.util.concurrent.atomic.AtomicInteger\nimport java.util.concurrent.atomic.AtomicLong\nimport java.util.concurrent.atomic.AtomicReference\n''',
)

replace_once(
    PREBUFFER,
    '''    private val firstFramePresented = AtomicBoolean(false)\n    private var consumer: Thread? = null\n    private val pending = AtomicReference<Timed?>(null)\n\n    /**\n''',
    '''    private val firstFramePresented = AtomicBoolean(false)\n    private var consumer: Thread? = null\n    private val pending = AtomicReference<Timed?>(null)\n\n    /** TEST3 is deliberately Android/Pojav-only; desktop pacing is left byte-for-byte equivalent. */\n    private val smartCatchupEnabled = System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true\n\n    /** Rate limiter for TEST3 catch-up diagnostics. */\n    private val lastSmartCatchupLogNanos = AtomicLong(0L)\n\n    /**\n''',
)

replace_once(
    PREBUFFER,
    '''                val tf = pending.getAndSet(null) ?: queue.poll(POLL_MS, TimeUnit.MILLISECONDS)\n                if (tf == null) {\n                    if (inputClosed) break // Tail drained\n                    continue\n                }\n                if (tf.generation != generation.get()) {\n                    surface.recycleFrameBuffer(tf.buf)\n                    continue\n                }\n                // Playout begins with this frame, so start the clock (and open the audio gate) *before* pacing\n''',
    '''                val polled = pending.getAndSet(null) ?: queue.poll(POLL_MS, TimeUnit.MILLISECONDS)\n                if (polled == null) {\n                    if (inputClosed) break // Tail drained\n                    continue\n                }\n                var tf = polled\n                if (tf.generation != generation.get()) {\n                    surface.recycleFrameBuffer(tf.buf)\n                    continue\n                }\n\n                // TEST3 smart catch-up: after playout is established, a phone that hitches can leave the\n                // prebuffer full of pictures that are already obsolete relative to the audio clock. The\n                // old path eventually dropped them one at a time through FramePacing; collapse that backlog\n                // here, before pacing/presentation/GPU upload, while always keeping one newest candidate.\n                // Never run this during initial start or directly after seek: firstFramePresented is reset\n                // for those phases, so the authoritative sync gate and normal prefill behaviour stay intact.\n                if (smartCatchupEnabled && tolerateLateness && firstFramePresented.get() && !flushRequested) {\n                    val firstClock = getAudioClock()\n                    val initialLateNs = if (firstClock >= 0L) firstClock - tf.pts else 0L\n                    if (initialLateNs >= SMART_CATCHUP_TRIGGER_NS) {\n                        var droppedForCatchup = 0\n                        var currentLateNs = initialLateNs\n                        while (\n                            droppedForCatchup < SMART_CATCHUP_MAX_DROP_FRAMES &&\n                            currentLateNs > SMART_CATCHUP_TARGET_NS &&\n                            !flushRequested && alive() && !parked()\n                        ) {\n                            val newer = queue.poll() ?: break\n                            if (newer.generation != generation.get()) {\n                                surface.recycleFrameBuffer(newer.buf)\n                                continue\n                            }\n                            surface.recycleFrameBuffer(tf.buf)\n                            MediaPlayer.framesDropped.incrementAndGet()\n                            recordFrame(currentLateNs, presentedIt = false)\n                            droppedForCatchup++\n                            tf = newer\n                            val latestClock = getAudioClock()\n                            currentLateNs = if (latestClock >= 0L) latestClock - tf.pts else 0L\n                        }\n\n                        if (droppedForCatchup > 0) {\n                            val now = System.nanoTime()\n                            val last = lastSmartCatchupLogNanos.get()\n                            if (now - last >= SMART_CATCHUP_LOG_INTERVAL_NS &&\n                                lastSmartCatchupLogNanos.compareAndSet(last, now)\n                            ) {\n                                logger.warn(\n                                    "$debugLabel TEST3 smart catch-up dropped $droppedForCatchup queued frame(s) " +\n                                            "before upload (initialLate=${initialLateNs / 1_000_000} ms, " +\n                                            "remainingLate=${currentLateNs.coerceAtLeast(0L) / 1_000_000} ms, " +\n                                            "queue=${queue.size}/$capacityFrames)."\n                                )\n                            }\n                        }\n                    }\n                }\n\n                // Playout begins with this frame, so start the clock (and open the audio gate) *before* pacing\n''',
)

replace_once(
    PREBUFFER,
    '''        /** Lateness at which a presented frame counts as behind the clock rather than on it. */\n        private const val BEHIND_WARN_NANOS = 80_000_000L\n\n        /** Default prebuffer cushion. Smooths cold start / seek / quality-switch; kept under the\n''',
    '''        /** Lateness at which a presented frame counts as behind the clock rather than on it. */\n        private const val BEHIND_WARN_NANOS = 80_000_000L\n\n        /**\n         * TEST3 starts burst catch-up only at twice the normal 80 ms late-frame threshold. This avoids\n         * turning ordinary jitter into visible frame skipping. The target is below that normal threshold,\n         * then FramePacing makes the final present/drop decision as before.\n         */\n        private const val SMART_CATCHUP_TRIGGER_NS = 160_000_000L\n        private const val SMART_CATCHUP_TARGET_NS = 60_000_000L\n\n        /** Hard bound per consumer pass: never drain an unbounded queue in one iteration. */\n        private const val SMART_CATCHUP_MAX_DROP_FRAMES = 12\n\n        /** At most one TEST3 catch-up line every two seconds per display. */\n        private const val SMART_CATCHUP_LOG_INTERVAL_NS = 2_000_000_000L\n\n        /** Default prebuffer cushion. Smooths cold start / seek / quality-switch; kept under the\n''',
)

print("Applied TEST3 Android bounded smart late-frame catch-up patch.")
