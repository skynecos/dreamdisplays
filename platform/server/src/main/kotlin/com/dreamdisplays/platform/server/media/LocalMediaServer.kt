package com.dreamdisplays.platform.server.media

import com.dreamdisplays.api.security.model.MediaHttpUrl
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.util.http.SingleByteRanges
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Paper-side HTTP origin for MP4 and WebVTT files below `plugins/DreamDisplays/media`.
 * Files are streamed byte-for-byte; MP4 is never transcoded. Single HTTP byte ranges allow FFmpeg
 * to seek without downloading the whole file.
 */
@PaperOnly
object LocalMediaServer {
    private val logger = LoggerFactory.getLogger("DreamDisplays/LocalMedia")

    private const val STORED_PREFIX = "ddfile:"
    private const val CONTEXT_PATH = "/dreamdisplays-media/"
    private const val CONFIG_NAME = "media-server.properties"
    private val allowedExtensions = setOf("mp4", "vtt")
    private val threadNumber = AtomicInteger()

    private data class Settings(
        val root: Path,
        val realRoot: Path,
        val bindAddress: String,
        val port: Int,
        val publicBaseUrl: String,
        val enabled: Boolean,
    )

    private data class LocalFile(val relative: String, val realPath: Path)

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var settings: Settings? = null

    private var executor: ExecutorService? = null

    /** True only while the configured HTTP listener is active. */
    val isRunning: Boolean get() = server != null

    /** Starts or restarts the local media origin. Failure is logged without disabling Dream Displays. */
    fun start(plugin: PaperServer): Boolean {
        stop()
        return runCatching {
            val loaded = loadSettings(plugin)
            settings = loaded
            if (!loaded.enabled) {
                logger.info("Local media server is disabled in $CONFIG_NAME.")
                return true
            }

            val http = HttpServer.create(InetSocketAddress(loaded.bindAddress, loaded.port), 0)
            val pool = Executors.newCachedThreadPool { task ->
                Thread(task, "DreamDisplays-MediaHTTP-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
            }
            http.executor = pool
            http.createContext(CONTEXT_PATH) { exchange -> handle(exchange, loaded) }
            http.start()

            executor = pool
            server = http
            logger.info(
                "Local media server listening on ${loaded.bindAddress}:${loaded.port}; " +
                    "public base ${loaded.publicBaseUrl}.",
            )
            val publicHost = runCatching { URI(loaded.publicBaseUrl).host.orEmpty() }.getOrDefault("")
            if (publicHost.equals("localhost", true) || publicHost == "127.0.0.1" || publicHost == "::1") {
                logger.warn(
                    "Local media public-base-url points to this machine only. Remote players need a public " +
                        "address in $CONFIG_NAME and TCP port ${loaded.port} must be reachable.",
                )
            }
            true
        }.getOrElse { error ->
            logger.error("Could not start the local media server: ${error.message}", error)
            stop()
            false
        }
    }

    /** Re-reads [CONFIG_NAME] and restarts the listener. */
    fun reload(plugin: PaperServer): Boolean = start(plugin)

    /** Stops the listener and all daemon request workers. */
    fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    /**
     * Validates [raw] as an existing local [extension] file and returns a persistent `ddfile:`
     * reference. Absolute paths, traversal, and symlink escapes are rejected.
     */
    fun referenceForExisting(raw: String, extension: String): String? {
        if (!isRunning) return null
        val loaded = settings ?: return null
        val local = resolveExisting(loaded, raw, extension.lowercase()) ?: return null
        return STORED_PREFIX + local.relative
    }

    /** Turns a stored `ddfile:` reference into the public HTTP URL sent to a client. */
    fun resolveForClient(source: String): String {
        if (!source.startsWith(STORED_PREFIX)) return source
        if (!isRunning) return ""
        val loaded = settings ?: return ""
        val local = resolveExisting(loaded, source.removePrefix(STORED_PREFIX), null) ?: return ""
        return publicUrl(loaded, local.relative)
    }

    /** Absolute folder into which the operator places `videos/*.mp4` and `subtitles/*.vtt`. */
    fun mediaDirectory(): String = settings?.root?.toString() ?: "plugins/DreamDisplays/media"

    private fun loadSettings(plugin: PaperServer): Settings {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        val root = File(plugin.dataFolder, "media").toPath().toAbsolutePath().normalize()
        Files.createDirectories(root.resolve("videos"))
        Files.createDirectories(root.resolve("subtitles"))
        val realRoot = root.toRealPath()

        val file = File(plugin.dataFolder, CONFIG_NAME)
        val properties = Properties()
        if (file.isFile) file.inputStream().use(properties::load)

        val enabled = properties.getProperty("enabled", "true").trim().toBooleanStrictOrNull() ?: true
        val bindAddress = properties.getProperty("bind-address", "0.0.0.0").trim().ifEmpty { "0.0.0.0" }
        val port = properties.getProperty("port", "8095").trim().toIntOrNull()?.coerceIn(1, 65_535) ?: 8095
        val rawPublicBase = properties.getProperty("public-base-url", "http://127.0.0.1:$port")
            .trim()
            .trimEnd('/')
        val publicBaseUrl = MediaHttpUrl.parse(rawPublicBase, 2_048)?.value
            ?: throw IllegalArgumentException("public-base-url must be an absolute HTTP(S) address")
        val publicBaseUri = URI(publicBaseUrl)
        require(publicBaseUri.rawQuery == null && publicBaseUri.rawFragment == null) {
            "public-base-url must not contain a query or fragment"
        }

        if (!file.exists()) {
            properties.setProperty("enabled", enabled.toString())
            properties.setProperty("bind-address", bindAddress)
            properties.setProperty("port", port.toString())
            properties.setProperty("public-base-url", publicBaseUrl)
            file.outputStream().use { output ->
                properties.store(
                    output,
                    "Dream Displays local MP4/VTT server. public-base-url must be reachable by players.",
                )
            }
        }

        return Settings(root, realRoot, bindAddress, port, publicBaseUrl, enabled)
    }

    private fun resolveExisting(loaded: Settings, raw: String, requiredExtension: String?): LocalFile? {
        val relative = normalizeRelative(raw) ?: return null
        val unresolved = loaded.root.resolve(relative).normalize()
        if (!unresolved.startsWith(loaded.root)) return null

        val extension = unresolved.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (extension !in allowedExtensions) return null
        if (requiredExtension != null && extension != requiredExtension) return null

        val realPath = runCatching { unresolved.toRealPath() }.getOrNull() ?: return null
        if (!realPath.startsWith(loaded.realRoot) || !Files.isRegularFile(realPath)) return null
        val safeRelative = loaded.realRoot.relativize(realPath).joinToString("/") { it.toString() }
        return LocalFile(safeRelative, realPath)
    }

    private fun normalizeRelative(raw: String): Path? {
        var value = raw.trim().replace('\\', '/')
        if (value.startsWith(STORED_PREFIX)) value = value.removePrefix(STORED_PREFIX)
        if (value.isEmpty() || value.indexOf('\u0000') >= 0) return null
        val path = runCatching { Path.of(value).normalize() }.getOrNull() ?: return null
        return path.takeUnless { it.isAbsolute || it.toString().isEmpty() }
    }

    private fun publicUrl(loaded: Settings, relative: String): String {
        val encoded = relative.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
        }
        return loaded.publicBaseUrl + CONTEXT_PATH + encoded
    }

    private fun handle(exchange: HttpExchange, loaded: Settings) {
        try {
            val method = exchange.requestMethod.uppercase()
            if (method != "GET" && method != "HEAD") {
                exchange.responseHeaders.set("Allow", "GET, HEAD")
                sendText(exchange, 405, "Method Not Allowed")
                return
            }

            val rawPath = exchange.requestURI.rawPath.orEmpty()
            if (!rawPath.startsWith(CONTEXT_PATH)) {
                sendText(exchange, 404, "Not Found")
                return
            }
            val decoded = rawPath.removePrefix(CONTEXT_PATH).split('/').joinToString("/") { segment ->
                runCatching { URLDecoder.decode(segment, StandardCharsets.UTF_8) }.getOrDefault("")
            }
            val local = resolveExisting(loaded, decoded, null)
            if (local == null) {
                sendText(exchange, 404, "Not Found")
                return
            }

            val size = Files.size(local.realPath)
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("Cache-Control", "public, max-age=60")
            exchange.responseHeaders.set("Content-Type", contentType(local.realPath))

            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            val range = rangeHeader?.let { SingleByteRanges.parse(it, size) }
            if (rangeHeader != null && range == null) {
                exchange.responseHeaders.set("Content-Range", "bytes */$size")
                sendText(exchange, 416, "Range Not Satisfiable")
                return
            }

            val start = range?.startInclusive ?: 0L
            val end = range?.endInclusive ?: (size - 1L)
            val length = range?.length ?: size
            val status = if (range == null) 200 else 206
            if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$size")
            exchange.responseHeaders.set("Content-Length", length.toString())

            if (method == "HEAD" || length == 0L) {
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
                return
            }

            exchange.sendResponseHeaders(status, length)
            RandomAccessFile(local.realPath.toFile(), "r").use { input ->
                input.seek(start)
                exchange.responseBody.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        } catch (error: IOException) {
            logger.debug("Local media connection ended: ${error.message}")
            runCatching(exchange::close)
        } catch (error: Exception) {
            logger.warn("Local media request failed: ${error.javaClass.simpleName}: ${error.message}")
            runCatching(exchange::close)
        }
    }

    private fun contentType(path: Path): String = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "vtt" -> "text/vtt; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun sendText(exchange: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.responseHeaders.set("Content-Length", bytes.size.toString())
        if (exchange.requestMethod.equals("HEAD", true)) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            return
        }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
