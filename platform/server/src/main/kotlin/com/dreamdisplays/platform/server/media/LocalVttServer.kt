package com.dreamdisplays.platform.server.media

import com.dreamdisplays.api.media.source.MediaHttpUrl
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.shared.subtitle.VttSubtitleSource
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Optional, VTT-only origin. The previous media folder and properties file remain usable. */
@PaperOnly
object LocalVttServer {
    private val logger = LoggerFactory.getLogger("DreamDisplays/LocalVtt")
    private const val PREFIX = "ddfile:"
    private const val CONTEXT = "/dreamdisplays-media/"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private var root: Path? = null
    private var publicBase = ""
    private var http: HttpServer? = null
    private var executor: ExecutorService? = null

    val isRunning: Boolean get() = http != null

    fun start(plugin: PaperServer) {
        stop()
        runCatching {
            val folder = plugin.dataFolder.toPath().resolve("media")
            Files.createDirectories(folder.resolve("subtitles"))
            root = folder.toRealPath()
            val file = plugin.dataFolder.resolve("media-server.properties")
            val properties = Properties()
            if (file.isFile) file.inputStream().use(properties::load)
            val enabled = properties.getProperty("enabled", "false").trim().toBoolean()
            val bind = properties.getProperty("bind-address", "127.0.0.1").trim()
            val port = properties.getProperty("port", "8095").trim().toIntOrNull()?.coerceIn(1, 65535) ?: 8095
            publicBase = properties.getProperty("public-base-url", "http://127.0.0.1:$port").trim().trimEnd('/')
            val parsed = MediaHttpUrl.parse(publicBase, 2048) ?: error("Invalid public-base-url")
            require(parsed.uri.rawQuery == null && parsed.uri.rawFragment == null) { "public-base-url cannot have a query or fragment" }
            if (!file.exists()) {
                properties.setProperty("enabled", enabled.toString())
                properties.setProperty("bind-address", bind)
                properties.setProperty("port", port.toString())
                properties.setProperty("public-base-url", publicBase)
                file.outputStream().use { properties.store(it, "Optional WebVTT origin. Public URL must be reachable by viewers.") }
            }
            if (enabled) {
                val pool = Executors.newFixedThreadPool(2) { task -> Thread(task, "DreamDisplays-VTT").apply { isDaemon = true } }
                val listener = HttpServer.create(InetSocketAddress(bind, port), 16)
                listener.executor = pool
                listener.createContext(CONTEXT, ::serve)
                listener.start()
                executor = pool
                http = listener
                logger.info("Local VTT origin listening on {}:{}", bind, port)
            }
        }.onFailure { logger.error("Could not start the local VTT origin.", it); stop() }
    }

    fun stop() {
        http?.stop(0)
        http = null
        executor?.shutdownNow()
        executor = null
    }

    fun referenceForExisting(file: String): String? = resolve(file)?.let { PREFIX + root!!.relativize(it).joinToString("/") }

    /** Resolves persistent local references only at the outgoing packet boundary. */
    fun forClients(data: Map<String, ByteArray>): Map<String, ByteArray> {
        val source = data[VttSubtitleSource.KEY]?.toString(UTF_8) ?: return data
        if (!source.startsWith(PREFIX)) return data
        val output = data.toMutableMap()
        output.remove(VttSubtitleSource.KEY)
        if (isRunning) {
            resolve(source)?.let { file ->
                val encoded = root!!.relativize(file).joinToString("/") { URLEncoder.encode(it.toString(), UTF_8).replace("+", "%20") }
                VttSubtitleSource.write(output, publicBase + CONTEXT + encoded)
            }
        }
        return output
    }

    private fun resolve(raw: String): Path? {
        val base = root ?: return null
        val relative = runCatching { Path.of(raw.removePrefix(PREFIX).replace('\\', '/')).normalize() }.getOrNull() ?: return null
        if (relative.isAbsolute || relative.toString().isBlank()) return null
        val candidate = base.resolve(relative).normalize()
        if (!candidate.startsWith(base) || !candidate.fileName.toString().endsWith(".vtt", true)) return null
        val real = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        return real.takeIf { it.startsWith(base) && Files.isRegularFile(it) && Files.size(it) <= MAX_BYTES }
    }

    private fun serve(exchange: HttpExchange) {
        exchange.use {
            runCatching {
                if (it.requestMethod != "GET" && it.requestMethod != "HEAD") {
                    it.responseHeaders.set("Allow", "GET, HEAD")
                    it.sendResponseHeaders(405, -1)
                    return
                }
                val encoded = it.requestURI.rawPath.removePrefix(CONTEXT)
                val relative = encoded.split('/').joinToString("/") { segment -> URLDecoder.decode(segment, UTF_8) }
                val path = resolve(relative)
                if (path == null) {
                    it.sendResponseHeaders(404, -1)
                    return
                }
                val size = Files.size(path)
                it.responseHeaders.set("Content-Type", "text/vtt; charset=utf-8")
                it.responseHeaders.set("Content-Length", size.toString())
                it.responseHeaders.set("Cache-Control", "public, max-age=60")
                it.sendResponseHeaders(200, if (it.requestMethod == "HEAD" || size == 0L) -1 else size)
                if (it.requestMethod == "GET" && size > 0) Files.newInputStream(path).use { input -> input.copyTo(it.responseBody) }
            }.onFailure { error -> logger.debug("VTT request ended: {}", error.message) }
        }
    }
}
