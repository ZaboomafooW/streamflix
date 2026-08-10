package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.SubtitleDebugState
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Url
import java.util.Locale
import java.util.concurrent.TimeUnit

class VixcloudExtractor(
    private val preferredLanguage: String? = null,
    private var customReferer: String? = null
) : Extractor() {

    override val name = "vixcloud"
    override val mainUrl = "https://vixcloud.co/"

    companion object {
        private val client = NetworkClient.default.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        private val retrofitCache = mutableMapOf<String, VixcloudExtractorService>()
        private val FORCED_YES = Regex("""\bFORCED=YES\b""", RegexOption.IGNORE_CASE)
        private val FORCED_ATTRIBUTE = Regex("""\bFORCED=(?:YES|NO)\b""", RegexOption.IGNORE_CASE)
        private val LANGUAGE_ATTRIBUTE = Regex("""\bLANGUAGE="[^"]*"""", RegexOption.IGNORE_CASE)

        private fun getService(baseUrl: String): VixcloudExtractorService {
            return retrofitCache.getOrPut(baseUrl) {
                Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(VixcloudExtractorService::class.java)
            }
        }
    }

    private fun sanitizeJsonKeysAndQuotes(jsonLikeString: String): String {
        var temp = jsonLikeString
        temp = temp.replace("'", "\"")
        temp = Regex("""(\b(?:id|filename|token|expires|asn)\b)\s*:""").replace(temp) { matchResult ->
            "\"${matchResult.groupValues[1]}\":"
        }
        return temp
    }

    private fun removeTrailingCommaFromJsonObjectString(jsonString: String): String {
        val temp = jsonString.trim()
        val lastBraceIndex = temp.lastIndexOf('}')
        if (lastBraceIndex > 0 && temp.startsWith("{")) {
            var charIndexBeforeBrace = lastBraceIndex - 1
            while (charIndexBeforeBrace >= 0 && temp[charIndexBeforeBrace].isWhitespace()) {
                charIndexBeforeBrace--
            }
            if (charIndexBeforeBrace >= 0 && temp[charIndexBeforeBrace] == ',') {
                return temp.take(charIndexBeforeBrace) + temp.substring(charIndexBeforeBrace + 1)
            }
        }
        return jsonString
    }

    /**
     * Vixcloud sometimes exposes a Forced subtitle only through its NAME/LANGUAGE convention while
     * incorrectly advertising FORCED=NO, for example NAME="Italian [Forced]" and
     * LANGUAGE="forced-ita". Normalize only that Vixcloud-specific malformed metadata and leave
     * DEFAULT/AUTOSELECT untouched so Media3 and the player's preference policy own selection.
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
        Log.d("VixcloudDebug", "Extracting link: $link with preferredLanguage: $preferredLanguage")
        SubtitleDebugState.clear()

        val uri = link.toHttpUrlOrNull() ?: throw Exception("Invalid Vixcloud link")
        val currentMainUrl = "${uri.scheme}://${uri.host}/"
        val referer = customReferer ?: currentMainUrl

        val service = getService(currentMainUrl)
        val source = try {
            service.getSource(
                uri.encodedPath + if (uri.encodedQuery != null) "?" + uri.encodedQuery else "",
                referer = referer,
            )
        } catch (e: Exception) {
            Log.e("VixcloudDebug", "Failed to get source from $link: ${e.message}")
            throw e
        }

        val scriptText = source.body().selectFirst("script")?.data() ?: ""

        var videoJson = scriptText
            .substringAfter("window.video = ", "")
            .substringBefore(";", "")
            .trim()

        if (videoJson.isEmpty()) {
            videoJson = scriptText
                .substringAfter("window.video=", "")
                .substringBefore(";", "")
                .trim()
        }

        if (videoJson.isNotEmpty()) {
            videoJson = sanitizeJsonKeysAndQuotes(videoJson)
            videoJson = removeTrailingCommaFromJsonObjectString(videoJson)
            if (!videoJson.startsWith("{") && videoJson.contains(":")) videoJson = "{$videoJson"
            if (!videoJson.endsWith("}") && videoJson.contains(":")) videoJson = "$videoJson}"
        } else {
            Log.e("VixcloudDebug", "Could not find window.video in script")
        }

        val paramsObjectContent = scriptText
            .substringAfter("window.masterPlaylist", "")
            .substringAfter("params: {", "")
            .substringBefore("},", "")
            .trim()

        val tokenFallback = scriptText.substringAfter("token: \"", "").substringBefore("\"")
        val expiresFallback = scriptText.substringAfter("expires: \"", "").substringBefore("\"")

        val masterPlaylistJson: String
        if (paramsObjectContent.isNotEmpty()) {
            var processedParams = sanitizeJsonKeysAndQuotes(paramsObjectContent).trim()
            if (processedParams.endsWith(",")) {
                processedParams = processedParams.dropLast(1).trim()
            }
            masterPlaylistJson = "{$processedParams}"
        } else {
            masterPlaylistJson = "{}"
        }

        val hasBParam = scriptText
            .substringAfter("url:", "")
            .substringBefore(",", "")
            .contains("b=1")

        val gson = Gson()
        val windowVideo = gson.fromJson(videoJson, VixcloudExtractorService.WindowVideo::class.java)
        val masterPlaylist = gson.fromJson(masterPlaylistJson, VixcloudExtractorService.WindowParams::class.java)

        val masterParams = mutableMapOf<String, String>()
        if (masterPlaylist?.token != null) {
            masterParams["token"] = masterPlaylist.token
        } else if (tokenFallback.isNotEmpty()) {
            masterParams["token"] = tokenFallback
        }

        if (masterPlaylist?.expires != null) {
            masterParams["expires"] = masterPlaylist.expires
        } else if (expiresFallback.isNotEmpty()) {
            masterParams["expires"] = expiresFallback
        }

        val currentParams = link.split("&")
            .map { param -> param.split("=") }
            .filter { it.size == 2 }
            .associate { it[0] to it[1] }

        if (hasBParam) masterParams["b"] = "1"
        if (currentParams.containsKey("canPlayFHD")) masterParams["h"] = "1"

        // Keep Vixcloud's request-language parameter because it can affect which asset the server
        // returns. It is not authoritative track metadata and must not be used to select tracks.
        preferredLanguage?.let { masterParams["language"] = it }

        val baseUrl = "https://${uri.host}/playlist/${windowVideo.id}"
        val httpUrlBuilder = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Invalid base URL")
        masterParams.forEach { (key, value) -> httpUrlBuilder.addQueryParameter(key, value) }
        val finalUrl = httpUrlBuilder.build().toString()

        val finalHeaders = mutableMapOf(
            "Referer" to currentMainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
        )

        preferredLanguage?.let { lang ->
            finalHeaders["Accept-Language"] = if (lang == "en") "en-US,en;q=0.9" else "it-IT,it;q=0.9"
            finalHeaders["Cookie"] = "language=$lang"
        }

        var videoSource = finalUrl

        try {
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
                        source = "Vixcloud",
                        preferredLanguage = preferredLanguage,
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
            Log.e("VixcloudDebug", "Error normalizing playlist: ${e.message}")
        }

        return Video(
            source = videoSource,
            subtitles = emptyList(),
            type = MimeTypes.APPLICATION_M3U8,
            headers = finalHeaders,
        )
    }

    private interface VixcloudExtractorService {

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language: it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        suspend fun getSource(@Url url: String, @Header("Referer") referer: String): Document

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
