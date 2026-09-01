package com.dreamdisplays.util.http

/** One satisfiable HTTP byte range with inclusive bounds. */
data class SingleByteRange(val startInclusive: Long, val endInclusive: Long) {
    init {
        require(startInclusive >= 0) { "Range start must not be negative." }
        require(endInclusive >= startInclusive) { "Range end must not precede its start." }
    }

    /** Number of bytes covered by this range. */
    val length: Long get() = endInclusive - startInclusive + 1L
}

/** Parser for the single-range subset used by the local progressive-media origin. */
object SingleByteRanges {
    /**
     * Parses `bytes=start-end`, `bytes=start-`, or `bytes=-suffixLength` for a resource of [size].
     * Multiple ranges and unsatisfiable ranges return `null` and must be answered with HTTP 416.
     */
    fun parse(header: String, size: Long): SingleByteRange? {
        if (size <= 0L) return null
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val spec = value.substring(6).trim()
        if (spec.isEmpty() || ',' in spec) return null

        val dash = spec.indexOf('-')
        if (dash < 0 || spec.indexOf('-', dash + 1) >= 0) return null
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()

        if (left.isEmpty()) {
            val suffixLength = right.toLongOrNull() ?: return null
            if (suffixLength <= 0L) return null
            val start = if (suffixLength >= size) 0L else size - suffixLength
            return SingleByteRange(start, size - 1L)
        }

        val start = left.toLongOrNull() ?: return null
        if (start < 0L || start >= size) return null
        val end = if (right.isEmpty()) {
            size - 1L
        } else {
            val requestedEnd = right.toLongOrNull() ?: return null
            if (requestedEnd < start) return null
            minOf(requestedEnd, size - 1L)
        }
        return SingleByteRange(start, end)
    }
}
