package com.streamflixreborn.streamflix.providers

import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.doramasflix.ApiResponse
import com.streamflixreborn.streamflix.models.doramasflix.Content
import com.streamflixreborn.streamflix.models.doramasflix.ContentPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class DoramasflixGenreBrowser(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val userAgent: String,
) {

    data class Entry(
        val show: Show,
        val markers: Set<DoramasflixContentPolicy.Marker>,
    )

    private data class CatalogBatch(
        val entries: List<Entry>,
        val hasNextPage: Boolean,
    )

    companion object {
        private const val API_URL = "https://userapi.cloudfleir.xyz/graphql"
        private const val PAGE_SIZE = 20
        private val JSON = "application/json".toMediaType()

        val genres = listOf(
            Genre("accion", "Acción"),
            Genre("aventura", "Aventura"),
            Genre("animacion", "Animación"),
            Genre("comedia", "Comedia"),
            Genre("crimen", "Crimen"),
            Genre("documental", "Documental"),
            Genre("drama", "Drama"),
            Genre("familia", "Familia"),
            Genre("fantasia", "Fantasía"),
            Genre("history", "History"),
            Genre("misterio", "Misterio"),
            Genre("music", "Music"),
            Genre("reality", "Reality"),
            Genre("romance", "Romance"),
            Genre("ciencia-ficcion", "Ciencia ficción"),
            Genre("soap", "Soap"),
            Genre("terror", "Terror"),
            Genre("thriller", "Thriller"),
            Genre("war", "War"),
        )
    }

    private val gson = Gson()

    suspend fun getEntries(slug: String, page: Int): List<Entry> = coroutineScope {
        if (genres.none { it.id == slug }) return@coroutineScope emptyList()

        val requestedPage = page.coerceAtLeast(1)
        val startIndex = (requestedPage - 1) * PAGE_SIZE
        val endIndex = requestedPage * PAGE_SIZE
        val settings = DoramasflixContentPreferences.settings()
        val entriesById = linkedMapOf<String, Entry>()

        var sourcePage = 1
        var hasMoreMovies = true
        var hasMoreDoramas = true
        var hasMoreVariedades = true

        while (
            entriesById.size < endIndex &&
            (hasMoreMovies || hasMoreDoramas || hasMoreVariedades)
        ) {
            val moviesDeferred = if (hasMoreMovies) {
                async { loadMoviePage(sourcePage, slug) }
            } else {
                null
            }
            val doramasDeferred = if (hasMoreDoramas) {
                async { loadDoramaPage(sourcePage, slug, isTvShow = false) }
            } else {
                null
            }
            val variedadesDeferred = if (hasMoreVariedades) {
                async { loadDoramaPage(sourcePage, slug, isTvShow = true) }
            } else {
                null
            }

            val movies = moviesDeferred?.await() ?: CatalogBatch(emptyList(), false)
            val doramas = doramasDeferred?.await() ?: CatalogBatch(emptyList(), false)
            val variedades = variedadesDeferred?.await() ?: CatalogBatch(emptyList(), false)

            appendInterleaved(
                destination = entriesById,
                groups = listOf(doramas.entries, movies.entries, variedades.entries)
                    .map { entries ->
                        entries.filter { entry ->
                            DoramasflixContentPolicy.allows(entry.markers, settings)
                        }
                    },
            )

            hasMoreMovies = movies.hasNextPage
            hasMoreDoramas = doramas.hasNextPage
            hasMoreVariedades = variedades.hasNextPage
            sourcePage++
        }

        entriesById.values.drop(startIndex).take(PAGE_SIZE)
    }

    private fun appendInterleaved(
        destination: MutableMap<String, Entry>,
        groups: List<List<Entry>>,
    ) {
        val largest = groups.maxOfOrNull { it.size } ?: return
        repeat(largest) { index ->
            groups.forEach { group ->
                group.getOrNull(index)?.let { entry ->
                    destination.putIfAbsent(entry.show.id, entry)
                }
            }
        }
    }

    private suspend fun loadMoviePage(page: Int, genreSlug: String): CatalogBatch {
        val response = request(
            operationName = "DoramasflixGenreMovies",
            variables = JSONObject()
                .put("page", page)
                .put("limit", PAGE_SIZE)
                .put("sort", "POPULARITY_DESC")
                .put("filter", JSONObject()),
            query = """
                query DoramasflixGenreMovies(
                  ${'$'}page: Int
                  ${'$'}limit: Int
                  ${'$'}sort: SortMovie
                  ${'$'}filter: FilterMoviesInput
                ) {
                  paginationMovie(
                    page: ${'$'}page
                    limit: ${'$'}limit
                    sort: ${'$'}sort
                    filter: ${'$'}filter
                  ) {
                    pageInfo { hasNextPage }
                    items {
                      _id
                      slug
                      name
                      name_es
                      original_name
                      overview
                      genres { name slug }
                      labels { name slug }
                      poster_path
                      poster
                      release_date
                    }
                  }
                }
            """.trimIndent(),
        ).paginationMovie ?: throw Exception("Doramasflix movie catalog returned no data.")

        return catalogBatch(response, genreSlug, isMovie = true)
    }

    private suspend fun loadDoramaPage(
        page: Int,
        genreSlug: String,
        isTvShow: Boolean,
    ): CatalogBatch {
        val response = request(
            operationName = "DoramasflixGenreDoramas",
            variables = JSONObject()
                .put("page", page)
                .put("limit", PAGE_SIZE)
                .put("sort", "POPULARITY_DESC")
                .put("filter", JSONObject().put("isTVShow", isTvShow)),
            query = """
                query DoramasflixGenreDoramas(
                  ${'$'}page: Int
                  ${'$'}limit: Int
                  ${'$'}sort: SortDorama
                  ${'$'}filter: FilterDoramasInput
                ) {
                  paginationDorama(
                    page: ${'$'}page
                    limit: ${'$'}limit
                    sort: ${'$'}sort
                    filter: ${'$'}filter
                  ) {
                    pageInfo { hasNextPage }
                    items {
                      _id
                      slug
                      name
                      name_es
                      original_name
                      overview
                      genres { name slug }
                      labels { name slug }
                      poster_path
                      poster
                      first_air_date
                    }
                  }
                }
            """.trimIndent(),
        ).paginationDorama ?: throw Exception("Doramasflix series catalog returned no data.")

        return catalogBatch(response, genreSlug, isMovie = false)
    }

    private fun catalogBatch(
        page: ContentPage,
        genreSlug: String,
        isMovie: Boolean,
    ): CatalogBatch = CatalogBatch(
        entries = page.items
            .asSequence()
            .filter { content -> hasGenre(content, genreSlug) }
            .mapNotNull { content -> entry(content, isMovie) }
            .toList(),
        hasNextPage = page.pageInfo?.hasNextPage == true,
    )

    private fun hasGenre(content: Content, genreSlug: String): Boolean =
        content.genres.orEmpty().any { genre ->
            genre.slug?.trim()?.equals(genreSlug, ignoreCase = true) == true
        }

    private fun entry(content: Content, isMovie: Boolean): Entry? {
        val slug = content.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val title = DoramasflixLogic.displayTitle(
            nameEs = content.nameEs,
            name = content.name,
            originalName = content.originalName,
        ) ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
        val poster = sequenceOf(content.posterPath, content.poster)
            .mapNotNull(::posterUrl)
            .firstOrNull()
        val show = if (isMovie) {
            Movie(
                id = "peliculas-online/$slug",
                title = title,
                poster = poster,
                released = DoramasflixLogic.normalizeDate(content.releaseDate),
            )
        } else {
            TvShow(
                id = "doramas-online/$slug",
                title = title,
                poster = poster,
                released = DoramasflixLogic.normalizeDate(content.firstAirDate),
            )
        }
        val markers = buildSet {
            addAll(DoramasflixContentPolicy.analyzeOverview(content.overview).markers)
            addAll(
                DoramasflixContentPolicy.markersFromLabels(
                    content.labels.orEmpty().map { it.name }
                )
            )
        }
        return Entry(show = show, markers = markers)
    }

    private fun posterUrl(path: String?): String? {
        val value = DoramasflixLogic.meaningfulImage(path) ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("https://") || value.startsWith("http://") -> value
            else -> "https://image.tmdb.org/t/p/w500/${value.removePrefix("/")}"
        }
    }

    private suspend fun request(
        operationName: String,
        variables: JSONObject,
        query: String,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("operationName", operationName)
            .put("variables", variables)
            .put("query", query)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(API_URL)
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/plain, */*")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string()
                ?: throw Exception("Doramasflix genre catalog returned an empty response.")
            if (!response.isSuccessful) {
                throw Exception("Doramasflix genre catalog failed: HTTP ${response.code}")
            }
            val parsed = gson.fromJson(raw, ApiResponse::class.java)
            if (parsed.errors.isNotEmpty()) {
                val message = parsed.errors
                    .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
                    .distinct()
                    .joinToString("; ")
                    .ifBlank { "Unknown GraphQL error" }
                throw Exception("Doramasflix genre catalog failed: $message")
            }
            parsed.data ?: throw Exception("Doramasflix genre catalog returned no GraphQL data.")
        }
    }
}
