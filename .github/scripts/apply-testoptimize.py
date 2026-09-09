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

print("Applied DreamDisplays testoptimize mobile playback patch.")
