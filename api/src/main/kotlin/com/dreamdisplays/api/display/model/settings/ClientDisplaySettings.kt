package com.dreamdisplays.api.display.model.settings

import com.dreamdisplays.api.Unstable
import kotlinx.serialization.Serializable

/**
 * Client-local preferences: volume, quality, mute, URL / language overrides, PiP position, acoustics.
 *
 * @since 1.0.x
 */
@Unstable
@Serializable
data class ClientDisplaySettings(
    /** Volume in the range [0.0, 1.0]. */
    var volume: Float = DEFAULT_VOLUME,

    /** Video quality, e.g. "720" or "1080". */
    var quality: String = "1080",

    /** Brightness in the range [0.0, 2.0]. */
    var brightness: Float = 1.0f,

    /** Whether the display is muted. */
    var muted: Boolean = false,

    /** Whether the display is paused. */
    var paused: Boolean = true,

    /** URL override for the video, or null if not overridden. */
    var urlOverride: String? = null,

    /** Language override for the video, or null if not overridden. */
    var langOverride: String? = null,

    /** Last known playback position in nanoseconds, resumed on Local displays after a restart. */
    var savedTimeNanos: Long = 0,

    /** Viewer-chosen render distance in blocks, or `0` if never customized (falls back to the config default). */
    var renderDistance: Int = 0,

    /** Whether the viewer pinned this display to a Picture-in-Picture overlay; re-opened on rejoin regardless of render distance. */
    var pipOpen: Boolean = false,

    /** Picture-in-Picture anchor point. */
    var pipAnchor: String? = null,

    /** Height of the PiP as a fraction of the screen, or `0` when the viewer never resized it. */
    var pipSizeFraction: Float = 0f,

    /** Whether the 3D acoustics engine applies to this display; false forces the legacy distance-gain-only path. */
    var acousticsEnabled: Boolean = true,

    /** Viewer-local subtitle visibility. Never sent to or persisted by the server. */
    var subtitlesEnabled: Boolean = true,

    /** Viewer-local subtitle text scale. Clamped to [0.5, 2.0] by the client. */
    var subtitleScale: Float = 1.0f,

    /** Viewer-local vertical subtitle position, from 0.0 (bottom) to 1.0 (higher). */
    var subtitleVerticalPosition: Float = 0.15f,

    /** Viewer-local subtitle font preset token. */
    var subtitleFont: String = "default",

    /** Viewer-local opaque ARGB subtitle text color. */
    var subtitleTextColor: Int = -1,

    /** Viewer-local ARGB subtitle outline color. Alpha zero disables the outline. */
    var subtitleOutlineColor: Int = 0x00000000,

    /** Viewer-local RGB/ARGB source color used by the subtitle background plate. */
    var subtitleBackgroundColor: Int = 0xFF000000.toInt(),

    /** Viewer-local subtitle background opacity in the range 0.0..1.0. */
    var subtitleBackgroundOpacity: Float = 0.0f,
) {

    companion object {
        /** Default volume for all displays. The UI presents this as 50% (slider range is [0, 1] -> [0%, 200%]). */
        const val DEFAULT_VOLUME = 0.25f
    }
}
