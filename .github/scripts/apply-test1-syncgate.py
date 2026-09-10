#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace(path: str, old: str, new: str, count: int = 1):
    p = ROOT / path
    text = p.read_text()
    found = text.count(old)
    if found != count:
        raise SystemExit(
            f"test1 sync-gate anchor mismatch in {path}: expected {count}, found {found}: {old[:180]!r}"
        )
    p.write_text(text.replace(old, new, count))


PLAYER = "media/player/src/main/kotlin/com/dreamdisplays/media/player/MediaPlayer.kt"
CONTROLLER = "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayMediaController.kt"
SCREEN = "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayScreen.kt"
FOLLOWER = "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/TimelineFollower.kt"

# The production stallfix1 pipeline remains intact. This patch only delays creation of a
# server-timeline MediaPlayer until the first authoritative timeline packet is available.
# The start position is passed into the constructor, so decoder startup cannot race a setter.
replace(
    PLAYER,
    '''    replayBootstrap: ReplayBootstrap? = null,\n    private val audioStage: AudioDspStage? = null,\n) {\n''',
    '''    replayBootstrap: ReplayBootstrap? = null,\n    private val audioStage: AudioDspStage? = null,\n    initialStartPositionNanos: Long = -1L,\n) {\n''',
)
replace(
    PLAYER,
    '''    private val primedStartPositionNanos = AtomicLong(-1L)\n''',
    '''    private val primedStartPositionNanos = AtomicLong(initialStartPositionNanos.coerceAtLeast(-1L))\n''',
)

# Dedicated TEST1 diagnostics; no URL/token data is logged.
replace(
    CONTROLLER,
    '''import net.minecraft.client.Minecraft\n\n/**\n''',
    '''import net.minecraft.client.Minecraft\nimport org.slf4j.LoggerFactory\n\nprivate val test1SyncGateLogger = LoggerFactory.getLogger("DreamDisplays/Test1SyncGate")\n\n/**\n''',
)

replace(
    CONTROLLER,
    '''    /** Generation counter for async callbacks. */\n    private val generation = atomic(0L)\n\n    /** The active media player, or null between videos and after [shutdown]. */\n''',
    '''    /** Generation counter for async callbacks. */\n    private val generation = atomic(0L)\n\n    private data class PendingInitialLoad(\n        val expectedGeneration: Long,\n        val videoUrl: String,\n        val lang: String,\n        val shouldBePaused: Boolean,\n    )\n\n    @Volatile\n    private var pendingInitialLoad: PendingInitialLoad? = null\n\n    /** True only while a server-timeline video is deliberately held before decoder creation. */\n    internal val hasPendingInitialLoad: Boolean get() = pendingInitialLoad != null\n\n    /** The active media player, or null between videos and after [shutdown]. */\n''',
)

# Suppress duplicate packets while the same video is already waiting for its first timeline.
replace(
    CONTROLLER,
    '''        val current = player\n        if (current != null && screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored) {\n            return\n        }\n\n        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(videoUrl))\n''',
    '''        val current = player\n        val pending = pendingInitialLoad\n        if ((current != null || pending != null) &&\n            screen.videoUrl == videoUrl && screen.lang == lang && !screen.errored\n        ) {\n            return\n        }\n\n        DreamServices.registry.getOrNull(MediaServices.RESOLVER_REGISTRY)?.prefetch(MediaSource.from(videoUrl))\n''',
)

replace(
    CONTROLLER,
    '''        val expected = generation.incrementAndGet()\n        val oldPlayer = player\n''',
    '''        val expected = generation.incrementAndGet()\n        pendingInitialLoad = null\n        val oldPlayer = player\n''',
)

# Replace only the player-construction tail of load(). Local playback still takes the immediate path.
replace(
    CONTROLLER,
    '''        screen.onVideoSwapped(videoUrl, lang)\n        DisplayRegistry.recordScreen(screen)\n        val shouldBePaused = preservePausedState && screen.paused\n        val audioStage = DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.registerSource(screen.uuid)\n        val newPlayer = MediaPlayer(\n            videoUrl, lang, DisplayPlaybackHost(screen), DreamPlaybackEnvironment,\n            screen.takeReplayBootstrap(videoUrl), audioStage,\n        )\n        player = newPlayer\n        screen.timelineFollower.onPlayerCreated()\n        // Set the effective volume (incl. distance) now, before the bridge prelude (which starts at\n        // construction) becomes audible — otherwise its first moment plays at the un-attenuated level.\n        screen.primeNewPlayerVolume(newPlayer)\n        screen.prepareTextureDimensions()\n\n        screen.attachPopout(newPlayer)\n\n        whenInitialized(expected) {\n            start()\n            if (shouldBePaused) {\n                screen.paused = true\n                player?.pause()\n            }\n            reportDurationIfNeeded()\n        }\n\n        Minecraft.getInstance().execute { screen.reloadTexture() }\n''',
    '''        screen.onVideoSwapped(videoUrl, lang)\n        DisplayRegistry.recordScreen(screen)\n        val shouldBePaused = preservePausedState && screen.paused\n        val request = PendingInitialLoad(expected, videoUrl, lang, shouldBePaused)\n\n        if (screen.isWaitingForInitialTimeline) {\n            // Server-timeline modes must not open LAV at a locally guessed/stale position. The request\n            // is sent only after this pending gate exists, so an immediate reply cannot outrun us.\n            pendingInitialLoad = request\n            test1SyncGateLogger.info(\n                "TEST1 initial timeline gate armed display={} generation={}", screen.uuid, expected\n            )\n            screen.requestInitialTimeline()\n            return\n        }\n\n        createPlayer(request, initialStartPositionNanos = -1L)\n''',
)

# Deterministic release: authoritative position is present before MediaPlayer.init dispatches resolve.
replace(
    CONTROLLER,
    '''    /** Applies volume, brightness, and paused state to the player, then seeks to the saved position. */\n    fun start() {\n''',
    '''    @Synchronized\n    internal fun releaseInitialTimeline(startPositionNanos: Long): Boolean {\n        val request = pendingInitialLoad ?: return false\n        if (request.expectedGeneration != generation.value) {\n            pendingInitialLoad = null\n            return false\n        }\n        pendingInitialLoad = null\n        val start = startPositionNanos.coerceAtLeast(0L)\n        test1SyncGateLogger.info(\n            "TEST1 initial timeline gate released display={} start={}ms generation={}",\n            screen.uuid, start / 1_000_000L, request.expectedGeneration\n        )\n        // Server pause/play state will be applied by TimelineFollower; do not reapply a stale local pause.\n        createPlayer(request.copy(shouldBePaused = false), start)\n        return true\n    }\n\n    @Synchronized\n    internal fun releaseInitialTimelineFallback(): Boolean {\n        val request = pendingInitialLoad ?: return false\n        if (request.expectedGeneration != generation.value) {\n            pendingInitialLoad = null\n            return false\n        }\n        pendingInitialLoad = null\n        test1SyncGateLogger.warn(\n            "TEST1 initial timeline gate fallback display={} generation={} (no timeline before timeout)",\n            screen.uuid, request.expectedGeneration\n        )\n        createPlayer(request.copy(shouldBePaused = false), initialStartPositionNanos = 0L)\n        return true\n    }\n\n    private fun createPlayer(request: PendingInitialLoad, initialStartPositionNanos: Long) {\n        if (request.expectedGeneration != generation.value) return\n        val audioStage = DreamServices.registry.getOrNull(AudioAcousticsServices.ACOUSTICS)?.registerSource(screen.uuid)\n        val newPlayer = MediaPlayer(\n            request.videoUrl, request.lang, DisplayPlaybackHost(screen), DreamPlaybackEnvironment,\n            screen.takeReplayBootstrap(request.videoUrl), audioStage,\n            initialStartPositionNanos = initialStartPositionNanos,\n        )\n        player = newPlayer\n        screen.timelineFollower.onPlayerCreated()\n        // Set the effective volume (incl. distance) now, before the bridge prelude becomes audible.\n        screen.primeNewPlayerVolume(newPlayer)\n        screen.prepareTextureDimensions()\n        screen.attachPopout(newPlayer)\n\n        whenInitialized(request.expectedGeneration) {\n            start()\n            if (request.shouldBePaused) {\n                screen.paused = true\n                player?.pause()\n            }\n            reportDurationIfNeeded()\n        }\n\n        Minecraft.getInstance().execute { screen.reloadTexture() }\n    }\n\n    /** Applies volume, brightness, and paused state to the player, then seeks to the saved position. */\n    fun start() {\n''',
)

replace(
    CONTROLLER,
    '''    fun shutdown(): MediaPlayer? {\n        generation.incrementAndGet()\n        videoStarted = false\n''',
    '''    fun shutdown(): MediaPlayer? {\n        generation.incrementAndGet()\n        pendingInitialLoad = null\n        videoStarted = false\n''',
)

# Expose only the narrow hooks needed by TimelineFollower and the existing 5-second self-heal timeout.
replace(
    SCREEN,
    '''    /** Clears the initial-timeline gate so the video may render. */\n    internal fun markInitialTimelineReady() {\n''',
    '''    /** Releases a deferred server-timeline player with a known initial position. */\n    internal fun releaseInitialTimelineLoad(positionNanos: Long): Boolean =\n        media.releaseInitialTimeline(positionNanos)\n\n    /** Clears the initial-timeline gate so the video may render. */\n    internal fun markInitialTimelineReady() {\n''',
)

replace(
    SCREEN,
    '''    /** Sends a [RequestSync] packet to ask the server for the current playback state. */\n    private fun sendRequestSyncPacket() {\n        Initializer.sendPacket(RequestSync(uuid))\n    }\n''',
    '''    /** Sends a [RequestSync] packet to ask the server for the current playback state. */\n    private fun sendRequestSyncPacket() {\n        Initializer.sendPacket(RequestSync(uuid))\n    }\n\n    /** TEST1: request sync only after the controller has armed its pending-load gate. */\n    internal fun requestInitialTimeline() = sendRequestSyncPacket()\n''',
)

replace(
    SCREEN,
    '''    fun tick(pos: BlockPos) {\n        val maxRadius = if (isPopoutActive) Double.MAX_VALUE else ClientStateManager.config.defaultDistance.toDouble()\n''',
    '''    fun tick(pos: BlockPos) {\n        // stillWaitingForInitialTimeline() owns the existing 5 s self-heal. Once it expires, release\n        // exactly once at 0 instead of leaving the display blank forever.\n        if (media.hasPendingInitialLoad && !isWaitingForInitialTimeline) {\n            media.releaseInitialTimelineFallback()\n        }\n        val maxRadius = if (isPopoutActive) Double.MAX_VALUE else ClientStateManager.config.defaultDistance.toDouble()\n''',
)

# When the first authoritative packet arrives, release before applying it. createPlayer() calls
# onPlayerCreated(), which re-enters applyPending() exactly once with this packet still pending.
replace(
    FOLLOWER,
    '''        val packet = Pending(++nextSeq, targetMs, serverTimeMs, paused, loop, System.nanoTime())\n        pending = packet\n        applyPending(packet)\n''',
    '''        val packet = Pending(++nextSeq, targetMs, serverTimeMs, paused, loop, System.nanoTime())\n        pending = packet\n        val projectedStartNanos = projectTargetMs(packet).coerceAtLeast(0L) * 1_000_000L\n        if (screen.releaseInitialTimelineLoad(projectedStartNanos)) return\n        applyPending(packet)\n''',
)

print("TEST1 sync-gate patch applied cleanly")
