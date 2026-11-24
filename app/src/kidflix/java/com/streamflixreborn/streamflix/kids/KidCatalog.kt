package com.streamflixreborn.streamflix.kids

import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONArray
import com.streamflixreborn.streamflix.utils.TMDb3
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class Shelf(val brand: String, val movie: Boolean, val field: String, val ids: Set<Int>) {
    val label = "$brand " + if (movie) "Movies" else "Shows"
}

internal data class KidTitle(val id: Int, val name: String, val poster: String?, val shelf: Shelf) {
    val key = (if (shelf.movie) "movie" else "tv") + "/$id"
}

internal object KidCatalog {
    private fun tv(brand: String, vararg ids: Int) = Shelf(brand, false, "with_networks", ids.toSet())
    private fun films(brand: String, vararg ids: Int) = Shelf(brand, true, "with_companies", ids.toSet())
    private val favorites = tv("Family Favorites")
    private val pbs = tv("PBS KIDS", 122)
    private val specials = films("PBS KIDS")
    val shelves = listOf(pbs, specials, favorites,
        tv("Nickelodeon", 13, 35), films("Nickelodeon", 2348),
        tv("Disney", 54, 44, 281), films("Pixar", 3),
        Shelf("Pixar", false, "with_companies", setOf(3)),
        films("Disney", 2, 6125), tv("Paramount", 2076, 4330), films("Paramount", 4),
        Shelf("DreamWorks", false, "with_companies", setOf(521, 42141)), films("DreamWorks", 521))

    val catalog: List<KidTitle> by lazy {
        val json = StreamFlixApp.instance.assets.open("kidflix_catalog.json").bufferedReader().use { JSONArray(it.readText()) }
        (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            KidTitle(item.getInt("id"),item.getString("name"),item.optString("poster").takeIf { it.startsWith("/") },
                shelves.single { it.label == item.getString("shelf") })
        }
    }

    private val permits = Semaphore(5)

    data class Page(val titles: List<KidTitle>, val more: Boolean)

    suspend fun page(shelf: Shelf?, query: String, page: Int): Page {
        if (query.isBlank() && shelf != null && (shelf == favorites || shelf == specials)) {
            return Page(catalog.filter { it.shelf == shelf }, false)
        }
        val result = if (query.isNotBlank()) TMDb3.Search.multi(query, false, "en", page)
        else {
            requireNotNull(shelf)
            val params = mapOf(shelf.field to shelf.ids.joinToString("|"), "page" to page.toString(),
                "include_adult" to "false", "language" to "en", "sort_by" to "popularity.desc")
            if (shelf.movie) TMDb3.Discover.movie(params) else TMDb3.Discover.tv(params)
        }
        val titles = coroutineScope {
            result.results.map { item -> async { permits.withPermit { inspect(item) } } }.awaitAll()
        }.filterNotNull().filter { shelf == null || it.shelf == shelf }
        return Page(titles.distinctBy { it.key }, page < result.totalPages && page < 500)
    }

    private suspend fun inspect(item: TMDb3.MultiItem): KidTitle? = when (item) {
        is TMDb3.Movie -> movie(item.id)
        is TMDb3.Tv -> show(item.id)
        else -> null
    }

    private fun trusted(movie: Boolean, companies: Set<Int>, networks: Set<Int>): Shelf? =
        shelves.firstOrNull { shelf -> shelf.movie == movie && shelf.ids.any {
            it in (if (shelf.field == "with_companies") companies else networks)
        } }

    private suspend fun movie(id: Int): KidTitle? {
        val info = TMDb3.Movies.details(id, listOf(TMDb3.Params.AppendToResponse.Movie.RELEASES_DATES), "en")
        val companies = info.productionCompanies.orEmpty()
        if (info.adult || info.video || KidPolicy.excluded(info.title, info.overview, companies)) return null
        val ratings = info.releaseDates?.results.orEmpty().filter { it.iso3166 == "US" }
            .flatMap { it.releaseDates }.mapNotNull { it.certification }.filter { it.isNotBlank() }
        val approved = catalog.firstOrNull { it.shelf.movie && it.id == id }
        val special = approved?.shelf == specials
        if (!KidPolicy.allowedRatings(ratings, movie = true, pbs = special)) return null
        val shelf = approved?.shelf ?: trusted(true, companies.map { it.id }.toSet(), emptySet()) ?: return null
        return KidTitle(id, info.title, info.posterPath, shelf)
    }

    private suspend fun show(id: Int): KidTitle? {
        val info = TMDb3.TvSeries.details(id, listOf(TMDb3.Params.AppendToResponse.Tv.CONTENT_RATING), "en")
        val companies = info.productionCompanies.orEmpty()
        if (info.adult || KidPolicy.excluded(info.name, info.overview, companies)) return null
        val approved = catalog.firstOrNull { !it.shelf.movie && it.id == id }
        val shelf = approved?.shelf ?: trusted(false, companies.map { it.id }.toSet(), info.networks.map { it.id }.toSet()) ?: return null
        if (shelf != pbs && shelf != favorites && info.type != TMDb3.Tv.Type.SCRIPTED) return null
        val ratings = info.contentRatings?.results.orEmpty().filter { it.iso3166 == "US" }.map { it.rating }.filter { it.isNotBlank() }
        if (!KidPolicy.allowedRatings(ratings, movie = false, pbs = shelf == pbs, favorite = shelf == favorites)) return null
        return KidTitle(id, info.name, info.posterPath, shelf)
    }
}
