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
import retrofit2.http.Path
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream

object OpenSubtitles {

    private const val URL = "https://rest.opensubtitles.org/"

    private val service = Service.build()

    suspend fun download(
        subtitle: Subtitle,
    ): Uri = withContext(Dispatchers.IO) {
        if (subtitle.subDownloadLink.isBlank()) {
            throw IllegalArgumentException("OpenSubtitles download link is missing")
        }

        val sourceFileName = subtitle.sourceFileName
            ?: subtitle.subFileName
            ?: throw IllegalArgumentException("OpenSubtitles file name is missing")
        val extension = File(sourceFileName).extension
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
            ?: subtitle.subFormat
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("OpenSubtitles subtitle format is missing")

        val zip = File.createTempFile(
            "${File(sourceFileName).nameWithoutExtension}-",
            ".${File(subtitle.subDownloadLink).extension}"
        )

        URL(subtitle.subDownloadLink).openStream().use { input ->
            FileOutputStream(zip).use { output -> input.copyTo(output) }
        }

        val outputDirectory = File.createTempFile("streamflix-subtitle-", "").let { tempFile ->
            if (!tempFile.delete() || !tempFile.mkdir()) {
                throw IllegalStateException("Unable to create subtitle output directory")
            }
            tempFile
        }
        val outputLabel = if (subtitle.sourceFileName != null) {
            subtitle.subFileName?.trim()?.takeIf { it.isNotBlank() } ?: subtitle.displayLabel
        } else {
            subtitle.displayLabel
        }
        val safeOutputLabel = outputLabel
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
            .ifBlank { "subtitle" }
        val subtitleFile = File(outputDirectory, "$safeOutputLabel.$extension")

        try {
            FileInputStream(zip).use { fileInputStream ->
                GZIPInputStream(fileInputStream).use { gzipInputStream ->
                    val sourceCharset = getCharsetFromEncoding(subtitle.subEncoding)
                    val reader = gzipInputStream.bufferedReader(sourceCharset)
                    subtitleFile.writer(Charsets.UTF_8).use { writer ->
                        reader.copyTo(writer)
                    }
                }
            }
        } finally {
            zip.delete()
        }

        subtitleFile.toUri()
    }

    suspend fun search(
        imdbId: String? = null,
        query: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subLanguageId: String? = null,
    ): List<Subtitle> {
        val params = listOfNotNull(
            episode?.let { Params.Key.EPISODE to it.toString() },
            normalizeImdbId(imdbId)?.let { Params.Key.IMDB_ID to it },
            query
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.ROOT)
                ?.let(::encodePathValue)
                ?.let { Params.Key.QUERY to it },
            season?.let { Params.Key.SEASON to it.toString() },
            subLanguageId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.ROOT)
                ?.let { Params.Key.SUB_LANGUAGE_ID to it },
        ).sortedBy { it.first }

        if (params.isEmpty()) return emptyList()

        return service.search(
            params = params.joinToString("/") { (key, value) -> "$key-$value" }
        )
    }

    internal fun normalizeImdbId(imdbId: String?): String? {
        val digits = imdbId
            ?.trim()
            ?.removePrefix("tt")
            ?.removePrefix("TT")
            ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: return null

        return digits.toLongOrNull()
            ?.toString()
            ?.padStart(7, '0')
    }

    internal fun displayResults(subtitles: List<Subtitle>): List<Subtitle> {
        val visible = subtitles.filterNot(Subtitle::isForced)
        val displayNames = PlaybackTrackDisplayNames.disambiguate(
            visible.map(Subtitle::displayLabel),
        )
        return visible.zip(displayNames).map { (subtitle, displayName) ->
            subtitle.copy(
                subFileName = displayName,
                sourceFileName = subtitle.sourceFileName ?: subtitle.subFileName,
            )
        }
    }

    internal fun uniqueForcedForLanguage(
        subtitles: List<Subtitle>,
        language: String,
    ): Subtitle? {
        val requestedLanguage = primaryLanguage(language) ?: return null
        return subtitles
            .asSequence()
            .filter(Subtitle::isForced)
            .filter { subtitle -> primaryLanguage(subtitle.languageTag) == requestedLanguage }
            .filter { subtitle -> subtitle.subDownloadLink.isNotBlank() }
            .distinctBy { subtitle -> subtitle.stableIdentity }
            .toList()
            .singleOrNull()
    }

    private fun primaryLanguage(language: String?): String? =
        SubtitleLanguage.normalize(language)?.substringBefore('-')

    private fun encodePathValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

    private fun getCharsetFromEncoding(encoding: String?): java.nio.charset.Charset {
        if (encoding.isNullOrBlank()) return Charsets.UTF_8

        return try {
            when (encoding.uppercase()) {
                "CP1256", "WINDOWS-1256" -> java.nio.charset.Charset.forName("Windows-1256")
                "CP1251", "WINDOWS-1251" -> java.nio.charset.Charset.forName("Windows-1251")
                "CP1252", "WINDOWS-1252", "ISO-8859-1" -> java.nio.charset.Charset.forName("Windows-1252")
                "CP1254", "WINDOWS-1254" -> java.nio.charset.Charset.forName("Windows-1254")
                "CP1253", "WINDOWS-1253" -> java.nio.charset.Charset.forName("Windows-1253")
                "UTF-8" -> Charsets.UTF_8
                else -> java.nio.charset.Charset.forName(encoding)
            }
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    object Params {
        object Key {
            const val IMDB_ID = "imdbid"
            const val QUERY = "query"
            const val EPISODE = "episode"
            const val SEASON = "season"
            const val SUB_LANGUAGE_ID = "sublanguageid"
        }
    }

    private interface Service {

        companion object {
            fun build(): Service {
                val client = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val requestBuilder = chain.request().newBuilder()
                            .addHeader("User-Agent", "TemporaryUserAgent")

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

        @GET("search/{params}")
        suspend fun search(
            @Path("params", encoded = true) params: String,
        ): List<Subtitle>
    }

    data class Subtitle(
        @SerializedName("MatchedBy") val matchedBy: String? = null,
        @SerializedName("IDSubMovieFile") val idSubMovieFile: String? = null,
        @SerializedName("MovieHash") val movieHash: String? = null,
        @SerializedName("MovieByteSize") val movieByteSize: String? = null,
        @SerializedName("MovieTimeMS") val movieTimeMS: String? = null,
        @SerializedName("IDSubtitleFile") val idSubtitleFile: String? = null,
        @SerializedName("SubFileName") val subFileName: String? = null,
        @SerializedName("SubActualCD") val subActualCD: String? = null,
        @SerializedName("SubSize") val subSize: String? = null,
        @SerializedName("SubHash") val subHash: String? = null,
        @SerializedName("SubLastTS") val subLastTS: String? = null,
        @SerializedName("SubTSGroup") val subTSGroup: String? = null,
        @SerializedName("InfoReleaseGroup") val infoReleaseGroup: String? = null,
        @SerializedName("InfoFormat") val infoFormat: String? = null,
        @SerializedName("InfoOther") val infoOther: String? = null,
        @SerializedName("IDSubtitle") val idSubtitle: String? = null,
        @SerializedName("UserID") val userID: String? = null,
        @SerializedName("SubLanguageID") val subLanguageID: String? = null,
        @SerializedName("SubFormat") val subFormat: String? = null,
        @SerializedName("SubSumCD") val subSumCD: String? = null,
        @SerializedName("SubAuthorComment") val subAuthorComment: String? = null,
        @SerializedName("SubAddDate") val subAddDate: String? = null,
        @SerializedName("SubBad") val subBad: String? = null,
        @SerializedName("SubRating") val subRating: String? = null,
        @SerializedName("SubSumVotes") val subSumVotes: String? = null,
        @SerializedName("SubDownloadsCnt") val subDownloadsCnt: String? = null,
        @SerializedName("MovieReleaseName") val movieReleaseName: String? = null,
        @SerializedName("MovieFPS") val movieFPS: String? = null,
        @SerializedName("IDMovie") val idMovie: String? = null,
        @SerializedName("IDMovieImdb") val idMovieImdb: String? = null,
        @SerializedName("MovieName") val movieName: String? = null,
        @SerializedName("MovieNameEng") val movieNameEng: String? = null,
        @SerializedName("MovieYear") val movieYear: String? = null,
        @SerializedName("MovieImdbRating") val movieImdbRating: String? = null,
        @SerializedName("SubFeatured") val subFeatured: String? = null,
        @SerializedName("UserNickName") val userNickName: String? = null,
        @SerializedName("SubTranslator") val subTranslator: String? = null,
        @SerializedName("ISO639") val iso639: String? = null,
        @SerializedName("LanguageName") val languageName: String? = null,
        @SerializedName("SubComments") val subComments: String? = null,
        @SerializedName("SubHearingImpaired") val subHearingImpaired: String? = null,
        @SerializedName("UserRank") val userRank: String? = null,
        @SerializedName("SeriesSeason") val seriesSeason: String? = null,
        @SerializedName("SeriesEpisode") val seriesEpisode: String? = null,
        @SerializedName("MovieKind") val movieKind: String? = null,
        @SerializedName("SubHD") val subHD: String? = null,
        @SerializedName("SeriesIMDBParent") val seriesIMDBParent: String? = null,
        @SerializedName("SubEncoding") val subEncoding: String? = null,
        @SerializedName("SubAutoTranslation") val subAutoTranslation: String? = null,
        @SerializedName("SubForeignPartsOnly") val subForeignPartsOnly: String? = null,
        @SerializedName("SubFromTrusted") val subFromTrusted: String? = null,
        @SerializedName("QueryCached") val queryCached: Int? = null,
        @SerializedName("SubDownloadLink") val subDownloadLink: String = "",
        @SerializedName("ZipDownloadLink") val zipDownloadLink: String? = null,
        @SerializedName("SubtitlesLink") val subtitlesLink: String? = null,
        @SerializedName("QueryNumber") val queryNumber: String? = null,
        @SerializedName("Score") val score: Double? = null,
        @Transient val sourceFileName: String? = null,
    ) {
        val isForced: Boolean
            get() = subForeignPartsOnly?.trim() == "1"

        val isHearingImpaired: Boolean
            get() = subHearingImpaired?.trim() == "1"

        val languageTag: String?
            get() = SubtitleLanguage.normalize(iso639 ?: subLanguageID)

        val displayLanguage: String
            get() = SubtitleLanguage.displayName(
                language = iso639 ?: subLanguageID,
                explicitName = languageName,
            )

        val displayLabel: String
            get() = buildString {
                append(displayLanguage)
                if (isForced) append(" (Forced)")
                if (isHearingImpaired) append(" [HI]")
            }

        val displayRelease: String
            get() = movieReleaseName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: sourceFileName
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: subFileName
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: "OpenSubtitles"

        internal val stableIdentity: String
            get() = idSubtitleFile
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: subDownloadLink.trim()
    }
}
