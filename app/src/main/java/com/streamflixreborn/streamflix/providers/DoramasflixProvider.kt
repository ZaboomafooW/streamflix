package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
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
import com.streamflixreborn.streamflix.models.doramasflix.LanguageMetadata
import com.streamflixreborn.streamflix.models.doramasflix.OnlineLink
import com.streamflixreborn.streamflix.models.doramasflix.Season as DoramasflixSeason
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.HttpException
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

    private const val apiUrl = "https://userapi.cloudfleir.xyz/"
    private const val playbackApp = "com.asiapp.doramasgo"
    private const val featuredDoramaLimit = 6
    private const val featuredMovieLimit = 1
    private const val recommendationLimit = 12
    private const val catalogPageSize = 20
    private const val searchPageSize = 20
    private const val episodePageSize = 50
    private const val tmdbCacheTtlMillis = 10L * 60L * 1000L
    private const val userAgent =
        "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36"

    private val movieBackendIds = ConcurrentHashMap<String, String>()
    private val doramaBackendIds = ConcurrentHashMap<String, String>()
    private val doramaTmdbIds = ConcurrentHashMap<String, String>()
    private val episodeBackendIds = ConcurrentHashMap<String, String>()

    private data class TimedCache<T>(
        val value: T,
        val loadedAt: Long,
    )

    private val tmdbMovieCache = ConcurrentHashMap<String, TimedCache<Movie>>()
    private val tmdbDoramaCache = ConcurrentHashMap<String, TimedCache<TvShow>>()
    private val tmdbSeasonCache = ConcurrentHashMap<String, TimedCache<List<Episode>>>()
    private val tmdbPeopleCache = ConcurrentHashMap<String, TimedCache<People>>()

    @Volatile
    private var serverNamesByCode: Map<String, String>? = null

    private val client = OkHttpClient.Builder()
        .cache(Cache(File("cacheDir", "okhttpcache"), 10L * 1024 * 1024))
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    private val pageMetadata = DoramasflixPageMetadata(baseUrl, client)

    private val service = Retrofit.Builder()
        .baseUrl(apiUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private interface DoramasflixService {
        @POST("graphql")
        @Headers(
            "Accept: application/json, text/plain, */*",
            "Content-Type: application/json",
        )
        suspend fun getApiResponse(
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

    private fun requestContext(operationName: String): String = when (operationName) {
        "SearchFullDoramas" -> "Doramasflix Dorama search"
        "SearchFullMovies" -> "Doramasflix movie search"
        "DoramasCarrousel" -> "Doramasflix Home featured Doramas"
        "MoviesCarrousel" -> "Doramasflix Home featured movies"
        "DetailMovieSlug" -> "Doramasflix movie details"
        "DetailDoramaSlug" -> "Doramasflix series details"
        "similarsMovies" -> "Doramasflix movie recommendations"
        "SimilarsDoramas" -> "Doramasflix series recommendations"
        "ListSeasons" -> "Doramasflix seasons"
        "EpisodesPagination" -> "Doramasflix episodes"
        "ListServers" -> "Doramasflix server list"
        "PaginationMovie" -> "Doramasflix movies catalog"
        "PaginationDorama" -> "Doramasflix Doramas catalog"
        "MoviePlaybackContext" -> "Doramasflix movie playback sources"
        "EpisodePlaybackContext" -> "Doramasflix episode playback sources"
        else -> "Doramasflix $operationName"
    }

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

            throw Exception("$context failed: $message")
        }

        return data ?: throw Exception("$context returned no data.")
    }

    private fun httpFailure(
        context: String,
        error: HttpException,
    ): Exception {
        val detail = runCatching {
            DoramasflixLogic.graphQlErrorMessage(error.response()?.errorBody()?.string())
        }.getOrNull()

        val message = buildString {
            append(context)
            append(" failed: HTTP ")
            append(error.code())
            detail?.let {
                append(" — ")
                append(it)
            }
        }
        return Exception(message, error)
    }

    private suspend fun apiRequest(
        operationName: String,
        variables: JSONObject,
        query: String,
    ): Data {
        val context = requestContext(operationName)
        return try {
            service.getApiResponse(
                origin = baseUrl,
                referer = "$baseUrl/",
                userAgent = userAgent,
                body = requestBody(operationName, variables, query),
            ).requireData(context)
        } catch (error: HttpException) {
            throw httpFailure(context, error)
        }
    }

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

    private fun contentPoster(content: Content): String? =
        sequenceOf(content.posterPath, content.poster)
            .mapNotNull(::posterUrl)
            .mapNotNull(DoramasflixLogic::meaningfulImage)
            .firstOrNull()

    private fun contentBackdrop(content: Content): String? =
        sequenceOf(
            content.backdropPath,
            content.backdrop,
            content.images?.backdrops?.firstOrNull(),
        ).mapNotNull(::backdropUrl)
            .mapNotNull(DoramasflixLogic::meaningfulImage)
            .firstOrNull()

    private fun normalizePath(id: String): String = id
        .removePrefix("$baseUrl/")
        .removePrefix("/")
        .substringBefore('?')

    private fun slugFromId(id: String): String = normalizePath(id).substringAfterLast('/')

    private fun movieId(slug: String) = "peliculas-online/$slug"
    private fun doramaId(slug: String) = "doramas-online/$slug"

    private fun apiTitleFor(content: Content): String? {
        val slug = contentSlug(content)
        val primary = sequenceOf(content.name, content.originalName, content.nameEs)
            .mapNotNull { value -> DoramasflixLogic.meaningfulTitle(value, slug) }
            .firstOrNull()
            ?: return null
        val alternate = DoramasflixLogic.meaningfulTitle(content.nameEs, slug)
            ?.takeIf { !it.equals(primary, ignoreCase = true) }
        return alternate?.let { "$primary ($it)" } ?: primary
    }

    private fun titleFor(content: Content): String =
        apiTitleFor(content)
            ?: content.slug
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.replace('-', ' ')
            ?: "Doramasflix"

    private fun contentSlug(content: Content): String? =
        content.slug?.trim()?.takeIf { it.isNotEmpty() }

    private fun contentBackendId(content: Content): String? =
        content.id?.trim()?.takeIf { it.isNotEmpty() }

    private fun numericTmdbId(content: Content): Int? =
        content.tmdbId?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun tmdbPersonId(value: String?): Int? = value
        ?.trim()
        ?.substringAfterLast('/')
        ?.substringBefore('-')
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    private fun genresFor(content: Content): List<Genre> =
        content.genres.orEmpty().mapNotNull { tag ->
            val genreName = tag.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val genreId = tag.slug?.trim()?.takeIf { it.isNotEmpty() } ?: genreName
            Genre(id = genreId, name = genreName)
        }

    private fun castFor(content: Content): List<People> =
        content.cast.orEmpty().mapNotNull { member ->
            val id = member.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = member.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            People(
                id = id,
                name = name,
                image = DoramasflixLogic.meaningfulImage(imageUrl(member.profilePath, "w185")),
            )
        }.distinctBy { it.id }

    private fun rebindTmdbCast(
        content: Content,
        externalCast: List<People>,
    ): List<People> {
        if (externalCast.isEmpty()) return emptyList()
        val providerCast = content.cast.orEmpty()

        val providerIdsByTmdbId = providerCast.mapNotNull { member ->
            val slug = member.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val personId = member.ref?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: tmdbPersonId(slug)
                ?: return@mapNotNull null
            personId.toString() to slug
        }.toMap()

        val providerIdsByName = providerCast
            .mapNotNull { member ->
                val slug = member.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = member.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                name.lowercase(Locale.ROOT) to slug
            }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (name, slugs) ->
                slugs.distinct().singleOrNull()?.let { slug -> name to slug }
            }
            .toMap()

        return externalCast.map { person ->
            val providerId = providerIdsByTmdbId[person.id]
                ?: providerIdsByName[person.name.trim().lowercase(Locale.ROOT)]
            providerId?.let { person.copy(id = it) } ?: person
        }.distinctBy { it.id }
    }

    private fun cacheMovie(content: Content) {
        val slug = contentSlug(content) ?: return
        val backendId = contentBackendId(content) ?: return
        movieBackendIds[slug] = backendId
    }

    private fun cacheDorama(content: Content) {
        val slug = contentSlug(content) ?: return
        contentBackendId(content)?.let { backendId ->
            doramaBackendIds[slug] = backendId
        }
        numericTmdbId(content)?.let { tmdbId ->
            doramaTmdbIds[slug] = tmdbId.toString()
        }
    }

    private fun episodeCacheKey(showSlug: String, episodeSlug: String) = "$showSlug|$episodeSlug"

    private fun cacheEpisodes(
        showSlug: String,
        episodes: List<DoramasflixEpisode>,
    ) {
        episodes.forEach { episode ->
            val slug = episode.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val backendId = episode.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            episodeBackendIds[episodeCacheKey(showSlug, slug)] = backendId
        }
    }

    private fun listRating(content: Content): Double? =
        DoramasflixLogic.resolveApiRating(content.rating, content.ratingCount).rating

    private fun <T> cachedValue(
        cache: ConcurrentHashMap<String, TimedCache<T>>,
        key: String,
    ): T? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.loadedAt <= tmdbCacheTtlMillis) {
            return entry.value
        }
        cache.remove(key, entry)
        return null
    }

    private fun <T> cacheValue(
        cache: ConcurrentHashMap<String, TimedCache<T>>,
        key: String,
        value: T,
    ): T {
        cache[key] = TimedCache(value, System.currentTimeMillis())
        return value
    }

    private fun tmdbKey(id: Int): String = "$id|${DoramasflixTmdbMetadata.language}"

    private suspend fun tmdbMovie(content: Content): Movie? {
        if (!UserPreferences.enableTmdb) return null
        val tmdbId = numericTmdbId(content) ?: return null
        val key = tmdbKey(tmdbId)
        cachedValue(tmdbMovieCache, key)?.let { return it }
        val external = DoramasflixTmdbMetadata.movie(tmdbId) ?: return null
        return cacheValue(tmdbMovieCache, key, external)
    }

    private suspend fun tmdbDorama(content: Content): TvShow? {
        if (!UserPreferences.enableTmdb) return null
        val tmdbId = numericTmdbId(content) ?: return null
        val key = tmdbKey(tmdbId)
        cachedValue(tmdbDoramaCache, key)?.let { return it }
        val external = DoramasflixTmdbMetadata.tvShow(tmdbId) ?: return null
        return cacheValue(tmdbDoramaCache, key, external)
    }

    private suspend fun tmdbPeople(id: Int): People? {
        if (!UserPreferences.enableTmdb) return null
        val key = tmdbKey(id)
        cachedValue(tmdbPeopleCache, key)?.let { return it }
        val external = DoramasflixTmdbMetadata.person(id) ?: return null
        return cacheValue(tmdbPeopleCache, key, external)
    }

    private fun doramasflixMovie(
        content: Content,
        slug: String,
    ): Movie = Movie(
        id = movieId(slug),
        title = titleFor(content),
        overview = DoramasflixLogic.firstNonBlank(content.overview),
        released = DoramasflixLogic.normalizeDate(content.releaseDate),
        runtime = DoramasflixLogic.meaningfulRuntime(content.runtime),
        trailer = DoramasflixLogic.normalizeTrailer(content.trailer),
        rating = listRating(content),
        poster = contentPoster(content),
        banner = contentBackdrop(content),
        genres = genresFor(content),
        cast = castFor(content),
    )

    private fun doramasflixDorama(
        content: Content,
        slug: String,
    ): TvShow = TvShow(
        id = doramaId(slug),
        title = titleFor(content),
        overview = DoramasflixLogic.firstNonBlank(content.overview),
        released = DoramasflixLogic.normalizeDate(content.firstAirDate),
        runtime = DoramasflixLogic.meaningfulRuntime(content.episodeTime),
        trailer = DoramasflixLogic.normalizeTrailer(content.trailer),
        rating = listRating(content),
        poster = contentPoster(content),
        banner = contentBackdrop(content),
        genres = genresFor(content),
        cast = castFor(content),
    )

    private suspend fun resolveMovieMetadata(
        content: Content,
        slug: String,
    ): Movie {
        val provider = doramasflixMovie(content, slug)
        val external = tmdbMovie(content) ?: return provider
        val externalCast = rebindTmdbCast(content, external.cast)
        return provider.copy(
            title = external.title.trim().takeIf { it.isNotEmpty() } ?: provider.title,
            overview = DoramasflixLogic.firstNonBlank(external.overview, provider.overview),
            released = external.released?.format("yyyy-MM-dd") ?: provider.released?.format("yyyy-MM-dd"),
            runtime = DoramasflixLogic.meaningfulRuntime(external.runtime) ?: provider.runtime,
            trailer = DoramasflixLogic.normalizeTrailer(external.trailer) ?: provider.trailer,
            rating = external.rating?.takeIf { it > 0.0 }?.div(2.0) ?: provider.rating,
            poster = DoramasflixLogic.meaningfulImage(external.poster) ?: provider.poster,
            banner = DoramasflixLogic.meaningfulImage(external.banner) ?: provider.banner,
            imdbId = external.imdbId,
            genres = external.genres.takeIf { it.isNotEmpty() } ?: provider.genres,
            cast = externalCast.takeIf { it.isNotEmpty() } ?: provider.cast,
        )
    }

    private suspend fun resolveDoramaMetadata(
        content: Content,
        slug: String,
    ): TvShow {
        val provider = doramasflixDorama(content, slug)
        val external = tmdbDorama(content) ?: return provider
        val externalCast = rebindTmdbCast(content, external.cast)
        return provider.copy(
            title = external.title.trim().takeIf { it.isNotEmpty() } ?: provider.title,
            overview = DoramasflixLogic.firstNonBlank(external.overview, provider.overview),
            released = external.released?.format("yyyy-MM-dd") ?: provider.released?.format("yyyy-MM-dd"),
            runtime = DoramasflixLogic.meaningfulRuntime(external.runtime) ?: provider.runtime,
            trailer = DoramasflixLogic.normalizeTrailer(external.trailer) ?: provider.trailer,
            rating = external.rating?.takeIf { it > 0.0 }?.div(2.0) ?: provider.rating,
            poster = DoramasflixLogic.meaningfulImage(external.poster) ?: provider.poster,
            banner = DoramasflixLogic.meaningfulImage(external.banner) ?: provider.banner,
            imdbId = external.imdbId,
            seasons = external.seasons,
            genres = external.genres.takeIf { it.isNotEmpty() } ?: provider.genres,
            cast = externalCast.takeIf { it.isNotEmpty() } ?: provider.cast,
        )
    }

    private fun movieListItem(content: Content): Movie? {
        val slug = contentSlug(content) ?: return null
        return Movie(
            id = movieId(slug),
            title = titleFor(content),
            released = DoramasflixLogic.normalizeDate(content.releaseDate),
            rating = listRating(content),
            poster = contentPoster(content),
            banner = contentBackdrop(content),
        )
    }

    private fun doramaListItem(content: Content): TvShow? {
        val slug = contentSlug(content) ?: return null
        return TvShow(
            id = doramaId(slug),
            title = titleFor(content),
            released = DoramasflixLogic.normalizeDate(content.firstAirDate),
            rating = listRating(content),
            poster = contentPoster(content),
            banner = contentBackdrop(content),
        )
    }

    private suspend fun optionalMovieDetail(slug: String): Content? = try {
        detailMovie(slug)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    private suspend fun optionalDoramaDetail(slug: String): Content? = try {
        detailDorama(slug)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    private suspend fun searchDoramas(input: String, page: Int): List<Content> {
        val items = apiRequest(
            operationName = "SearchFullDoramas",
            variables = JSONObject()
                .put("input", input)
                .put("filter", JSONObject())
                .put("page", page)
                .put("perPage", searchPageSize)
                .put("fuzzy", true),
            query = """
                query SearchFullDoramas(
                  ${'$'}input: String!
                  ${'$'}filter: FilterDoramasInput
                  ${'$'}page: Int
                  ${'$'}perPage: Int
                  ${'$'}fuzzy: Boolean
                ) {
                  searchFullDoramas(
                    input: ${'$'}input
                    filter: ${'$'}filter
                    page: ${'$'}page
                    perPage: ${'$'}perPage
                    fuzzy: ${'$'}fuzzy
                  ) {
                    count
                    pageInfo {
                      currentPage
                      perPage
                      pageCount
                      itemCount
                      hasNextPage
                      hasPreviousPage
                    }
                    items {
                      _id
                      slug
                      name
                      name_es
                      original_name
                      poster_path
                      first_air_date
                      country
                      isTVShow
                      isFinish
                      premiere
                      number_of_episodes
                      number_of_episodes_online
                      rating
                      rating_count
                      rating_total
                      tmdb_id
                      seasons {
                        _id
                        emision
                        uploading
                        pause
                        commingSoon
                        status
                        status_source
                      }
                    }
                  }
                }
            """.trimIndent(),
        ).searchFullDoramas?.items.orEmpty()

        items.forEach(::cacheDorama)
        return items
    }

    private suspend fun searchMovies(input: String, page: Int): List<Content> {
        val items = apiRequest(
            operationName = "SearchFullMovies",
            variables = JSONObject()
                .put("input", input)
                .put("filter", JSONObject())
                .put("page", page)
                .put("perPage", searchPageSize)
                .put("fuzzy", true),
            query = """
                query SearchFullMovies(
                  ${'$'}input: String!
                  ${'$'}filter: FilterMoviesInput
                  ${'$'}page: Int
                  ${'$'}perPage: Int
                  ${'$'}fuzzy: Boolean
                ) {
                  searchFullMovies(
                    input: ${'$'}input
                    filter: ${'$'}filter
                    page: ${'$'}page
                    perPage: ${'$'}perPage
                    fuzzy: ${'$'}fuzzy
                  ) {
                    count
                    pageInfo {
                      currentPage
                      perPage
                      pageCount
                      itemCount
                      hasNextPage
                      hasPreviousPage
                    }
                    items {
                      _id
                      slug
                      name
                      name_es
                      original_name
                      poster_path
                      release_date
                      country
                      commingSoon
                      status
                      status_source
                      status_changed_at
                      rating
                      rating_count
                      rating_total
                      tmdb_id
                    }
                  }
                }
            """.trimIndent(),
        ).searchFullMovies?.items.orEmpty()

        items.forEach(::cacheMovie)
        return items
    }

    private suspend fun getFeaturedDoramas(): List<Content> = try {
        val items = apiRequest(
            operationName = "DoramasCarrousel",
            variables = JSONObject().put("limit", featuredDoramaLimit),
            query = """
                query DoramasCarrousel(${'$'}limit: Int) {
                  carrouselDoramas(limit: ${'$'}limit) {
                    _id
                    name
                    name_es
                    slug
                    poster_path
                    poster
                    backdrop_path
                    backdrop
                  }
                }
            """.trimIndent(),
        ).carrouselDoramas.orEmpty()
        items.forEach(::cacheDorama)
        items
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w("DoramasflixProvider", "Featured Doramas are unavailable", error)
        emptyList()
    }

    private suspend fun getFeaturedMovies(): List<Content> = try {
        val items = apiRequest(
            operationName = "MoviesCarrousel",
            variables = JSONObject().put("limit", featuredMovieLimit),
            query = """
                query MoviesCarrousel(${'$'}limit: Int) {
                  carrouselMovies(limit: ${'$'}limit) {
                    _id
                    name
                    name_es
                    slug
                    poster_path
                    poster
                    backdrop_path
                    backdrop
                  }
                }
            """.trimIndent(),
        ).carrouselMovies.orEmpty()
        items.forEach(::cacheMovie)
        items
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w("DoramasflixProvider", "Featured movies are unavailable", error)
        emptyList()
    }

    private suspend fun featuredDorama(content: Content): Show? {
        val slug = contentSlug(content) ?: return null
        val detailed = optionalDoramaDetail(slug) ?: content
        val resolved = doramasflixDorama(detailed, slug)
        val banner = contentBackdrop(content) ?: resolved.banner ?: return null
        return resolved.copy(
            id = doramaId(slug),
            title = apiTitleFor(detailed) ?: resolved.title,
            poster = contentPoster(content) ?: resolved.poster,
            banner = banner,
        )
    }

    private suspend fun featuredMovie(content: Content): Show? {
        val slug = contentSlug(content) ?: return null
        val detailed = optionalMovieDetail(slug) ?: content
        val resolved = doramasflixMovie(detailed, slug)
        val banner = contentBackdrop(content) ?: resolved.banner ?: return null
        return resolved.copy(
            id = movieId(slug),
            title = apiTitleFor(detailed) ?: resolved.title,
            poster = contentPoster(content) ?: resolved.poster,
            banner = banner,
        )
    }

    private suspend fun detailMovie(slug: String): Content {
        val content = apiRequest(
            operationName = "DetailMovieSlug",
            variables = JSONObject().put("slug", slug),
            query = """
                query DetailMovieSlug(${'$'}slug: String!) {
                  detailMovie(filter: {slug: ${'$'}slug}) {
                    _id
                    slug
                    name
                    name_es
                    original_name
                    tmdb_id
                    overview
                    trailer
                    release
                    status
                    status_source
                    status_changed_at
                    release_date
                    poster_path
                    poster
                    backdrop_path
                    backdrop
                    runtime
                    cast {
                      name
                      character
                      profile_path
                      ref
                      slug
                    }
                    images {
                      backdrops
                    }
                    country
                    labels {
                      name
                      slug
                      ref
                    }
                    genres {
                      name
                      slug
                      ref
                    }
                    networks {
                      name
                      slug
                      ref
                    }
                    subbers_ref {
                      nickname
                    }
                    langs {
                      _id
                      name
                      slug
                      code
                      code_flix
                      flag
                      images {
                        image_tmdb
                        image_url
                      }
                    }
                    subtitles_available
                    rating
                    rating_count
                    rating_total
                    views_count
                    favs_count
                    age_limit
                  }
                }
            """.trimIndent(),
        ).detailMovie ?: throw Exception("Doramasflix could not find movie '$slug'.")

        cacheMovie(content)
        return content
    }

    private suspend fun detailDorama(slug: String): Content {
        val content = apiRequest(
            operationName = "DetailDoramaSlug",
            variables = JSONObject().put("slug", slug),
            query = """
                query DetailDoramaSlug(${'$'}slug: String!) {
                  detailDorama(filter: {slug: ${'$'}slug}) {
                    _id
                    name
                    slug
                    name_es
                    original_name
                    overview
                    trailer
                    isTVShow
                    isFinish
                    number_of_episodes_online
                    last_air_date
                    first_air_date
                    poster_path
                    poster
                    backdrop_path
                    backdrop
                    tmdb_id
                    number_of_seasons
                    number_of_episodes
                    episode_time
                    cast {
                      name
                      slug
                      character
                      profile_path
                      ref
                    }
                    images {
                      backdrops
                    }
                    premiere
                    country
                    labels {
                      name
                      slug
                      ref
                    }
                    genres {
                      name
                      slug
                      ref
                    }
                    networks {
                      name
                      slug
                      ref
                    }
                    subbers_ref {
                      nickname
                    }
                    langs {
                      _id
                      name
                      slug
                      flag
                      images {
                        image_tmdb
                        image_url
                      }
                    }
                    subtitles_available
                    rating
                    rating_count
                    rating_total
                    views_count
                    favs_count
                    age_limit
                    seasons {
                      ref
                      slug
                      season_number
                      emision
                      emision_days
                      notShowDate
                      number_of_episodes
                      pause
                      uploading
                      commingSoon
                      status
                      status_source
                    }
                  }
                }
            """.trimIndent(),
        ).detailDorama ?: throw Exception("Doramasflix could not find dorama '$slug'.")

        cacheDorama(content)
        return content
    }

    private suspend fun getSimilarMovies(movieBackendId: String): List<Movie> = try {
        val items = apiRequest(
            operationName = "similarsMovies",
            variables = JSONObject()
                .put("limit", recommendationLimit)
                .put("movie_id", movieBackendId),
            query = """
                query similarsMovies(${'$'}limit: Int, ${'$'}movie_id: String!) {
                  similarsMovies(limit: ${'$'}limit, movie_id: ${'$'}movie_id) {
                    _id
                    slug
                    name
                    name_es
                    original_name
                    poster_path
                    poster
                  }
                }
            """.trimIndent(),
        ).similarsMovies.orEmpty()

        items.forEach(::cacheMovie)
        items.mapNotNull { content ->
            val slug = contentSlug(content) ?: return@mapNotNull null
            Movie(
                id = movieId(slug),
                title = titleFor(content),
                poster = contentPoster(content),
            )
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        emptyList()
    }

    private suspend fun getSimilarDoramas(doramaBackendId: String): List<TvShow> = try {
        val items = apiRequest(
            operationName = "SimilarsDoramas",
            variables = JSONObject()
                .put("limit", recommendationLimit)
                .put("dorama_id", doramaBackendId),
            query = """
                query SimilarsDoramas(${'$'}limit: Int, ${'$'}dorama_id: String) {
                  similarsDoramas(limit: ${'$'}limit, dorama_id: ${'$'}dorama_id) {
                    _id
                    slug
                    name
                    name_es
                    original_name
                    poster_path
                    poster
                  }
                }
            """.trimIndent(),
        ).similarsDoramas.orEmpty()

        items.forEach(::cacheDorama)
        items.mapNotNull { content ->
            val slug = contentSlug(content) ?: return@mapNotNull null
            TvShow(
                id = doramaId(slug),
                title = titleFor(content),
                poster = contentPoster(content),
            )
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        emptyList()
    }

    private suspend fun getSeasons(slug: String): List<DoramasflixSeason> =
        apiRequest(
            operationName = "ListSeasons",
            variables = JSONObject().put("slug", slug),
            query = """
                query ListSeasons(${'$'}slug: String!) {
                  listSeasons(sort: NUMBER_ASC, filter: {serie_slug: ${'$'}slug}) {
                    _id
                    slug
                    name
                    name_es
                    poster
                    poster_path
                    serie_id
                    season_number
                    serie_backdrop_path
                    backdrop
                    number_of_episodes
                    number_of_episodes_online
                    emision
                    uploading
                    pause
                    commingSoon
                    status
                    status_source
                    status_changed_at
                    langs {
                      _id
                      name
                      slug
                      code
                      code_flix
                      flag
                    }
                    subtitles_available
                  }
                }
            """.trimIndent(),
        ).listSeasons.orEmpty()
            .filter { it.seasonNumber != null }
            .distinctBy { it.seasonNumber }

    private suspend fun resolveDoramaBackendId(slug: String): String {
        doramaBackendIds[slug]?.let { return it }
        return contentBackendId(detailDorama(slug))
            ?: throw Exception("Doramasflix could not resolve the series ID.")
    }

    private suspend fun resolveDoramaTmdbId(slug: String): String? {
        doramaTmdbIds[slug]?.let { return it }
        val content = detailDorama(slug)
        return numericTmdbId(content)?.toString()
    }

    private suspend fun getTmdbEpisodeMetadata(
        slug: String,
        seasonNumber: Int,
    ): Map<Int, Episode> {
        if (!UserPreferences.enableTmdb) return emptyMap()
        val tmdbId = resolveDoramaTmdbId(slug)?.toIntOrNull() ?: return emptyMap()
        val key = "$tmdbId|$seasonNumber|${DoramasflixTmdbMetadata.language}"
        cachedValue(tmdbSeasonCache, key)?.let { cached ->
            return cached.associateBy { it.number }
        }
        val episodes = DoramasflixTmdbMetadata.episodes(tmdbId, seasonNumber)
        if (episodes.isNotEmpty()) {
            cacheValue(tmdbSeasonCache, key, episodes)
        }
        return episodes.associateBy { it.number }
    }

    private suspend fun getEpisodes(
        slug: String,
        seasonNumber: Int,
    ): List<DoramasflixEpisode> {
        val backendId = resolveDoramaBackendId(slug)
        val episodes = mutableListOf<DoramasflixEpisode>()
        var page = 1

        while (true) {
            val response = apiRequest(
                operationName = "EpisodesPagination",
                variables = JSONObject()
                    .put("page", page)
                    .put("serie_id", backendId)
                    .put("season_number", seasonNumber)
                    .put("limit", episodePageSize)
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
                          _id
                          slug
                          name
                          name_es
                          serie_backdrop_path
                          backdrop
                          still_path
                          serie_id
                          still_image
                          episode_number
                          season_number
                          date_string
                          air_date
                          overview
                          count_links
                        }
                        count
                        pageInfo {
                          currentPage
                          perPage
                          pageCount
                          itemCount
                          hasNextPage
                          hasPreviousPage
                        }
                      }
                    }
                """.trimIndent(),
            ).paginationEpisode ?: break

            episodes += response.items
            if (response.pageInfo?.hasNextPage != true) break
            page++
        }

        cacheEpisodes(slug, episodes)
        return episodes
    }

    private suspend fun getServerNamesByCode(): Map<String, String> {
        serverNamesByCode?.let { return it }

        val discovered = try {
            apiRequest(
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
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyMap()
        }

        if (discovered.isNotEmpty()) {
            serverNamesByCode = discovered
        }
        return discovered
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val featuredDoramasDeferred = async { getFeaturedDoramas() }
        val featuredMoviesDeferred = async { getFeaturedMovies() }
        val doramas = async { getTvShows(1) }
        val movies = async { getMovies(1) }

        val featuredDoramas = featuredDoramasDeferred.await().mapNotNull { featuredDorama(it) }
        val featuredMovies = featuredMoviesDeferred.await().mapNotNull { featuredMovie(it) }
        val featured = DoramasflixLogic.mixAlternating(
            first = featuredDoramas,
            second = featuredMovies,
        )

        buildList {
            featured.takeIf { it.isNotEmpty() }?.let {
                add(Category(name = Category.FEATURED, list = it))
            }
            doramas.await().takeIf { it.isNotEmpty() }?.let {
                add(Category(name = "Doramas Populares", list = it))
            }
            movies.await().takeIf { it.isNotEmpty() }?.let {
                add(Category(name = "Películas Populares", list = it))
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> = coroutineScope {
        if (query.isBlank()) {
            return@coroutineScope listOf(
                Genre("doramas", "Doramas"),
                Genre("peliculas", "Películas"),
                Genre("variedades", "Variedades"),
            )
        }

        val requestedPage = page.coerceAtLeast(1)
        val doramas = async { searchDoramas(query, requestedPage) }
        val movies = async { searchMovies(query, requestedPage) }
        val results = mutableListOf<AppAdapter.Item>()

        doramas.await().forEach { show ->
            doramaListItem(show)?.let(results::add)
        }
        movies.await().forEach { show ->
            movieListItem(show)?.let(results::add)
        }
        results
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val items = apiRequest(
            operationName = "PaginationMovie",
            variables = JSONObject()
                .put("page", page)
                .put("limit", catalogPageSize)
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
                      slug
                      name
                      name_es
                      original_name
                      poster_path
                      poster
                      backdrop_path
                      backdrop
                      release_date
                      country
                      commingSoon
                      status
                      status_source
                      status_changed_at
                      rating
                      rating_count
                      rating_total
                      tmdb_id
                    }
                    count
                    pageInfo {
                      currentPage
                      perPage
                      pageCount
                      itemCount
                      hasNextPage
                      hasPreviousPage
                    }
                  }
                }
            """.trimIndent(),
        ).paginationMovie?.items.orEmpty()

        items.forEach(::cacheMovie)
        val movies = mutableListOf<Movie>()
        items.forEach { show ->
            movieListItem(show)?.let(movies::add)
        }
        return movies
    }

    override suspend fun getTvShows(page: Int): List<TvShow> =
        getDoramaPage(page, isTvShow = false)

    private suspend fun getDoramaPage(
        page: Int,
        isTvShow: Boolean,
    ): List<TvShow> {
        val items = apiRequest(
            operationName = "PaginationDorama",
            variables = JSONObject()
                .put("page", page)
                .put("limit", catalogPageSize)
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
                      slug
                      name
                      name_es
                      original_name
                      poster_path
                      poster
                      backdrop_path
                      backdrop
                      first_air_date
                      country
                      isTVShow
                      isFinish
                      premiere
                      number_of_episodes
                      number_of_episodes_online
                      rating
                      rating_count
                      rating_total
                      tmdb_id
                    }
                    count
                    pageInfo {
                      currentPage
                      perPage
                      pageCount
                      itemCount
                      hasNextPage
                      hasPreviousPage
                    }
                  }
                }
            """.trimIndent(),
        ).paginationDorama?.items.orEmpty()

        items.forEach(::cacheDorama)
        val shows = mutableListOf<TvShow>()
        items.forEach { show ->
            doramaListItem(show)?.let(shows::add)
        }
        return shows
    }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val slug = slugFromId(id)
        val content = detailMovie(slug)
        val backendId = contentBackendId(content)
            ?: throw Exception("Doramasflix could not resolve movie '$slug'.")
        val recommendationsDeferred = async { getSimilarMovies(backendId) }
        val resolved = resolveMovieMetadata(content, slug)

        resolved.copy(recommendations = recommendationsDeferred.await())
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val slug = slugFromId(id)
        val detailDeferred = async { detailDorama(slug) }
        val seasonsDeferred = async { getSeasons(slug) }
        val content = detailDeferred.await()
        val backendId = contentBackendId(content)
            ?: throw Exception("Doramasflix could not resolve series '$slug'.")
        val recommendationsDeferred = async { getSimilarDoramas(backendId) }
        val resolved = resolveDoramaMetadata(content, slug)
        val seasonsData = seasonsDeferred.await()
        val tmdbSeasons = resolved.seasons.associateBy { it.number }

        resolved.copy(
            seasons = seasonsData.mapNotNull { season ->
                val seasonNumber = season.seasonNumber ?: return@mapNotNull null
                val tmdbSeason = tmdbSeasons[seasonNumber]
                Season(
                    id = "$slug/$seasonNumber",
                    number = seasonNumber,
                    title = DoramasflixLogic.firstNonBlank(
                        tmdbSeason?.title,
                        season.nameEs,
                        season.name,
                    ) ?: "Temporada $seasonNumber",
                    poster = DoramasflixLogic.meaningfulImage(tmdbSeason?.poster)
                        ?: sequenceOf(season.posterPath, season.poster)
                            .mapNotNull(::posterUrl)
                            .mapNotNull(DoramasflixLogic::meaningfulImage)
                            .firstOrNull(),
                )
            },
            recommendations = recommendationsDeferred.await(),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val slug = seasonId.substringBeforeLast('/')
        val seasonNumber = seasonId.substringAfterLast('/').toIntOrNull()
            ?: throw Exception("Invalid Doramasflix season ID: $seasonId")

        val external = getTmdbEpisodeMetadata(slug, seasonNumber)
        val episodes = getEpisodes(slug, seasonNumber)

        return episodes.mapNotNull { episode ->
            val episodeSlug = episode.slug?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val number = episode.episodeNumber ?: 0
            val tmdbEpisode = external[number]
            val providerArtwork = DoramasflixLogic.episodeArtwork(
                stillPath = episode.stillPath,
                backdrop = episode.backdrop,
                stillImage = episode.stillImage,
            )

            Episode(
                id = episodeSlug,
                number = number,
                title = DoramasflixLogic.firstNonBlank(
                    tmdbEpisode?.title,
                    episode.nameEs,
                    episode.name,
                ),
                released = tmdbEpisode?.released?.format("yyyy-MM-dd")
                    ?.let(DoramasflixLogic::normalizeDate)
                    ?: DoramasflixLogic.normalizeDate(episode.airDate)
                    ?: DoramasflixLogic.normalizeDate(episode.dateString),
                poster = DoramasflixLogic.meaningfulImage(tmdbEpisode?.poster)
                    ?: posterUrl(providerArtwork),
                overview = DoramasflixLogic.firstNonBlank(
                    tmdbEpisode?.overview,
                    episode.overview,
                ),
            )
        }
    }

    private suspend fun resolveMovieBackendId(slug: String): String {
        movieBackendIds[slug]?.let { return it }
        return contentBackendId(detailMovie(slug))
            ?: throw Exception("Doramasflix could not resolve the movie playback ID.")
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
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Doramasflix could not resolve the episode playback ID.")
    }

    private data class PlaybackContext(
        val links: List<OnlineLink>,
        val languages: List<LanguageMetadata>,
    )

    private suspend fun getPlaybackContext(
        id: String,
        videoType: Video.Type,
    ): PlaybackContext = when (videoType) {
        is Video.Type.Movie -> {
            val slug = slugFromId(id)
            val backendId = resolveMovieBackendId(slug)
            val data = apiRequest(
                operationName = "MoviePlaybackContext",
                variables = JSONObject()
                    .put("slug", slug)
                    .put("movie_id", backendId),
                query = """
                    query MoviePlaybackContext(${'$'}slug: String!, ${'$'}movie_id: ID!) {
                      detailMovie(filter: {slug: ${'$'}slug}) {
                        _id
                        slug
                        name
                        langs {
                          name
                          code
                          code_flix
                          flag
                        }
                      }
                      getMovieLinks(id: ${'$'}movie_id, app: "$playbackApp") {
                        links_online {
                          server
                          lang
                          link
                          page
                          is_recommended
                          subtitles {
                            language_code
                            type
                          }
                        }
                      }
                    }
                """.trimIndent(),
            )
            PlaybackContext(
                links = data.getMovieLinks?.linksOnline.orEmpty(),
                languages = data.detailMovie?.langs.orEmpty(),
            )
        }

        is Video.Type.Episode -> {
            val showSlug = slugFromId(videoType.tvShow.id)
            val backendId = resolveEpisodeBackendId(
                episodeSlug = id,
                showSlug = showSlug,
                seasonNumber = videoType.season.number,
            )
            val data = apiRequest(
                operationName = "EpisodePlaybackContext",
                variables = JSONObject()
                    .put("slug", id)
                    .put("episode_id", backendId),
                query = """
                    query EpisodePlaybackContext(${'$'}slug: String!, ${'$'}episode_id: ID!) {
                      detailEpisode(filter: {slug: ${'$'}slug}) {
                        _id
                        slug
                        name
                        langs {
                          name
                          code
                          code_flix
                          flag
                        }
                      }
                      getEpisodeLinks(id: ${'$'}episode_id, app: "$playbackApp") {
                        links_online {
                          server
                          lang
                          link
                          page
                          _id
                          is_recommended
                          subtitles {
                            language_code
                            type
                          }
                        }
                      }
                    }
                """.trimIndent(),
            )
            PlaybackContext(
                links = data.getEpisodeLinks?.linksOnline.orEmpty(),
                languages = data.detailEpisode?.langs.orEmpty(),
            )
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
            "ok.ru" -> "OK.ru"
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
        val playback = getPlaybackContext(id, videoType)
        if (playback.links.isEmpty()) {
            throw Exception("Doramasflix currently has no playback sources for this title.")
        }

        val registry = getServerNamesByCode()
        val languagesByCode = mutableMapOf<String, String>()
        for (metadata in playback.languages) {
            val name = metadata.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            listOf(metadata.codeFlix, metadata.code).forEach { rawCode ->
                rawCode
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { code -> languagesByCode[code] = name }
            }
        }
        var primeloadCount = 0
        var invalidCount = 0

        val servers = playback.links
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

                val registryName = onlineLink.server?.let(registry::get)
                val serverName = DoramasflixLogic.normalizeServerName(registryName)
                    ?: hostFallbackServerName(decodedLink)

                if (isPrimeload(decodedLink, serverName)) {
                    primeloadCount++
                    return@mapNotNull null
                }

                val rawLanguageCode = onlineLink.lang?.trim()?.takeIf { it.isNotEmpty() }
                val languageName = rawLanguageCode?.let(languagesByCode::get)
                val subtitles = onlineLink.subtitles.orEmpty().mapNotNull { subtitle ->
                    DoramasflixLogic.subtitleDescriptor(
                        languageCode = subtitle.languageCode,
                        type = subtitle.type,
                    )
                }

                Video.Server(
                    id = decodedLink,
                    name = DoramasflixLogic.playbackSourceName(
                        serverName = serverName,
                        languageName = languageName,
                        languageCode = rawLanguageCode,
                        subtitleDescriptors = subtitles,
                    ),
                )
            }
            .distinctBy { it.id }

        if (servers.isNotEmpty()) return servers

        if (primeloadCount == playback.links.size) {
            throw Exception(
                "Doramasflix currently offers this title only on Primeload, which StreamFlix does not support yet."
            )
        }

        if (invalidCount == playback.links.size) {
            throw Exception("Doramasflix returned invalid playback source URLs for this title.")
        }

        throw Exception("Doramasflix currently has no supported playback sources for this title.")
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int): Genre {
        val (categoryName, shows) = when (id) {
            "doramas" -> "Doramas" to getTvShows(page)
            "peliculas" -> "Películas" to getMovies(page)
            "variedades" -> "Variedades" to getDoramaPage(page, isTvShow = true)
            else -> throw Exception("Unknown Doramasflix category: $id")
        }

        return Genre(
            id = id,
            name = categoryName,
            shows = shows,
        )
    }

    private suspend fun optionalPeople(id: String): People? = try {
        pageMetadata.getPeople(id)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    override suspend fun getPeople(id: String, page: Int): People = coroutineScope {
        if (page > 1) return@coroutineScope People(id = id, name = "")

        val personId = tmdbPersonId(id)
        if (!UserPreferences.enableTmdb || personId == null) {
            return@coroutineScope pageMetadata.getPeople(id)
        }

        val tmdbDeferred = async { tmdbPeople(personId) }
        val providerDeferred = async { optionalPeople(id) }
        val external = tmdbDeferred.await()
        val provider = providerDeferred.await()

        if (external == null) {
            return@coroutineScope provider ?: People(id = id, name = "")
        }

        external.copy(
            id = id,
            name = external.name.trim().takeIf { it.isNotEmpty() } ?: provider?.name.orEmpty(),
            image = DoramasflixLogic.meaningfulImage(external.image) ?: provider?.image,
            biography = DoramasflixLogic.firstNonBlank(external.biography, provider?.biography),
            placeOfBirth = DoramasflixLogic.firstNonBlank(external.placeOfBirth, provider?.placeOfBirth),
            birthday = external.birthday?.format("yyyy-MM-dd") ?: provider?.birthday?.format("yyyy-MM-dd"),
            deathday = external.deathday?.format("yyyy-MM-dd") ?: provider?.deathday?.format("yyyy-MM-dd"),
            filmography = provider?.filmography.orEmpty(),
        )
    }
}
