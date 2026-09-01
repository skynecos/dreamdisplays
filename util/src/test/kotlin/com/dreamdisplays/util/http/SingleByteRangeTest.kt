package com.dreamdisplays.util.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SingleByteRangeTest {
    @Test
    fun parsesClosedOpenAndSuffixRanges() {
        assertEquals(SingleByteRange(2, 5), SingleByteRanges.parse("bytes=2-5", 10))
        assertEquals(SingleByteRange(7, 9), SingleByteRanges.parse("bytes=7-", 10))
        assertEquals(SingleByteRange(6, 9), SingleByteRanges.parse("bytes=-4", 10))
        assertEquals(SingleByteRange(0, 9), SingleByteRanges.parse("bytes=-99", 10))
    }

    @Test
    fun clampsEndAndRejectsUnsatisfiableOrMultipleRanges() {
        assertEquals(SingleByteRange(4, 9), SingleByteRanges.parse("bytes=4-999", 10))
        assertNull(SingleByteRanges.parse("bytes=10-", 10))
        assertNull(SingleByteRanges.parse("bytes=8-4", 10))
        assertNull(SingleByteRanges.parse("bytes=0-1,4-5", 10))
        assertNull(SingleByteRanges.parse("items=0-1", 10))
        assertNull(SingleByteRanges.parse("bytes=0-1", 0))
    }
}
