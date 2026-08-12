package com.streamflixreborn.streamflix.utils

import android.net.Uri
import androidx.core.net.toUri
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

object SubDL {

    private const val URL = "https://api.subdl.com/api/v1/"
    private const val DOWNLOAD_BASE_URL = "https://dl.subdl.com"
    private val supportedSubtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "sub", "ttml")

    private val service = Service.build()

    suspend fun download(
        subtitle: Subtitle,
    ): Uri = withContext(Dispatchers.IO) {
        val path = subtitle.url
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("SubDL download link is missing")
        val downloadUrl = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "$DOWNLOAD_BASE_URL$path"
        }

        if (subtitle.directFile) {
            return@withContext downloadDirectFile(downloadUrl, subtitle)
        }

        val zip = File.createTempFile(
            "subdl-${subtitle.releaseName ?: "subtitle"}-",
            ".zip",
        )
        try {
            URL(downloadUrl).openStream().use { input ->
                FileOutputStream(zip).use { output -> input.copyTo(output) }
            }

            val extracted = mutableListOf<File>()
            ZipInputStream(zip.inputStream()).use { zipInputStream ->
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val extension = File(entry.name).extension.lowercase(Locale.ROOT)
                        if (extension in supportedSubtitleExtensions) {
                            val output = File.createTempFile("subdl-", ".$extension")
                            FileOutputStream(output).use { fileOutputStream ->
                                zipInputStream.copyTo(fileOutputStream)
                            }
                            extracted += output
                        }
                    }
                    zipInputStream.closeEntry()
                    entry = zipInputStream.nextEntry
                }
            }

            if (extracted.size != 1) {
                extracted.forEach(File::delete)
                throw IllegalStateException(
                    "SubDL archive contained ${extracted.size} subtitle files; an exact file could not be selected"
                )
            }

            extracted.single().toUri()
        } finally {
            zip.delete()
        }
    }

    private fun downloadDirectFile(downloadUrl: String, subtitle: Subtitle): Uri {
        val extension = subtitle.format
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in supportedSubtitleExtensions }
            ?: subtitle.name
                ?.let(::File)
                ?.extension
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it in supportedSubtitleExtensions }
            ?: throw IllegalArgumentException("SubDL subtitle format is missing")

        val file = File.createTempFile("subdl-", ".$extension")
        URL(downloadUrl).openStream().use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        return file.toUri()
    }

    suspend fun search(
        imdbId: String? = null,
        filmName: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        type: String? = null,
        year: Int? = null,
        subsPerPage: Int = 30,
    ): List<Subtitle> {
        if (UserPreferences.subdlApiKey.isEmpty()) {
            return emptyList()
        }

        val response = service.search(
            apiKey = UserPreferences.subdlApiKey,
            imdbId = normalizeImdbId(imdbId),
            filmName = filmName?.trim()?.takeIf { it.isNotBlank() },
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            type = type,
            year = year,
            subsPerPage = subsPerPage,
            hi = 1,
            unpack = 1,
            client = "custom_integration",
        )

        if (!response.status) {
            throw SubDLException(response.error ?: "SubDL search failed")
        }

        return response.subtitles
            .orEmpty()
            .flatMap { subtitle ->
                expandSubtitle(
                    subtitle = subtitle,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                )
            }
    }

    internal fun normalizeImdbId(imdbId: String?): String? {
        val value = imdbId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("tt", ignoreCase = true) &&
                value.drop(2).isNotBlank() &&
                value.drop(2).all(Char::isDigit) -> "tt${value.drop(2)}"
            value.all(Char::isDigit) -> "tt$value"
            else -> null
        }
    }

    internal fun expandSubtitle(
        subtitle: Subtitle,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): List<Subtitle> {
        val unpacked = subtitle.unpackFiles.orEmpty()
        if (unpacked.isNotEmpty()) {
            val matchingFiles = if (episodeNumber != null) {
                unpacked.filter { file ->
                    file.episode == episodeNumber &&
                        (seasonNumber == null || file.season == seasonNumber)
                }
            } else {
                unpacked
            }

            return matchingFiles.mapNotNull { file ->
                val url = file.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Subtitle(
                    releaseName = file.releaseName ?: subtitle.releaseName,
                    name = file.name ?: subtitle.name,
                    lang = file.language ?: subtitle.lang,
                    language = file.language ?: subtitle.language,
                    url = url,
                    season = file.season ?: subtitle.season,
                    episode = file.episode ?: subtitle.episode,
                    hi = file.hi ?: subtitle.hi,
                    format = file.format,
                    directFile = true,
                )
            }
        }

        if (episodeNumber != null && subtitle.fullSeason == true) {
            return emptyList()
        }

        return if (subtitle.url.isNullOrBlank()) emptyList() else listOf(subtitle)
    }

    class SubDLException(message: String) : Exception(message)

    private interface Service {

        companion object {
            fun build(): Service {
                val client = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val requestBuilder = chain.request().newBuilder()
                            .addHeader("Accept", "application/json")

                        chain.proceed(requestBuilder.build())
                    }
                    .build()

                return Retrofit.Builder()
                    .baseUrl(URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET("subtitles")
        suspend fun search(
            @Query("api_key") apiKey: String,
            @Query("imdb_id") imdbId: String? = null,
            @Query("film_name") filmName: String? = null,
            @Query("season_number") seasonNumber: Int? = null,
            @Query("episode_number") episodeNumber: Int? = null,
            @Query("type") type: String? = null,
            @Query("year") year: Int? = null,
            @Query("subs_per_page") subsPerPage: Int? = null,
            @Query("hi") hi: Int? = null,
            @Query("unpack") unpack: Int? = null,
            @Query("client") client: String? = null,
        ): SearchResponse
    }

    data class SearchResponse(
        @SerializedName("status") val status: Boolean = false,
        @SerializedName("subtitles") val subtitles: List<Subtitle>? = null,
        @SerializedName("error") val error: String? = null,
    )

    data class Subtitle(
        @SerializedName("release_name") val releaseName: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("lang") val lang: String? = null,
        @SerializedName("language") val language: String? = null,
        @SerializedName("url") val url: String? = null,
        @SerializedName("season") val season: Int? = null,
        @SerializedName("episode") val episode: Int? = null,
        @SerializedName("hi") val hi: Boolean? = null,
        @SerializedName("format") val format: String? = null,
        @SerializedName("full_season") val fullSeason: Boolean? = null,
        @SerializedName("unpack_files") val unpackFiles: List<UnpackedFile>? = null,
        val directFile: Boolean = false,
    ) {
        val languageTag: String?
            get() = SubtitleLanguage.normalize(lang ?: language)

        val displayLanguage: String
            get() = SubtitleLanguage.displayName(
                language = lang,
                explicitName = language?.takeUnless { it.length in 2..3 },
            )

        val displayLabel: String
            get() = if (hi == true) "$displayLanguage [HI]" else displayLanguage

        val displayRelease: String
            get() = releaseName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: name
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: "SubDL"
    }

    data class UnpackedFile(
        @SerializedName("file_n_id") val fileNId: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("release_name") val releaseName: String? = null,
        @SerializedName("season") val season: Int? = null,
        @SerializedName("episode") val episode: Int? = null,
        @SerializedName("language") val language: String? = null,
        @SerializedName("hi") val hi: Boolean? = null,
        @SerializedName("format") val format: String? = null,
        @SerializedName("size") val size: Long? = null,
        @SerializedName("md5") val md5: String? = null,
        @SerializedName("url") val url: String? = null,
    )
}
