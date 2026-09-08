package com.dreamdisplays.core.catalog

import com.dreamdisplays.api.security.policy.MediaUrlPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KiraziumCatalogTest {
    @Test
    fun narutoSeasonOneKeepsVerifiedEpisodesInOrder() {
        val naruto = KiraziumCatalog.series.single { it.id == "naruto" }
        val seasonOne = naruto.seasons.single { it.number == 1 }

        assertEquals(listOf(1, 2), seasonOne.episodes.map { it.number })
        assertEquals(listOf("Bölüm 1", "Bölüm 2"), seasonOne.episodes.map { it.title })
    }

    @Test
    fun exactVideoSubtitlePairIsRequired() {
        val naruto = KiraziumCatalog.series.single { it.id == "naruto" }
        val seasonOne = naruto.seasons.single { it.number == 1 }
        val episodeOne = seasonOne.episodes.single { it.number == 1 }
        val episodeTwo = seasonOne.episodes.single { it.number == 2 }

        assertNotNull(KiraziumCatalog.findByMedia(episodeOne.videoUrl, episodeOne.subtitleUrl))
        assertNotNull(KiraziumCatalog.findByMedia(episodeTwo.videoUrl, episodeTwo.subtitleUrl))
        assertNull(KiraziumCatalog.findByMedia(episodeOne.videoUrl, episodeTwo.subtitleUrl))
        assertNull(KiraziumCatalog.findByMedia(episodeTwo.videoUrl, episodeOne.subtitleUrl))
    }

    @Test
    fun everyCatalogEndpointPassesMediaUrlPolicy() {
        val episodes = KiraziumCatalog.series
            .flatMap { it.seasons }
            .flatMap { it.episodes }

        assertTrue(episodes.isNotEmpty())
        episodes.forEach { episode ->
            assertTrue(MediaUrlPolicy.isAllowed(episode.videoUrl), "Rejected video URL for ${episode.title}")
            assertTrue(MediaUrlPolicy.isAllowed(episode.subtitleUrl), "Rejected subtitle URL for ${episode.title}")
        }
    }

    @Test
    fun episodeNumbersAndMediaPairsAreUniquePerSeason() {
        KiraziumCatalog.series.forEach { series ->
            series.seasons.forEach { season ->
                assertEquals(
                    season.episodes.size,
                    season.episodes.map { it.number }.toSet().size,
                    "Duplicate episode number in ${series.id} season ${season.number}",
                )
                assertEquals(
                    season.episodes.size,
                    season.episodes.map { it.videoUrl to it.subtitleUrl }.toSet().size,
                    "Duplicate media pair in ${series.id} season ${season.number}",
                )
            }
        }
    }
}
