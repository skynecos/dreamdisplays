package com.dreamdisplays.shared.subtitle

import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import com.dreamdisplays.api.media.source.MediaHttpUrl
import com.dreamdisplays.shared.policy.MediaUrlPolicy

/** Optional external WebVTT source carried by the upstream display custom-data channel. */
object VttSubtitleSource {
    const val KEY = "dreamdisplays:subtitle_vtt_url"

    /** Only absolute HTTP(S) sources are accepted by clients; internal file references stay server-side. */
    fun read(customData: Map<String, ByteArray>): String {
        val bytes = customData[KEY] ?: return ""
        if (bytes.size > MediaUrlPolicy.MAX_URL_LENGTH * 4) return ""
        return normalize(bytes.toString(Charsets.UTF_8)).orEmpty()
    }

    fun normalize(url: String): String? = MediaHttpUrl.parse(url, MediaUrlPolicy.MAX_URL_LENGTH)?.value

    /** Returns whether the value changed. Invalid URLs or a full custom-data map are rejected. */
    fun write(customData: MutableMap<String, ByteArray>, url: String): Boolean {
        if (url.isBlank()) return customData.remove(KEY) != null
        val normalized = normalize(url) ?: return false
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        if (!DisplayCustomDataLimits.accepts(customData, KEY, bytes)) return false
        if (customData[KEY]?.contentEquals(bytes) == true) return false
        customData[KEY] = bytes
        return true
    }
}
