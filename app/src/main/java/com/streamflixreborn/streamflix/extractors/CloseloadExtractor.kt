package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.RidomoviesProvider
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.util.Locale

class CloseloadExtractor : Extractor() {

    override val name = "Closeload"
    override val mainUrl = "https://closeload.top/"

    override suspend fun extract(link: String): Video {
        extractStaticSource(link)?.let { candidate ->
            resolveStaticCandidate(candidate, link)?.let { return it }
        }

        return BrowserStreamResolver.resolve(
            link = link,
            referer = RidomoviesProvider.URL,
            timeoutMs = 15_000L,
        ) { candidate ->
            isPlayableMediaUrl(candidate)
        }
    }

    private suspend fun resolveStaticCandidate(source: String, link: String): Video? {
        if (isPlayableMediaUrl(source)) return video(source, link)
        if (!source.contains("master.txt", ignoreCase = true)) return null

        val embedUrl = link.toHttpUrlOrNull()
        val origin = embedUrl?.let { "${it.scheme}://${it.host}" } ?: mainUrl.trimEnd('/')
        val body = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(source)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .header("Referer", "$origin/")
                .header("Origin", origin)
                .build()
            runCatching {
                NetworkClient.default.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrNull()
        }?.trim().orEmpty()

        if (body.startsWith("#EXTM3U")) {
            return Video(
                source = source,
                headers = mediaHeaders(link),
                type = MimeTypes.APPLICATION_M3U8,
            )
        }

        val direct = body.lineSequence()
            .map(String::trim)
            .firstOrNull(::isPlayableMediaUrl)
        return direct?.let { video(it, link) }
    }

    private suspend fun extractStaticSource(link: String): String? = runCatching {
        val document = Service.build(mainUrl).get(link, RidomoviesProvider.URL)
        val html = document.toString()
        val unpacker = JsUnpacker(html)
        val unpacked = if (unpacker.detect()) unpacker.unpack() ?: html else html

        var magicNum = 399756995L
        var offset = 5
        Regex("""(\d+)\s*%\s*\(\s*i\s*\+\s*(\d+)\s*\)""")
            .find(unpacked)
            ?.let { match ->
                magicNum = match.groupValues[1].toLong()
                offset = match.groupValues[2].toInt()
            }

        val inputs = mutableListOf<String>()
        Regex("""myPlayer\.src\(\{\s*src:\s*(\w+)\s*,""")
            .find(unpacked)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { varName ->
                Regex("""var\s+$varName\s*=\s*dc_hello\("([^"]+)"\)""")
                    .find(unpacked)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(inputs::add)
            }

        Regex("""\[\s*((?:"[^"]+",?\s*)+)\]""").findAll(unpacked).forEach { match ->
            val parts = Regex("\"([^\"]+)\"")
                .findAll(match.groupValues[1])
                .map { it.groupValues[1] }
                .toList()
            if (parts.size > 5) inputs.add(parts.joinToString(""))
        }

        Regex("""\(\s*"([a-zA-Z0-9+/=]{30,})"\s*\)""").findAll(unpacked).forEach { match ->
            inputs.add(match.groupValues[1])
        }

        inputs.firstNotNullOfOrNull { smartBruteForce(it, magicNum, offset) }
            ?: Regex("[\"'](aHR0[a-zA-Z0-9+/=]{20,})[\"']")
                .findAll(unpacked)
                .mapNotNull { safeBase64Decode(it.groupValues[1]) }
                .map { String(it, Charsets.UTF_8).trim() }
                .firstOrNull(::isPotentialStaticUrl)
    }.getOrNull()

    private fun smartBruteForce(inputData: String, magicNum: Long, offset: Int): String? {
        val stringTransforms = listOf<(String) -> String>(
            { it },
            { it.reversed() },
            { rot13(it) },
            { rot13(it.reversed()) },
            { rot13(it).reversed() },
        )
        val byteTransforms = listOf<(ByteArray) -> ByteArray>(
            { it },
            { it.reversedArray() },
            { rot13Bytes(it) },
            { rot13Bytes(it.reversedArray()) },
            { rot13Bytes(it).reversedArray() },
        )

        for (stringTransform in stringTransforms) {
            for (byteTransform in byteTransforms) {
                val firstDecode = safeBase64Decode(stringTransform(inputData)) ?: continue
                val candidates = mutableListOf(firstDecode)
                val firstDecodeString = String(firstDecode, Charsets.ISO_8859_1)
                safeBase64Decode(firstDecodeString)?.let(candidates::add)
                safeBase64Decode(firstDecodeString.reversed())?.let(candidates::add)

                for (candidate in candidates) {
                    val transformed = byteTransform(candidate)
                    runCatching { String(unmixLoop(transformed, magicNum, offset), Charsets.UTF_8).trim() }
                        .getOrNull()
                        ?.takeIf(::isPotentialStaticUrl)
                        ?.let { return it }
                    runCatching { String(transformed, Charsets.UTF_8).trim() }
                        .getOrNull()
                        ?.takeIf(::isPotentialStaticUrl)
                        ?.let { return it }
                }
            }
        }
        return null
    }

    private fun video(source: String, link: String): Video {
        val path = source.substringBefore('?').substringBefore('#')
        val type = if (path.endsWith(".mp4", ignoreCase = true)) {
            MimeTypes.VIDEO_MP4
        } else {
            MimeTypes.APPLICATION_M3U8
        }
        return Video(
            source = source,
            headers = mediaHeaders(link),
            type = type,
        )
    }

    private fun mediaHeaders(link: String): Map<String, String> {
        val embedUrl = link.toHttpUrlOrNull()
        val origin = embedUrl?.let { "${it.scheme}://${it.host}" } ?: mainUrl.trimEnd('/')
        return mapOf(
            "Referer" to "$origin/",
            "Origin" to origin,
            "User-Agent" to NetworkClient.USER_AGENT,
        )
    }

    private fun isPlayableMediaUrl(value: String): Boolean {
        val lower = value.trim().lowercase(Locale.ROOT)
        if (!lower.startsWith("http") || lower.contains("master.txt")) return false
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8") || path.endsWith(".mp4")
    }

    private fun isPotentialStaticUrl(value: String): Boolean {
        val lower = value.trim().lowercase(Locale.ROOT)
        return isPlayableMediaUrl(value) ||
            (lower.startsWith("http") && lower.contains("master.txt"))
    }

    private fun safeBase64Decode(value: String): ByteArray? = try {
        Base64.decode(value, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun rot13(input: String): String = input.map {
        when (it) {
            in 'A'..'Z' -> 'A' + (it - 'A' + 13) % 26
            in 'a'..'z' -> 'a' + (it - 'a' + 13) % 26
            else -> it
        }
    }.joinToString("")

    private fun rot13Bytes(data: ByteArray): ByteArray {
        val result = ByteArray(data.size)
        for (index in data.indices) {
            val value = data[index].toInt()
            result[index] = when (value) {
                in 65..90 -> (65 + (value - 65 + 13) % 26).toByte()
                in 97..122 -> (97 + (value - 97 + 13) % 26).toByte()
                else -> value.toByte()
            }
        }
        return result
    }

    private fun unmixLoop(decodedBytes: ByteArray, magicNum: Long, offset: Int): ByteArray {
        val finalBytes = ByteArray(decodedBytes.size)
        for (index in decodedBytes.indices) {
            val value = decodedBytes[index].toInt() and 0xFF
            val adjustment = (magicNum % (index + offset)).toInt()
            finalBytes[index] = ((value - adjustment + 256) % 256).toByte()
        }
        return finalBytes
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder().build()
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String, @Header("referer") referer: String): Document
    }
}
