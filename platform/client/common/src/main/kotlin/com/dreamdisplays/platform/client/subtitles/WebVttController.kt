package com.dreamdisplays.platform.client.subtitles

import com.dreamdisplays.api.media.source.MediaHttpUrl
import com.dreamdisplays.util.subtitle.WebVttParser
import com.dreamdisplays.util.subtitle.WebVttTrack
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
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
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "DreamDisplays-VTT-deadline").apply { isDaemon = true }
    }
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val BODY_TIMEOUT_MS = 12_000

    fun load(url: String): CompletableFuture<WebVttTrack> = CompletableFuture.supplyAsync({
        val mediaUrl = MediaHttpUrl.parse(url, 2_048) ?: throw IllegalArgumentException("VTT must use HTTP(S).")
        val connection = mediaUrl.uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = BODY_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "DreamDisplays/1.10.4 Kirazium-VTT")
            connection.setRequestProperty("Accept", "text/vtt,text/plain;q=0.9,*/*;q=0.1")
            connection.setRequestProperty("Accept-Encoding", "identity")
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong <= MAX_BYTES) { "VTT exceeds ${MAX_BYTES / 1024} KiB." }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BODY_TIMEOUT_MS.toLong())
            val cancellation = deadlines.schedule({ connection.disconnect() }, BODY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            val bytes = try { connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) throw SocketTimeoutException("VTT body download timed out.")
                    connection.readTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L).toInt()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES) { "VTT exceeds ${MAX_BYTES / 1024} KiB." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } } finally { cancellation.cancel(false) }
            WebVttParser.parse(bytes)
        } finally {
            try { connection.errorStream?.close() } finally { connection.disconnect() }
        }
    }, executor)
}

/** Per-display asynchronous subtitle state backed by an immutable parsed track. */
internal class WebVttController(private val displayId: UUID) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/WebVTT")
    private val generation = AtomicLong()

    @Volatile
    private var source = ""

    @Volatile
    private var track: WebVttTrack? = null

    /** Changes the source, invalidating the old track immediately and loading the new one asynchronously. */
    fun setSource(url: String) {
        val normalized = url.trim()
        if (normalized == source) return
        source = normalized
        track = null
        val token = generation.incrementAndGet()
        if (normalized.isEmpty()) return

        WebVttLoader.load(normalized).whenComplete { loaded, error ->
            if (generation.get() != token || source != normalized) return@whenComplete
            if (error != null) {
                val cause = error.cause ?: error
                logger.warn("$displayId could not load WebVTT: ${cause.javaClass.simpleName}: ${cause.message}")
                return@whenComplete
            }
            track = loaded
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
    }
}
