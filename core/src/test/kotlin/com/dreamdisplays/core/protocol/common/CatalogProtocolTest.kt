package com.dreamdisplays.core.protocol.common

import com.dreamdisplays.api.capability.ServerFeature
import com.dreamdisplays.api.protocol.model.PacketDirection
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.core.protocol.common.packets.SetVideo
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CatalogProtocolTest {
    @Test
    fun catalogSetVideoRoundTripsWithSubtitleFlags() {
        val packet = SetVideo(
            id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            url = "https://example.invalid/video.mp4",
            lang = "tr",
            subtitleUrl = "https://example.invalid/subtitle.vtt",
            replaceSubtitle = true,
        )

        val decoded = assertIs<SetVideo>(
            PacketRegistry.decode(PacketRegistry.encode(packet), PacketDirection.CLIENT_TO_SERVER),
        )

        assertEquals(packet, decoded)
        assertTrue(decoded.replaceSubtitle)
        assertEquals(packet.subtitleUrl, decoded.subtitleUrl)
    }

    @Test
    fun ordinarySetVideoKeepsBackwardCompatibleDefaults() {
        val packet = SetVideo(url = "https://example.invalid/video.mp4", lang = "tr")

        assertEquals("", packet.subtitleUrl)
        assertFalse(packet.replaceSubtitle)
    }

    @Test
    fun catalogCapabilityIsExplicitAndNotImplicitlyAdvertisedByAllServers() {
        assertEquals("catalog_media", ServerFeature.CATALOG_MEDIA.wire)
        assertFalse(ServerFeature.CATALOG_MEDIA in ServerFeature.playbackFeatures)

        val oldServer = ServerHello(allowedFeatures = ServerFeature.playbackFeatureWires)
        val catalogServer = ServerHello(
            allowedFeatures = ServerFeature.playbackFeatureWires + ServerFeature.CATALOG_MEDIA.wire,
        )

        assertFalse(oldServer.hasFeature(ServerFeature.CATALOG_MEDIA))
        assertTrue(catalogServer.hasFeature(ServerFeature.CATALOG_MEDIA))
    }
}
