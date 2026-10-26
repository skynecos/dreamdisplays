package com.dreamdisplays.util.subtitle

import java.nio.charset.StandardCharsets

/** One parsed WebVTT cue. End time is exclusive. */
data class WebVttCue(
    val startNanos: Long,
    val endNanos: Long,
    val lines: List<String>,
)

/** Immutable, start-time-sorted WebVTT cue track. */
class WebVttTrack internal constructor(private val cues: List<WebVttCue>) {
    /** Largest cue end time at or before each index, used to stop overlap scans early. */
    private val maxEndThrough = LongArray(cues.size).also { maxima ->
        var maximum = Long.MIN_VALUE
        cues.forEachIndexed { index, cue ->
            maximum = maxOf(maximum, cue.endNanos)
            maxima[index] = maximum
        }
    }

    /** Number of parsed cues. */
    val cueCount: Int get() = cues.size

    /** All subtitle lines active at [timeNanos], ordered by cue start time. */
    fun activeLines(timeNanos: Long): List<String> {
        if (timeNanos < 0L || cues.isEmpty()) return emptyList()

        var low = 0
        var high = cues.lastIndex
        var newestStarted = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startNanos <= timeNanos) {
                newestStarted = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (newestStarted < 0) return emptyList()

        val active = ArrayList<WebVttCue>()
        var index = newestStarted
        while (index >= 0 && maxEndThrough[index] > timeNanos) {
            val cue = cues[index]
            if (timeNanos >= cue.startNanos && timeNanos < cue.endNanos) active += cue
            index--
        }
        active.reverse()
        return active.flatMap(WebVttCue::lines)
    }
}

/** Parser for subtitle-oriented WebVTT, including identifiers, cue settings, tags, and UTF-8 BOMs. */
object WebVttParser {
    private const val MAX_CUES = 20_000
    private val tag = Regex("<[^>]*>")
    private val lineBreakTag = Regex("(?i)<br\\s*/?>")
    private val numericEntity = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
    private val timestamp = Regex("^(?:(\\d+):)?([0-5]\\d):([0-5]\\d)[\\.,](\\d{3})$")

    /** Parses a UTF-8 WebVTT payload. */
    fun parse(bytes: ByteArray): WebVttTrack = parse(bytes.toString(StandardCharsets.UTF_8))

    /** Parses WebVTT text into an immutable track. */
    fun parse(input: String): WebVttTrack {
        var text = input
        if (text.startsWith('\uFEFF')) text = text.substring(1)
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        val firstContent = lines.indexOfFirst(String::isNotBlank)
        require(firstContent >= 0 && lines[firstContent].trim().startsWith("WEBVTT")) {
            "Missing WEBVTT header."
        }

        val cues = ArrayList<WebVttCue>()
        var index = firstContent + 1
        while (index < lines.size && lines[index].isNotBlank()) index++

        while (index < lines.size) {
            while (index < lines.size && lines[index].isBlank()) index++
            if (index >= lines.size) break

            val marker = lines[index].trim()
            if (marker == "STYLE" || marker == "REGION" || marker == "NOTE" || marker.startsWith("NOTE ")) {
                while (index < lines.size && lines[index].isNotBlank()) index++
                continue
            }

            var timing = marker
            if ("-->" !in timing) {
                index++
                if (index >= lines.size) break
                timing = lines[index].trim()
            }
            if ("-->" !in timing) {
                while (index < lines.size && lines[index].isNotBlank()) index++
                continue
            }

            val start = parseTimestamp(timing.substringBefore("-->").trim())
            val end = parseTimestamp(timing.substringAfter("-->").trim().substringBefore(' ').trim())
            index++

            val cueLines = ArrayList<String>()
            while (index < lines.size && lines[index].isNotBlank()) {
                cleanCueText(lines[index])
                    .split('\n')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(cueLines::add)
                index++
            }

            if (start != null && end != null && end > start && cueLines.isNotEmpty()) {
                cues += WebVttCue(start, end, cueLines)
                require(cues.size <= MAX_CUES) { "WebVTT contains more than $MAX_CUES cues." }
            }
        }

        cues.sortBy(WebVttCue::startNanos)
        return WebVttTrack(cues)
    }

    private fun parseTimestamp(value: String): Long? {
        val match = timestamp.matchEntire(value) ?: return null
        val hours = match.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: return null
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millis = match.groupValues[4].toLongOrNull() ?: return null
        return runCatching {
            val secondsNanos = Math.multiplyExact(
                Math.addExact(
                    Math.multiplyExact(Math.addExact(Math.multiplyExact(hours, 60L), minutes), 60L),
                    seconds,
                ),
                1_000_000_000L,
            )
            Math.addExact(secondsNanos, Math.multiplyExact(millis, 1_000_000L))
        }.getOrNull()
    }

    private fun cleanCueText(raw: String): String {
        var value = lineBreakTag.replace(raw, "\n")
        value = tag.replace(value, "")
        value = numericEntity.replace(value) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith('x', ignoreCase = true)) {
                token.substring(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            codePoint
                ?.takeIf { Character.isValidCodePoint(it) }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lrm;", "")
            .replace("&rlm;", "")
    }
}
