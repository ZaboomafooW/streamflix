package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.RidomoviesProvider
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale

class CloseloadExtractor : Extractor() {

    override val name = "Closeload"
    override val mainUrl = "https://closeload.top/"

    override suspend fun extract(link: String): Video {
        extractStaticVideo(link)?.let { return it }

        return BrowserStreamResolver.resolve(
            link = link,
            referer = RidomoviesProvider.URL,
            timeoutMs = 15_000L,
        ) { candidate ->
            isPlayableMediaUrl(candidate)
        }
    }

    private suspend fun extractStaticVideo(link: String): Video? = runCatching {
        val embedUrl = link.toHttpUrlOrNull()
            ?: throw Exception("Invalid Closeload embed URL")
        val origin = "${embedUrl.scheme}://${embedUrl.host}"
        val html = requestText(
            Request.Builder()
                .url(link)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", RidomoviesProvider.URL)
                .build(),
        ) ?: return@runCatching null

        val document = Jsoup.parse(html, link).apply { setBaseUri(link) }
        val script = document.select("script")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { it.contains("eval(") && it.contains("PlayerInit") }
            ?: document.select("script")
                .asSequence()
                .map { it.data().ifBlank { it.html() } }
                .firstOrNull { it.contains("eval(") }
            ?: html

        val unpacker = JsUnpacker(script)
        val unpacked = if (unpacker.detect()) unpacker.unpack() ?: script else script

        initializePlayer(unpacked, link, origin)

        val source = decodeCurrentPlaylist(unpacked)
            ?: extractLegacySource(unpacked)
            ?: return@runCatching null

        resolveStaticCandidate(
            source = source,
            link = link,
            subtitles = parseSubtitles(document),
        )
    }.getOrNull()

    private suspend fun initializePlayer(unpacked: String, link: String, origin: String) {
        val hash = Regex("""\bhash\s*:\s*["']([^"']+)["']""")
            .find(unpacked)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val endpoint = Regex("""\burl\s*:\s*["']([^"']+)["']""")
            .find(unpacked)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (hash.isBlank() || endpoint.isBlank()) return

        val target = absolute(endpoint, origin)
        val body = FormBody.Builder().add("hash", hash).build()
        val request = Request.Builder()
            .url(target)
            .post(body)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", link)
            .header("Origin", origin)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        withContext(Dispatchers.IO) {
            NetworkClient.default.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Closeload player initialization failed with HTTP ${response.code}")
                }
            }
        }
    }

    private fun decodeCurrentPlaylist(unpacked: String): String? {
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
            ?.takeIf(::isPotentialStaticUrl)
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
        return String(unmixLoop(decoded, 399756995L, 5), Charsets.UTF_8).trim()
    }

    private fun extractLegacySource(unpacked: String): String? {
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

        return inputs.firstNotNullOfOrNull { smartBruteForce(it, magicNum, offset) }
            ?: Regex("[\"'](aHR0[a-zA-Z0-9+/=]{20,})[\"']")
                .findAll(unpacked)
                .mapNotNull { safeBase64Decode(it.groupValues[1]) }
                .map { String(it, Charsets.UTF_8).trim() }
                .firstOrNull(::isPotentialStaticUrl)
    }

    private suspend fun resolveStaticCandidate(
        source: String,
        link: String,
        subtitles: List<Video.Subtitle>,
    ): Video? {
        if (isPlayableMediaUrl(source)) return video(source, link, subtitles)
        if (!source.contains("master.txt", ignoreCase = true)) return null

        val origin = link.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
            ?: mainUrl.trimEnd('/')
        val body = requestText(
            Request.Builder()
                .url(source)
                .header("User-Agent", NetworkClient.USER_AGENT)
                .header("Accept", "*/*")
                .header("Referer", link)
                .header("Origin", origin)
                .build(),
        )?.trim().orEmpty()

        if (body.startsWith("#EXTM3U")) {
            return Video(
                source = source,
                subtitles = subtitles,
                headers = mediaHeaders(link),
                type = MimeTypes.APPLICATION_M3U8,
            )
        }

        val direct = body.lineSequence()
            .map(String::trim)
            .firstOrNull(::isPlayableMediaUrl)
        return direct?.let { video(it, link, subtitles) }
    }

    private fun parseSubtitles(document: Document): List<Video.Subtitle> =
        document.select("track[src]").mapNotNull { track ->
            val file = track.absUrl("src").ifBlank { track.attr("src") }
                .trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val label = track.attr("label").trim()
                .ifBlank { track.attr("srclang").trim() }
                .ifBlank { "Subtitle" }
            Video.Subtitle(
                label = label,
                file = file,
                default = track.hasAttr("default"),
                initialDefault = track.hasAttr("default"),
            )
        }.distinctBy { it.file }

    private suspend fun requestText(request: Request): String? = withContext(Dispatchers.IO) {
        runCatching {
            NetworkClient.default.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }

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

    private fun video(
        source: String,
        link: String,
        subtitles: List<Video.Subtitle> = emptyList(),
    ): Video {
        val path = source.substringBefore('?').substringBefore('#')
        val type = if (path.endsWith(".mp4", ignoreCase = true)) {
            MimeTypes.VIDEO_MP4
        } else {
            MimeTypes.APPLICATION_M3U8
        }
        return Video(
            source = source,
            subtitles = subtitles,
            headers = mediaHeaders(link),
            type = type,
        )
    }

    private fun mediaHeaders(link: String): Map<String, String> {
        val embedUrl = link.toHttpUrlOrNull()
        val origin = embedUrl?.let { "${it.scheme}://${it.host}" } ?: mainUrl.trimEnd('/')
        return mapOf(
            "Referer" to link,
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

    private fun absolute(value: String, origin: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") || value.startsWith("https://") -> value
        value.startsWith("/") -> "$origin$value"
        else -> "$origin/${value.trimStart('/')}"
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
}
