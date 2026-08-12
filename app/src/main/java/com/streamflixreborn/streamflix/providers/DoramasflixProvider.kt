package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.models.doramasflix.ApiResponse
import com.streamflixreborn.streamflix.models.doramasflix.Data
import com.streamflixreborn.streamflix.models.doramasflix.Episode as DoramasflixEpisode
import com.streamflixreborn.streamflix.models.doramasflix.OnlineLink
import com.streamflixreborn.streamflix.models.doramasflix.Show as DoramasflixShow
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DoramasflixProvider : Provider {

    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.in"
    override val language = "es"
    override val logo =
        "https://assets.seriesapi.co/brands/doramasflix/websites/6a651fa138cbd16df74343be/logo/logo-1785013866419.png"

    private const val apiUrl = "https://sv7.fluxcedene.net/"
    private const val playbackApiUrl = "https://userapi.cloudfleir.xyz/"
    private const val playbackApp = "com.asiapp.doramasgo"
    private const val episodeAvailabilityPageSize = 100

    private val movieBackendIds = ConcurrentHashMap<String, String>()
    private val episodeBackendIds = ConcurrentHashMap<String, String>()

    private val client = OkHttpClient.Builder()
        .cache(Cache(File("cacheDir", "okhttpcache"), 10L * 1024 * 1024))
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    private val catalogService = Retrofit.Builder()
        .baseUrl(apiUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private val playbackService = Retrofit.Builder()
        .baseUrl(playbackApiUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private val htmlService = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private val languages = mapOf(
        "36" to "[ENG]",
        "37" to "[CAST]",
        "38" to "[LAT]",
        "192" to "[SUB]",
        "1327" to "[POR]",
        "13109" to "[COR]",
        "13110" to "[JAP]",
        "13111" to "[MAN]",
        "13112" to "[TAI]",
        "13113" to "[FIL]",
        "13114" to "[IND]",
        "343422" to "[VIET]",
    )

    private interface DoramasflixService {
        @POST("graphql")
        @Headers("Content-Type: application/json")
        suspend fun getApiResponse(
            @Header("Referer") referer: String,
            @Body body: okhttp3.RequestBody,
        ): ApiResponse

        @POST("graphql")
        @Headers(
            "Accept: application/json, text/plain, */*",
            "Content-Type: application/json",
        )
        suspend fun getPlaybackResponse(
            @Header("Origin") origin: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String,
            @Body body: okhttp3.RequestBody,
        ): ApiResponse

        @GET
        suspend fun getPage(@Url url: String): Document
    }

    private data class StructuredMetadata(
        val description: String? = null,
        val rating: Double? = null,
    )

    private fun requestBody(
        operationName: String,
        variables: JSONObject,
        query: String,
    ) = JSONObject()
        .put("operationName", operationName)
        .put("variables", variables)
        .put("query", query)
        .toString()
        .toRequestBody("application/json".toMediaType())

    private fun ApiResponse.requireData(context: String): Data {
        if (errors.isNotEmpty()) {
            val message = errors
                .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
                .joinToString("; ")
                .ifBlank { "Unknown GraphQL error" }

            if (message.contains("Too Many Requests", ignoreCase = true)) {
                throw Exception("$context is temporarily rate limiting requests. Please try again shortly.")
            }
            throw Exception("$context returned an error: $message")
        }

        return data ?: throw Exception("$context returned no data.")
    }

    private suspend fun catalogRequest(
        operationName: String,
        variables: JSONObject,
        query: String,
    ): Data = catalogService.getApiResponse(
        referer = "$baseUrl/",
        body = requestBody(operationName, variables, query),
    ).requireData("Doramasflix catalog API")

    private suspend fun playbackRequest(
        operationName: String,
        variables: JSONObject,
        query: String,
    ): Data = playbackService.getPlaybackResponse(
        origin = baseUrl,
        referer = "$baseUrl/",
        userAgent = "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36",
        body = requestBody(operationName, variables, query),
    ).requireData("Doramasflix playback API")

    private fun imageUrl(path: String?, size: String): String? {
        val value = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("https://") || value.startsWith("http://") -> value
            else -> "https://image.tmdb.org/t/p/$size/${value.removePrefix("/")}"
        }
    }

    private fun posterUrl(path: String?) = imageUrl(path, "w500")
    private fun backdropUrl(path: String?) = imageUrl(path, "w1280")

    private fun normalizePath(id: String): String = id
        .removePrefix("$baseUrl/")
        .removePrefix("/")
        .substringBefore('?')

    private fun slugFromId(id: String): String = normalizePath(id).substringAfterLast('/')

    private fun movieId(slug: String) = "peliculas-online/$slug"
    private fun doramaId(slug: String) = "doramas-online/$slug"

    private fun titleFor(show: DoramasflixShow): String {
        val translated = show.nameEs
            ?.takeIf { it.isNotBlank() && !it.equals(show.name, ignoreCase = true) }
        return translated?.let { "${show.name} ($it)" } ?: show.name
    }

    private fun cacheMovie(show: DoramasflixShow) {
        movieBackendIds[show.slug] = show.id
    }

    private fun episodeCacheKey(showSlug: String, episodeSlug: String) =
        "$showSlug|$episodeSlug"

    private fun cacheEpisodes(
        showSlug: String,
        episodes: List<DoramasflixEpisode>,
    ) {
        episodes.forEach { episode ->
            episodeBackendIds[episodeCacheKey(showSlug, episode.slug)] = episode.id
        }
    }

    private fun structuredMetadata(
        document: Document,
        type: String,
        pageUrl: String,
    ): StructuredMetadata {
        val normalizedPageUrl = pageUrl.trimEnd('/')
        val data = document.select("script[type=application/ld+json]")
            .asSequence()
            .mapNotNull { script ->
                runCatching { JSONObject(script.data()) }.getOrNull()
            }
            .firstOrNull { candidate ->
                candidate.optString("@type") == type &&
                    candidate.optString("url").trimEnd('/') == normalizedPageUrl
            }
            ?: return StructuredMetadata()

        val description = data.optString("description")
            .trim()
            .takeIf { it.isNotBlank() }
        val rating = data.optJSONObject("aggregateRating")
            ?.optDouble("ratingValue", Double.NaN)
            ?.takeIf { !it.isNaN() }

        return StructuredMetadata(
            description = description,
            rating = rating,
        )
    }

    private suspend fun searchAll(input: String): Data {
        val data = catalogRequest(
            operationName = "searchAll",
            variables = JSONObject().put("input", input),
            query = """
                query searchAll(${'$'}input: String!) {
                  searchDorama(input: ${'$'}input, limit: 32) {
                    _id
                    slug
                    name
                    name_es
                    poster_path
                    poster
                  }
                  searchMovie(input: ${'$'}input, limit: 32) {
                    _id
                    slug
                    name
                    name_es
                    poster_path
                    poster
                    backdrop_path
                    backdrop
                    overview
                  }
                }
            """.trimIndent(),
        )

        data.searchMovie.orEmpty().forEach(::cacheMovie)
        return data
    }

    private suspend fun findMovieBySlug(
        slug: String,
        titleHint: String? = null,
    ): DoramasflixShow? {
        val primaryInput = titleHint?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        val primary = searchAll(primaryInput).searchMovie.orEmpty()
            .firstOrNull { it.slug == slug }
        if (primary != null || titleHint == null) return primary

        return searchAll(slug.replace('-', ' ')).searchMovie.orEmpty()
            .firstOrNull { it.slug == slug }
    }

    private suspend fun findDoramaBySlug(slug: String): DoramasflixShow? =
        searchAll(slug.replace('-', ' ')).searchDorama.orEmpty()
            .firstOrNull { it.slug == slug }

    private suspend fun getSeasons(
        slug: String,
    ): List<com.streamflixreborn.streamflix.models.doramasflix.Season> =
        catalogRequest(
            operationName = "listSeasons",
            variables = JSONObject().put("slug", slug),
            query = """
                query listSeasons(${'$'}slug: String!) {
                  listSeasons(sort: NUMBER_ASC, filter: {serie_slug: ${'$'}slug}) {
                    season_number
                    poster_path
                    serie_backdrop_path
                    serie_name
                    trailer
                    backdrop
                    overview
                    name
                  }
                }
            """.trimIndent(),
        ).listSeasons.orEmpty()
            .distinctBy { it.seasonNumber }

    private suspend fun getEpisodes(
        slug: String,
        seasonNumber: Int,
    ): List<DoramasflixEpisode> {
        val episodes = catalogRequest(
            operationName = "listEpisodes",
            variables = JSONObject()
                .put("slug", slug)
                .put("season_number", seasonNumber),
            query = """
                query listEpisodes(${'$'}slug: String!, ${'$'}season_number: Int!) {
                  listEpisodes(
                    sort: NUMBER_ASC
                    filter: {serie_slug: ${'$'}slug, season_number: ${'$'}season_number}
                  ) {
                    _id
                    name
                    slug
                    serie_id
                    still_path
                    episode_number
                  }
                }
            """.trimIndent(),
        ).listEpisodes.orEmpty()

        cacheEpisodes(slug, episodes)
        return episodes
    }

    private suspend fun getAvailableEpisodes(
        slug: String,
        seasonNumber: Int,
    ): List<DoramasflixEpisode> {
        val episodes = getEpisodes(slug, seasonNumber)
        if (episodes.isEmpty()) return episodes

        val serieIds = episodes
            .mapNotNull { it.serieId?.takeIf(String::isNotBlank) }
            .distinct()
        if (serieIds.size != 1) return episodes

        val availability = runCatching {
            val availabilityBySlug = linkedMapOf<String, Int?>()
            var page = 1

            while (availabilityBySlug.size < episodes.size) {
                val pageItems = catalogRequest(
                    operationName = "EpisodesPagination",
                    variables = JSONObject()
                        .put("page", page)
                        .put("serie_id", serieIds.single())
                        .put("season_number", seasonNumber)
                        .put("limit", episodeAvailabilityPageSize)
                        .put("sort", "NUMBER_ASC"),
                    query = """
                        query EpisodesPagination(
                          ${'$'}page: Int!
                          ${'$'}serie_id: ID!
                          ${'$'}season_number: Int!
                          ${'$'}limit: Int!
                          ${'$'}sort: SortEpisode
                        ) {
                          paginationEpisode(
                            page: ${'$'}page
                            limit: ${'$'}limit
                            sort: ${'$'}sort
                            filter: {serie_id: ${'$'}serie_id, season_number: ${'$'}season_number}
                          ) {
                            items {
                              slug
                              count_links
                            }
                          }
                        }
                    """.trimIndent(),
                ).paginationEpisode?.items.orEmpty()

                if (pageItems.isEmpty()) break

                val previousSize = availabilityBySlug.size
                pageItems.forEach { episode ->
                    availabilityBySlug[episode.slug] = episode.countLinks
                }
                if (availabilityBySlug.size == previousSize) break
                page++
            }

            availabilityBySlug
        }.getOrNull().orEmpty()

        return DoramasflixLogic.filterAvailableEpisodes(
            episodes = episodes,
            availabilityBySlug = availability,
        )
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val doramas = async { getTvShows(1) }
        val movies = async { getMovies(1) }

        buildList {
            doramas.await().takeIf { it.isNotEmpty() }?.let {
                add(Category(name = "Doramas Populares", list = it))
            }
            movies.await().takeIf { it.isNotEmpty() }?.let {
                add(Category(name = "Películas Populares", list = it))
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        if (query.isBlank()) {
            return listOf(
                Genre("doramas", "Doramas"),
                Genre("peliculas", "Películas"),
                Genre("variedades", "Variedades"),
            )
        }

        val data = searchAll(query)
        return buildList {
            data.searchDorama.orEmpty().forEach { show ->
                add(
                    TvShow(
                        id = doramaId(show.slug),
                        title = titleFor(show),
                        poster = posterUrl(show.posterPath ?: show.poster),
                    )
                )
            }
            data.searchMovie.orEmpty().forEach { show ->
                add(
                    Movie(
                        id = movieId(show.slug),
                        title = titleFor(show),
                        poster = posterUrl(show.posterPath ?: show.poster),
                    )
                )
            }
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val items = catalogRequest(
            operationName = "PaginationMovie",
            variables = JSONObject()
                .put("page", page)
                .put("limit", 20)
                .put("filter", JSONObject()),
            query = """
                query PaginationMovie(${'$'}page: Int, ${'$'}limit: Int, ${'$'}filter: FilterMoviesInput) {
                  paginationMovie(page: ${'$'}page, limit: ${'$'}limit, filter: ${'$'}filter) {
                    items {
                      _id
                      name
                      name_es
                      slug
                      poster_path
                      poster
                    }
                  }
                }
            """.trimIndent(),
        ).paginationMovie?.items.orEmpty()

        items.forEach(::cacheMovie)
        return items.map { show ->
            Movie(
                id = movieId(show.slug),
                title = titleFor(show),
                poster = posterUrl(show.posterPath ?: show.poster),
            )
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> =
        getDoramaPage(page, isTvShow = false)

    private suspend fun getDoramaPage(
        page: Int,
        isTvShow: Boolean,
    ): List<TvShow> {
        val items = catalogRequest(
            operationName = "PaginationDorama",
            variables = JSONObject()
                .put("page", page)
                .put("limit", 20)
                .put("filter", JSONObject().put("isTVShow", isTvShow)),
            query = """
                query PaginationDorama(${'$'}page: Int, ${'$'}limit: Int, ${'$'}filter: FilterDoramasInput) {
                  paginationDorama(page: ${'$'}page, limit: ${'$'}limit, filter: ${'$'}filter) {
                    items {
                      _id
                      name
                      name_es
                      slug
                      poster_path
                      poster
                    }
                  }
                }
            """.trimIndent(),
        ).paginationDorama?.items.orEmpty()

        return items.map { show ->
            TvShow(
                id = doramaId(show.slug),
                title = titleFor(show),
                poster = posterUrl(show.posterPath ?: show.poster),
            )
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val path = normalizePath(id)
        val slug = slugFromId(id)
        val pageUrl = "$baseUrl/$path"

        try {
            val document = runCatching { htmlService.getPage(pageUrl) }.getOrNull()
            val pageTitle = document
                ?.selectFirst("h1")
                ?.text()
                ?.takeIf { it.isNotBlank() }
            val apiMovie = findMovieBySlug(slug, pageTitle)
                ?: throw Exception("No se pudo resolver la película en la API de Doramasflix.")
            val metadata = document?.let {
                structuredMetadata(it, "Movie", pageUrl)
            } ?: StructuredMetadata()

            return Movie(
                id = movieId(slug),
                title = pageTitle ?: titleFor(apiMovie),
                overview = metadata.description ?: apiMovie.overview,
                rating = metadata.rating,
                poster = posterUrl(apiMovie.posterPath ?: apiMovie.poster),
                banner = backdropUrl(apiMovie.backdropPath ?: apiMovie.backdrop),
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles de la película: ${e.message}", e)
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val path = normalizePath(id)
        val slug = slugFromId(id)
        val pageUrl = "$baseUrl/$path"

        try {
            val seasonsData = getSeasons(slug)
            val firstSeason = seasonsData.firstOrNull()
            val needsSearchFallback =
                firstSeason == null ||
                    firstSeason.serieName.isNullOrBlank() ||
                    firstSeason.posterPath.isNullOrBlank()
            val apiShow = if (needsSearchFallback) findDoramaBySlug(slug) else null
            val pageMetadata = runCatching { htmlService.getPage(pageUrl) }
                .getOrNull()
                ?.let { structuredMetadata(it, "TVSeries", pageUrl) }
                ?: StructuredMetadata()

            val seasons = seasonsData.map { season ->
                Season(
                    id = "$slug/${season.seasonNumber}",
                    number = season.seasonNumber,
                    title = season.name
                        ?.takeIf { it.isNotBlank() }
                        ?: "Temporada ${season.seasonNumber}",
                    poster = posterUrl(season.posterPath),
                )
            }

            return TvShow(
                id = path,
                title = firstSeason?.serieName
                    ?.takeIf { it.isNotBlank() }
                    ?: apiShow?.let(::titleFor)
                    ?: slug.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) },
                overview = pageMetadata.description ?: firstSeason?.overview,
                rating = pageMetadata.rating,
                poster = posterUrl(
                    firstSeason?.posterPath ?: apiShow?.posterPath ?: apiShow?.poster
                ),
                banner = backdropUrl(
                    firstSeason?.serieBackdropPath ?: firstSeason?.backdrop
                ),
                trailer = firstSeason?.trailer,
                seasons = seasons,
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles del dorama: ${e.message}", e)
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val slug = seasonId.substringBeforeLast('/')
        val seasonNumber = seasonId.substringAfterLast('/').toIntOrNull()
            ?: throw Exception("Invalid Doramasflix season ID: $seasonId")
        val episodes = getAvailableEpisodes(slug, seasonNumber)
        val sharedStillPath = DoramasflixLogic.sharedStillPath(episodes)

        return episodes.map { episode ->
            Episode(
                id = episode.slug,
                number = episode.episodeNumber ?: 0,
                title = "Episodio ${episode.episodeNumber ?: 0}: ${episode.name.orEmpty()}".trim(),
                poster = posterUrl(
                    episode.stillPath?.takeUnless { it == sharedStillPath }
                ),
            )
        }
    }

    private suspend fun resolveMovieBackendId(
        slug: String,
        titleHint: String?,
    ): String {
        movieBackendIds[slug]?.let { return it }

        val movie = findMovieBySlug(slug, titleHint)
            ?: throw Exception("Doramasflix could not resolve the movie playback ID.")
        cacheMovie(movie)
        return movie.id
    }

    private suspend fun resolveEpisodeBackendId(
        episodeSlug: String,
        showSlug: String,
        seasonNumber: Int,
    ): String {
        episodeBackendIds[episodeCacheKey(showSlug, episodeSlug)]?.let { return it }

        return getEpisodes(showSlug, seasonNumber)
            .firstOrNull { it.slug == episodeSlug }
            ?.id
            ?: throw Exception("Doramasflix could not resolve the episode playback ID.")
    }

    private suspend fun getPlaybackLinks(
        id: String,
        videoType: Video.Type,
    ): List<OnlineLink> = when (videoType) {
        is Video.Type.Movie -> {
            val slug = slugFromId(id)
            val backendId = resolveMovieBackendId(slug, videoType.title)
            playbackRequest(
                operationName = "MovieLinks",
                variables = JSONObject().put("movie_id", backendId),
                query = """
                    query MovieLinks(${'$'}movie_id: ID!) {
                      getMovieLinks(id: ${'$'}movie_id, app: "$playbackApp") {
                        links_online {
                          lang
                          link
                          is_recommended
                        }
                      }
                    }
                """.trimIndent(),
            ).getMovieLinks?.linksOnline.orEmpty()
        }

        is Video.Type.Episode -> {
            val showSlug = slugFromId(videoType.tvShow.id)
            val backendId = resolveEpisodeBackendId(
                episodeSlug = id,
                showSlug = showSlug,
                seasonNumber = videoType.season.number,
            )
            playbackRequest(
                operationName = "EpisodeLinksOnline",
                variables = JSONObject().put("episode_id", backendId),
                query = """
                    query EpisodeLinksOnline(${'$'}episode_id: ID!) {
                      getEpisodeLinks(id: ${'$'}episode_id, app: "$playbackApp") {
                        links_online {
                          lang
                          link
                          is_recommended
                        }
                      }
                    }
                """.trimIndent(),
            ).getEpisodeLinks?.linksOnline.orEmpty()
        }
    }

    private fun decodePlaybackLink(link: String): String? {
        if (!link.contains("embedshortener.co/e/")) {
            return DoramasflixLogic.normalizePlaybackTarget(link)
        }

        return runCatching {
            val token = link.substringAfter("/e/")
                .substringBefore('?')
                .substringBefore('#')
            val payload = token.split('.').getOrNull(1)
                ?: return@runCatching null
            val payloadJson = String(
                Base64.decode(
                    payload,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
            )
            val encodedLink = JSONObject(payloadJson)
                .optString("link")
                .takeIf { it.isNotBlank() }
                ?: return@runCatching null

            DoramasflixLogic.normalizePlaybackTarget(
                String(Base64.decode(encodedLink, Base64.DEFAULT))
            )
        }.getOrNull()
    }

    private fun getServerName(link: String): String {
        val host = runCatching {
            URL(link).host.lowercase(Locale.ROOT).removePrefix("www.")
        }.getOrNull() ?: return "Server"

        return when (host) {
            "do7go.com" -> "DoodStream"
            "flaswish.com" -> "Streamwish"
            "bysefujedu.com" -> "Filemoon"
            "callistanise.com" -> "VidHide"
            "jessicayeahcatch.com" -> "VOE"
            "streamtape.com" -> "Streamtape"
            "ok.ru" -> "Okru"
            "m1xdrop.bz", "miixdrop.com" -> "MixDrop"
            else -> host.substringBefore('.')
                .replaceFirstChar { it.titlecase(Locale.ROOT) }
                .takeIf { it.isNotBlank() }
                ?: "Server"
        }
    }

    private fun isPrimeload(link: String): Boolean {
        val host = runCatching { URL(link).host.lowercase(Locale.ROOT) }
            .getOrNull()
            ?: return false
        return host == "primeload.co" || host.endsWith(".primeload.co")
    }

    override suspend fun getServers(
        id: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        val playbackLinks = try {
            getPlaybackLinks(id, videoType)
        } catch (e: Exception) {
            throw Exception("Doramasflix playback lookup failed: ${e.message}", e)
        }

        if (playbackLinks.isEmpty()) {
            throw Exception("Doramasflix currently has no playback sources for this title.")
        }

        var primeloadCount = 0
        var invalidCount = 0

        val servers = playbackLinks
            .sortedByDescending { it.isRecommended == true }
            .mapNotNull { onlineLink ->
                val rawLink = onlineLink.link?.takeIf { it.isNotBlank() }
                if (rawLink == null) {
                    invalidCount++
                    return@mapNotNull null
                }

                val decodedLink = decodePlaybackLink(rawLink)
                if (decodedLink == null) {
                    invalidCount++
                    return@mapNotNull null
                }

                if (isPrimeload(decodedLink)) {
                    primeloadCount++
                    return@mapNotNull null
                }

                val language = onlineLink.lang
                    ?.let(languages::get)
                    .orEmpty()
                Video.Server(
                    id = decodedLink,
                    name = "${getServerName(decodedLink)} $language".trim(),
                )
            }
            .distinctBy { it.id }

        if (servers.isNotEmpty()) return servers

        if (primeloadCount == playbackLinks.size) {
            throw Exception(
                "Doramasflix currently offers this title only on Primeload, which StreamFlix does not support yet."
            )
        }

        if (invalidCount == playbackLinks.size) {
            throw Exception("Doramasflix returned invalid playback source URLs for this title.")
        }

        throw Exception("Doramasflix currently has no supported playback sources for this title.")
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int): Genre {
        val shows: List<Show> = when (id) {
            "peliculas" -> getMovies(page)
            "variedades" -> getDoramaPage(page, isTvShow = true)
            else -> getTvShows(page)
        }

        return Genre(
            id = id,
            name = id.replaceFirstChar { it.uppercase() },
            shows = shows,
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw Exception("Not yet implemented")
}
