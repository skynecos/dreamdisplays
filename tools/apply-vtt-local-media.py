#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    found = text.count(old)
    if found != count:
        raise SystemExit(
            f"Patch anchor mismatch in {path}: expected exactly {count}, found {found}: {old[:180]!r}"
        )
    p.write_text(text.replace(old, new, count), encoding="utf-8")


def write(path: str, content: str) -> None:
    p = ROOT / path
    if p.exists():
        raise SystemExit(f"Refusing to overwrite existing upstream file: {path}")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")


# ---------------------------------------------------------------------------
# Protocol: append-only protobuf field. Frozen v1 stays byte-for-byte unchanged.
# ---------------------------------------------------------------------------
replace(
    "core/src/main/kotlin/com/dreamdisplays/core/protocol/common/packets/DisplayPackets.kt",
    '''    @ProtoNumber(18) val scheduledStartEpochMillis: Long = 0,\n    @ProtoNumber(19) val scheduledAction: Int = -1,\n) : DreamPacket\n''',
    '''    @ProtoNumber(18) val scheduledStartEpochMillis: Long = 0,\n    @ProtoNumber(19) val scheduledAction: Int = -1,\n    /** Optional WebVTT source URL. Empty means subtitles are disabled. */\n    @ProtoNumber(20) val subtitleUrl: String = "",\n) : DreamPacket\n''',
)


# ---------------------------------------------------------------------------
# Persistent server model + DB migration.
# ---------------------------------------------------------------------------
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/datatypes/display/DisplayData.kt",
    '''    /** Video's language code. */\n    var lang: String\n\n    /** Optional, space-free alias usable anywhere a display id is accepted (see [com.dreamdisplays.platform.server.managers.DisplayManager.resolveByIdOrPrefix]). */\n''',
    '''    /** Video's language code. */\n    var lang: String\n\n    /** Optional WebVTT subtitle source. May be HTTP(S) or an internal `ddfile:` reference on Paper. */\n    var subtitleUrl: String\n\n    /** Optional, space-free alias usable anywhere a display id is accepted (see [com.dreamdisplays.platform.server.managers.DisplayManager.resolveByIdOrPrefix]). */\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/datatypes/display/DisplayData.kt",
    '''    /** Video's language code. */\n    override var lang: String = ""\n\n    /** Optional, space-free alias usable anywhere a display id is accepted. */\n''',
    '''    /** Video's language code. */\n    override var lang: String = ""\n\n    /** Optional WebVTT subtitle source. */\n    override var subtitleUrl: String = ""\n\n    /** Optional, space-free alias usable anywhere a display id is accepted. */\n''',
)

replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/managers/StorageManager.kt",
    '''    /** String representing the language code of the video associated with the display. */\n    val lang = varchar("lang", 255).default("")\n\n    /** Boolean indicating whether the display is locked to its owner. */\n''',
    '''    /** String representing the language code of the video associated with the display. */\n    val lang = varchar("lang", 255).default("")\n\n    /** Optional WebVTT source (HTTP(S) or Paper-local ddfile reference). */\n    val subtitleUrl = varchar("subtitleUrl", MediaUrlPolicy.MAX_URL_LENGTH).default("")\n\n    /** Boolean indicating whether the display is locked to its owner. */\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/managers/StorageManager.kt",
    '''                it[lang] = data.lang\n                it[isLocked] = data.isLocked\n''',
    '''                it[lang] = data.lang\n                it[subtitleUrl] = data.subtitleUrl\n                it[isLocked] = data.isLocked\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/managers/StorageManager.kt",
    '''        lang = row[table.lang]\n        isLocked = row[table.isLocked]\n''',
    '''        lang = row[table.lang]\n        subtitleUrl = row[table.subtitleUrl]\n        isLocked = row[table.isLocked]\n''',
)


# ---------------------------------------------------------------------------
# Paper local-media HTTP server: MP4 + VTT, GET/HEAD, proper byte ranges.
# Internal ddfile: references survive public-base-url changes in the database.
# ---------------------------------------------------------------------------
write(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/media/LocalMediaServer.kt",
    r'''package com.dreamdisplays.platform.server.media

import com.dreamdisplays.platform.server.PaperServer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Small Paper-side HTTP origin for server-local Dream Displays media.
 *
 * Only files below `plugins/DreamDisplays/media` with an explicitly supported extension are served.
 * Video bytes are never transcoded. HTTP Range/206 is implemented so FFmpeg can seek without
 * downloading the full MP4.
 */
@PaperOnly
object LocalMediaServer {
    private val logger = LoggerFactory.getLogger("DreamDisplays/LocalMedia")

    private const val STORED_PREFIX = "ddfile:"
    private const val CONTEXT = "/dreamdisplays-media/"
    private const val CONFIG_NAME = "media-server.properties"
    private val ALLOWED_EXTENSIONS = setOf("mp4", "vtt")

    private data class Settings(
        val root: Path,
        val bind: String,
        val port: Int,
        val publicBaseUrl: String,
        val enabled: Boolean,
    )

    @Volatile private var server: HttpServer? = null
    @Volatile private var settings: Settings? = null
    private var executor: ExecutorService? = null

    /** True when the local HTTP origin is actually listening. */
    val isRunning: Boolean get() = server != null

    /** Starts the origin and creates the media folder/config on first run. Failure never disables the plugin. */
    fun start(plugin: PaperServer) {
        stop()
        val root = File(plugin.dataFolder, "media").toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        val loaded = loadSettings(plugin, root)
        settings = loaded
        if (!loaded.enabled) {
            logger.info("Local media server is disabled in $CONFIG_NAME.")
            return
        }

        runCatching {
            val http = HttpServer.create(InetSocketAddress(loaded.bind, loaded.port), 0)
            val pool = Executors.newCachedThreadPool { task ->
                Thread(task, "DreamDisplays-MediaHTTP").apply { isDaemon = true }
            }
            http.executor = pool
            http.createContext(CONTEXT) { exchange -> handle(exchange, loaded) }
            http.start()
            executor = pool
            server = http
            logger.info("Local media server listening on ${loaded.bind}:${loaded.port}; public base ${loaded.publicBaseUrl}.")
            if (loaded.publicBaseUrl.contains("127.0.0.1") || loaded.publicBaseUrl.contains("localhost")) {
                logger.warn("Local media public-base-url is loopback; remote players cannot use local MP4/VTT until it is changed in $CONFIG_NAME.")
            }
        }.onFailure { e ->
            logger.error("Could not start local media server on ${loaded.bind}:${loaded.port}: ${e.message}", e)
            stop()
            settings = loaded
        }
    }

    /** Stops the listener and its daemon request pool. */
    fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    /**
     * Validates [raw] as an existing local media file of [extension] and returns a persistent
     * `ddfile:` reference. Absolute paths and traversal outside the media root are rejected.
     */
    fun referenceForExisting(raw: String, extension: String): String? {
        if (!isRunning) return null
        val cfg = settings ?: return null
        val normalized = normalizeRelative(cfg.root, raw, extension.lowercase()) ?: return null
        val file = cfg.root.resolve(normalized).normalize()
        if (!Files.isRegularFile(file)) return null
        return STORED_PREFIX + normalized.replace(File.separatorChar, '/')
    }

    /** Resolves an internal `ddfile:` source to the public HTTP URL sent to a client. */
    fun resolveForClient(source: String): String {
        if (!source.startsWith(STORED_PREFIX)) return source
        val cfg = settings ?: return ""
        val relative = source.removePrefix(STORED_PREFIX)
        val safe = normalizeRelative(cfg.root, relative, null) ?: return ""
        return publicUrl(cfg, safe.replace(File.separatorChar, '/'))
    }

    /** Returns the media directory, useful in command error messages. */
    fun mediaDirectory(): String = settings?.root?.toString() ?: "plugins/DreamDisplays/media"

    private fun loadSettings(plugin: PaperServer, root: Path): Settings {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        val file = File(plugin.dataFolder, CONFIG_NAME)
        val props = Properties()
        if (file.isFile) file.inputStream().use(props::load)

        val enabled = props.getProperty("enabled", "true").trim().toBooleanStrictOrNull() ?: true
        val bind = props.getProperty("bind", "0.0.0.0").trim().ifBlank { "0.0.0.0" }
        val port = props.getProperty("port", "8095").trim().toIntOrNull()?.coerceIn(1, 65535) ?: 8095
        val publicBase = props.getProperty("public-base-url", "http://127.0.0.1:$port")
            .trim().trimEnd('/').ifBlank { "http://127.0.0.1:$port" }

        if (!file.exists()) {
            props.setProperty("enabled", enabled.toString())
            props.setProperty("bind", bind)
            props.setProperty("port", port.toString())
            props.setProperty("public-base-url", publicBase)
            file.outputStream().use { out ->
                props.store(out, "Dream Displays local MP4/VTT server. public-base-url must be reachable by players.")
            }
        }
        return Settings(root, bind, port, publicBase, enabled)
    }

    private fun normalizeRelative(root: Path, raw: String, extension: String?): String? {
        var text = raw.trim().replace('\\', '/')
        if (text.startsWith(STORED_PREFIX)) text = text.removePrefix(STORED_PREFIX)
        if (text.isBlank() || text.indexOf('\u0000') >= 0) return null
        val rel = runCatching { Path.of(text).normalize() }.getOrNull() ?: return null
        if (rel.isAbsolute || rel.toString().isBlank()) return null
        val resolved = root.resolve(rel).normalize()
        if (!resolved.startsWith(root)) return null
        val ext = resolved.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext !in ALLOWED_EXTENSIONS) return null
        if (extension != null && ext != extension) return null
        return root.relativize(resolved).toString()
    }

    private fun publicUrl(cfg: Settings, relative: String): String {
        val encoded = relative.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
        }
        return cfg.publicBaseUrl + CONTEXT + encoded
    }

    private fun handle(exchange: HttpExchange, cfg: Settings) {
        try {
            val method = exchange.requestMethod.uppercase()
            if (method != "GET" && method != "HEAD") {
                exchange.responseHeaders.set("Allow", "GET, HEAD")
                sendText(exchange, 405, "Method Not Allowed")
                return
            }

            val rawPath = exchange.requestURI.rawPath ?: ""
            if (!rawPath.startsWith(CONTEXT)) {
                sendText(exchange, 404, "Not Found")
                return
            }
            val encodedRelative = rawPath.removePrefix(CONTEXT)
            val decoded = encodedRelative.split('/').joinToString("/") { part ->
                runCatching { URLDecoder.decode(part, StandardCharsets.UTF_8) }.getOrElse { "" }
            }
            val relative = normalizeRelative(cfg.root, decoded, null)
            if (relative == null) {
                sendText(exchange, 403, "Forbidden")
                return
            }
            val file = cfg.root.resolve(relative).normalize()
            if (!Files.isRegularFile(file)) {
                sendText(exchange, 404, "Not Found")
                return
            }

            val size = Files.size(file)
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("Cache-Control", "public, max-age=60")
            exchange.responseHeaders.set("Content-Type", contentType(file))

            val header = exchange.requestHeaders.getFirst("Range")
            val range = if (header == null) null else parseRange(header, size)
            if (header != null && range == null) {
                exchange.responseHeaders.set("Content-Range", "bytes */$size")
                sendText(exchange, 416, "Range Not Satisfiable")
                return
            }

            val start = range?.first ?: 0L
            val end = range?.last ?: (size - 1L)
            val length = if (size == 0L) 0L else end - start + 1L
            val status = if (range == null) 200 else 206
            if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$size")
            exchange.responseHeaders.set("Content-Length", length.toString())

            if (method == "HEAD") {
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
                return
            }
            if (length == 0L) {
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
                return
            }

            exchange.sendResponseHeaders(status, length)
            RandomAccessFile(file.toFile(), "r").use { input ->
                input.seek(start)
                exchange.responseBody.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Local media request failed: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { exchange.close() }
        }
    }

    private fun parseRange(header: String, size: Long): LongRange? {
        if (size <= 0L || !header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substringAfter('=').trim()
        if (spec.isEmpty() || ',' in spec) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()

        val start: Long
        val end: Long
        if (left.isEmpty()) {
            val suffix = right.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            start = max(0L, size - suffix)
            end = size - 1
        } else {
            start = left.toLongOrNull() ?: return null
            if (start < 0 || start >= size) return null
            end = if (right.isEmpty()) size - 1 else min(right.toLongOrNull() ?: return null, size - 1)
            if (end < start) return null
        }
        return start..end
    }

    private fun contentType(file: Path): String = when (file.fileName.toString().substringAfterLast('.', "").lowercase()) {
        "mp4" -> "video/mp4"
        "vtt" -> "text/vtt; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun sendText(exchange: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
''',
)


# Start/stop the local origin with the Paper plugin lifecycle.
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/PaperServer.kt",
    '''import com.dreamdisplays.platform.server.managers.StorageManager\n''',
    '''import com.dreamdisplays.platform.server.managers.StorageManager\nimport com.dreamdisplays.platform.server.media.LocalMediaServer\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/PaperServer.kt",
    '''    fun doEnable() {\n        Scheduler.init(this)\n\n        val s = Companion.config.storage\n''',
    '''    fun doEnable() {\n        Scheduler.init(this)\n        LocalMediaServer.start(this)\n\n        val s = Companion.config.storage\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/PaperServer.kt",
    '''    fun doDisable() {\n        if (::storage.isInitialized) {\n''',
    '''    fun doDisable() {\n        LocalMediaServer.stop()\n        if (::storage.isInitialized) {\n''',
)


# ---------------------------------------------------------------------------
# Paper commands: local MP4 and WebVTT url/file/off.
# ---------------------------------------------------------------------------
write(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/commands/subcommands/LocalVideoCommand.kt",
    r'''package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.api.playback.policy.PlaybackPermissions
import com.dreamdisplays.api.security.model.LanguageTag
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.managers.StateManager
import com.dreamdisplays.platform.server.media.LocalMediaServer
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.playback.TimelineManager
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Assigns an MP4 from plugins/DreamDisplays/media without transcoding it. */
@PaperOnly
class LocalVideoCommand {
    fun execute(sender: CommandSender, token: String, file: String, lang: String) {
        val player = sender as? Player ?: return
        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return
        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uniqueId, player.hasPermission(PaperServer.config.permissions.delete))
            )
        ) {
            player.sendMessage("§dDream Displays §7» §cBu ekranın videosunu değiştiremezsin.")
            return
        }
        if (!LocalMediaServer.isRunning) {
            player.sendMessage("§dDream Displays §7» §cYerel medya sunucusu çalışmıyor. media-server.properties dosyasını kontrol et.")
            return
        }
        val source = LocalMediaServer.referenceForExisting(file, "mp4")
        if (source == null) {
            player.sendMessage("§dDream Displays §7» §cMP4 bulunamadı veya geçersiz yol: ${LocalMediaServer.mediaDirectory()}")
            return
        }

        val changed = data.url != source
        val wasSync = data.isSync
        data.url = source
        data.lang = LanguageTag.canonicalAudioCode(lang).value
        if (changed) data.subtitleUrl = ""

        runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
        DisplayManager.broadcastUpdate(data)
        if (wasSync) StateManager.resetAndBroadcast(data)
        TimelineManager.onVideoChanged(data)
        player.sendMessage("§dDream Displays §7» §fYerel MP4 ayarlandı: §d$file")
    }
}
''',
)

write(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/commands/subcommands/SubtitleCommand.kt",
    r'''package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.api.media.source.url.CustomMediaUrls
import com.dreamdisplays.api.playback.policy.PlaybackPermissions
import com.dreamdisplays.api.security.policy.MediaUrlPolicy
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.media.LocalMediaServer
import com.dreamdisplays.platform.server.meta.Scheduler.runAsync
import com.dreamdisplays.platform.server.playback.PlaybackContexts
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.net.CustomMediaGate
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Configures the optional WebVTT source independently from the video source. */
@PaperOnly
class SubtitleCommand {
    fun setUrl(sender: CommandSender, token: String, rawUrl: String) {
        val player = sender as? Player ?: return
        val data = editable(sender, player, token) ?: return
        val normalized = CustomMediaUrls.normalize(rawUrl)
            ?.takeIf(MediaUrlPolicy::isAllowed)
        if (normalized == null) {
            MessageUtil.sendMessage(player, "invalidURL")
            return
        }
        CustomMediaGate.refusalKey(
            normalized,
            PaperServer.config.settings.customMediaPolicy,
            player.hasPermission(PaperServer.config.permissions.custom),
            player.uniqueId,
        )?.let {
            MessageUtil.sendMessage(player, it)
            return
        }
        apply(player, data, normalized, "VTT URL ayarlandı.")
    }

    fun setFile(sender: CommandSender, token: String, file: String) {
        val player = sender as? Player ?: return
        val data = editable(sender, player, token) ?: return
        if (!LocalMediaServer.isRunning) {
            player.sendMessage("§dDream Displays §7» §cYerel medya sunucusu çalışmıyor. media-server.properties dosyasını kontrol et.")
            return
        }
        val source = LocalMediaServer.referenceForExisting(file, "vtt")
        if (source == null) {
            player.sendMessage("§dDream Displays §7» §cVTT bulunamadı veya geçersiz yol: ${LocalMediaServer.mediaDirectory()}")
            return
        }
        apply(player, data, source, "Yerel VTT ayarlandı: §d$file")
    }

    fun clear(sender: CommandSender, token: String) {
        val player = sender as? Player ?: return
        val data = editable(sender, player, token) ?: return
        apply(player, data, "", "Altyazı kapatıldı.")
    }

    private fun editable(sender: CommandSender, player: Player, token: String): PaperDisplayData? {
        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return null
        if (!PlaybackPermissions.canSetVideo(
                PlaybackContexts.of(data, player.uniqueId, player.hasPermission(PaperServer.config.permissions.delete))
            )
        ) {
            player.sendMessage("§dDream Displays §7» §cBu ekranın altyazısını değiştiremezsin.")
            return null
        }
        return data
    }

    private fun apply(player: Player, data: PaperDisplayData, source: String, message: String) {
        if (data.subtitleUrl == source) {
            player.sendMessage("§dDream Displays §7» §f$message")
            return
        }
        data.subtitleUrl = source
        runAsync { PaperServer.getInstance().storage.saveDisplay(data) }
        DisplayManager.broadcastUpdate(data)
        player.sendMessage("§dDream Displays §7» §f$message")
    }
}
''',
)

# Clear a stale subtitle when the actual remote video source changes.
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/commands/subcommands/VideoCommand.kt",
    '''        val wasSync = data.isSync\n        data.apply {\n            url = requestedUrl\n            lang = LanguageTag.canonicalAudioCode(args.getOrNull(2)).value\n        }\n''',
    '''        val videoChanged = data.url != requestedUrl\n        val wasSync = data.isSync\n        data.apply {\n            url = requestedUrl\n            lang = LanguageTag.canonicalAudioCode(args.getOrNull(2)).value\n            if (videoChanged) subtitleUrl = ""\n        }\n''',
)

# Paper Brigadier tree: preserve the old URL syntax while adding explicit url/file modes and subtitle controls.
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/registrar/CommandRegistrar.kt",
    '''        .then(videoSubCommand())\n        .then(nameSubCommand())\n''',
    '''        .then(videoSubCommand())\n        .then(subtitleSubCommand())\n        .then(nameSubCommand())\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/registrar/CommandRegistrar.kt",
    '''    private fun videoSubCommand() = Commands.literal("video")\n        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.video) }\n        .then(Commands.literal("this").then(videoUrlArgument { "this" }))\n        .then(\n            Commands.argument("id", PaperBareTokenArgumentType)\n                .suggests { _, b ->\n                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }\n                    b.buildFuture()\n                }\n                .then(videoUrlArgument { ctx -> StringArgumentType.getString(ctx, "id") })\n        )\n''',
    '''    private fun videoSubCommand() = Commands.literal("video")\n        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.video) }\n        .then(\n            Commands.literal("this")\n                .then(Commands.literal("url").then(videoUrlArgument { "this" }))\n                .then(Commands.literal("file").then(localVideoFileArgument { "this" }))\n                .then(videoUrlArgument { "this" })\n        )\n        .then(\n            Commands.argument("id", PaperBareTokenArgumentType)\n                .suggests { _, b ->\n                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }\n                    b.buildFuture()\n                }\n                .then(Commands.literal("url").then(videoUrlArgument { ctx -> StringArgumentType.getString(ctx, "id") }))\n                .then(Commands.literal("file").then(localVideoFileArgument { ctx -> StringArgumentType.getString(ctx, "id") }))\n                .then(videoUrlArgument { ctx -> StringArgumentType.getString(ctx, "id") })\n        )\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/registrar/CommandRegistrar.kt",
    '''    /** Builds the `/display name this|<id> [name]` subcommand with optional name argument. */\n    private fun nameSubCommand() = Commands.literal("name")\n''',
    '''    /** A quoted-or-bare relative MP4 path followed by an optional audio-language code. */\n    private fun localVideoFileArgument(token: (CommandContext<CommandSourceStack>) -> String) =\n        Commands.argument("file", StringArgumentType.string())\n            .executes { ctx ->\n                LocalVideoCommand().execute(\n                    ctx.source.sender, token(ctx), StringArgumentType.getString(ctx, "file"), ""\n                )\n                Command.SINGLE_SUCCESS\n            }\n            .then(\n                Commands.argument("lang", StringArgumentType.word())\n                    .suggests { _, builder ->\n                        VideoCommand.languageSuggestions\n                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }\n                            .forEach(builder::suggest)\n                        builder.buildFuture()\n                    }\n                    .executes { ctx ->\n                        LocalVideoCommand().execute(\n                            ctx.source.sender, token(ctx), StringArgumentType.getString(ctx, "file"),\n                            StringArgumentType.getString(ctx, "lang")\n                        )\n                        Command.SINGLE_SUCCESS\n                    }\n            )\n\n    /** `/display subtitle this|<id> url <vtt-url> | file <path.vtt> | off`. */\n    private fun subtitleSubCommand() = Commands.literal("subtitle")\n        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.video) }\n        .then(\n            Commands.literal("this")\n                .then(Commands.literal("off").executes { ctx ->\n                    SubtitleCommand().clear(ctx.source.sender, "this")\n                    Command.SINGLE_SUCCESS\n                })\n                .then(Commands.literal("url").then(subtitleUrlArgument { "this" }))\n                .then(Commands.literal("file").then(subtitleFileArgument { "this" }))\n        )\n        .then(\n            Commands.argument("id", PaperBareTokenArgumentType)\n                .suggests { _, b ->\n                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }\n                    b.buildFuture()\n                }\n                .then(Commands.literal("off").executes { ctx ->\n                    SubtitleCommand().clear(ctx.source.sender, StringArgumentType.getString(ctx, "id"))\n                    Command.SINGLE_SUCCESS\n                })\n                .then(Commands.literal("url").then(subtitleUrlArgument { ctx -> StringArgumentType.getString(ctx, "id") }))\n                .then(Commands.literal("file").then(subtitleFileArgument { ctx -> StringArgumentType.getString(ctx, "id") }))\n        )\n\n    private fun subtitleUrlArgument(token: (CommandContext<CommandSourceStack>) -> String) =\n        Commands.argument("vtt_url", StringArgumentType.greedyString())\n            .executes { ctx ->\n                SubtitleCommand().setUrl(ctx.source.sender, token(ctx), StringArgumentType.getString(ctx, "vtt_url"))\n                Command.SINGLE_SUCCESS\n            }\n\n    private fun subtitleFileArgument(token: (CommandContext<CommandSourceStack>) -> String) =\n        Commands.argument("vtt_file", StringArgumentType.string())\n            .executes { ctx ->\n                SubtitleCommand().setFile(ctx.source.sender, token(ctx), StringArgumentType.getString(ctx, "vtt_file"))\n                Command.SINGLE_SUCCESS\n            }\n\n    /** Builds the `/display name this|<id> [name]` subcommand with optional name argument. */\n    private fun nameSubCommand() = Commands.literal("name")\n''',
)


# ---------------------------------------------------------------------------
# Server -> client: resolve ddfile references only at send time; v1 wire shape is untouched.
# ---------------------------------------------------------------------------
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/utils/net/PacketUtil.kt",
    '''import com.dreamdisplays.platform.server.managers.PlayerManager\n''',
    '''import com.dreamdisplays.platform.server.managers.PlayerManager\nimport com.dreamdisplays.platform.server.media.LocalMediaServer\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/utils/net/PacketUtil.kt",
    '''        url: String,\n        lang: String,\n        facing: BlockFace,\n''',
    '''        url: String,\n        lang: String,\n        subtitleUrl: String = "",\n        facing: BlockFace,\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/utils/net/PacketUtil.kt",
    '''        val isVertical = facing == BlockFace.UP || facing == BlockFace.DOWN\n        val recipients = if (isVertical) players.filterNotNull().filter { supportsVertical(it.uniqueId) } else players\n        val (v2, players) = partition(recipients)\n''',
    '''        val isVertical = facing == BlockFace.UP || facing == BlockFace.DOWN\n        val clientUrl = LocalMediaServer.resolveForClient(url)\n        val clientSubtitleUrl = LocalMediaServer.resolveForClient(subtitleUrl)\n        val recipients = if (isVertical) players.filterNotNull().filter { supportsVertical(it.uniqueId) } else players\n        val (v2, players) = partition(recipients)\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/utils/net/PacketUtil.kt",
    '''                width = width, height = height, url = url,\n                facing = facing.toPacketByte().toInt(),\n                isSync = isSync, lang = lang, isLocked = isLocked,\n''',
    '''                width = width, height = height, url = clientUrl,\n                facing = facing.toPacketByte().toInt(),\n                isSync = isSync, lang = lang, isLocked = isLocked,\n                subtitleUrl = clientSubtitleUrl,\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/utils/net/PacketUtil.kt",
    '''                output.writeString(url)\n                output.writeByte(facing.toPacketByte())\n''',
    '''                output.writeString(clientUrl)\n                output.writeByte(facing.toPacketByte())\n''',
)

# Positional callers must pass the new value before facing.
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/managers/DisplayManager.kt",
    '''            display.url, display.lang, display.facing, display.isSync, display.isLocked,\n''',
    '''            display.url, display.lang, display.subtitleUrl, display.facing, display.isSync, display.isLocked,\n''',
)
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/utils/net/DisplayActions.kt",
    '''                display.url,\n                display.lang,\n                display.facing,\n''',
    '''                display.url,\n                display.lang,\n                display.subtitleUrl,\n                display.facing,\n''',
)

# Vanilla server v2 support (there is no local-file origin there, but remote VTT persists/serializes).
replace(
    "platform/server/src/main/kotlin/com/dreamdisplays/platform/server/utils/net/VanillaPacketUtil.kt",
    '''                isSync = display.isSync, lang = display.lang, isLocked = display.isLocked,\n                mode = display.mode.wire, qualityCap = display.qualityCap,\n''',
    '''                isSync = display.isSync, lang = display.lang, isLocked = display.isLocked,\n                subtitleUrl = display.subtitleUrl,\n                mode = display.mode.wire, qualityCap = display.qualityCap,\n''',
)


# ---------------------------------------------------------------------------
# Client WebVTT parser/loader/controller. The player clock remains source of truth.
# ---------------------------------------------------------------------------
write(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/subtitles/WebVttController.kt",
    r'''package com.dreamdisplays.platform.client.subtitles

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal data class WebVttCue(val startNanos: Long, val endNanos: Long, val text: String)

/** Immutable, time-sorted cue track. */
internal class WebVttTrack(private val cues: List<WebVttCue>) {
    /** Returns the newest cue active at [timeNanos], which is the normal WebVTT subtitle behavior. */
    fun activeText(timeNanos: Long): String? {
        var low = 0
        var high = cues.lastIndex
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startNanos <= timeNanos) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (candidate < 0) return null
        for (index in candidate downTo maxOf(0, candidate - 8)) {
            val cue = cues[index]
            if (timeNanos >= cue.startNanos && timeNanos < cue.endNanos) return cue.text
        }
        return null
    }

    val size: Int get() = cues.size
}

/** Strict-enough WebVTT parser for subtitle tracks; unsupported cue settings are safely ignored. */
internal object WebVttParser {
    private val tag = Regex("<[^>]*>")
    private val br = Regex("(?i)<br\\s*/?>")
    private val timestamp = Regex("^(?:(\\d{2,}):)?([0-5]\\d):([0-5]\\d)[\\.,](\\d{3})$")

    fun parse(bytes: ByteArray): WebVttTrack {
        var text = bytes.toString(StandardCharsets.UTF_8)
        if (text.startsWith('\uFEFF')) text = text.substring(1)
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        val first = lines.indexOfFirst { it.isNotBlank() }
        require(first >= 0 && lines[first].trim().startsWith("WEBVTT")) { "Missing WEBVTT header" }

        val cues = ArrayList<WebVttCue>()
        var index = first + 1
        // Header metadata ends at the first blank line.
        while (index < lines.size && lines[index].isNotBlank()) index++

        while (index < lines.size) {
            while (index < lines.size && lines[index].isBlank()) index++
            if (index >= lines.size) break

            val marker = lines[index].trim()
            if (marker == "STYLE" || marker == "REGION" || marker.startsWith("NOTE")) {
                while (index < lines.size && lines[index].isNotBlank()) index++
                continue
            }

            var timing = lines[index].trim()
            if ("-->" !in timing) {
                index++ // optional cue identifier
                if (index >= lines.size) break
                timing = lines[index].trim()
            }
            if ("-->" !in timing) {
                while (index < lines.size && lines[index].isNotBlank()) index++
                continue
            }

            val startText = timing.substringBefore("-->").trim()
            val endText = timing.substringAfter("-->").trim().substringBefore(' ').trim()
            val start = parseTimestamp(startText)
            val end = parseTimestamp(endText)
            index++

            val cueLines = ArrayList<String>()
            while (index < lines.size && lines[index].isNotBlank()) {
                val cleaned = cleanCueText(lines[index])
                if (cleaned.isNotBlank()) cueLines += cleaned
                index++
            }
            if (start != null && end != null && end > start && cueLines.isNotEmpty()) {
                cues += WebVttCue(start, end, cueLines.joinToString("\n"))
            }
            if (cues.size > 20_000) throw IllegalArgumentException("Too many WebVTT cues")
        }
        cues.sortBy { it.startNanos }
        return WebVttTrack(cues)
    }

    private fun parseTimestamp(value: String): Long? {
        val match = timestamp.matchEntire(value) ?: return null
        val hours = match.groupValues[1].ifBlank { "0" }.toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millis = match.groupValues[4].toLongOrNull() ?: return null
        val totalMillis = (((hours * 60L + minutes) * 60L + seconds) * 1000L) + millis
        return totalMillis * 1_000_000L
    }

    private fun cleanCueText(raw: String): String {
        var text = br.replace(raw, "\n")
        text = tag.replace(text, "")
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&lrm;", "")
            .replace("&rlm;", "")
            .trim()
    }
}

/** Shared bounded downloader; VTT fetches never run on the render thread. */
internal object WebVttLoader {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "DreamDisplays-VTT").apply { isDaemon = true }
    }
    private val client = HttpClient.newBuilder()
        .executor(executor)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(6))
        .build()

    fun load(url: String): CompletableFuture<WebVttTrack> = CompletableFuture.supplyAsync({
        val uri = URI.create(url)
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "VTT must use HTTP(S)" }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", "DreamDisplays/1.9.5 VTT")
            .header("Accept", "text/vtt,text/plain;q=0.9,*/*;q=0.1")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        val bytes = response.body().use { input -> input.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "VTT exceeds ${MAX_BYTES / 1024} KiB" }
        WebVttParser.parse(bytes)
    }, executor)
}

/** Per-display async subtitle state; immutable tracks make render-thread reads lock-free. */
internal class WebVttController(private val displayId: UUID) {
    private val logger = LoggerFactory.getLogger("DreamDisplays/WebVTT")
    private val generation = AtomicLong(0)

    @Volatile private var source = ""
    @Volatile private var track: WebVttTrack? = null

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
            logger.info("$displayId loaded WebVTT (${loaded.size} cues).")
        }
    }

    fun activeLines(timeNanos: Long): List<String> =
        track?.activeText(timeNanos)?.split('\n') ?: emptyList()

    fun close() {
        generation.incrementAndGet()
        source = ""
        track = null
    }
}
''',
)

# DisplayScreen owns subtitle source/track and samples it from the exact media-player clock.
replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayScreen.kt",
    '''import com.dreamdisplays.platform.client.storage.ClientSettingsStore\n''',
    '''import com.dreamdisplays.platform.client.storage.ClientSettingsStore\nimport com.dreamdisplays.platform.client.subtitles.WebVttController\n''',
)
replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayScreen.kt",
    '''    /** Audio track / language of the current video, or `null` when idle. */\n    var lang: String? = null; private set\n\n    /** True once the video is effectively playing: not awaiting the initial timeline and a frame has filled. */\n''',
    '''    /** Audio track / language of the current video, or `null` when idle. */\n    var lang: String? = null; private set\n\n    /** Current server-provided WebVTT source, empty when subtitles are disabled. */\n    var subtitleUrl: String = ""; private set\n\n    /** Async immutable WebVTT track for this display. */\n    private val subtitles = WebVttController(uuid)\n\n    /** Subtitle lines active at the exact playback clock, so pause/resume/seek remain synchronized. */\n    val activeSubtitleLines: List<String> get() = subtitles.activeLines(currentTimeNanos)\n\n    /** True once the video is effectively playing: not awaiting the initial timeline and a frame has filled. */\n''',
)
replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayScreen.kt",
    '''        owner = Minecraft.getInstance().player?.gameProfile?.id?.toString() == packet.ownerId.toString()\n\n        if (videoUrl != packet.url || lang != packet.lang) {\n''',
    '''        owner = Minecraft.getInstance().player?.gameProfile?.id?.toString() == packet.ownerId.toString()\n\n        if (subtitleUrl != packet.subtitleUrl) {\n            subtitleUrl = packet.subtitleUrl\n            subtitles.setSource(packet.subtitleUrl)\n        }\n\n        if (videoUrl != packet.url || lang != packet.lang) {\n''',
)
replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/displays/DisplayScreen.kt",
    '''    fun unregister() {\n        captureReplayCache()\n        val currentPlayer = media.shutdown()\n''',
    '''    fun unregister() {\n        captureReplayCache()\n        subtitles.close()\n        val currentPlayer = media.shutdown()\n''',
)


# World-space subtitle rendering. No frame pixels are modified and no video quality/resolution changes.
replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/ScreenRenderer.kt",
    '''import net.minecraft.client.Camera\n''',
    '''import net.minecraft.client.Camera\nimport net.minecraft.client.Minecraft\nimport net.minecraft.client.gui.Font\n''',
)
replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/ScreenRenderer.kt",
    '''            renderPlaceholder(\n                stack,\n                drawQuad,\n                DisplayYuvRenderTypes.solidColorType(),\n                facing,\n                w,\n                h,\n                displayScreen.errored,\n                lift,\n            )\n        }\n    }\n''',
    '''            renderPlaceholder(\n                stack,\n                drawQuad,\n                DisplayYuvRenderTypes.solidColorType(),\n                facing,\n                w,\n                h,\n                displayScreen.errored,\n                lift,\n            )\n        }\n        if (!replay) renderSubtitle(displayScreen, stack)\n    }\n''',
)
replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/ScreenRenderer.kt",
    '''    /** Depth-layer spacing between placeholder elements, in blocks toward the viewer (see [drawLayer]). */\n    private const val OVERLAY_LIFT = 0.01f\n''',
    '''    /** Subtitle glyph width target in Minecraft font pixels; maps to ~80% of the display width. */\n    private const val SUBTITLE_WRAP_PIXELS = 320\n    private const val SUBTITLE_TEXT_SCALE = 0.0025f\n    private const val SUBTITLE_LIFT = 0.028f\n    private const val SUBTITLE_MAX_LINES = 4\n\n    /** Draws active VTT lines as full-bright world text slightly in front of the video plane. */\n    private fun renderSubtitle(displayScreen: DisplayScreen, stack: PoseStack) {\n        if (!displayScreen.isVideoStarted) return\n        val rawLines = displayScreen.activeSubtitleLines\n        if (rawLines.isEmpty()) return\n        val mc = Minecraft.getInstance()\n        val font = mc.font\n        val lines = wrapSubtitleLines(rawLines, font).take(SUBTITLE_MAX_LINES)\n        if (lines.isEmpty()) return\n\n        val lineAdvance = font.lineHeight + 2\n        val totalHeight = lines.size * lineAdvance * SUBTITLE_TEXT_SCALE\n        val top = 0.075f + totalHeight\n\n        stack.pushPose()\n        DisplayGeometry.liftTowardViewer(stack, displayScreen.facing, SUBTITLE_LIFT)\n        DisplayGeometry.applyScreenTransform(stack, displayScreen.facing, displayScreen.width, displayScreen.height)\n        stack.translate(0.5f, top, 0f)\n        stack.scale(SUBTITLE_TEXT_SCALE, -SUBTITLE_TEXT_SCALE, 1f)\n\n        val buffers = mc.renderBuffers().bufferSource()\n        lines.forEachIndexed { index, line ->\n            val x = -font.width(line) / 2f\n            font.drawInBatch(\n                line, x, (index * lineAdvance).toFloat(), -1, true,\n                stack.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, 0xF000F0,\n            )\n        }\n        buffers.endBatch()\n        stack.popPose()\n    }\n\n    /** Word-wrap without GUI dependencies so the same cue is readable on screens of any block size. */\n    private fun wrapSubtitleLines(rawLines: List<String>, font: Font): List<String> {\n        val output = ArrayList<String>()\n        for (raw in rawLines) {\n            val words = raw.trim().split(Regex("\\s+"))\n            if (words.isEmpty() || (words.size == 1 && words[0].isEmpty())) continue\n            var current = ""\n            for (word in words) {\n                val candidate = if (current.isEmpty()) word else "$current $word"\n                if (current.isNotEmpty() && font.width(candidate) > SUBTITLE_WRAP_PIXELS) {\n                    output += current\n                    current = word\n                } else {\n                    current = candidate\n                }\n            }\n            if (current.isNotEmpty()) output += current\n        }\n        return output\n    }\n\n    /** Depth-layer spacing between placeholder elements, in blocks toward the viewer (see [drawLayer]). */\n    private const val OVERLAY_LIFT = 0.01f\n''',
)

print("Applied Kirazium VTT + local MP4/VTT patch to Dream Displays 1.9.5.")
