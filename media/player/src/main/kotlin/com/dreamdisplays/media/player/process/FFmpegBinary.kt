package com.dreamdisplays.media.player.process

import com.dreamdisplays.media.player.util.daemon
import com.dreamdisplays.media.runtime.system.Processes
import com.dreamdisplays.util.OsInfo
import com.dreamdisplays.util.net.DreamHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** `FFmpeg` binary downloader. **/
object FFmpegBinary {
    private val logger = LoggerFactory.getLogger("DreamDisplays/FFmpeg")
    private const val CACHE_ROOT = "./dreamdisplays/ffmpeg"
    private const val BTBN_BASE = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest"

    @Volatile
    private var cachedPath: String? = null

    /** Returns the path to a usable `FFmpeg` binary, resolving and caching it on the first call. */
    fun getPath(): String? {
        cachedPath?.let { return it }
        synchronized(this) {
            cachedPath?.let { return it }
            cachedPath = resolve()
            return cachedPath
        }
    }

    /**
     * Resolves the `FFmpeg` binary in the background to minimize latency on first use, and probes
     * its optional filters while it is there — otherwise that probe spawns its own `ffmpeg -filters`
     * synchronously inside the first playback launch, right where latency is most visible.
     */
    fun prewarmAsync() {
        daemon({
            runCatching {
                val path = getPath()
                if (path != null) FFmpegCapabilities.hasFilter(path, "scale_vt")
            }.onFailure { e -> logger.warn("Prewarm failed", e) }
        }, "FFmpeg-prewarm").start()
    }

    /**
     * Checks the cache directory for an existing binary, downloads and extracts one if not found,
     * and falls back to the system `FFmpeg` on any failure.
     */
    private fun resolve(): String? {
        // Android launchers run on a Linux kernel but cannot use the glibc BtbN Linux bundle. Prefer
        // the launcher's own executable/native-library payload (including the separate FFmpeg APK)
        // and never attempt the desktop Linux download path there.
        if (OsInfo.isAndroidLike) {
            disableAndroidNativePipeline()
            return resolveAndroidFfmpeg()
        }

        val p = detectPlatform() ?: run {
            logger.warn("No bundled binary URL for this OS / arch; trying system FFmpeg.")
            return findSystemFfmpeg()
        }

        val cacheDir = File("$CACHE_ROOT/${p.key}")
        val binary = File(cacheDir, p.binaryName)

        if (binary.isFile && binary.length() > 0 && binary.canExecute()) {
            logger.info("Using binary: ${binary.absolutePath}.")
            return binary.absolutePath
        }

        return runCatching {
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw IOException("Cannot create cache dir: $cacheDir.")
            }
            downloadAndExtract(p, binary)
            if (!binary.isFile || binary.length() == 0L) {
                throw IOException("Extracted binary is missing or empty.")
            }
            Processes.markExecutable(binary.toPath())
            Processes.removeMacQuarantine(binary.toPath())
            logger.info("Ready to work.")
            binary.absolutePath
        }.getOrElse { e ->
            logger.error("Download failed, falling back to system ffmpeg", e)
            findSystemFfmpeg()
        }
    }

    /**
     * Android/Pojav cannot load the desktop Rust/Linux native bundle reliably. Disable every native
     * video path before [PlaybackSessionManager] creates its channel so it deterministically falls
     * back to the JVM PPM/RGB pipeline while desktop platforms keep their native/YUV path unchanged.
     */
    private fun disableAndroidNativePipeline() {
        System.setProperty("dreamdisplays.native", "false")
        System.setProperty("dreamdisplays.native.libav", "false")
        System.setProperty("dreamdisplays.native.nv12", "false")
        System.setProperty("dreamdisplays.native.yuvgpu", "false")
        logger.info("Android/Pojav detected; using external FFmpeg with the JVM RGB video pipeline.")
    }

    /**
     * Resolves FFmpeg supplied by an Android Java launcher or by the companion FFmpeg APK.
     *
     * Mojo/Pojav exposes `git.mojo.ffmpeg` as `POJAV_FFMPEG_PATH`, pointing at the plugin's
     * `nativeLibraryDir/libffmpeg.so`. Its native ProcessBuilder hook intentionally intercepts the
     * executable name `ffmpeg`, swaps it for that `.so`, and installs the plugin's dependency path.
     * Therefore the wrapper name is preferred over executing `libffmpeg.so` directly.
     */
    private fun resolveAndroidFfmpeg(): String? {
        val pojavPlugin = System.getenv("POJAV_FFMPEG_PATH")?.trim()?.takeIf { it.isNotEmpty() }
        if (pojavPlugin != null) {
            if (probeFfmpeg("ffmpeg")) {
                logger.info("Using Pojav FFmpeg plugin through launcher exec hook: $pojavPlugin")
                return "ffmpeg"
            }
            // Custom launchers may expose the same variable without the exec hook. Keep a direct
            // fallback for those implementations, but Mojo/Kirazium should normally take the path above.
            if (probeFfmpeg(pojavPlugin)) {
                logger.info("Using Pojav FFmpeg plugin directly: $pojavPlugin")
                return pojavPlugin
            }
            logger.warn("POJAV_FFMPEG_PATH is set but unusable: $pojavPlugin")
        }

        val explicit = listOfNotNull(
            System.getProperty("dreamdisplays.android.ffmpeg"),
            System.getProperty("dreamdisplays.ffmpeg"),
            System.getenv("DREAMDISPLAYS_ANDROID_FFMPEG"),
            System.getenv("DREAMDISPLAYS_FFMPEG"),
        ).map { it.trim() }.filter { it.isNotEmpty() }

        for (candidate in explicit) {
            if (probeFfmpeg(candidate)) {
                logger.info("Using Android FFmpeg override: $candidate")
                return candidate
            }
            logger.warn("Ignoring unusable Android FFmpeg override: $candidate")
        }

        val dirs = linkedSetOf<String>()
        fun addPathList(value: String?) {
            value?.split(File.pathSeparatorChar)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.forEach { dirs.add(it) }
        }

        addPathList(System.getenv("POJAV_NATIVEDIR"))
        addPathList(System.getenv("LD_LIBRARY_PATH"))
        addPathList(System.getProperty("java.library.path"))
        addPathList(System.getenv("PATH"))

        val names = arrayOf("libffmpeg.so", "ffmpeg")
        for (dir in dirs) {
            val base = File(dir)
            if (!base.isDirectory) continue
            for (name in names) {
                val candidate = File(base, name)
                if (!candidate.isFile || candidate.length() <= 0L) continue
                val path = candidate.absolutePath
                if (probeFfmpeg(path)) {
                    logger.info("Using Android launcher/APK FFmpeg: $path")
                    return path
                }
            }
        }

        // Some launchers put a wrapper named ffmpeg directly on PATH.
        findSystemFfmpeg()?.let { return it }
        logger.error(
            "Android FFmpeg not found. Install/expose the git.mojo.ffmpeg companion plugin or set " +
                "POJAV_FFMPEG_PATH / DREAMDISPLAYS_ANDROID_FFMPEG to its executable path."
        )
        return null
    }

    /** Returns true only when [candidate] can actually execute FFmpeg and answer `-version`. */
    private fun probeFfmpeg(candidate: String): Boolean {
        return try {
            val process = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
            daemon({
                try {
                    process.inputStream.transferTo(OutputStream.nullOutputStream())
                } catch (_: Exception) {
                }
            }, "FFmpeg-version-drain").start()
            val ok = process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
            if (!ok) process.destroyForcibly()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Downloads the archive for [p] to a temp file, extracts the binary to [destBinary], and cleans up the temp file. */
    @Throws(IOException::class)
    private fun downloadAndExtract(p: Platform, destBinary: File) {
        logger.info("Downloading ${p.url}...")
        val parent = destBinary.parentFile
        val tempArchive = File(parent, "_download" + if (p.isTarXz) ".tar.xz" else ".zip")
        try {
            downloadWithRedirects(p.url, tempArchive)
            logger.info("Downloaded ${tempArchive.length()} bytes, extracting '${p.entrySuffix}'...")
            if (p.isTarXz) extractFromTarXz(tempArchive, p.entrySuffix, destBinary)
            else extractFromZip(tempArchive, p.entrySuffix, destBinary)
        } finally {
            if (tempArchive.exists() && !tempArchive.delete()) tempArchive.deleteOnExit()
        }
    }

    /** Downloads [url] to [dest], following up to 10 HTTP redirects manually (GitHub releases use multiple hops). */
    @Throws(IOException::class)
    private fun downloadWithRedirects(url: String, dest: File) {
        DreamHttpClient.downloadToFile(
            url,
            dest.toPath(),
            DreamHttpClient.RequestOptions(
                headers = DreamHttpClient.headersOf("User-Agent" to "DreamDisplays-ffmpeg-bootstrap"),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 300_000,
            ),
        )
    }

    /** Extracts the first ZIP entry whose name ends with [suffix] from [archive] to [dest]. */
    @Throws(IOException::class)
    private fun extractFromZip(archive: File, suffix: String, dest: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(suffix)) {
                    BufferedOutputStream(FileOutputStream(dest)).use { out -> zis.transferTo(out) }
                    return
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        throw IOException("'$suffix' not found in ${archive.name}.")
    }

    /** Extracts the first tar.xz entry whose name ends with [suffix] from [archive] to [dest]. */
    @Throws(IOException::class)
    private fun extractFromTarXz(archive: File, suffix: String, dest: File) {
        BufferedInputStream(FileInputStream(archive)).use { fis ->
            XZCompressorInputStream(fis).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(suffix)) {
                            BufferedOutputStream(FileOutputStream(dest)).use { out -> tar.transferTo(out) }
                            return
                        }
                        e = tar.nextEntry
                    }
                }
            }
        }
        throw IOException("'$suffix' not found in ${archive.name}.")
    }

    /** Scans well-known system paths for a working `ffmpeg` binary; returns null if none responds with exit 0. */
    private fun findSystemFfmpeg(): String? {
        val candidates = arrayOf("ffmpeg", "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")
        for (candidate in candidates) {
            if (probeFfmpeg(candidate)) {
                logger.info("Using system ffmpeg: $candidate...")
                return candidate
            }
        }
        logger.error("FFmpeg not found (no download succeeded, no system binary).")
        return null
    }

    /** Returns a [Platform] descriptor for the current OS and architecture, or null if no bundled build is available. */
    private fun detectPlatform(): Platform? {
        if (OsInfo.isAndroidLike) return null
        val isArm = OsInfo.isArm
        return when {
            OsInfo.isWindows -> if (isArm) null else
                Platform(
                    "windows-x64",
                    "$BTBN_BASE/ffmpeg-master-latest-win64-gpl.zip",
                    "ffmpeg.exe",
                    "/bin/ffmpeg.exe",
                    false
                )

            OsInfo.isMac -> if (isArm)
                Platform("macos-aarch64", "https://www.osxexperts.net/ffmpeg71arm.zip", "ffmpeg", "ffmpeg", false)
            else
                Platform("macos-x64", "https://evermeet.cx/ffmpeg/getrelease/zip", "ffmpeg", "ffmpeg", false)

            else -> if (isArm)
                Platform(
                    "linux-aarch64",
                    "$BTBN_BASE/ffmpeg-master-latest-linuxarm64-gpl.tar.xz",
                    "ffmpeg",
                    "/bin/ffmpeg",
                    true
                )
            else
                Platform(
                    "linux-x64",
                    "$BTBN_BASE/ffmpeg-master-latest-linux64-gpl.tar.xz",
                    "ffmpeg",
                    "/bin/ffmpeg",
                    true
                )
        }
    }

    private data class Platform(
        val key: String,
        val url: String,
        val binaryName: String,
        val entrySuffix: String,
        val isTarXz: Boolean,
    )
}