package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.google.gson.Gson
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.models.doramasflix.ApiResponse
import com.streamflixreborn.streamflix.models.doramasflix.Show as DoramasflixShow
import com.streamflixreborn.streamflix.models.doramasflix.TokenModel
import com.streamflixreborn.streamflix.models.doramasflix.VideoToken
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
import java.util.concurrent.TimeUnit

object DoramasflixProvider : Provider {

    override val name = "Doramasflix"
    override val baseUrl = "https://doramasflix.in"
    private const val apiUrl = "https://sv7.fluxcedene.net/"
    override val language = "es"
    override val logo = "https://doramasflix.in/img/logo.png"

    private val serverReferers = listOf(
        "$baseUrl/",
        "https://doramasflix.co/",
        "https://doramasflix.io/",
    )

    private val client = getOkHttpClient()

    private val service = Retrofit.Builder()
        .baseUrl(apiUrl)
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private val serviceHtml = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(DoramasflixService::class.java)

    private fun getOkHttpClient(): OkHttpClient {
        val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
        return OkHttpClient.Builder()
            .cache(appCache)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()
    }

    private val languages = arrayOf(
        Pair("36", "[ENG]"),
        Pair("37", "[CAST]"),
        Pair("38", "[LAT]"),
        Pair("192", "[SUB]"),
        Pair("1327", "[POR]"),
        Pair("13109", "[COR]"),
        Pair("13110", "[JAP]"),
        Pair("13111", "[MAN]"),
        Pair("13112", "[TAI]"),
        Pair("13113", "[FIL]"),
        Pair("13114", "[IND]"),
        Pair("343422", "[VIET]"),
    )

    private fun String.getLang(): String = languages.firstOrNull { it.first == this }?.second ?: ""

    private interface DoramasflixService {
        @POST("graphql")
        @Headers("Content-Type: application/json")
        suspend fun getApiResponse(
            @Header("Referer") referer: String,
            @Body body: okhttp3.RequestBody,
        ): ApiResponse

        @GET
        suspend fun getPage(@Url url: String): Document

        @POST
        @Headers("Content-Type: application/json")
        suspend fun postApi(@Url url: String, @Body body: okhttp3.RequestBody): VideoToken
    }

    private suspend fun apiRequest(
        operationName: String,
        variables: JSONObject,
        query: String,
        referer: String = "$baseUrl/",
    ): ApiResponse {
        val payload = JSONObject()
            .put("operationName", operationName)
            .put("variables", variables)
            .put("query", query)
            .toString()
            .toRequestBody("application/json".toMediaType())
        return service.getApiResponse(referer, payload)
    }

    private fun getPosterUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/w500$path"
    }

    private fun normalizePath(id: String): String = id
        .removePrefix("$baseUrl/")
        .removePrefix("/")
        .substringBefore('?')

    private fun slugFromId(id: String): String = normalizePath(id).substringAfterLast('/')

    private fun titleFor(show: DoramasflixShow): String {
        val translated = show.nameEs?.takeIf { it.isNotBlank() && !it.equals(show.name, ignoreCase = true) }
        return translated?.let { "${show.name} ($it)" } ?: show.name
    }

    private fun movieId(slug: String) = "peliculas-online/$slug"
    private fun doramaId(slug: String) = "doramas-online/$slug"

    private suspend fun searchAll(input: String): ApiResponse = apiRequest(
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
                __typename
              }
              searchMovie(input: ${'$'}input, limit: 32) {
                _id
                slug
                name
                name_es
                poster_path
                poster
                __typename
              }
            }
        """.trimIndent(),
    )

    private suspend fun findMovieBySlug(slug: String, titleHint: String? = null): DoramasflixShow? {
        val input = titleHint?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        return searchAll(input).data?.searchMovie?.firstOrNull { it.slug == slug }
            ?: if (titleHint != null) {
                searchAll(slug.replace('-', ' ')).data?.searchMovie?.firstOrNull { it.slug == slug }
            } else null
    }

    private suspend fun findDoramaBySlug(slug: String, titleHint: String? = null): DoramasflixShow? {
        val input = titleHint?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        return searchAll(input).data?.searchDorama?.firstOrNull { it.slug == slug }
            ?: if (titleHint != null) {
                searchAll(slug.replace('-', ' ')).data?.searchDorama?.firstOrNull { it.slug == slug }
            } else null
    }

    private suspend fun getSeasons(slug: String): List<com.streamflixreborn.streamflix.models.doramasflix.Season> {
        val response = apiRequest(
            operationName = "listSeasons",
            variables = JSONObject().put("slug", slug),
            query = """
                query listSeasons(${'$'}slug: String!) {
                  listSeasons(sort: NUMBER_ASC, filter: {serie_slug: ${'$'}slug}) {
                    _id
                    slug
                    season_number
                    poster_path
                    serie_backdrop_path
                    serie_name
                    trailer
                    backdrop
                    overview
                    name
                    __typename
                  }
                }
            """.trimIndent(),
        )
        return response.data?.listSeasons.orEmpty().distinctBy { it.seasonNumber }
    }

    private suspend fun getEpisodes(slug: String, seasonNumber: Int): List<com.streamflixreborn.streamflix.models.doramasflix.Episode> {
        val response = apiRequest(
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
                    serie_name
                    serie_id
                    still_path
                    season_number
                    episode_number
                    languages
                    backdrop
                    __typename
                  }
                }
            """.trimIndent(),
        )
        return response.data?.listEpisodes.orEmpty()
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val doramasDeferred = async { getTvShows(1) }
        val moviesDeferred = async { getMovies(1) }

        buildList {
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
            return listOf(
                Genre("doramas", "Doramas"),
                Genre("peliculas", "Películas"),
                Genre("variedades", "Variedades"),
            )
        }

        return try {
            val response = searchAll(query)
            buildList {
                response.data?.searchDorama.orEmpty().forEach { show ->
                    add(
                        TvShow(
                            id = doramaId(show.slug),
                            title = titleFor(show),
                            poster = getPosterUrl(show.posterPath ?: show.poster),
                        )
                    )
                }
                response.data?.searchMovie.orEmpty().forEach { show ->
                    add(
                        Movie(
                            id = movieId(show.slug),
                            title = titleFor(show),
                            poster = getPosterUrl(show.posterPath ?: show.poster),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        return try {
            val response = apiRequest(
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
                          __typename
                        }
                      }
                    }
                """.trimIndent(),
            )
            response.data?.paginationMovie?.items.orEmpty().map { show ->
                Movie(
                    id = movieId(show.slug),
                    title = titleFor(show),
                    poster = getPosterUrl(show.posterPath ?: show.poster),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = getDoramaPage(page, isTvShow = false)

    private suspend fun getDoramaPage(page: Int, isTvShow: Boolean): List<TvShow> {
        return try {
            val response = apiRequest(
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
                          isTVShow
                          __typename
                        }
                      }
                    }
                """.trimIndent(),
            )
            response.data?.paginationDorama?.items.orEmpty().map { show ->
                TvShow(
                    id = if (isTvShow) show.slug else doramaId(show.slug),
                    title = titleFor(show),
                    poster = getPosterUrl(show.posterPath ?: show.poster),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val path = normalizePath(id)
        val slug = slugFromId(path)
        return try {
            val document = serviceHtml.getPage("$baseUrl/$path")
            val pageTitle = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            val apiMovie = findMovieBySlug(slug, pageTitle)
                ?: throw Exception("No se pudo resolver la película en la API de Doramasflix.")
            val title = pageTitle ?: titleFor(apiMovie)
            val overviewPrefix = "Ver $title online:"
            val overview = document.select("p")
                .asSequence()
                .map { it.text().trim() }
                .firstOrNull { it.startsWith(overviewPrefix, ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()

            Movie(
                id = apiMovie.id,
                title = title,
                overview = overview,
                poster = getPosterUrl(apiMovie.posterPath ?: apiMovie.poster),
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles de la película: ${e.message}")
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val path = normalizePath(id)
        val slug = slugFromId(path)
        return try {
            val seasonsData = getSeasons(slug)
            val apiShow = findDoramaBySlug(slug)
            val firstSeason = seasonsData.firstOrNull()

            val seasons = seasonsData.map { season ->
                Season(
                    id = "$slug/${season.seasonNumber}",
                    number = season.seasonNumber,
                    title = season.name?.takeIf { it.isNotBlank() } ?: "Temporada ${season.seasonNumber}",
                    poster = getPosterUrl(season.posterPath),
                )
            }

            TvShow(
                id = apiShow?.id ?: path,
                title = firstSeason?.serieName?.takeIf { it.isNotBlank() }
                    ?: apiShow?.let(::titleFor)
                    ?: slug.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) },
                overview = firstSeason?.overview,
                poster = getPosterUrl(firstSeason?.posterPath ?: apiShow?.posterPath ?: apiShow?.poster),
                banner = getPosterUrl(firstSeason?.serieBackdropPath ?: firstSeason?.backdrop),
                trailer = firstSeason?.trailer,
                seasons = seasons,
            )
        } catch (e: Exception) {
            throw Exception("No se pudieron cargar los detalles del dorama: ${e.message}")
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val slug = seasonId.substringBeforeLast('/')
        val seasonNumber = seasonId.substringAfterLast('/').toIntOrNull() ?: return emptyList()

        return try {
            getEpisodes(slug, seasonNumber).map { episode ->
                Episode(
                    id = episode.id,
                    number = episode.episodeNumber ?: 0,
                    title = "Episodio ${episode.episodeNumber ?: 0}: ${episode.name.orEmpty()}".trim(),
                    poster = getPosterUrl(episode.stillPath),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun getServersForReferer(id: String, referer: String): List<Video.Server> {
        val response = apiRequest(
            operationName = "listProblemsItem",
            variables = JSONObject().put("problem_id", id),
            query = """
                query listProblemsItem(${'$'}problem_id: ID!) {
                  listProblems(filter: {problem_id: ${'$'}problem_id}) {
                    server {
                      link
                      lang
                    }
                  }
                }
            """.trimIndent(),
            referer = referer,
        )

        return response.data?.listProblems.orEmpty().mapNotNull { problem ->
            val serverUrl = problem.server?.link?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lang = problem.server.lang?.getLang().orEmpty()
            val serverName = runCatching {
                URL(serverUrl).host
                    .split('.')
                    .firstOrNull { it.isNotBlank() && it != "www" }
                    ?.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }.getOrNull() ?: "Server"

            Video.Server(
                id = getRealLink(serverUrl),
                name = "$serverName $lang".trim(),
            )
        }.distinctBy { it.id }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        for (referer in serverReferers) {
            val servers = try {
                getServersForReferer(id, referer)
            } catch (_: Exception) {
                emptyList()
            }
            if (servers.isNotEmpty()) return servers
        }
        return emptyList()
    }

    private suspend fun getRealLink(link: String): String {
        if (!link.contains("fkplayer.xyz")) return link

        return try {
            val document = serviceHtml.getPage(link)
            val script = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return link
            val tokenData = Gson().fromJson(script, TokenModel::class.java)
            val token = tokenData.props?.pageProps?.token ?: return link
            val requestBody = "{\"token\":\"$token\"}".toRequestBody("application/json".toMediaType())
            val videoResponse = service.postApi("https://fkplayer.xyz/api/decoding", requestBody)
            String(Base64.decode(videoResponse.link, Base64.DEFAULT))
        } catch (_: Exception) {
            link
        }
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.id, server)

    override suspend fun getGenre(id: String, page: Int): Genre {
        val list: List<Show> = when (id) {
            "peliculas" -> getMovies(page)
            "variedades" -> getDoramaPage(page, isTvShow = true)
            else -> getTvShows(page)
        }
        return Genre(id = id, name = id.replaceFirstChar { it.uppercase() }, shows = list)
    }

    override suspend fun getPeople(id: String, page: Int): People = throw Exception("Not yet implemented")
}
