package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import androidx.media3.common.MimeTypes
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.RidomoviesProvider
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

class RidooExtractor : Extractor() {

    override val name = "Ridoo"
    override val mainUrl = "https://ridoo.net"
    override val rotatingDomain = listOf(Regex("""(^|\.)ridorapid\.""", RegexOption.IGNORE_CASE))

    override suspend fun extract(link: String): Video {
        val embedUrl = link.toHttpUrlOrNull()
            ?: throw Exception("Invalid Ridoo embed URL")
        val embedOrigin = "${embedUrl.scheme}://${embedUrl.host}"

        if (embedUrl.host.contains("ridorapid", ignoreCase = true)) {
            return runCatching { extractRapidrame(link, embedOrigin) }
                .getOrElse {
                    BrowserStreamResolver.resolve(
                        link = link,
                        referer = RidomoviesProvider.URL,
                    ) { candidate ->
                        isHlsUrl(candidate)
                    }
                }
        }

        val document = Service.build(embedOrigin, RidomoviesProvider.URL).get(link)
        val m3u8Url = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            .find(document.toString())
            ?.groups?.get(1)?.value
            ?: throw Exception("Can't extract m3u8 URL from embed page")

        return Video(
            source = m3u8Url,
            headers = mediaHeaders(link, embedOrigin),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private suspend fun extractRapidrame(link: String, embedOrigin: String): Video {
        val document = Service.build(embedOrigin, RidomoviesProvider.URL).get(link)
        val script = document.select("script")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { it.contains("eval(") }
            ?: throw Exception("Rapidrame player script not found")

        val unpacker = JsUnpacker(script)
        val unpacked = if (unpacker.detect()) unpacker.unpack() ?: script else script
        val playlist = decodePlaylist(unpacked)
            ?: throw Exception("Rapidrame playlist could not be decoded")

        return Video(
            source = playlist,
            subtitles = parseCaptions(script, unpacked, embedOrigin),
            headers = mediaHeaders(link, embedOrigin),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private fun decodePlaylist(unpacked: String): String? {
        val partsBlock = Regex(
            """[\w_]+\s*=\s*[\w_]+\(\[(.*?)]\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(unpacked)?.groupValues?.getOrNull(1) ?: return null

        val encoded = Regex("""["']([^"']+)["']""")
            .findAll(partsBlock)
            .map { it.groupValues[1] }
            .joinToString("")
            .takeIf { it.isNotBlank() }
            ?: return null

        return runCatching { base64Rot13ReverseUnmix(encoded) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.toHttpUrlOrNull() != null }
    }

    private fun base64Rot13ReverseUnmix(value: String): String {
        var decoded = Base64.decode(value, Base64.DEFAULT)
        decoded.forEachIndexed { index, byte ->
            val char = (byte.toInt() and 0xFF).toChar()
            decoded[index] = when (char) {
                in 'A'..'Z' -> ('A'.code + (char.code - 'A'.code + 13) % 26).toByte()
                in 'a'..'z' -> ('a'.code + (char.code - 'a'.code + 13) % 26).toByte()
                else -> byte
            }
        }
        decoded = decoded.reversedArray()

        return buildString(decoded.size) {
            decoded.forEachIndexed { index, byte ->
                val valueAtIndex = byte.toInt() and 0xFF
                val adjustment = (399756995L % (index + 5)).toInt()
                append(((valueAtIndex - adjustment + 256) % 256).toChar())
            }
        }
    }

    private fun parseCaptions(
        script: String,
        unpacked: String,
        embedOrigin: String,
    ): List<Video.Subtitle> {
        val tracksJson = sequenceOf(script, unpacked)
            .mapNotNull { source ->
                Regex(
                    """tracks\s*:\s*(\[[^]]*]),""",
                    RegexOption.DOT_MATCHES_ALL,
                ).find(source)?.groupValues?.getOrNull(1)
            }
            .firstOrNull()
            ?: return emptyList()

        return runCatching {
            JsonParser.parseString(tracksJson).asJsonArray.mapNotNull { element ->
                val track = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val kind = track.get("kind")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (!kind.equals("captions", true) && !kind.equals("subtitles", true)) {
                    return@mapNotNull null
                }

                val file = track.get("file")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val language = track.get("language")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.trim().orEmpty()
                val label = track.get("label")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.trim().orEmpty().ifBlank { language }.ifBlank { "Subtitle" }

                Video.Subtitle(
                    label = label,
                    file = absolute(file, embedOrigin),
                    default = false,
                    initialDefault = false,
                )
            }.distinctBy { it.file }
        }.getOrDefault(emptyList())
    }

    private fun absolute(value: String, origin: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") || value.startsWith("https://") -> value
        value.startsWith("/") -> "$origin$value"
        else -> "$origin/${value.trimStart('/')}"
    }

    private fun mediaHeaders(link: String, embedOrigin: String) = mapOf(
        "Referer" to link,
        "Origin" to embedOrigin,
        "User-Agent" to NetworkClient.USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private fun isHlsUrl(value: String): Boolean {
        val lower = value.lowercase()
        if (
            lower.contains("test-videos") ||
            lower.contains("sample-videos") ||
            lower.contains("bigbuckbunny") ||
            lower.contains("cdn.plyr.io")
        ) return false
        return lower.substringBefore('?').substringBefore('#').endsWith(".m3u8")
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String, referer: String): Service {
                val client = NetworkClient.default.newBuilder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", NetworkClient.USER_AGENT)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .header("Referer", referer)
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                return Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): Document
    }
}
