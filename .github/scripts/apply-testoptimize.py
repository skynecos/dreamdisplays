#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace(path: str, old: str, new: str, count: int = 1):
    p = ROOT / path
    text = p.read_text()
    found = text.count(old)
    if found != count:
        raise SystemExit(
            f"testoptimize patch anchor mismatch in {path}: expected {count}, found {found}: {old[:180]!r}"
        )
    p.write_text(text.replace(old, new, count))


# 1) Suppress duplicate healthy reloads of the same URL/language.
# Retry after an actual error is intentionally NOT suppressed because screen.errored stays true
# until the replacement player begins loading.
replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayMediaController.kt",
    '''    fun load(videoUrl: String, lang: String, preservePausedState: Boolean) {\n        if (videoUrl == "") return\n\n        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(videoUrl))\n''',
    '''    fun load(videoUrl: String, lang: String, preservePausedState: Boolean) {\n        if (videoUrl == "") return\n\n        val current = player\n        if (current != null && screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored) {\n            return\n        }\n\n        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(videoUrl))\n''',
)

# 2) Android/Pojav initialization is intentionally less bursty. Resolution work is mostly I/O,
# but letting 8 displays initialize at once can fan out into decoder/audio startup spikes on phones.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/MediaPlayer.kt",
    '''        private val INIT_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(\n            Runtime.getRuntime().availableProcessors().coerceIn(4, 8),\n        ) { r -> daemon(r, "MediaPlayer-init-${INIT_THREAD_COUNTER.incrementAndGet()}") }\n''',
    '''        private val ANDROID_POJAV: Boolean =\n            System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true\n\n        private val INIT_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(\n            if (ANDROID_POJAV) Runtime.getRuntime().availableProcessors().coerceIn(2, 4)\n            else Runtime.getRuntime().availableProcessors().coerceIn(4, 8),\n        ) { r -> daemon(r, "MediaPlayer-init-${INIT_THREAD_COUNTER.incrementAndGet()}") }\n''',
)

# 3) Do not keep background audio-track FFmpeg processes pre-warmed on Android.
# This trades slightly slower manual audio-track switching for lower steady CPU/RAM/process pressure.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/PlaybackSessionManager.kt",
    '''    fun setWarmAudioTracks(tracks: List<WarmTrack>) =\n        audioWarmPool.setTracks(tracks.filter { it.url !in SILENT_SOURCES })\n''',
    '''    fun setWarmAudioTracks(tracks: List<WarmTrack>) {\n        if (System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true) {\n            audioWarmPool.setTracks(emptyList())\n            return\n        }\n        audioWarmPool.setTracks(tracks.filter { it.url !in SILENT_SOURCES })\n    }\n''',
)

# 4) Reuse a small process-wide teardown pool instead of creating a fresh Java thread for every
# superseded video/audio half. This keeps rapid seek/reload cleanup from creating thread bursts.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/PlaybackSessionManager.kt",
    '''import java.util.concurrent.CountDownLatch\n''',
    '''import java.util.concurrent.CountDownLatch\nimport java.util.concurrent.ExecutorService\nimport java.util.concurrent.Executors\n''',
)
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/PlaybackSessionManager.kt",
    '''        /** Pacing cadence for replay-only video; PTS still drives pacing, this is only the fallback. */\n        const val REPLAY_FPS = 30.0\n\n        /** How many silent-source verdicts to remember; well past any one player's stream ladder. */\n''',
    '''        /** Pacing cadence for replay-only video; PTS still drives pacing, this is only the fallback. */\n        const val REPLAY_FPS = 30.0\n\n        /** Shared teardown workers: bounded to avoid one thread per seek/reload on mobile. */\n        val DISCARD_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2) { r ->\n            daemon(r, "MediaPlayer-discard")\n        }\n\n        /** How many silent-source verdicts to remember; well past any one player's stream ladder. */\n''',
)
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/PlaybackSessionManager.kt",
    '''    private fun discardHalvesAsync(video: VideoChannel?, audioHalf: AudioHalf?) {\n        daemon({\n            audioHalf?.let {\n                MediaProcess.gracefulDestroy(it.process)\n                joinSafely(it.thread)\n            }\n            video?.let { ch ->\n                ch.teardownProcess()\n                renderExecutor.execute { ch.pipe.cleanup() }\n            }\n        }, "MediaPlayer-session-discard").start()\n    }\n''',
    '''    private fun discardHalvesAsync(video: VideoChannel?, audioHalf: AudioHalf?) {\n        DISCARD_EXECUTOR.execute {\n            audioHalf?.let {\n                MediaProcess.gracefulDestroy(it.process)\n                joinSafely(it.thread)\n            }\n            video?.let { ch ->\n                ch.teardownProcess()\n                renderExecutor.execute { ch.pipe.cleanup() }\n            }\n        }\n    }\n''',
)
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/PlaybackSessionManager.kt",
    '''    private fun discardChannelAsync(channel: VideoChannel) {\n        daemon({\n            channel.teardownProcess()\n            renderExecutor.execute { channel.pipe.cleanup() }\n        }, "MediaPlayer-video-discard").start()\n    }\n''',
    '''    private fun discardChannelAsync(channel: VideoChannel) {\n        DISCARD_EXECUTOR.execute {\n            channel.teardownProcess()\n            renderExecutor.execute { channel.pipe.cleanup() }\n        }\n    }\n''',
)

# 5) The old watchdog observed decoder input, not actual video playout. A rejoin can therefore
# decode/pre-roll forever while the last preview frame remains frozen and audio/subtitles advance.
# Track real presented frames separately. Seek-preview keyframes intentionally do NOT refresh this stamp.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePipe.kt",
    '''internal interface FramePipe {\n    /** Updated by the reader thread on every frame; used by the watchdog to detect stalls. */\n    val lastFrameReceivedNanos: AtomicLong\n\n    /** Set by the popout window to receive raw frames. Called on the reader thread. */\n''',
    '''internal interface FramePipe {\n    /** Updated by the reader thread on every decoded frame. */\n    val lastFrameReceivedNanos: AtomicLong\n\n    /**\n     * Updated when a real playback frame reaches presentation. Native seek-preview keyframes do not\n     * count: they may stay on screen while libav is still pre-rolling to the requested rejoin point.\n     * Pipes without a separate playout stage fall back to the decode-arrival stamp.\n     */\n    val lastFramePresentedNanos: AtomicLong\n        get() = lastFrameReceivedNanos\n\n    /** Set by the popout window to receive raw frames. Called on the reader thread. */\n''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/NativeVideoFramePipe.kt",
    '''    /** Updated by the reader thread on every frame; used by the watchdog to detect stalls. */\n    override val lastFrameReceivedNanos = AtomicLong(0)\n\n    @Volatile\n''',
    '''    /** Updated by the reader thread on every decoded frame. */\n    override val lastFrameReceivedNanos = AtomicLong(0)\n\n    /** Updated only when a real playback frame enters the presentation path (not seek preview). */\n    override val lastFramePresentedNanos = AtomicLong(0)\n\n    @Volatile\n''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/NativeVideoFramePipe.kt",
    '''        prebuffer?.onPresent = { buf -> feedPopout(buf, w, h, frameSize, metrics) }\n''',
    '''        prebuffer?.onPresent = { buf ->\n            lastFramePresentedNanos.set(System.nanoTime())\n            feedPopout(buf, w, h, frameSize, metrics)\n        }\n''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/NativeVideoFramePipe.kt",
    '''            spare = surface.publish(spare, frameSize)\n            if (MediaPlayer.DEBUG) MediaPlayer.samplesIn.incrementAndGet()\n''',
    '''            spare = surface.publish(spare, frameSize)\n            lastFramePresentedNanos.set(System.nanoTime())\n            if (MediaPlayer.DEBUG) MediaPlayer.samplesIn.incrementAndGet()\n''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/PlaybackSessionManager.kt",
    '''    /** Timestamp of the live channel's last decoded video frame; read by [StreamWatchdog]. */\n    val lastFrameNanos: AtomicLong get() = active?.pipe?.lastFrameReceivedNanos ?: noFrames\n''',
    '''    /** Timestamp of the live channel's last real playback frame; read by [StreamWatchdog]. */\n    val lastFrameNanos: AtomicLong get() = active?.pipe?.lastFramePresentedNanos ?: noFrames\n''',
)

# 6) A presentation stamp starts at zero, so give startup its own monotonic grace window instead of
# interpreting zero as "stalled since boot". This also makes the watchdog valid for all future pipes.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/StreamWatchdog.kt",
    '''    private var deliveredAFrame = false\n    private var lastSeenStamp = 0L\n\n    /** Coroutine scope for the watchdog task. */\n''',
    '''    private var deliveredAFrame = false\n    private var lastSeenStamp = 0L\n    private var startedNanos = 0L\n\n    /** Coroutine scope for the watchdog task. */\n''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/StreamWatchdog.kt",
    '''        deliveredAFrame = false\n        lastSeenStamp = getLastFrameNanos()\n        job = scope.launch {\n''',
    '''        deliveredAFrame = false\n        lastSeenStamp = getLastFrameNanos()\n        startedNanos = System.nanoTime()\n        job = scope.launch {\n''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/managers/StreamWatchdog.kt",
    '''            val silenceMs = (System.nanoTime() - stamp) / 1_000_000L\n            if (silenceMs < (if (deliveredAFrame) stallThresholdMs else startupThresholdMs)) return true\n''',
    '''            val now = System.nanoTime()\n            val silenceMs = if (!deliveredAFrame) {\n                (now - startedNanos) / 1_000_000L\n            } else {\n                (now - stamp) / 1_000_000L\n            }\n            if (silenceMs < (if (deliveredAFrame) stallThresholdMs else startupThresholdMs)) return true\n''',
)

# 7) Phones should not sit on a frozen preview for tens of seconds. If no real frame is presented
# within 8 s (or presentation stops for 8 s), reopen the A/V session once at the current clock.
# A second stall still follows the existing stronger cache-invalidate/re-resolve path.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/MediaPlayer.kt",
    '''    private val watchdog = StreamWatchdog(\n        debugLabel = debugLabel,\n        isSessionActive = { sessionManager.isPlaying && !sessionManager.isParked() && !terminated.get() },\n        getLastFrameNanos = { sessionManager.lastFrameNanos.get() },\n        onStall = { handleSessionStall("no frames") },\n    )\n''',
    '''    private val watchdog = StreamWatchdog(\n        debugLabel = debugLabel,\n        isSessionActive = { sessionManager.isPlaying && !sessionManager.isParked() && !terminated.get() },\n        getLastFrameNanos = { sessionManager.lastFrameNanos.get() },\n        stallThresholdMs = if (ANDROID_POJAV) 8_000L else 45_000L,\n        startupThresholdMs = if (ANDROID_POJAV) 8_000L else 20_000L,\n        onStall = { handleSessionStall("no presented video frames") },\n    )\n''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/MediaPlayer.kt",
    '''                if (sessionManager.beginSeek(ss, pos, lastQuality, currentHwAccel())) {\n                    // The watchdog stops itself when it reports a stall, and this path never goes\n                    // through startStreams, so nothing else would ever watch this session again.\n                    watchdog.start()\n                } else {\n                    startStreams(ss, pos)\n                }\n''',
    '''                if (ANDROID_POJAV && reason == "no presented video frames") {\n                    // A frozen rejoin can keep the decoder alive while playout is wedged on one frame.\n                    // Reusing that same in-process LAV handle for an in-place seek can preserve the bad\n                    // prebuffer/pacing state, so reopen the full A/V session once. If this also stalls,\n                    // the existing repeated-stall branch above invalidates URLs and re-resolves.\n                    logger.warn(\n                        "$debugLabel Android video presentation stalled; reopening A/V session at " +\n                                "${pos / 1_000_000} ms."\n                    )\n                    startStreams(ss, pos)\n                } else if (sessionManager.beginSeek(ss, pos, lastQuality, currentHwAccel())) {\n                    // The watchdog stops itself when it reports a stall, and this path never goes\n                    // through startStreams, so nothing else would ever watch this session again.\n                    watchdog.start()\n                } else {\n                    startStreams(ss, pos)\n                }\n''',
)

print("Applied DreamDisplays testoptimize mobile playback patch.")
