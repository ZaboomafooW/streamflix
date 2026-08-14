package com.streamflixreborn.streamflix.providers

import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.QueryMap

internal object DoramasflixTmdbMetadata {

    const val language = "es-ES"

    private const val baseUrl = "https://api.themoviedb.org/3/"
    private const val episodeImageConcurrency = 4
    private const val appendedMedia = "credits,videos,external_ids,images"
    private const val imageLanguages = "es,null"

    private data class EpisodeImagesResponse(
        @SerializedName("id") val id: Int? = null,
        @SerializedName("stills") val stills: List<TMDb3.Images.FileImage> = emptyList(),
    )

    private interface Service {
        @GET("movie/{movie_id}")
        suspend fun movie(
            @Path("movie_id") movieId: Int,
            @QueryMap params: Map<String, String>,
        ): TMDb3.Movie.Detail

        @GET("tv/{series_id}")
        suspend fun tvShow(
            @Path("series_id") seriesId: Int,
            @QueryMap params: Map<String, String>,
        ): TMDb3.Tv.Detail

        @GET("tv/{series_id}/season/{season_number}")
        suspend fun season(
            @Path("series_id") seriesId: Int,
            @Path("season_number") seasonNumber: Int,
            @QueryMap params: Map<String, String>,
        ): TMDb3.Season.Detail

        @GET("tv/{series_id}/season/{season_number}/episode/{episode_number}/images")
        suspend fun episodeImages(
            @Path("series_id") seriesId: Int,
            @Path("season_number") seasonNumber: Int,
            @Path("episode_number") episodeNumber: Int,
            @QueryMap params: Map<String, String>,
        ): EpisodeImagesResponse

        @GET("person/{person_id}")
        suspend fun person(
            @Path("person_id") personId: Int,
            @QueryMap params: Map<String, String>,
        ): TMDb3.Person.Detail
    }

    private val service: Service by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val apiKey = UserPreferences.tmdbApiKey.ifEmpty { BuildConfig.TMDB_API_KEY }
                val request = chain.request().newBuilder()
                    .url(
                        chain.request().url.newBuilder()
                            .addQueryParameter("api_key", apiKey)
                            .build()
                    )
                    .build()
                chain.proceed(request)
            }
            .build()

        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(Service::class.java)
    }

    suspend fun movie(id: Int): Movie? {
        if (!UserPreferences.enableTmdb) return null
        return optionalRequest {
            val details = service.movie(id, mediaParams())
            Movie(
                id = details.id.toString(),
                title = details.title,
                overview = details.overview.nonBlank(),
                released = details.releaseDate,
                runtime = details.runtime,
                trailer = spanishTrailer(details.videos),
                rating = details.voteAverage.toDouble(),
                poster = preferredPoster(details.images?.posters.orEmpty(), details.posterPath),
                banner = preferredBackdrop(details.images?.backdrops.orEmpty(), details.backdropPath),
                imdbId = details.externalIds?.imdbId ?: details.imdbId,
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast.orEmpty().map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                },
            )
        }
    }

    suspend fun tvShow(id: Int): TvShow? {
        if (!UserPreferences.enableTmdb) return null
        return optionalRequest {
            val details = service.tvShow(id, mediaParams())
            TvShow(
                id = details.id.toString(),
                title = details.name,
                overview = details.overview.nonBlank(),
                released = details.firstAirDate,
                runtime = details.episodeRuntime.firstOrNull { it > 0 },
                trailer = spanishTrailer(details.videos),
                rating = details.voteAverage.toDouble(),
                poster = preferredPoster(details.images?.posters.orEmpty(), details.posterPath),
                banner = preferredBackdrop(details.images?.backdrops.orEmpty(), details.backdropPath),
                imdbId = details.externalIds?.imdbId,
                seasons = details.seasons.map { season ->
                    Season(
                        id = "${details.id}-${season.seasonNumber}",
                        number = season.seasonNumber,
                        title = season.name.nonBlank(),
                        poster = season.posterPath?.w500,
                    )
                },
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast.orEmpty().map { cast ->
                    People(
                        id = cast.id.toString(),
                        name = cast.name,
                        image = cast.profilePath?.w500,
                    )
                },
            )
        }
    }

    suspend fun episodes(id: Int, seasonNumber: Int): List<Episode> {
        if (!UserPreferences.enableTmdb) return emptyList()
        val details = optionalRequest {
            service.season(
                seriesId = id,
                seasonNumber = seasonNumber,
                params = mapOf("language" to language),
            )
        } ?: return emptyList()

        val episodes = details.episodes.orEmpty()
        if (episodes.isEmpty()) return emptyList()

        val imageSemaphore = Semaphore(episodeImageConcurrency)
        return coroutineScope {
            episodes.map { episode ->
                async {
                    val images = imageSemaphore.withPermit {
                        optionalRequest {
                            service.episodeImages(
                                seriesId = id,
                                seasonNumber = seasonNumber,
                                episodeNumber = episode.episodeNumber,
                                params = mapOf(
                                    "language" to language,
                                    "include_image_language" to imageLanguages,
                                ),
                            ).stills
                        }.orEmpty()
                    }

                    Episode(
                        id = episode.id.toString(),
                        number = episode.episodeNumber,
                        title = episode.name.nonBlank(),
                        released = episode.airDate,
                        poster = preferredEpisodeImage(images, episode.stillPath),
                        overview = episode.overview.nonBlank(),
                    )
                }
            }.awaitAll()
        }
    }

    suspend fun person(id: Int): People? {
        if (!UserPreferences.enableTmdb) return null
        return optionalRequest {
            val details = service.person(id, mapOf("language" to language))
            People(
                id = details.id.toString(),
                name = details.name,
                image = details.profilePath?.w500,
                biography = details.biography.nonBlank(),
                placeOfBirth = details.placeOfBirth.nonBlank(),
                birthday = details.birthday,
                deathday = details.deathday,
            )
        }
    }

    internal fun preferredImagePath(
        images: List<TMDb3.Images.FileImage>,
        fallbackPath: String?,
    ): String? = bestImage(images.filter { it.iso639.equals("es", ignoreCase = true) })?.filePath
        ?: fallbackPath?.trim()?.takeIf { it.isNotEmpty() }
        ?: bestImage(images.filter { it.iso639 == null })?.filePath
        ?: bestImage(images)?.filePath

    private fun preferredPoster(
        images: List<TMDb3.Images.FileImage>,
        fallbackPath: String?,
    ): String? = preferredImagePath(images, fallbackPath)?.original

    private fun preferredBackdrop(
        images: List<TMDb3.Images.FileImage>,
        fallbackPath: String?,
    ): String? = preferredImagePath(images, fallbackPath)?.original

    private fun preferredEpisodeImage(
        images: List<TMDb3.Images.FileImage>,
        fallbackPath: String?,
    ): String? = preferredImagePath(images, fallbackPath)?.w500

    private fun bestImage(images: List<TMDb3.Images.FileImage>): TMDb3.Images.FileImage? =
        images.maxWithOrNull(
            compareBy<TMDb3.Images.FileImage> { it.voteAverage ?: 0f }
                .thenBy { it.voteCount ?: 0 }
                .thenBy { it.width.toLong() * it.height.toLong() }
        )

    private fun mediaParams(): Map<String, String> = mapOf(
        "language" to language,
        "append_to_response" to appendedMedia,
        "include_image_language" to imageLanguages,
        "include_video_language" to "es,null",
    )

    private fun spanishTrailer(videos: TMDb3.Result<TMDb3.Video>?): String? {
        val youtubeVideos = videos?.results.orEmpty()
            .filter { it.site == TMDb3.Video.VideoSite.YOUTUBE && !it.key.isNullOrBlank() }
        val selected = youtubeVideos
            .filter { it.iso639.equals("es", ignoreCase = true) }
            .maxByOrNull { it.publishedAt.orEmpty() }
            ?: youtubeVideos.maxByOrNull { it.publishedAt.orEmpty() }
        return selected?.key?.let { "https://www.youtube.com/watch?v=$it" }
    }

    private suspend fun <T> optionalRequest(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
