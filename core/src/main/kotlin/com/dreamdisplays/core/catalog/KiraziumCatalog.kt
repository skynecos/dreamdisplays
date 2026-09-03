package com.dreamdisplays.core.catalog

/** One playable episode in the server-verifiable Kirazium catalog. */
data class CatalogEpisode(
    val number: Int,
    val title: String,
    val videoUrl: String,
    val subtitleUrl: String,
)

data class CatalogSeason(
    val number: Int,
    val episodes: List<CatalogEpisode>,
)

data class CatalogSeries(
    val id: String,
    val title: String,
    val seasons: List<CatalogSeason>,
)

/**
 * Single source of truth shared by client and server. The server only accepts catalog media pairs
 * present here, so a modified client cannot use the catalog packet as a custom-URL permission bypass.
 */
object KiraziumCatalog {
    val series: List<CatalogSeries> = listOf(
        CatalogSeries(
            id = "naruto",
            title = "Naruto",
            seasons = listOf(
                CatalogSeason(
                    number = 1,
                    episodes = listOf(
                        CatalogEpisode(
                            number = 2,
                            title = "Bölüm 2",
                            videoUrl = "https://r.aniziumserver.site/46260/1/2/1080p.original.mp4",
                            subtitleUrl = "https://x.anizium.co/api/subtitle/get/file.vtt?id=921466367&name=s1_b2_18730010&season=1&episode=2",
                        ),
                    ),
                ),
            ),
        ),
    )

    fun findByMedia(videoUrl: String, subtitleUrl: String): CatalogEpisode? =
        series.asSequence()
            .flatMap { it.seasons.asSequence() }
            .flatMap { it.episodes.asSequence() }
            .firstOrNull { it.videoUrl == videoUrl && it.subtitleUrl == subtitleUrl }
}
