package com.dreamdisplays.platform.client.subtitles

import com.dreamdisplays.api.security.model.MediaHttpUrl
import com.dreamdisplays.util.subtitle.WebVttParser
import com.dreamdisplays.util.subtitle.WebVttTrack
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Shared bounded downloader. VTT network and parsing work never runs on the render thread. */
private object WebVttLoader {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val executor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "DreamDisplays-VTT").apply { isDaemon = true }
    }
    private val client = HttpClient.newBuilder()
        .executor(executor)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(6))
        .build()

    fun load(url: String): CompletableFuture<WebVttTrack> = CompletableFuture.supplyAsync({
        val mediaUrl = MediaHttpUrl.parse(url, 2_048) ?: throw IllegalArgumentException("VTT must use HTTP(S).")
        val request = HttpRequest.newBuilder(mediaUrl.uri)
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", "DreamDisplays/1.9.5 Kirazium-VTT")
            .header("Accept", "text/vtt,text/plain;q=0.9,*/*;q=0.1")
            .header("Accept-Encoding", "identity")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        response.headers().firstValueAsLong("Content-Length").ifPresent { length ->
            require(length <= MAX_BYTES) { "VTT exceeds ${MAX_BYTES / 1024} KiB." }
        }
        val bytes = response.body().use { input -> input.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "VTT exceeds ${MAX_BYTES / 1024} KiB." }
        WebVttParser.parse(bytes)
    }, executor)
}

/** Per-display asynchronous subtitle state backed by an immutable parsed track. */
internal class WebVttController(
    private val displayId: UUID,
    private val loader: (String) -> CompletableFuture<WebVttTrack> = WebVttLoader::load,
    private val nanoTime: () -> Long = { System.nanoTime() },
) {
    private enum class LoadState {
        IDLE,
        LOADING,
        READY,
        FAILED,
    }

    private val logger = LoggerFactory.getLogger("DreamDisplays/WebVTT")
    private val generation = AtomicLong()

    @Volatile
    private var source = ""

    @Volatile
    private var track: WebVttTrack? = null

    @Volatile
    private var state = LoadState.IDLE

    /** Consecutive failures for the current source; reset only after success or a real source change. */
    private var failureCount = 0

    /** Monotonic instant after which a FAILED source may retry from [activeLines]. */
    private var nextRetryAtNanos = 0L

    /**
     * Changes or refreshes the source. A READY/LOADING source is deduplicated, while a FAILED
     * source is allowed to retry immediately if an explicit same-URL update reaches the controller.
     */
    fun setSource(url: String) {
        val normalized = url.trim()
        var token = 0L
        synchronized(this) {
            val sameSource = normalized == source
            if (sameSource && (state == LoadState.READY || state == LoadState.LOADING)) return

            if (!sameSource) {
                source = normalized
                track = null
                failureCount = 0
                nextRetryAtNanos = 0L
            }

            token = generation.incrementAndGet()
            if (normalized.isEmpty()) {
                track = null
                state = LoadState.IDLE
                failureCount = 0
                nextRetryAtNanos = 0L
                return
            }
            state = LoadState.LOADING
        }
        startLoad(normalized, token)
    }

    /**
     * Active cue lines at the exact player clock position. A failed source retries with bounded
     * exponential backoff while subtitles are actually being queried, never once per render frame.
     */
    fun activeLines(timeNanos: Long): List<String> {
        retryFailedIfDue()
        return track?.activeLines(timeNanos) ?: emptyList()
    }

    /** Invalidates pending completion callbacks and releases the current track. */
    fun close() {
        synchronized(this) {
            generation.incrementAndGet()
            source = ""
            track = null
            state = LoadState.IDLE
            failureCount = 0
            nextRetryAtNanos = 0L
        }
    }

    /** Starts one asynchronous load for [url]; synchronous loader failures use the same failure path. */
    private fun startLoad(url: String, token: Long) {
        val future = runCatching { loader(url) }.getOrElse { error ->
            handleFailure(url, token, error)
            return
        }
        future.whenComplete { loaded, error ->
            if (error != null) {
                handleFailure(url, token, error.cause ?: error)
                return@whenComplete
            }

            synchronized(this) {
                if (generation.get() != token || source != url) return@whenComplete
                track = loaded
                state = LoadState.READY
                failureCount = 0
                nextRetryAtNanos = 0L
            }
            logger.info("$displayId loaded WebVTT (${loaded.cueCount} cues).")
        }
    }

    /** Marks the current generation failed and computes the next backoff instant. */
    private fun handleFailure(url: String, token: Long, error: Throwable) {
        synchronized(this) {
            if (generation.get() != token || source != url) return
            track = null
            state = LoadState.FAILED
            failureCount++
            val delay = RETRY_DELAYS_NANOS[(failureCount - 1).coerceAtMost(RETRY_DELAYS_NANOS.lastIndex)]
            nextRetryAtNanos = nanoTime() + delay
        }
        logger.warn("$displayId could not load WebVTT: ${error.javaClass.simpleName}: ${error.message}")
    }

    /** Promotes a due FAILED source to LOADING exactly once; later render calls see LOADING and do nothing. */
    private fun retryFailedIfDue() {
        var retryUrl: String? = null
        var token = 0L
        synchronized(this) {
            if (state != LoadState.FAILED || source.isEmpty() || nanoTime() < nextRetryAtNanos) return
            state = LoadState.LOADING
            retryUrl = source
            token = generation.incrementAndGet()
        }
        startLoad(retryUrl ?: return, token)
    }

    companion object {
        /** 1s, 2s, 5s, 15s, 30s, then at most one retry per minute for a persistent failure. */
        private val RETRY_DELAYS_NANOS = longArrayOf(1L, 2L, 5L, 15L, 30L, 60L)
            .map { seconds -> seconds * 1_000_000_000L }
            .toLongArray()
    }
}
