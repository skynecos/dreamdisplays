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

    /**
     * Changes or refreshes the source. A READY/LOADING source is deduplicated, while a FAILED
     * source is allowed to retry when the server or catalog sends the same URL again.
     */
    fun setSource(url: String) {
        val normalized = url.trim()
        val sameSource = normalized == source
        if (sameSource && (state == LoadState.READY || state == LoadState.LOADING)) return

        if (!sameSource) {
            source = normalized
            track = null
        }

        val token = generation.incrementAndGet()
        if (normalized.isEmpty()) {
            track = null
            state = LoadState.IDLE
            return
        }

        state = LoadState.LOADING
        loader(normalized).whenComplete { loaded, error ->
            if (generation.get() != token || source != normalized) return@whenComplete
            if (error != null) {
                track = null
                state = LoadState.FAILED
                val cause = error.cause ?: error
                logger.warn("$displayId could not load WebVTT: ${cause.javaClass.simpleName}: ${cause.message}")
                return@whenComplete
            }
            track = loaded
            state = LoadState.READY
            logger.info("$displayId loaded WebVTT (${loaded.cueCount} cues).")
        }
    }

    /** Active cue lines at the exact player clock position. */
    fun activeLines(timeNanos: Long): List<String> = track?.activeLines(timeNanos) ?: emptyList()

    /** Invalidates pending completion callbacks and releases the current track. */
    fun close() {
        generation.incrementAndGet()
        source = ""
        track = null
        state = LoadState.IDLE
    }
}
