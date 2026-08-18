package com.streamflixreborn.streamflix.providers

import android.util.Base64
import android.util.Log
import com.google.gson.JsonParseException
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
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
import com.streamflixreborn.streamflix.models.doramasflix.ContentPage
import com.streamflixreborn.streamflix.models.doramasflix.Data
import com.streamflixreborn.streamflix.models.doramasflix.Episode as DoramasflixEpisode
import com.streamflixreborn.streamflix.models.doramasflix.EpisodePage
import com.streamflixreborn.streamflix.models.doramasflix.LanguageMetadata
import com.streamflixreborn.streamflix.models.doramasflix.OnlineLink
import com.streamflixreborn.streamflix.models.doramasflix.Season as DoramasflixSeason
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TmdbUtils
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
import java.io.IOException
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DoramasflixProvider : Provider {

    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.in"
    override val language = "es"
    override val logo = "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.ic_doramasflix}"

    private const val apiUrl = "https://userapi.cloudfleir.xyz/"
    private const val playbackApp = "com.asiapp.doramasgo"
    private const val featuredDoramaLimit = 6
    private const val featuredMovieLimit = 1
    private const val recommendationLimit = 12
    private const val catalogPageSize = 20
    private const val searchPageSize = 20
    private const val episodePageSize = 50
    private const val userAgent =
        "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36"

    private val movieBackendIds = ConcurrentHashMap<String, String>()
    private val doramaBackendIds = ConcurrentHashMap<String, String>()
    private val doramaTmdbIds = ConcurrentHashMap<String, String>()
    private val movieLocalizedTitles = ConcurrentHashMap<String, String>()
    private val doramaLocalizedTitles = ConcurrentHashMap<String, String>()
    private val doramaDetails = ConcurrentHashMap<String, Content>()
    private val episodeBackendIds = ConcurrentHashMap<String, String>()
    private val movieCatalogPages = ConcurrentHashMap<Int, ContentPage>()
    private val doramaCatalogPages = ConcurrentHashMap<String, ContentPage>()
    private val searchPages = ConcurrentHashMap<String, SearchContentPage>()

    @Volatile
    private var serverNamesByCode: Map<String, String>? = null

    private val client = OkHttpClient.Builder()
        .cache(Cache(File("cacheDir", "okhttpcache"), 10L * 1024 * 1024))
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .dns(DnsResolver.doh)
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(apiUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private val genreBrowser by lazy {
        DoramasflixGenreBrowser(
            loadMoviePage = ::loadMovieCatalogPage,
            loadDoramaPage = ::loadDoramaCatalogPage,
            mapMovie = ::movieListItem,
            mapDorama = ::doramaListItem,
        )
    }

    private val peopleResolver by lazy {
        DoramasflixPeopleResolver(
            searchDoramas = ::searchDoramas,
            searchMovies = ::searchMovies,
            mapMovie = ::movieListItem,
            mapDorama = ::doramaListItem,
        )
    }

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
                .mapNotNull { DoramasflixLogic.nonBlank(it.message) }
                .distinct()
                .joinToString("; ")
                .ifBlank { "Unknown GraphQL error" }

            if (message.contains("Too Many Requests", ignoreCase = true)) {
                throw Exception("$context is temporarily rate limiting requests. Please try again shortly.")
            }
            throw Exception("$context failed: $message")
        }
        return data ?: throw DoramasflixUnavailableException(
            IllegalStateException("$context returned no GraphQL data")
        )
    }

    private fun httpFailure(context: String, error: HttpException): Exception {
        if (error.code() == 429) {
            return Exception(
                "$context is temporarily rate limiting requests. Please try again shortly.",
                error,
            )
        }
        if (DoramasflixLogic.isUnavailableHttpStatus(error.code())) {
            return DoramasflixUnavailableException(error)
        }

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
        } catch (error: IOException) {
            throw DoramasflixUnavailableException(error)
        } catch (error: JsonParseException) {
            throw DoramasflixUnavailableException(error)
        }
    }

    private fun <T> requireOperationPayload(context: String, payload: T?): T =
        payload ?: throw DoramasflixUnavailableException(
            IllegalStateException("$context returned no operation payload")
        )

    private fun imageUrl(path: String?, size: String): String? {
        val value = DoramasflixLogic.meaningfulImage(path) ?: return null
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
            .firstOrNull()

    private fun contentBackdrop(content: Content): String? = sequence {
        yield(content.backdropPath)
        yield(content.backdrop)
        yieldAll(content.images?.backdrops.orEmpty())
    }.mapNotNull(::backdropUrl).firstOrNull()

    private fun contentMarkers(content: Content): Set<DoramasflixContentPolicy.Marker> = buildSet {
        addAll(DoramasflixContentPolicy.analyzeOverview(content.overview).markers)
        addAll(
            DoramasflixContentPolicy.markersFromLabels(
                content.labels.orEmpty().map { it.name }
            )
        )
    }

    private fun contentAllowed(content: Content): Boolean = DoramasflixContentPolicy.allows(
        contentMarkers(content),
        DoramasflixContentPreferences.settings(),
    )

    private fun resolvedOverview(content: Content, externalOverview: String?): String? =
        DoramasflixContentPolicy.analyzeOverview(
            DoramasflixLogic.nonBlank(content.overview)
        ).cleanedOverview ?: DoramasflixLogic.nonBlank(externalOverview)

    private fun normalizePath(id: String): String = id
        .removePrefix("$baseUrl/")
        .removePrefix("/")
        .substringBefore('?')

    private fun slugFromId(id: String): String = normalizePath(id).substringAfterLast('/')
    private fun movieId(slug: String) = "peliculas-online/$slug"
    private fun doramaId(slug: String) = "doramas-online/$slug"

    private fun contentSlug(content: Content): String? = DoramasflixLogic.nonBlank(content.slug)
    private fun contentBackendId(content: Content): String? = DoramasflixLogic.nonBlank(content.id)
    private fun numericTmdbId(content: Content): Int? =
        DoramasflixLogic.nonBlank(content.tmdbId)?.toIntOrNull()?.takeIf { it > 0 }

    private fun apiTitleFor(content: Content): String? = DoramasflixLogic.displayTitle(
        nameEs = content.nameEs,
        name = content.name,
        originalName = content.originalName,
    )

    private fun genresFor(content: Content): List<Genre> =
        content.genres.orEmpty().mapNotNull { tag ->
            val genreName = DoramasflixLogic.nonBlank(tag.name) ?: return@mapNotNull null
            val genreId = DoramasflixLogic.nonBlank(tag.slug) ?: genreName
            Genre(id = genreId, name = genreName)
        }.distinctBy { it.id }

    private fun castFor(content: Content): List<People> =
        content.cast.orEmpty().mapNotNull { member ->
            val id = DoramasflixLogic.nonBlank(member.slug) ?: return@mapNotNull null
            val castName = DoramasflixLogic.nonBlank(member.name) ?: return@mapNotNull null
            People(
                id = id,
                name = castName,
                image = imageUrl(member.profilePath, "w185"),
            )
        }.distinctBy { it.id }

    private fun hasNonLatinCast(content: Content): Boolean =
        content.cast.orEmpty().any { member ->
            val castName = DoramasflixLogic.nonBlank(member.name) ?: return@any false
            !DoramasflixLogic.containsLatinLetter(castName)
        }

    private fun resolveCast(content: Content, external: List<People>): List<People> {
        val externalByTmdbId = external.mapNotNull { person ->
            person.id.toIntOrNull()?.let { id -> id to person }
        }.toMap()
        val resolved = castFor(content).map { member ->
            val tmdbId = DoramasflixPersonIdentity.tmdbId(member.id) ?: return@map member
            val externalMember = externalByTmdbId[tmdbId] ?: return@map member
            val localizedName = DoramasflixLogic.nonBlank(externalMember.name)
                ?.takeIf(DoramasflixLogic::containsLatinLetter)
                ?: return@map member
            People(
                id = member.id,
                name = localizedName,
                image = member.image ?: externalMember.image,
            )
        }
        peopleResolver.remember(resolved)
        return resolved
    }

    private fun cacheMovie(content: Content) {
        val slug = contentSlug(content) ?: return
        contentBackendId(content)?.let { movieBackendIds[slug] = it }
        DoramasflixLogic.nonBlank(content.nameEs)?.let { movieLocalizedTitles[slug] = it }
    }

    private fun cacheDorama(content: Content) {
        val slug = contentSlug(content) ?: return
        contentBackendId(content)?.let { doramaBackendIds[slug] = it }
        numericTmdbId(content)?.let { doramaTmdbIds[slug] = it.toString() }
        DoramasflixLogic.nonBlank(content.nameEs)?.let { doramaLocalizedTitles[slug] = it }
    }

    private fun episodeCacheKey(showSlug: String, episodeSlug: String) = "$showSlug|$episodeSlug"

    private fun cacheEpisodes(showSlug: String, episodes: List<DoramasflixEpisode>) {
        episodes.forEach { episode ->
            val slug = DoramasflixLogic.nonBlank(episode.slug) ?: return@forEach
            val backendId = DoramasflixLogic.nonBlank(episode.id) ?: return@forEach
            episodeBackendIds[episodeCacheKey(showSlug, slug)] = backendId
        }
    }

    private fun movieListItem(content: Content): Movie? {
        if (!contentAllowed(content)) return null
        val slug = contentSlug(content) ?: return null
        val title = apiTitleFor(content) ?: return null
        return Movie(
            id = movieId(slug),
            title = title,
            released = DoramasflixLogic.normalizeDate(content.releaseDate),
            rating = DoramasflixLogic.resolveApiRating(content.rating, content.ratingCount).rating,
            poster = contentPoster(content),
            banner = contentBackdrop(content),
        )
    }

    private fun doramaListItem(content: Content): TvShow? {
        if (!contentAllowed(content)) return null
        val slug = contentSlug(content) ?: return null
        val title = apiTitleFor(content) ?: return null
        return TvShow(
            id = doramaId(slug),
            title = title,
            released = DoramasflixLogic.normalizeDate(content.firstAirDate),
            rating = DoramasflixLogic.resolveApiRating(content.rating, content.ratingCount).rating,
            poster = contentPoster(content),
            banner = contentBackdrop(content),
        )
    }

    private fun movieNeedsExternal(content: Content): Boolean {
        val rating = DoramasflixLogic.resolveApiRating(content.rating, content.ratingCount)
        return apiTitleFor(content) == null ||
            DoramasflixLogic.nonBlank(content.overview) == null ||
            contentPoster(content) == null ||
            contentBackdrop(content) == null ||
            DoramasflixLogic.normalizeDate(content.releaseDate) == null ||
            DoramasflixLogic.meaningfulRuntime(content.runtime) == null ||
            DoramasflixLogic.normalizeTrailer(content.trailer) == null ||
            rating.allowExternalFallback ||
            genresFor(content).isEmpty() ||
            hasNonLatinCast(content)
    }

    private fun doramaNeedsExternal(
        content: Content,
        seasons: List<DoramasflixSeason> = emptyList(),
    ): Boolean {
        val rating = DoramasflixLogic.resolveApiRating(content.rating, content.ratingCount)
        val seasonMetadataMissing = seasons.any { season ->
            DoramasflixLogic.firstNonBlank(season.nameEs, season.name) == null ||
                sequenceOf(season.posterPath, season.poster).mapNotNull(::posterUrl).firstOrNull() == null
        }
        return apiTitleFor(content) == null ||
            DoramasflixLogic.nonBlank(content.overview) == null ||
            contentPoster(content) == null ||
            contentBackdrop(content) == null ||
            DoramasflixLogic.normalizeDate(content.firstAirDate) == null ||
            DoramasflixLogic.normalizeTrailer(content.trailer) == null ||
            rating.allowExternalFallback ||
            genresFor(content).isEmpty() ||
            seasonMetadataMissing ||
            hasNonLatinCast(content)
    }

    private suspend fun optionalMovieDetailForEnrichment(slug: String): Content? = try {
        detailMovie(slug)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    private suspend fun optionalDoramaDetailForEnrichment(slug: String): Content? = try {
        detailDorama(slug)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    private suspend fun externalMovie(content: Content): Movie? {
        if (!movieNeedsExternal(content)) return null
        val tmdbId = numericTmdbId(content) ?: return null
        return TmdbUtils.getMovieById(tmdbId, language)
    }

    private suspend fun externalDorama(
        content: Content,
        seasons: List<DoramasflixSeason> = emptyList(),
    ): TvShow? {
        if (!doramaNeedsExternal(content, seasons)) return null
        val tmdbId = numericTmdbId(content) ?: return null
        return TmdbUtils.getTvShowById(tmdbId, language)
    }

    private fun resolveMovieMetadata(content: Content, slug: String, external: Movie?): Movie? {
        val title = apiTitleFor(content) ?: DoramasflixLogic.nonBlank(external?.title) ?: return null
        val apiGenres = genresFor(content)
        return Movie(
            id = movieId(slug),
            title = title,
            overview = resolvedOverview(content, external?.overview),
            released = DoramasflixLogic.normalizeDate(content.releaseDate)
                ?: external?.released?.format("yyyy-MM-dd"),
            runtime = DoramasflixLogic.meaningfulRuntime(content.runtime)
                ?: DoramasflixLogic.meaningfulRuntime(external?.runtime),
            trailer = DoramasflixLogic.normalizeTrailer(content.trailer)
                ?: DoramasflixLogic.normalizeTrailer(external?.trailer),
            rating = DoramasflixLogic.resolveRating(
                apiRating = content.rating,
                apiRatingCount = content.ratingCount,
                tmdbRating = external?.rating,
            ),
            poster = contentPoster(content) ?: DoramasflixLogic.meaningfulImage(external?.poster),
            banner = contentBackdrop(content) ?: DoramasflixLogic.meaningfulImage(external?.banner),
            imdbId = external?.imdbId,
            genres = apiGenres.ifEmpty { external?.genres.orEmpty() },
            cast = resolveCast(content, external?.cast.orEmpty()),
        )
    }

    private fun resolveDoramaMetadata(content: Content, slug: String, external: TvShow?): TvShow? {
        val title = apiTitleFor(content) ?: DoramasflixLogic.nonBlank(external?.title) ?: return null
        val apiGenres = genresFor(content)
        return TvShow(
            id = doramaId(slug),
            title = title,
            overview = resolvedOverview(content, external?.overview),
            released = DoramasflixLogic.normalizeDate(content.firstAirDate)
                ?: external?.released?.format("yyyy-MM-dd"),
            runtime = DoramasflixLogic.meaningfulRuntime(content.episodeTime),
            trailer = DoramasflixLogic.normalizeTrailer(content.trailer)
                ?: DoramasflixLogic.normalizeTrailer(external?.trailer),
            rating = DoramasflixLogic.resolveRating(
                apiRating = content.rating,
                apiRatingCount = content.ratingCount,
                tmdbRating = external?.rating,
            ),
            poster = contentPoster(content) ?: DoramasflixLogic.meaningfulImage(external?.poster),
            banner = contentBackdrop(content) ?: DoramasflixLogic.meaningfulImage(external?.banner),
            imdbId = external?.imdbId,
            genres = apiGenres.ifEmpty { external?.genres.orEmpty() },
            cast = resolveCast(content, external?.cast.orEmpty()),
        )
    }

    private suspend fun searchDoramas(input: String, page: Int): List<Content> {
        val payload = apiRequest(
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
                      first_air_date
                      rating
                      rating_count
                      tmdb_id
                    }
                  }
                }
            """.trimIndent(),
        ).searchFullDoramas
        val items = requireOperationPayload("Doramasflix Dorama search", payload).items
        items.forEach(::cacheDorama)
        return items
    }

    private suspend fun searchMovies(input: String, page: Int): List<Content> {
        val payload = apiRequest(
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
                      release_date
                      rating
                      rating_count
                      tmdb_id
                    }
                  }
                }
            """.trimIndent(),
        ).searchFullMovies
        val items = requireOperationPayload("Doramasflix movie search", payload).items
        items.forEach(::cacheMovie)
        return items
    }

    private data class SearchContentPage(
        val doramas: List<Content>,
        val movies: List<Content>,
    )

    private suspend fun loadSearchPage(input: String, page: Int): SearchContentPage {
        val key = "$page|$input"
        searchPages[key]?.let { return it }

        val loaded = coroutineScope {
            val doramas = async { searchDoramas(input, page) }
            val movies = async { searchMovies(input, page) }
            SearchContentPage(
                doramas = doramas.await(),
                movies = movies.await(),
            )
        }
        searchPages.putIfAbsent(key, loaded)
        return searchPages[key] ?: loaded
    }

    private fun SearchContentPage.sourceSignature(): List<String> = buildList {
        doramas.mapTo(this) { "dorama:${it.sourceSignature()}" }
        movies.mapTo(this) { "movie:${it.sourceSignature()}" }
    }

    private suspend fun getFeaturedDoramas(): List<Content> = try {
        val data = apiRequest(
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
        )
        val items = requireOperationPayload(
            "Doramasflix Home featured Doramas",
            data.carrouselDoramas,
        )
        items.forEach(::cacheDorama)
        items
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w("DoramasflixProvider", "Featured Doramas are unavailable", error)
        emptyList()
    }

    private suspend fun getFeaturedMovies(): List<Content> = try {
        val data = apiRequest(
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
        )
        val items = requireOperationPayload(
            "Doramasflix Home featured movies",
            data.carrouselMovies,
        )
        items.forEach(::cacheMovie)
        items
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w("DoramasflixProvider", "Featured movies are unavailable", error)
        emptyList()
    }

    private suspend fun featuredDorama(content: Content): Show? {
        val slug = contentSlug(content) ?: return null
        val detailed = optionalDoramaDetailForEnrichment(slug) ?: content
        if (!contentAllowed(detailed)) return null
        val external = externalDorama(detailed)
        val resolved = resolveDoramaMetadata(detailed, slug, external) ?: return null
        val banner = contentBackdrop(content) ?: resolved.banner ?: return null
        return resolved.copy(
            poster = contentPoster(content) ?: resolved.poster,
            banner = banner,
        )
    }

    private suspend fun featuredMovie(content: Content): Show? {
        val slug = contentSlug(content) ?: return null
        val detailed = optionalMovieDetailForEnrichment(slug) ?: content
        if (!contentAllowed(detailed)) return null
        val external = externalMovie(detailed)
        val resolved = resolveMovieMetadata(detailed, slug, external) ?: return null
        val banner = contentBackdrop(content) ?: resolved.banner ?: return null
        return resolved.copy(
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
                    release_date
                    poster_path
                    poster
                    backdrop_path
                    backdrop
                    runtime
                    rating
                    rating_count
                    images { backdrops }
                    genres { name slug }
                    labels { name slug }
                    cast { name profile_path slug }
                  }
                }
            """.trimIndent(),
        ).detailMovie ?: throw DoramasflixContentNotFoundException(
            "Doramasflix could not find movie '$slug'."
        )
        cacheMovie(content)
        return content
    }

    private suspend fun detailDorama(slug: String): Content {
        doramaDetails[slug]?.let { return it }

        val content = apiRequest(
            operationName = "DetailDoramaSlug",
            variables = JSONObject().put("slug", slug),
            query = """
                query DetailDoramaSlug(${'$'}slug: String!) {
                  detailDorama(filter: {slug: ${'$'}slug}) {
                    _id
                    slug
                    name
                    name_es
                    original_name
                    tmdb_id
                    overview
                    trailer
                    first_air_date
                    poster_path
                    poster
                    backdrop_path
                    backdrop
                    episode_time
                    rating
                    rating_count
                    images { backdrops }
                    genres { name slug }
                    labels { name slug }
                    cast { name profile_path slug }
                    seasons { season_number }
                  }
                }
            """.trimIndent(),
        ).detailDorama ?: throw DoramasflixContentNotFoundException(
            "Doramasflix could not find dorama '$slug'."
        )
        cacheDorama(content)
        doramaDetails[slug] = content
        return content
    }

    private suspend fun getSimilarMovies(movieBackendId: String): List<Movie> = try {
        val data = apiRequest(
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
                    overview
                    genres { name slug }
                    labels { name slug }
                    poster_path
                    poster
                  }
                }
            """.trimIndent(),
        )
        requireOperationPayload("Doramasflix movie recommendations", data.similarsMovies)
            .mapNotNull { content ->
                cacheMovie(content)
                movieListItem(content)
            }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        emptyList()
    }

    private suspend fun getSimilarDoramas(doramaBackendId: String): List<TvShow> = try {
        val data = apiRequest(
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
                    overview
                    genres { name slug }
                    labels { name slug }
                    poster_path
                    poster
                  }
                }
            """.trimIndent(),
        )
        requireOperationPayload("Doramasflix series recommendations", data.similarsDoramas)
            .mapNotNull { content ->
                cacheDorama(content)
                doramaListItem(content)
            }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        emptyList()
    }

    private suspend fun getSeasons(slug: String): List<DoramasflixSeason> {
        val data = apiRequest(
            operationName = "ListSeasons",
            variables = JSONObject().put("slug", slug),
            query = """
                query ListSeasons(${'$'}slug: String!) {
                  listSeasons(sort: NUMBER_ASC, filter: {serie_slug: ${'$'}slug}) {
                    name
                    name_es
                    poster
                    poster_path
                    serie_id
                    season_number
                  }
                }
            """.trimIndent(),
        )
        val seasons = requireOperationPayload("Doramasflix seasons", data.listSeasons)
            .filter { it.seasonNumber != null }
            .distinctBy { it.seasonNumber }
        if (seasons.isNotEmpty()) {
            seasons.asSequence()
                .mapNotNull { DoramasflixLogic.nonBlank(it.serieId) }
                .firstOrNull()
                ?.let { doramaBackendIds[slug] = it }
            return seasons
        }

        return detailDorama(slug).seasons.orEmpty()
            .filter { it.seasonNumber != null }
            .distinctBy { it.seasonNumber }
    }

    private suspend fun resolveDoramaBackendId(slug: String): String {
        doramaBackendIds[slug]?.let { return it }
        return contentBackendId(detailDorama(slug))
            ?: throw Exception("Doramasflix could not resolve the series ID.")
    }

    private suspend fun resolveDoramaTmdbIdForEnrichment(slug: String): String? {
        doramaTmdbIds[slug]?.let { return it }
        val content = try {
            detailDorama(slug)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return null
        }
        return numericTmdbId(content)?.toString()
    }

    private data class ExternalEpisodeMetadata(
        val localized: Map<Int, Episode>,
        val defaultLanguage: Map<Int, Episode>,
        val defaultShowOverview: String?,
    )

    private suspend fun getTmdbEpisodeMetadata(
        slug: String,
        seasonNumber: Int,
    ): ExternalEpisodeMetadata {
        val tmdbId = resolveDoramaTmdbIdForEnrichment(slug)
            ?: return ExternalEpisodeMetadata(emptyMap(), emptyMap(), null)
        val localized = TmdbUtils.getEpisodesBySeason(tmdbId, seasonNumber, language)
            .associateBy { it.number }
        val defaultLanguage = TmdbUtils.getEpisodesBySeason(tmdbId, seasonNumber, null)
            .associateBy { it.number }
        val defaultShowOverview = tmdbId.toIntOrNull()
            ?.let { TmdbUtils.getTvShowById(it, null)?.overview }
        return ExternalEpisodeMetadata(localized, defaultLanguage, defaultShowOverview)
    }

    private suspend fun getEpisodes(slug: String, seasonNumber: Int): List<DoramasflixEpisode> {
        val backendId = resolveDoramaBackendId(slug)
        val episodes = mutableListOf<DoramasflixEpisode>()
        var page = 1

        while (true) {
            val data = apiRequest(
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
                          backdrop
                          still_path
                          still_image
                          episode_number
                          date_string
                          air_date
                          overview
                        }
                        pageInfo { hasNextPage }
                      }
                    }
                """.trimIndent(),
            )
            val response: EpisodePage = requireOperationPayload(
                "Doramasflix episodes",
                data.paginationEpisode,
            )
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
            val data = apiRequest(
                operationName = "ListServers",
                variables = JSONObject(),
                query = """
                    query ListServers {
                      listServers { name code_flix }
                    }
                """.trimIndent(),
            )
            data.listServers.orEmpty()
                .mapNotNull { server ->
                    val code = DoramasflixLogic.nonBlank(server.codeFlix) ?: return@mapNotNull null
                    val serverName = DoramasflixLogic.nonBlank(server.name) ?: return@mapNotNull null
                    code to serverName
                }
                .toMap()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyMap()
        }

        if (discovered.isNotEmpty()) serverNamesByCode = discovered
        return discovered
    }

    private suspend fun loadMovieCatalogPage(sourcePage: Int): ContentPage {
        val requestedPage = sourcePage.coerceAtLeast(1)
        movieCatalogPages[requestedPage]?.let { return it }

        val data = apiRequest(
            operationName = "PaginationMovie",
            variables = JSONObject()
                .put("page", requestedPage)
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
                      backdrop_path
                      backdrop
                      release_date
                      rating
                      rating_count
                      tmdb_id
                    }
                  }
                }
            """.trimIndent(),
        )
        val loaded = requireOperationPayload(
            "Doramasflix movies catalog",
            data.paginationMovie,
        )
        loaded.items.forEach(::cacheMovie)
        movieCatalogPages.putIfAbsent(requestedPage, loaded)
        return movieCatalogPages[requestedPage] ?: loaded
    }

    private suspend fun loadDoramaCatalogPage(
        sourcePage: Int,
        isTvShow: Boolean,
    ): ContentPage {
        val requestedPage = sourcePage.coerceAtLeast(1)
        val cacheKey = "$isTvShow:$requestedPage"
        doramaCatalogPages[cacheKey]?.let { return it }

        val data = apiRequest(
            operationName = "PaginationDorama",
            variables = JSONObject()
                .put("page", requestedPage)
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
                      backdrop_path
                      backdrop
                      first_air_date
                      rating
                      rating_count
                      tmdb_id
                    }
                  }
                }
            """.trimIndent(),
        )
        val loaded = requireOperationPayload(
            "Doramasflix Doramas catalog",
            data.paginationDorama,
        )
        loaded.items.forEach(::cacheDorama)
        doramaCatalogPages.putIfAbsent(cacheKey, loaded)
        return doramaCatalogPages[cacheKey] ?: loaded
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val featuredDoramasDeferred = async { getFeaturedDoramas() }
        val featuredMoviesDeferred = async { getFeaturedMovies() }
        val doramasDeferred = async { getTvShows(1) }
        val moviesDeferred = async { getMovies(1) }

        val featuredDoramas = mutableListOf<Show>()
        for (content in featuredDoramasDeferred.await()) {
            featuredDorama(content)?.let(featuredDoramas::add)
        }
        val featuredMovies = mutableListOf<Show>()
        for (content in featuredMoviesDeferred.await()) {
            featuredMovie(content)?.let(featuredMovies::add)
        }
        val featured = DoramasflixLogic.mixAlternating(
            first = featuredDoramas,
            second = featuredMovies,
        )

        buildList {
            featured.takeIf { it.isNotEmpty() }?.let {
                add(Category(name = Category.FEATURED, list = it))
            }
            doramasDeferred.await().takeIf { it.isNotEmpty() }?.let {
                add(Category(name = "Doramas Populares", list = it))
            }
            moviesDeferred.await().takeIf { it.isNotEmpty() }?.let {
                add(Category(name = "Películas Populares", list = it))
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return buildList {
                add(Genre("doramas", "Doramas"))
                add(Genre("peliculas", "Películas"))
                add(Genre("variedades", "Variedades"))
                addAll(genreBrowser.genres)
            }
        }

        return DoramasflixPaging.visiblePage(page) { sourcePage ->
            val raw = loadSearchPage(query, sourcePage)
            DoramasflixPageBatch(
                items = buildList {
                    raw.doramas.mapNotNullTo(this, ::doramaListItem)
                    raw.movies.mapNotNullTo(this, ::movieListItem)
                },
                hasNextPage = raw.doramas.isNotEmpty() || raw.movies.isNotEmpty(),
                sourceSignature = raw.sourceSignature(),
            )
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        DoramasflixPaging.visiblePage(page) { sourcePage ->
            val raw = loadMovieCatalogPage(sourcePage)
            DoramasflixPageBatch(
                items = raw.items.mapNotNull(::movieListItem),
                hasNextPage = raw.pageInfo?.hasNextPage == true,
                sourceSignature = raw.items.map { it.sourceSignature() },
            )
        }

    override suspend fun getTvShows(page: Int): List<TvShow> =
        getDoramaPage(page, isTvShow = false)

    private suspend fun getDoramaPage(page: Int, isTvShow: Boolean): List<TvShow> =
        DoramasflixPaging.visiblePage(page) { sourcePage ->
            val raw = loadDoramaCatalogPage(sourcePage, isTvShow)
            DoramasflixPageBatch(
                items = raw.items.mapNotNull(::doramaListItem),
                hasNextPage = raw.pageInfo?.hasNextPage == true,
                sourceSignature = raw.items.map { it.sourceSignature() },
            )
        }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val slug = slugFromId(id)
        val content = detailMovie(slug)
        val backendId = contentBackendId(content)
            ?: throw Exception("Doramasflix could not resolve movie '$slug'.")
        val recommendations = async { getSimilarMovies(backendId) }
        val external = externalMovie(content)
        val resolved = resolveMovieMetadata(content, slug, external)
            ?: throw Exception("Doramasflix movie '$slug' returned no usable title.")
        resolved.copy(recommendations = recommendations.await())
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val slug = slugFromId(id)
        val detailDeferred = async { detailDorama(slug) }
        val seasonsDeferred = async { getSeasons(slug) }
        val content = detailDeferred.await()
        val seasonsData = seasonsDeferred.await()
        val backendId = contentBackendId(content)
            ?: throw Exception("Doramasflix could not resolve series '$slug'.")
        val recommendations = async { getSimilarDoramas(backendId) }
        val external = externalDorama(content, seasonsData)
        val externalSeasons = external?.seasons.orEmpty().associateBy { it.number }
        val seasons = seasonsData.mapNotNull { season ->
            val seasonNumber = season.seasonNumber ?: return@mapNotNull null
            val externalSeason = externalSeasons[seasonNumber]
            Season(
                id = "$slug/$seasonNumber",
                number = seasonNumber,
                title = DoramasflixLogic.firstNonBlank(
                    season.nameEs,
                    season.name,
                    externalSeason?.title,
                ),
                poster = sequenceOf(season.posterPath, season.poster)
                    .mapNotNull(::posterUrl)
                    .firstOrNull()
                    ?: DoramasflixLogic.meaningfulImage(externalSeason?.poster),
            )
        }
        val resolved = resolveDoramaMetadata(content, slug, external)
            ?: throw Exception("Doramasflix series '$slug' returned no usable title.")
        resolved.copy(
            seasons = seasons,
            recommendations = recommendations.await(),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val slug = seasonId.substringBeforeLast('/')
        val seasonNumber = seasonId.substringAfterLast('/').toIntOrNull()
            ?: throw Exception("Invalid Doramasflix season ID: $seasonId")
        val detail = detailDorama(slug)
        val showTitles = listOf(detail.name, detail.nameEs, detail.originalName)
        val showArtwork = buildList {
            add(detail.posterPath)
            add(detail.poster)
            add(detail.backdropPath)
            add(detail.backdrop)
            addAll(detail.images?.backdrops.orEmpty())
        }
        val episodes = getEpisodes(slug, seasonNumber)

        fun providerTitle(episode: DoramasflixEpisode, number: Int): String? =
            DoramasflixLogic.firstNonBlank(
                DoramasflixLogic.meaningfulEpisodeTitle(
                    episode.nameEs,
                    showTitles,
                    seasonNumber,
                    number,
                ),
                DoramasflixLogic.meaningfulEpisodeTitle(
                    episode.name,
                    showTitles,
                    seasonNumber,
                    number,
                ),
            )

        fun providerArtwork(episode: DoramasflixEpisode): String? =
            DoramasflixLogic.episodeArtwork(
                stillPath = episode.stillPath,
                backdrop = episode.backdrop,
                stillImage = episode.stillImage,
                genericArtwork = showArtwork,
            )

        fun providerOverview(episode: DoramasflixEpisode, number: Int): String? =
            DoramasflixLogic.meaningfulEpisodeOverview(
                value = episode.overview,
                showOverview = detail.overview,
                showTitles = showTitles,
                seasonNumber = seasonNumber,
                episodeNumber = number,
            )

        val needsExternal = episodes.any { episode ->
            val number = episode.episodeNumber ?: return@any false
            providerTitle(episode, number) == null ||
                providerArtwork(episode) == null ||
                providerOverview(episode, number) == null ||
                (DoramasflixLogic.normalizeDate(episode.airDate)
                    ?: DoramasflixLogic.normalizeDate(episode.dateString)) == null
        }
        val external = if (needsExternal) {
            getTmdbEpisodeMetadata(slug, seasonNumber)
        } else {
            ExternalEpisodeMetadata(emptyMap(), emptyMap(), null)
        }

        return episodes.mapNotNull { episode ->
            val episodeSlug = DoramasflixLogic.nonBlank(episode.slug) ?: return@mapNotNull null
            val number = episode.episodeNumber ?: return@mapNotNull null
            val localized = external.localized[number]
            val defaultLanguage = external.defaultLanguage[number]
            val externalTitle = DoramasflixLogic.firstNonBlank(localized?.title, defaultLanguage?.title)
            val externalPoster = DoramasflixLogic.meaningfulImage(localized?.poster)
                ?: DoramasflixLogic.meaningfulImage(defaultLanguage?.poster)
            val externalOverview = DoramasflixLogic.firstNonBlank(
                localized?.overview,
                defaultLanguage?.overview,
            )?.takeUnless { overview ->
                DoramasflixLogic.sameNormalizedText(overview, detail.overview) ||
                    DoramasflixLogic.sameNormalizedText(overview, external.defaultShowOverview)
            }
            val externalDate = sequenceOf(localized, defaultLanguage)
                .mapNotNull { it?.released?.format("yyyy-MM-dd") }
                .mapNotNull(DoramasflixLogic::normalizeDate)
                .firstOrNull()
            val artwork = DoramasflixLogic.episodeArtwork(
                stillPath = episode.stillPath,
                backdrop = episode.backdrop,
                stillImage = episode.stillImage,
                genericArtwork = showArtwork,
                tmdbArtwork = externalPoster,
            )

            Episode(
                id = episodeSlug,
                number = number,
                title = providerTitle(episode, number) ?: externalTitle,
                released = DoramasflixLogic.normalizeDate(episode.airDate)
                    ?: DoramasflixLogic.normalizeDate(episode.dateString)
                    ?: externalDate,
                poster = posterUrl(artwork),
                overview = providerOverview(episode, number) ?: externalOverview,
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

    private suspend fun getPlaybackContext(id: String, videoType: Video.Type): PlaybackContext =
        when (videoType) {
            is Video.Type.Movie -> {
                val slug = slugFromId(id)
                val backendId = resolveMovieBackendId(slug)
                val data = apiRequest(
                    operationName = "MoviePlaybackContext",
                    variables = JSONObject().put("slug", slug).put("movie_id", backendId),
                    query = """
                        query MoviePlaybackContext(${'$'}slug: String!, ${'$'}movie_id: ID!) {
                          detailMovie(filter: {slug: ${'$'}slug}) {
                            langs { name code code_flix }
                          }
                          getMovieLinks(id: ${'$'}movie_id, app: "$playbackApp") {
                            links_online {
                              server
                              lang
                              link
                              is_recommended
                              subtitles { language_code type }
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
                    variables = JSONObject().put("slug", id).put("episode_id", backendId),
                    query = """
                        query EpisodePlaybackContext(${'$'}slug: String!, ${'$'}episode_id: ID!) {
                          detailEpisode(filter: {slug: ${'$'}slug}) {
                            langs { name code code_flix }
                          }
                          getEpisodeLinks(id: ${'$'}episode_id, app: "$playbackApp") {
                            links_online {
                              server
                              lang
                              link
                              is_recommended
                              subtitles { language_code type }
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
            val token = link.substringAfter("/e/").substringBefore('?').substringBefore('#')
            val payload = token.split('.').getOrNull(1) ?: return@runCatching null
            val payloadJson = String(
                Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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

    private fun isPrimeload(link: String, serverName: String?): Boolean {
        if (serverName.equals("Primeload", ignoreCase = true)) return true
        val host = runCatching { URL(link).host.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host == "primeload.co" || host.endsWith(".primeload.co")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val playback = getPlaybackContext(id, videoType)
        if (playback.links.isEmpty()) {
            throw Exception("Doramasflix currently has no playback sources for this title.")
        }

        val registry = getServerNamesByCode()
        val languagesByCode = mutableMapOf<String, String>()
        playback.languages.forEach { metadata ->
            val languageName = DoramasflixLogic.nonBlank(metadata.name) ?: return@forEach
            listOf(metadata.codeFlix, metadata.code).forEach { rawCode ->
                DoramasflixLogic.nonBlank(rawCode)?.let { languagesByCode[it] = languageName }
            }
        }

        var primeloadCount = 0
        var invalidCount = 0
        val servers = playback.links
            .sortedByDescending { it.isRecommended == true }
            .mapNotNull { onlineLink ->
                val rawLink = DoramasflixLogic.nonBlank(onlineLink.link)
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

                val rawLanguageCode = DoramasflixLogic.nonBlank(onlineLink.lang)
                val languageName = rawLanguageCode?.let(languagesByCode::get)
                val subtitles = onlineLink.subtitles.orEmpty().mapNotNull { subtitle ->
                    DoramasflixLogic.subtitleDescriptor(subtitle.languageCode, subtitle.type)
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

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int): Genre {
        val shortcut = when (id) {
            "doramas" -> Genre(id = id, name = "Doramas", shows = getTvShows(page))
            "peliculas" -> Genre(id = id, name = "Películas", shows = getMovies(page))
            "variedades" -> Genre(id = id, name = "Variedades", shows = getDoramaPage(page, isTvShow = true))
            else -> null
        }
        if (shortcut != null) return shortcut

        val name = genreBrowser.genreName(id)
            ?: throw Exception("Unknown Doramasflix category: $id")
        return Genre(
            id = id,
            name = name,
            shows = genreBrowser.getShows(id, page),
        )
    }

    override suspend fun getPeople(id: String, page: Int): People = peopleResolver.getPeople(id, page)
}
