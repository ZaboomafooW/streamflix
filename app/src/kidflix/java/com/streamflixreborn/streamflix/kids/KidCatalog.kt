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

    private val bundled: List<KidTitle> by lazy {
        val json = StreamFlixApp.instance.assets.open("kidflix_catalog.json").bufferedReader().use { JSONArray(it.readText()) }
        (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            KidTitle(item.getInt("id"),item.getString("name"),item.optString("poster").takeIf { it.startsWith("/") },
                shelves.single { it.label == item.getString("shelf") })
        }
    }

    private val additions = mapOf(
        "PBS KIDS" to listOf(
            "Curious George" to 2006,
            "Clifford the Big Red Dog" to 2000,
            "Dragon Tales" to 1999,
            "Martha Speaks" to 2008,
            "The Berenstain Bears" to 2003,
            "Fetch! with Ruff Ruffman" to 2006,
            "Between the Lions" to 2000,
            "Rosie's Rules" to 2022,
            "Pinkalicious & Peterrific" to 2018,
            "Let's Go Luna!" to 2018,
            "Xavier Riddle and the Secret Museum" to 2019,
            "Donkey Hodie" to 2021,
            "Lyla in the Loop" to 2024
        ),
        "Disney" to listOf(
            "Chip 'n Dale Rescue Rangers" to 1989,
            "TaleSpin" to 1990,
            "Darkwing Duck" to 1991,
            "Kim Possible" to 2002,
            "The Proud Family" to 2001,
            "Lilo & Stitch: The Series" to 2003,
            "Recess" to 1997,
            "Amphibia" to 2019,
            "Big City Greens" to 2018,
            "Elena of Avalor" to 2016,
            "Sofia the First" to 2013,
            "Doc McStuffins" to 2012,
            "The Lion Guard" to 2016
        ),
        "Nickelodeon" to listOf(
            "The Backyardigans" to 2004,
            "The Wonder Pets!" to 2006,
            "Bubble Guppies" to 2011,
            "Team Umizoomi" to 2010,
            "Go, Diego, Go!" to 2005,
            "Dora the Explorer" to 2000,
            "Hey Arnold!" to 1996,
            "Rugrats" to 1991,
            "The Wild Thornberrys" to 1998,
            "My Life as a Teenage Robot" to 2003
        ),
        "DreamWorks" to listOf(
            "Dragons: Race to the Edge" to 2015,
            "The Penguins of Madagascar" to 2008,
            "Kung Fu Panda: Legends of Awesomeness" to 2011,
            "The Mr. Peabody & Sherman Show" to 2015,
            "Dinotrux" to 2015,
            "Trolls: The Beat Goes On!" to 2018,
            "Spirit Riding Free" to 2017
        ),
        "Family Favorites" to listOf(
            "Octonauts" to 2010,
            "Hilda" to 2018,
            "Puffin Rock" to 2015,
            "Tumble Leaf" to 2013,
            "Sarah & Duck" to 2013
        )
    )
    private val saved get() = StreamFlixApp.instance.getSharedPreferences("kidflix_catalog_v1", 0)
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, KidTitle>()
    private var restored = false

    @Synchronized private fun restoreCatalog() {
        if (restored) return
        for ((key, value) in saved.all) {
            val fields = runCatching { JSONArray(value as String) }.getOrNull() ?: continue
            val shelf = shelves.firstOrNull { !it.movie && it.brand == fields.optString(3) } ?: continue
            resolved[key] = KidTitle(fields.getInt(0), fields.getString(1), fields.optString(2).ifBlank { null }, shelf)
        }
        restored = true
    }

    val catalog: List<KidTitle> get() {
        restoreCatalog()
        return bundled + resolved.values.sortedBy { it.name }
    }

    suspend fun expand(shelf: Shelf, append: (KidTitle) -> Unit) {
        if (shelf.movie) return
        restoreCatalog()
        for ((name, year) in additions[shelf.brand].orEmpty()) {
            val key = "$name/$year"
            if (key in resolved) continue
            try {
                val match = TMDb3.Search.multi(name, false, "en", 1).results.filterIsInstance<TMDb3.Tv>()
                    .firstOrNull { !it.adult && it.name.filter(Char::isLetterOrDigit).equals(name.filter(Char::isLetterOrDigit), true)
                        && it.firstAirDate?.take(4) == year.toString() } ?: continue
                val info = TMDb3.TvSeries.details(match.id, listOf(TMDb3.Params.AppendToResponse.Tv.CONTENT_RATING), "en")
                if (info.adult || KidPolicy.excluded(info.name, info.overview, info.productionCompanies.orEmpty())) continue
                val ratings = info.contentRatings?.results.orEmpty().filter { it.iso3166 == "US" }.map { it.rating }
                if (!KidPolicy.allowedRatings(ratings, false, pbs = shelf == pbs, favorite = shelf == favorites)) continue
                val item = KidTitle(info.id, info.name, info.posterPath, shelf)
                val existing = catalog.any { it.key == item.key }
                if (!existing) {
                    resolved[key] = item
                    saved.edit().putString(key, JSONArray(listOf(item.id, item.name, item.poster.orEmpty(), shelf.brand)).toString()).apply()
                    append(item)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                return
            } catch (e: retrofit2.HttpException) {
                if (e.code() != 404) return
            }
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
