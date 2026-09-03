package com.dreamdisplays.platform.client.catalog

/** One playable episode in the built-in Kirazium anime catalog. */
data class AnimeEpisode(
    val number: Int,
    val title: String,
    val videoUrl: String,
    val subtitleUrl: String,
)

/** A season groups episodes without coupling the UI to a particular series. */
data class AnimeSeason(
    val number: Int,
    val episodes: List<AnimeEpisode>,
)

/** A top-level catalog card shown in the horizontal series shelf. */
data class AnimeSeries(
    val id: String,
    val title: String,
    val seasons: List<AnimeSeason>,
)

/**
 * Curated media catalog. Keep episode endpoints centralized here so UI code never contains media URLs.
 * Only endpoints that have been explicitly verified are added; guessed episode/subtitle URLs are forbidden.
 */
object AnimeCatalog {
    val series: List<AnimeSeries> = listOf(
        AnimeSeries(
            id = "naruto",
            title = "Naruto",
            seasons = listOf(
                AnimeSeason(
                    number = 1,
                    episodes = listOf(
                        AnimeEpisode(
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
}
