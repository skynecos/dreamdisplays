package com.dreamdisplays.util

import java.util.*

/**
 * Single source of truth for OS / architecture detection.
 */
object OsInfo {
    private val os: String = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
    private val arch: String = System.getProperty("os.arch", "").lowercase(Locale.ENGLISH)
    private val javaVendor: String = System.getProperty("java.vendor", "").lowercase(Locale.ENGLISH)
    private val javaRuntime: String = System.getProperty("java.runtime.name", "").lowercase(Locale.ENGLISH)
    private val javaVm: String = System.getProperty("java.vm.name", "").lowercase(Locale.ENGLISH)

    val isWindows: Boolean = "win" in os
    val isMac: Boolean = "mac" in os

    /** True on Linux and Linux-derived kernels (`"nux"`/`"nix"` in `os.name`); false on BSD/Solaris/other Unix. */
    val isLinux: Boolean = "nux" in os || "nix" in os

    /**
     * True when the JVM is running on Android. Android launchers frequently report `Linux` as
     * `os.name`, so this must be checked before choosing Linux-only integrations such as VAAPI.
     */
    val isAndroid: Boolean =
        "android" in javaVendor ||
            "android" in javaRuntime ||
            "dalvik" in javaVm ||
            System.getenv("ANDROID_ROOT") != null ||
            System.getenv("ANDROID_DATA") != null

    /**
     * True for Pojav/Kirazium-style Java launchers. Different launcher builds expose different
     * environment variables, therefore keep the detection intentionally conservative but multi-signal.
     */
    val isPojav: Boolean =
        System.getenv("POJAV_FFMPEG_PATH") != null ||
            System.getenv("POJAV_RENDERER") != null ||
            System.getenv("POJAV_NATIVEDIR") != null ||
            System.getenv("POJAV_HOME") != null ||
            System.getProperty("pojav.path.minecraft") != null

    /** Android Java launchers need the portable process/RGB path rather than desktop Linux backends. */
    val isAndroidLike: Boolean = isAndroid || isPojav

    /** True on any 64-bit or 32-bit ARM architecture (aarch64, arm64, armv7, ...). */
    val isArm: Boolean = "aarch64" in arch || "arm64" in arch || "arm" in arch

    /** True specifically on 64-bit ARM. */
    val isArm64: Boolean = "aarch64" in arch || "arm64" in arch
}