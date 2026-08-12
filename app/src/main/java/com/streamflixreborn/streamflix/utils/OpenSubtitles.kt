package com.streamflixreborn.streamflix.utils

import android.net.Uri
import androidx.core.net.toUri
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.GZIPInputStream

object OpenSubtitles {

    private const val URL = "https://rest.opensubtitles.org/"
    private const val HASH_CHUNK_SIZE = 64 * 1024
    private const val MIN_HASHABLE_FILE_SIZE = HASH_CHUNK_SIZE * 2L
    private const val MAX_HASHABLE_FILE_SIZE = 9_000_000_000L

    private val contentRangeRegex = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

    private val iso3ToIso2: Map<String, String> by lazy {
        Locale.getAvailableLocales()
            .asSequence()
            .filter { it.language.length == 2 }
            .mapNotNull { locale ->
                runCatching { locale.getISO3Language().lowercase(Locale.ROOT) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { it to locale.language.lowercase(Locale.ROOT) }
            }
            .toMap()
    }

    private val service = Service.build()

    suspend fun download(
        subtitle: Subtitle,
    ): Uri = withContext(Dispatchers.IO) {
        val zip = File.createTempFile(
            "${File(subtitle.subFileName).nameWithoutExtension}-",
            ".${File(subtitle.subDownloadLink).extension}"
        )

        URL(subtitle.subDownloadLink).openStream().use { input ->
            FileOutputStream(zip).use { output -> input.copyTo(output) }
        }

        val subtitleFile = File("${zip.parent}${File.separator}${subtitle.subFileName}")

        if (subtitleFile.exists()) {
            subtitleFile.delete()
        }

        FileInputStream(zip).use { fileInputStream ->
            GZIPInputStream(fileInputStream).use { gzipInputStream ->
                // Writing to file using source charset and UTF_8 output
                val sourceCharset = getCharsetFromEncoding(subtitle.subEncoding)
                val reader = gzipInputStream.bufferedReader(sourceCharset)
                subtitleFile.writer(Charsets.UTF_8).use { writer ->
                    reader.copyTo(writer)
                }
            }
        }

        zip.delete()

        subtitleFile.toUri()
    }

    suspend fun search(
        imdbId: String? = null,
        query: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subLanguageId: String? = null,
        movieHash: String? = null,
        movieByteSize: Long? = null,
    ): List<Subtitle> {
        val params = mapOf(
            Params.Key.EPISODE to episode?.toString(),
            Params.Key.QUERY to query?.lowercase(),
            Params.Key.SEASON to season?.toString(),
            Params.Key.IMDB_ID to imdbId,
            Params.Key.SUB_LANGUAGE_ID to subLanguageId,
            Params.Key.MOVIE_HASH to movieHash,
            Params.Key.MOVIE_BYTE_SIZE to movieByteSize?.toString(),
        )
        return service.search(
            params = params
                .filterNotNullValues()
                .map { "${it.key}-${it.value}" }
                .joinToString("/")
        )
    }

    data class VideoFingerprint(
        val movieHash: String,
        val movieByteSize: Long,
    )

    suspend fun fingerprintRemoteVideo(
        source: String,
        headers: Map<String, String>? = null,
    ): VideoFingerprint? = withContext(Dispatchers.IO) {
        if (!source.startsWith("http://", ignoreCase = true) &&
            !source.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext null
        }

        val firstRange = readRange(
            source = source,
            start = 0L,
            end = HASH_CHUNK_SIZE - 1L,
            headers = headers,
        ) ?: return@withContext null

        if (firstRange.start != 0L || firstRange.end != HASH_CHUNK_SIZE - 1L) {
            return@withContext null
        }

        val movieByteSize = firstRange.totalSize
        if (movieByteSize < MIN_HASHABLE_FILE_SIZE || movieByteSize >= MAX_HASHABLE_FILE_SIZE) {
            return@withContext null
        }

        val lastStart = movieByteSize - HASH_CHUNK_SIZE
        val lastRange = readRange(
            source = source,
            start = lastStart,
            end = movieByteSize - 1L,
            headers = headers,
        ) ?: return@withContext null

        if (lastRange.start != lastStart ||
            lastRange.end != movieByteSize - 1L ||
            lastRange.totalSize != movieByteSize
        ) {
            return@withContext null
        }

        val movieHash = computeMovieHash(
            movieByteSize = movieByteSize,
            firstChunk = firstRange.bytes,
            lastChunk = lastRange.bytes,
        ) ?: return@withContext null

        VideoFingerprint(
            movieHash = movieHash,
            movieByteSize = movieByteSize,
        )
    }

    internal fun computeMovieHash(
        movieByteSize: Long,
        firstChunk: ByteArray,
        lastChunk: ByteArray,
    ): String? {
        if (firstChunk.size != HASH_CHUNK_SIZE || lastChunk.size != HASH_CHUNK_SIZE) {
            return null
        }

        var hash = movieByteSize
        listOf(firstChunk, lastChunk).forEach { chunk ->
            val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= Long.SIZE_BYTES) {
                hash += buffer.long
            }
        }

        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }

    fun exactForcedMatches(
        subtitles: List<Subtitle>,
        fingerprint: VideoFingerprint,
    ): List<Subtitle> = subtitles.filter { subtitle ->
        subtitle.isForced &&
            subtitle.subBad?.trim() != "1" &&
            subtitle.subDownloadLink.isNotBlank() &&
            subtitle.movieHash?.equals(fingerprint.movieHash, ignoreCase = true) == true &&
            subtitle.movieByteSize?.toLongOrNull() == fingerprint.movieByteSize
    }

    fun selectExactForcedSubtitle(
        subtitles: List<Subtitle>,
        fingerprint: VideoFingerprint,
        audioLanguage: String?,
    ): Subtitle? {
        val normalizedAudioLanguage = normalizeLanguageCode(audioLanguage) ?: return null

        return exactForcedMatches(subtitles, fingerprint)
            .filter { normalizeLanguageCode(it.languageTag ?: it.subLanguageID) == normalizedAudioLanguage }
            .sortedWith(
                compareByDescending<Subtitle> { it.subFromTrusted?.trim() == "1" }
                    .thenByDescending { it.subRating?.toDoubleOrNull() ?: 0.0 }
                    .thenByDescending { it.subDownloadsCnt?.toLongOrNull() ?: 0L }
            )
            .firstOrNull()
    }

    fun languagesMatch(first: String?, second: String?): Boolean {
        val firstLanguage = normalizeLanguageCode(first) ?: return false
        val secondLanguage = normalizeLanguageCode(second) ?: return false
        return firstLanguage == secondLanguage
    }

    fun normalizeLanguageCode(language: String?): String? {
        val raw = language
            ?.trim()
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?: return null

        return when (raw.length) {
            2 -> raw
            3 -> iso3ToIso2[raw] ?: raw
            else -> null
        }
    }

    private data class RangeResult(
        val bytes: ByteArray,
        val start: Long,
        val end: Long,
        val totalSize: Long,
    )

    private fun readRange(
        source: String,
        start: Long,
        end: Long,
        headers: Map<String, String>?,
    ): RangeResult? {
        val request = runCatching {
            Request.Builder()
                .url(source)
                .apply {
                    headers.orEmpty().forEach { (name, value) ->
                        if (!name.equals("Range", ignoreCase = true) &&
                            !name.equals("Accept-Encoding", ignoreCase = true)
                        ) {
                            header(name, value)
                        }
                    }
                }
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$start-$end")
                .build()
        }.getOrNull() ?: return null

        return runCatching {
            NetworkClient.default.newCall(request).execute().use { response ->
                if (response.code != 206) return@use null

                val contentRange = response.header("Content-Range") ?: return@use null
                val match = contentRangeRegex.matchEntire(contentRange.trim()) ?: return@use null
                val actualStart = match.groupValues[1].toLongOrNull() ?: return@use null
                val actualEnd = match.groupValues[2].toLongOrNull() ?: return@use null
                val totalSize = match.groupValues[3].toLongOrNull() ?: return@use null
                val bytes = response.body?.bytes() ?: return@use null

                if (bytes.size.toLong() != actualEnd - actualStart + 1L) {
                    return@use null
                }

                RangeResult(
                    bytes = bytes,
                    start = actualStart,
                    end = actualEnd,
                    totalSize = totalSize,
                )
            }
        }.getOrNull()
    }

    // Function to get charset from opensubtitles metadata
    private fun getCharsetFromEncoding(encoding: String?): java.nio.charset.Charset {
        if (encoding.isNullOrBlank()) return Charsets.UTF_8 // Default fallback

        return try {
            when (encoding.uppercase()) {
                "CP1256", "WINDOWS-1256" -> java.nio.charset.Charset.forName("Windows-1256") // Arabic
                "CP1251", "WINDOWS-1251" -> java.nio.charset.Charset.forName("Windows-1251") // Cyrillic / Russian
                "CP1252", "WINDOWS-1252", "ISO-8859-1" -> java.nio.charset.Charset.forName("Windows-1252") // Western European
                "CP1254", "WINDOWS-1254" -> java.nio.charset.Charset.forName("Windows-1254") // Turkish
                "CP1253", "WINDOWS-1253" -> java.nio.charset.Charset.forName("Windows-1253") // Greek
                "UTF-8" -> Charsets.UTF_8
                else -> java.nio.charset.Charset.forName(encoding) // Try loading dynamically
            }
        } catch (e: Exception) {
            Charsets.UTF_8 // Fallback to UTF-8 if the charset name is unresolvable
        }
    }

    object Params {

        object Key {
            const val IMDB_ID = "imdbid"
            const val QUERY = "query"
            const val EPISODE = "episode"
            const val SEASON = "season"
            const val SUB_LANGUAGE_ID = "sublanguageid"
            const val MOVIE_HASH = "moviehash"
            const val MOVIE_BYTE_SIZE = "moviebytesize"
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

                val retrofit = Retrofit.Builder()
                    .baseUrl(URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                return retrofit.create(Service::class.java)
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
        @SerializedName("Score") val score: Double? = null
    ) {
        val isForced: Boolean
            get() = subForeignPartsOnly?.trim() == "1"

        val languageTag: String?
            get() {
                val rawTag = iso639?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val normalized = Locale.forLanguageTag(rawTag.replace('_', '-')).toLanguageTag()
                return normalized.takeUnless { it.equals("und", ignoreCase = true) }
            }

        val displayLanguage: String
            get() {
                languageName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

                val tag = languageTag ?: return "Unknown"
                val displayName = Locale.forLanguageTag(tag)
                    .getDisplayLanguage(Locale.ENGLISH)
                    .trim()

                return displayName.takeIf { it.isNotEmpty() } ?: tag
            }

        val displayLabel: String
            get() = if (isForced) "$displayLanguage (Forced)" else displayLanguage
    }
}
