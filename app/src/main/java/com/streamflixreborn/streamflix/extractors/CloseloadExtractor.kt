package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import androidx.media3.common.MimeTypes
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.RidomoviesProvider
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale

class CloseloadExtractor : Extractor() {

    override val name = "Closeload"
    override val mainUrl = "https://closeload.top/"

    override suspend fun extract(link: String): Video {
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
        ) ?: throw Exception("Can't load Closeload embed page")

        val document = Jsoup.parse(html, link).apply { setBaseUri(link) }
        val subtitles = parseSubtitles(document, origin)

        extractStaticVideo(
            link = link,
            document = document,
            subtitles = subtitles,
        )?.let { return it }

        val resolved = BrowserStreamResolver.resolve(
            link = link,
            referer = RidomoviesProvider.URL,
            timeoutMs = 15_000L,
        ) { candidate ->
            isPlayableMediaUrl(candidate)
        }
        return resolved.copy(
            subtitles = (subtitles + resolved.subtitles).distinctBy { subtitle -> subtitle.file },
        )
    }

    private suspend fun extractStaticVideo(
        link: String,
        document: Document,
        subtitles: List<Video.Subtitle>,
    ): Video? = runCatching {
        val source = decodeCurrentSource(document)
            ?: extractLegacySource(document)
            ?: return@runCatching null

        resolveStaticCandidate(
            source = source,
            link = link,
            subtitles = subtitles,
        )
    }.getOrNull()

    private fun decodeCurrentSource(document: Document): String? {
        val scripts = document.select("script")
            .map { it.data().ifBlank { it.html() } }
        val setupScript = scripts.firstOrNull {
            it.contains("jwplayer(\"videoplayer\").setup") ||
                it.contains("jwplayer('videoplayer').setup")
        } ?: return null

        val sourceVar = Regex(
            """sources\s*:\s*\[\{\s*file\s*:\s*([A-Za-z_$][\w$]*)""",
        ).find(setupScript)?.groupValues?.getOrNull(1) ?: return null

        val definitionRegex = Regex(
            """\b(?:var|let|const)\s+${Regex.escape(sourceVar)}\s*=\s*([A-Za-z_$][\w$]*)\s*\(\s*\[(.*?)]\s*\)\s*;""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val definition = scripts.asSequence()
            .mapNotNull { source -> definitionRegex.find(source)?.let { source to it } }
            .firstOrNull()
            ?: return null

        val definitionSource = definition.first
        val match = definition.second
        val decoderName = match.groupValues[1]
        if (!definitionSource.contains("function $decoderName")) return null

        val encoded = Regex("""["']([^"']+)["']""")
            .findAll(match.groupValues[2])
            .map { it.groupValues[1] }
            .joinToString("")
            .takeIf { it.isNotBlank() }
            ?: return null

        val shift = Regex(
            """\(\s*o\s*-\s*base\s*\+\s*(\d+)\s*\)\s*%\s*26""",
        ).find(definitionSource)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        val base64Passes = Regex(
            """\bresult\s*=\s*atob\s*\(\s*result\s*\)\s*;""",
        ).findAll(definitionSource).count().takeIf { it > 0 }
            ?: return null
        val accumulatorStart = Regex(
            """\bvar\s+acc\s*=\s*(\d+)\s*;""",
        ).find(definitionSource)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        val accumulatorStep = Regex(
            """\bacc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)\s*%\s*256\s*;""",
        ).find(definitionSource)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        if (!Regex("""\bplain\s*=\s*b\s*\^\s*acc\s*;""").containsMatchIn(definitionSource)) {
            return null
        }

        return runCatching {
            decodeRollingXorSource(
                encoded = encoded,
                shift = shift,
                base64Passes = base64Passes,
                accumulatorStart = accumulatorStart,
                accumulatorStep = accumulatorStep,
            )
        }.getOrNull()?.takeIf(::isPotentialStaticUrl)
    }

    private fun decodeRollingXorSource(
        encoded: String,
        shift: Int,
        base64Passes: Int,
        accumulatorStart: Int,
        accumulatorStep: Int,
    ): String {
        var value = encoded.map { char ->
            when (char) {
                in 'A'..'Z' -> 'A' + (char - 'A' + shift) % 26
                in 'a'..'z' -> 'a' + (char - 'a' + shift) % 26
                else -> char
            }
        }.joinToString("")

        var decoded = ByteArray(0)
        repeat(base64Passes) { pass ->
            decoded = Base64.decode(value, Base64.DEFAULT)
            if (pass < base64Passes - 1) {
                value = String(decoded, Charsets.ISO_8859_1)
            }
        }

        var accumulator = accumulatorStart
        val plain = ByteArray(decoded.size)
        decoded.forEachIndexed { index, byte ->
            val valueAtIndex = byte.toInt() and 0xFF
            accumulator = (accumulator + accumulatorStep) % 256
            plain[index] = (valueAtIndex xor accumulator).toByte()
            accumulator = (accumulator + valueAtIndex) % 256
        }
        return String(plain, Charsets.UTF_8).trim()
    }

    private fun extractLegacySource(document: Document): String? {
        val script = document.select("script")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { it.contains("eval(") && it.contains("PlayerInit") }
            ?: document.select("script")
                .asSequence()
                .map { it.data().ifBlank { it.html() } }
                .firstOrNull { it.contains("eval(") }
            ?: return null

        val unpacker = JsUnpacker(script)
        val unpacked = if (unpacker.detect()) unpacker.unpack() ?: script else script

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
            ?.groups
            ?.get(1)
            ?.value
            ?.let { varName ->
                Regex("""var\s+$varName\s*=\s*dc_hello\("([^"]+)"\)""")
                    .find(unpacked)
                    ?.groups
                    ?.get(1)
                    ?.value
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

    private fun parseSubtitles(
        document: Document,
        origin: String,
    ): List<Video.Subtitle> {
        val scriptTracks = document.select("script")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .flatMap { source ->
                Regex(
                    """tracks\s*:\s*(\[[^]]*])""",
                    RegexOption.DOT_MATCHES_ALL,
                ).findAll(source).map { it.groupValues[1] }
            }
            .flatMap { tracksJson ->
                runCatching {
                    JsonParser.parseString(tracksJson).asJsonArray.mapNotNull { element ->
                        val track = element.takeIf { it.isJsonObject }?.asJsonObject
                            ?: return@mapNotNull null
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
                        val isDefault = track.get("default")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asBoolean
                            ?: false

                        Video.Subtitle(
                            label = label,
                            file = absolute(file, origin),
                            default = isDefault,
                            initialDefault = isDefault,
                        )
                    }
                }.getOrDefault(emptyList()).asSequence()
            }
            .toList()

        val htmlTracks = document.select("track[src]").mapNotNull { track ->
            val file = track.absUrl("src").ifBlank { track.attr("src") }
                .trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val label = track.attr("label").trim()
                .ifBlank { track.attr("srclang").trim() }
                .ifBlank { "Subtitle" }
            Video.Subtitle(
                label = label,
                file = absolute(file, origin),
                default = track.hasAttr("default"),
                initialDefault = track.hasAttr("default"),
            )
        }

        return (scriptTracks + htmlTracks).distinctBy { it.file }
    }

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
