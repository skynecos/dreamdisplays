package com.dreamdisplays.platform.client.ui.catalog

import com.dreamdisplays.platform.client.Initializer
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/** One playable episode in the built-in Kirazium anime catalog. */
data class AnimeEpisode(
    val number: Int,
    val title: String,
    val videoUrl: String,
    val subtitleUrl: String,
)

/** A numbered season and its episodes. */
data class AnimeSeason(
    val number: Int,
    val episodes: List<AnimeEpisode>,
)

/** Series metadata shown by the top carousel and detail screen. */
data class AnimeSeries(
    val id: String,
    val title: String,
    val artwork: Identifier,
    val seasons: List<AnimeSeason>,
)

/**
 * Small built-in catalog for the first Kirazium cinema UI pass. Keeping the data separate from the
 * player UI means a provider/API can replace this object later without rewriting the screens.
 */
object AnimeCatalog {
    val NARUTO = AnimeSeries(
        id = "naruto",
        title = "Naruto",
        artwork = Identifier.fromNamespaceAndPath(Initializer.MOD_ID, "anime/naruto"),
        seasons = listOf(
            AnimeSeason(
                number = 1,
                episodes = listOf(
                    AnimeEpisode(
                        number = 1,
                        title = "Bölüm 1",
                        videoUrl = "https://r.aniziumserver.site/46260/1/1/1080p.original.mp4",
                        subtitleUrl = "https://x.anizium.co/api/subtitle/get/file.vtt?id=921466367&name=s1_b1_55263308&season=1&episode=1",
                    ),
                ),
            ),
        ),
    )

    val series: List<AnimeSeries> = listOf(NARUTO)
}
