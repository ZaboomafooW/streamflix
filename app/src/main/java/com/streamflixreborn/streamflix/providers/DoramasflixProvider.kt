package com.streamflixreborn.streamflix.providers

import android.util.Base64
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
import com.streamflixreborn.streamflix.models.doramasflix.Content
import com.streamflixreborn.streamflix.models.doramasflix.Data
import com.streamflixreborn.streamflix.models.doramasflix.Episode as DoramasflixEpisode
import com.streamflixreborn.streamflix.models.doramasflix.OnlineLink
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
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

    private val movieBackendIds = ConcurrentHashMap<String, String>()
    private val episodeBackendIds = ConcurrentHashMap<String, String>()

    @Volatile
    private var serverNamesByCode: Map<String, String>? = null

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
    }

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

    private fun titleFor(content: Content): String {
        val alternate = content.nameEs
            ?.takeIf { it.isNotBlank() && !it.equals(content.name, ignoreCase = true) }
        return alternate?.let { "${content.name} ($it)" } ?: content.name
    }

    private fun genresFor(content: Content): List<Genre> =
        content.genres.mapNotNull { tag ->
            val genreName = tag.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val genreId = tag.slug?.trim()?.takeIf { it.isNotEmpty() } ?: genreName
            Genre(id = genreId, name = genreName)
        }

    private fun cacheMovie(content: Content) {
        movieBackendIds[content.slug] = content.id
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
                  }
                }
            """.trimIndent(),
        )

        data.searchMovie.orEmpty().forEach(::cacheMovie)
        return data
    }

    private suspend fun detailMovie(slug: String): Content {
        val content = catalogRequest(
            operationName = "DetailMovieSlug",
            variables = JSONObject().put("slug", slug),
            query = """
                query DetailMovieSlug(${'$'}slug: String!) {
                  detailMovie(filter: {slug: ${'$'}slug}) {
                    _id
                    slug
                    name
                    name_es
                    overview
                    trailer
                    release_date
                    poster_path
                    poster
                    backdrop_path
                    backdrop
                    runtime
                    rating
                    genres {
                      name
                      slug
                    }
                  }
                }
            """.trimIndent(),
        ).detailMovie
            ?: throw Exception("Doramasflix could not find movie '$slug'.")

        cacheMovie(content)
        return content
    }

    private suspend fun detailDorama(slug: String): Content =
        catalogRequest(
            operationName = "DetailDoramaSlug",
            variables = JSONObject().put("slug", slug),
            query = """
                query DetailDoramaSlug(${'$'}slug: String!) {
                  detailDorama(filter: {slug: ${'$'}slug}) {
                    _id
                    slug
                    name
                    name_es
                    overview
                    trailer
                    first_air_date
                    poster_path
                    poster
                    backdrop_path
                    backdrop
                    episode_time
                    rating
                    isTVShow
                    genres {
                      name
                      slug
                    }
                  }
                }
            """.trimIndent(),
        ).detailDorama
            ?: throw Exception("Doramasflix could not find dorama '$slug'.")

    private suspend fun getSeasons(
        slug: String,
    ): List<com.streamflixreborn.streamflix.models.doramasflix.Season> =
        catalogRequest(
            operationName = "listSeasons",
            variables = JSONObject().put("slug", slug),
            query = """
                query listSeasons(${'$'}slug: String!) {
                  listSeasons(sort: NUMBER_ASC, filter: {serie_slug: ${'$'}slug}) {
                    _id
                    slug
                    name
                    name_es
                    poster
                    poster_path
                    serie_id
                    serie_slug
                    season_number
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
                    name_es
                    slug
                    serie_id
                    episode_number
                    season_number
                    still_path
                    still_image
                    serie_backdrop_path
                    backdrop
                    overview
                    air_date
                    count_links
                  }
                }
            """.trimIndent(),
        ).listEpisodes.orEmpty()

        cacheEpisodes(slug, episodes)
        return episodes
    }

    private suspend fun getServerNamesByCode(): Map<String, String> {
        serverNamesByCode?.let { return it }

        val discovered = runCatching {
            catalogRequest(
                operationName = "ListServers",
                variables = JSONObject(),
                query = """
                    query ListServers {
                      listServers {
                        name
                        code_flix
                      }
                    }
                """.trimIndent(),
            ).listServers.orEmpty()
                .mapNotNull { server ->
                    val code = server.codeFlix?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    val serverName = server.name?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    code to serverName
                }
                .toMap()
        }.getOrElse {
            emptyMap()
        }

        if (discovered.isNotEmpty()) {
            serverNamesByCode = discovered
        }
        return discovered
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
                .put("sort", "POPULARITY_DESC")
                .put("filter", JSONObject()),
            query = """
                query PaginationMovie(
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
                .put("sort", "POPULARITY_DESC")
                .put("filter", JSONObject().put("isTVShow", isTvShow)),
            query = """
                query PaginationDorama(
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
        val slug = slugFromId(id)
        val content = detailMovie(slug)

        return Movie(
            id = movieId(slug),
            title = titleFor(content),
            overview = content.overview?.takeIf { it.isNotBlank() },
            released = content.releaseDate,
            runtime = content.runtime,
            trailer = DoramasflixLogic.normalizeTrailer(content.trailer),
            rating = DoramasflixLogic.normalizeRating(content.rating),
            poster = posterUrl(content.posterPath ?: content.poster),
            banner = backdropUrl(content.backdropPath ?: content.backdrop),
            genres = genresFor(content),
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val slug = slugFromId(id)
        val detailDeferred = async { detailDorama(slug) }
        val seasonsDeferred = async { getSeasons(slug) }

        val content = detailDeferred.await()
        val seasonsData = seasonsDeferred.await()

        TvShow(
            id = doramaId(slug),
            title = titleFor(content),
            overview = content.overview?.takeIf { it.isNotBlank() },
            released = content.firstAirDate,
            runtime = content.episodeTime,
            trailer = DoramasflixLogic.normalizeTrailer(content.trailer),
            rating = DoramasflixLogic.normalizeRating(content.rating),
            poster = posterUrl(content.posterPath ?: content.poster),
            banner = backdropUrl(content.backdropPath ?: content.backdrop),
            seasons = seasonsData.map { season ->
                Season(
                    id = "$slug/${season.seasonNumber}",
                    number = season.seasonNumber,
                    title = season.name
                        ?.takeIf { it.isNotBlank() }
                        ?: "Temporada ${season.seasonNumber}",
                    poster = posterUrl(season.posterPath ?: season.poster),
                )
            },
            genres = genresFor(content),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val slug = seasonId.substringBeforeLast('/')
        val seasonNumber = seasonId.substringAfterLast('/').toIntOrNull()
            ?: throw Exception("Invalid Doramasflix season ID: $seasonId")

        return getEpisodes(slug, seasonNumber)
            .filter { DoramasflixLogic.isEpisodeAvailable(it.countLinks) }
            .map { episode ->
                Episode(
                    id = episode.slug,
                    number = episode.episodeNumber ?: 0,
                    title = episode.nameEs
                        ?.takeIf { it.isNotBlank() }
                        ?: episode.name?.takeIf { it.isNotBlank() }
                        ?: "Episodio ${episode.episodeNumber ?: 0}",
                    released = DoramasflixLogic.normalizeAirDate(episode.airDate),
                    poster = posterUrl(
                        DoramasflixLogic.episodeArtwork(
                            stillPath = episode.stillPath,
                            backdrop = episode.backdrop,
                            stillImage = episode.stillImage,
                            seriesBackdropPath = episode.serieBackdropPath,
                        )
                    ),
                    overview = episode.overview?.takeIf { it.isNotBlank() },
                )
            }
    }

    private suspend fun resolveMovieBackendId(slug: String): String {
        movieBackendIds[slug]?.let { return it }
        return detailMovie(slug).id
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
            val backendId = resolveMovieBackendId(slug)
            playbackRequest(
                operationName = "MovieLinks",
                variables = JSONObject().put("movie_id", backendId),
                query = """
                    query MovieLinks(${'$'}movie_id: ID!) {
                      getMovieLinks(id: ${'$'}movie_id, app: "$playbackApp") {
                        links_online {
                          server
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
                          server
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

    private fun hostFallbackServerName(link: String): String {
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
            "primeload.co" -> "Primeload"
            else -> host
        }
    }

    private fun isPrimeload(
        link: String,
        serverName: String?,
    ): Boolean {
        if (serverName.equals("Primeload", ignoreCase = true)) return true

        val host = runCatching { URL(link).host.lowercase(Locale.ROOT) }
            .getOrNull()
            ?: return false
        return host == "primeload.co" || host.endsWith(".primeload.co")
    }

    override suspend fun getServers(
        id: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        val playbackLinks = getPlaybackLinks(id, videoType)
        if (playbackLinks.isEmpty()) {
            throw Exception("Doramasflix currently has no playback sources for this title.")
        }

        val registry = getServerNamesByCode()
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

                val registryName = onlineLink.server
                    ?.let(registry::get)
                val serverName = DoramasflixLogic.normalizeServerName(registryName)
                    ?: hostFallbackServerName(decodedLink)

                if (isPrimeload(decodedLink, serverName)) {
                    primeloadCount++
                    return@mapNotNull null
                }

                val languageLabel = onlineLink.lang
                    ?.let(languages::get)
                    .orEmpty()

                Video.Server(
                    id = decodedLink,
                    name = "$serverName $languageLabel".trim(),
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
            "doramas" -> getTvShows(page)
            "peliculas" -> getMovies(page)
            "variedades" -> getDoramaPage(page, isTvShow = true)
            else -> throw Exception("Unknown Doramasflix category: $id")
        }

        return Genre(
            id = id,
            name = id.replaceFirstChar { it.uppercase() },
            shows = shows,
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw Exception("Doramasflix people lookup is not implemented.")
}
