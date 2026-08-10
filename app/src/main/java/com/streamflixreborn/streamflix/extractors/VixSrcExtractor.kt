package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.SubtitleDebugState
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.util.Locale
import java.util.concurrent.TimeUnit

class VixSrcExtractor : Extractor() {

    override val name = "VixSrc"
    override val mainUrl = "https://vixsrc.to"

    companion object {
        private val FORCED_YES = Regex("""\bFORCED=YES\b""", RegexOption.IGNORE_CASE)
        private val FORCED_ATTRIBUTE = Regex("""\bFORCED=(?:YES|NO)\b""", RegexOption.IGNORE_CASE)
        private val LANGUAGE_ATTRIBUTE = Regex("""\bLANGUAGE="[^"]*"""", RegexOption.IGNORE_CASE)
    }

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Episode -> "$mainUrl/api/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
                is Video.Type.Movie -> "$mainUrl/api/movie/${videoType.id}"
            },
        )
    }

    /**
     * VixSrc uses the same malformed Forced-subtitle convention seen on Vixcloud: a track can be
     * named Forced or use LANGUAGE="forced-ita" while FORCED remains NO. Repair only that source
     * metadata. Provider language still goes to VixSrc as a request parameter because it may affect
     * the returned asset, but it must not be used to rewrite track defaults or choose a track.
     */
    private fun normalizeForcedSubtitleLine(line: String): String {
        if (!line.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES")) return line

        val name = hlsQuotedAttribute(line, "NAME")
        val rawLanguage = hlsQuotedAttribute(line, "LANGUAGE")
        val forced = FORCED_YES.containsMatchIn(line) ||
            name?.contains("forced", ignoreCase = true) == true ||
            rawLanguage?.startsWith("forced-", ignoreCase = true) == true

        if (!forced) return line

        var normalized = if (FORCED_ATTRIBUTE.containsMatchIn(line)) {
            FORCED_ATTRIBUTE.replace(line, "FORCED=YES")
        } else {
            "$line,FORCED=YES"
        }

        rawLanguage
            ?.takeIf { it.startsWith("forced-", ignoreCase = true) }
            ?.let(::canonicalVixLanguage)
            ?.let { language ->
                normalized = LANGUAGE_ATTRIBUTE.replace(normalized, "LANGUAGE=\"$language\"")
            }

        return normalized
    }

    private fun hlsQuotedAttribute(line: String, attribute: String): String? =
        Regex("""\b${Regex.escape(attribute)}="([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(line)
            ?.groupValues
            ?.getOrNull(1)

    private fun canonicalVixLanguage(value: String): String? {
        val primary = value
            .trim()
            .lowercase(Locale.ROOT)
            .removePrefix("forced-")
            .substringBefore('-')
            .takeIf { it.isNotBlank() }
            ?: return null

        return Locale.getISOLanguages().firstOrNull { languageCode ->
            languageCode.equals(primary, ignoreCase = true) ||
                runCatching {
                    Locale.forLanguageTag(languageCode).isO3Language.equals(primary, ignoreCase = true)
                }.getOrDefault(false)
        }
    }

    override suspend fun extract(link: String): Video {
        SubtitleDebugState.clear()

        val service = VixSrcExtractorService.build(mainUrl)
        val providerLang = UserPreferences.currentProvider?.language ?: "en"

        var apiPath = link.substringAfter(mainUrl).trimStart('/')
        if (!apiPath.startsWith("api/")) {
            apiPath = "api/$apiPath"
        }

        if (!apiPath.contains("lang=")) {
            val separator = if (apiPath.contains("?")) "&" else "?"
            apiPath += "${separator}lang=$providerLang"
        }

        Log.i("VixSrcDebug", "Calling API: $mainUrl/$apiPath")
        val apiResponse = try {
            service.getSourceApi(apiPath)
        } catch (e: Exception) {
            Log.e("VixSrcDebug", "API call failed: ${e.message}")
            throw e
        }

        var currentEmbedPath = apiResponse.src.trimStart('/')
        Log.i("VixSrcDebug", "Embed path from API: $currentEmbedPath")

        val source = try {
            service.getSource(currentEmbedPath)
        } catch (e: Exception) {
            val isGone = (e as? retrofit2.HttpException)?.code() == 410 ||
                e.message?.contains("410") == true
            if (isGone) {
                Log.w("VixSrcDebug", "410 Gone detected, retrying API call...")
                val retryApiResponse = service.getSourceApi(apiPath)
                currentEmbedPath = retryApiResponse.src.trimStart('/')
                service.getSource(currentEmbedPath)
            } else {
                throw e
            }
        }
        val scriptText = source.body().selectFirst("script")?.data() ?: ""

        val videoId = scriptText
            .substringAfter("window.video = {", "")
            .substringAfter("id: '", "")
            .substringBefore("',", "")
            .trim()

        val token = scriptText
            .substringAfter("window.masterPlaylist", "")
            .substringAfter("'token': '", "")
            .substringBefore("',", "")
            .trim()

        val expires = scriptText
            .substringAfter("window.masterPlaylist", "")
            .substringAfter("'expires': '", "")
            .substringBefore("',", "")
            .trim()

        val hasBParam = scriptText
            .substringAfter("url:", "")
            .substringBefore(",", "")
            .contains("b=1")

        val canPlayFHD = scriptText.contains("window.canPlayFHD = true")

        val masterParams = mutableMapOf<String, String>()
        masterParams["token"] = token
        masterParams["expires"] = expires
        if (hasBParam) masterParams["b"] = "1"
        if (canPlayFHD) masterParams["h"] = "1"
        masterParams["lang"] = providerLang

        val baseUrl = "https://vixsrc.to/playlist/$videoId"
        val httpUrlBuilder = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Invalid base URL")
        masterParams.forEach { (key, value) -> httpUrlBuilder.addQueryParameter(key, value) }
        val finalUrl = httpUrlBuilder.build().toString()

        val finalHeaders = mutableMapOf(
            "Referer" to "$mainUrl/$currentEmbedPath",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        )

        var videoSource = finalUrl

        try {
            val client = OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val headersBuilder = okhttp3.Headers.Builder()
            finalHeaders.forEach { (key, value) -> headersBuilder.add(key, value) }
            val request = Request.Builder().url(finalUrl).headers(headersBuilder.build()).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    val playlistContent = response.body!!.string()
                    val baseUri = response.request.url
                    val lines = playlistContent.lines()
                    val rawAudioLines = lines.filter { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") }
                    val rawSubtitleLines = lines.filter { it.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES") }
                    val uriRegex = """URI=["']([^"']+)["']""".toRegex()

                    val finalLines = lines.map { line ->
                        var patchedLine = line

                        if (line.startsWith("#")) {
                            patchedLine = uriRegex.replace(line) { matchResult ->
                                val relative = matchResult.groupValues[1]
                                if (relative.startsWith("http") || relative.startsWith("data:")) {
                                    matchResult.value
                                } else {
                                    "URI=\"${baseUri.resolve(relative) ?: relative}\""
                                }
                            }
                        } else if (line.isNotBlank()) {
                            patchedLine = baseUri.resolve(line)?.toString() ?: line
                        }

                        normalizeForcedSubtitleLine(patchedLine)
                    }

                    SubtitleDebugState.update(
                        source = "VixSrc",
                        preferredLanguage = providerLang,
                        rawAudioLines = rawAudioLines,
                        rawSubtitleLines = rawSubtitleLines,
                        patchedAudioLines = finalLines.filter { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") },
                        patchedSubtitleLines = finalLines.filter { it.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES") },
                    )

                    val base64Manifest = Base64.encodeToString(
                        finalLines.joinToString("\n").toByteArray(),
                        Base64.NO_WRAP,
                    )
                    videoSource = "data:application/vnd.apple.mpegurl;base64,$base64Manifest"
                }
            }
        } catch (e: Exception) {
            Log.e("VixSrcDebug", "Error normalizing playlist: ${e.message}")
        }

        return Video(
            source = videoSource,
            subtitles = emptyList(),
            type = MimeTypes.APPLICATION_M3U8,
            headers = finalHeaders,
        )
    }

    private interface VixSrcExtractorService {
        companion object {
            fun build(baseUrl: String): VixSrcExtractorService {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Referer", baseUrl)
                            .build()
                        chain.proceed(request)
                    }
                    .build()
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(VixSrcExtractorService::class.java)
            }
        }

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: application/json, text/plain, */*",
            "X-Requested-With: XMLHttpRequest"
        )
        suspend fun getSourceApi(@Url url: String): VixSrcApiResponse

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
            "X-Requested-With: XMLHttpRequest"
        )
        suspend fun getSource(@Url url: String): Document

        data class VixSrcApiResponse(val src: String)

        data class WindowVideo(
            @SerializedName("id") val id: Int,
            @SerializedName("filename") val filename: String
        )

        data class WindowParams(
            @SerializedName("token") val token: String?,
            @SerializedName("expires") val expires: String?
        )
    }
}
