package com.dreamdisplays.platform.client.catalog

import com.dreamdisplays.core.catalog.CatalogEpisode
import com.dreamdisplays.core.catalog.CatalogSeason
import com.dreamdisplays.core.catalog.CatalogSeries
import com.dreamdisplays.core.catalog.KiraziumCatalog

/** Client-facing names kept small while the actual catalog lives in shared core for server validation. */
typealias AnimeEpisode = CatalogEpisode
typealias AnimeSeason = CatalogSeason
typealias AnimeSeries = CatalogSeries

/** Read-only client view of the server-verifiable Kirazium catalog. */
object AnimeCatalog {
    val series: List<AnimeSeries> get() = KiraziumCatalog.series
}
