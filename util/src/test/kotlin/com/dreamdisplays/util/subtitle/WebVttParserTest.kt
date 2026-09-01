package com.dreamdisplays.util.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebVttParserTest {
    @Test
    fun parsesTurkishMultilineCuesIdentifiersAndSettings() {
        val track = WebVttParser.parse(
            """
                ﻿WEBVTT - Türkçe
                Kind: captions

                giriş
                00:00:01.250 --> 00:00:03.500 align:middle
                <v Anlatıcı>İstanbul'da güzel bir gün.</v>
                İkinci satır &amp; devamı

                00:04.000 --> 00:05.000
                Görüşürüz!<br>Hoşça kal.
            """.trimIndent()
        )

        assertEquals(2, track.cueCount)
        assertEquals(emptyList(), track.activeLines(1_249_000_000L))
        assertEquals(
            listOf("İstanbul'da güzel bir gün.", "İkinci satır & devamı"),
            track.activeLines(1_250_000_000L),
        )
        assertEquals(listOf("Görüşürüz!", "Hoşça kal."), track.activeLines(4_500_000_000L))
        assertEquals(emptyList(), track.activeLines(5_000_000_000L))
    }

    @Test
    fun returnsAllOverlappingCuesInStartOrder() {
        val track = WebVttParser.parse(
            """
                WEBVTT

                00:00:01.000 --> 00:00:10.000
                Uzun

                00:00:05.000 --> 00:00:06.000
                Kısa
            """.trimIndent()
        )

        assertEquals(listOf("Uzun", "Kısa"), track.activeLines(5_500_000_000L))
        assertEquals(listOf("Uzun"), track.activeLines(7_000_000_000L))
    }

    @Test
    fun rejectsTextWithoutWebVttHeader() {
        assertFailsWith<IllegalArgumentException> {
            WebVttParser.parse("00:00:00.000 --> 00:00:01.000\nMerhaba")
        }
    }
}
