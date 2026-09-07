package com.dreamdisplays.platform.client.subtitles

import com.dreamdisplays.util.subtitle.WebVttParser
import com.dreamdisplays.util.subtitle.WebVttTrack
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

class WebVttControllerTest {
    private val track: WebVttTrack = WebVttParser.parse(
        """
        WEBVTT

        00:00:00.000 --> 00:00:05.000
        Merhaba
        """.trimIndent(),
    )

    @Test
    fun retriesSameSourceAfterFailure() {
        var loads = 0
        val controller = WebVttController(UUID.randomUUID()) {
            loads++
            if (loads == 1) {
                CompletableFuture.failedFuture(IllegalStateException("temporary failure"))
            } else {
                CompletableFuture.completedFuture(track)
            }
        }

        controller.setSource("https://example.com/episode-1.vtt")
        assertEquals(emptyList(), controller.activeLines(1_000_000_000L))

        controller.setSource("https://example.com/episode-1.vtt")

        assertEquals(2, loads)
        assertEquals(listOf("Merhaba"), controller.activeLines(1_000_000_000L))
    }

    @Test
    fun doesNotReloadReadySameSource() {
        var loads = 0
        val controller = WebVttController(UUID.randomUUID()) {
            loads++
            CompletableFuture.completedFuture(track)
        }

        controller.setSource("https://example.com/episode-1.vtt")
        controller.setSource("https://example.com/episode-1.vtt")

        assertEquals(1, loads)
    }

    @Test
    fun doesNotDuplicateInFlightSameSource() {
        var loads = 0
        val pending = CompletableFuture<WebVttTrack>()
        val controller = WebVttController(UUID.randomUUID()) {
            loads++
            pending
        }

        controller.setSource("https://example.com/episode-1.vtt")
        controller.setSource("https://example.com/episode-1.vtt")

        assertEquals(1, loads)
        pending.complete(track)
        assertEquals(listOf("Merhaba"), controller.activeLines(1_000_000_000L))
    }

    @Test
    fun staleCompletionCannotReplaceNewerSource() {
        val oldPending = CompletableFuture<WebVttTrack>()
        val newerTrack = WebVttParser.parse(
            """
            WEBVTT

            00:00:00.000 --> 00:00:05.000
            Yeni
            """.trimIndent(),
        )
        val controller = WebVttController(UUID.randomUUID()) { url ->
            if (url.endsWith("old.vtt")) oldPending else CompletableFuture.completedFuture(newerTrack)
        }

        controller.setSource("https://example.com/old.vtt")
        controller.setSource("https://example.com/new.vtt")
        oldPending.complete(track)

        assertEquals(listOf("Yeni"), controller.activeLines(1_000_000_000L))
    }
}
