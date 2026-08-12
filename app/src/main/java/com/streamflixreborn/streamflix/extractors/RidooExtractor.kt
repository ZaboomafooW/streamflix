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
            val document = Service.build(embedOrigin, RidomoviesProvider.URL).get(link)
            val subtitles = parseCaptions(document, embedOrigin)
            return extractRapidrame(
                document = document,
                link = link,
                embedOrigin = embedOrigin,
                subtitles = subtitles,
            )
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

    private fun extractRapidrame(
        document: Document,
        link: String,
        embedOrigin: String,
        subtitles: List<Video.Subtitle>,
    ): Video {
        val script = document.select("script")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { it.contains("eval(") }
            ?: throw Exception("Rapidrame player script not found")

        val unpacked = unpackRapidrameScript(script)
        val playlist = decodePlaylist(unpacked)
            ?: throw Exception("Rapidrame playlist could not be decoded")

        return Video(
            source = playlist,
            subtitles = subtitles,
            headers = mediaHeaders(link, embedOrigin),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private fun unpackRapidrameScript(script: String): String {
        var current = script
        repeat(MAX_PACKER_LAYERS) {
            val unpacker = JsUnpacker(current)
            if (!unpacker.detect()) return current
            current = unpacker.unpack()
                ?.takeIf { it.isNotBlank() && it != current }
                ?: throw Exception("Rapidrame player script could not be unpacked")
        }

        if (JsUnpacker(current).detect()) {
            throw Exception("Rapidrame player script exceeded the supported packer depth")
        }
        return current
    }

    private fun decodePlaylist(unpacked: String): String? {
        val decoderCall = Regex(
            """\b(?:var|let|const)\s+[A-Za-z_$][\w$]*\s*=\s*([A-Za-z_$][\w$]*)\s*\(\s*\[(.*?)]\s*\)\s*;?""",
            RegexOption.DOT_MATCHES_ALL,
        )

        return decoderCall.findAll(unpacked).firstNotNullOfOrNull { match ->
            val decoderName = match.groupValues[1]
            val functionBody = extractFunctionBody(unpacked, decoderName)
                ?: return@firstNotNullOfOrNull null
            val decoder = parseRapidrameDecoder(functionBody)
                ?: return@firstNotNullOfOrNull null
            val encoded = Regex("""["']([^"']+)["']""")
                .findAll(match.groupValues[2])
                .map { it.groupValues[1] }
                .joinToString("")
                .takeIf { it.isNotBlank() }
                ?: return@firstNotNullOfOrNull null

            runCatching { decoder.decode(encoded) }
                .getOrNull()
                ?.trim()
                ?.takeIf(::isHlsUrl)
        }
    }

    private enum class DecoderOperation {
        BASE64_DECODE,
        REVERSE,
    }

    private data class RapidrameDecoder(
        val operations: List<DecoderOperation>,
        val accumulatorStart: Int,
        val accumulatorStep: Int,
        val modulus: Int,
    ) {
        fun decode(encoded: String): String {
            var result = encoded.toByteArray(Charsets.ISO_8859_1)
            operations.forEach { operation ->
                result = when (operation) {
                    DecoderOperation.BASE64_DECODE -> Base64.decode(
                        String(result, Charsets.ISO_8859_1),
                        Base64.DEFAULT,
                    )
                    DecoderOperation.REVERSE -> result.reversedArray()
                }
            }

            var accumulator = accumulatorStart
            val plain = ByteArray(result.size)
            result.forEachIndexed { index, byte ->
                val encodedByte = byte.toInt() and 0xFF
                accumulator = (accumulator + accumulatorStep) % modulus
                plain[index] = (encodedByte xor accumulator).toByte()
                accumulator = (accumulator + encodedByte) % modulus
            }
            return String(plain, Charsets.ISO_8859_1)
        }
    }

    private fun parseRapidrameDecoder(functionBody: String): RapidrameDecoder? {
        val byteMatch = Regex(
            """\b(?:var|let|const)\s+([A-Za-z_$][\w$]*)\s*=\s*([A-Za-z_$][\w$]*)\.charCodeAt\s*\([^)]*\)\s*;""",
        ).find(functionBody) ?: return null
        val byteVariable = byteMatch.groupValues[1]
        val resultVariable = byteMatch.groupValues[2]

        val plainMatch = Regex(
            """\b(?:var|let|const)\s+([A-Za-z_$][\w$]*)\s*=\s*${Regex.escape(byteVariable)}\s*\^\s*([A-Za-z_$][\w$]*)\s*;""",
        ).find(functionBody) ?: return null
        val plainVariable = plainMatch.groupValues[1]
        val accumulatorVariable = plainMatch.groupValues[2]

        val accumulatorStart = Regex(
            """\b(?:var|let|const)\s+${Regex.escape(accumulatorVariable)}\s*=\s*(\d+)\s*;""",
        ).find(functionBody)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null

        val stepMatch = Regex(
            """\b${Regex.escape(accumulatorVariable)}\s*=\s*\(\s*${Regex.escape(accumulatorVariable)}\s*\+\s*(\d+)\s*\)\s*%\s*(\d+)\s*;""",
        ).find(functionBody) ?: return null
        val accumulatorStep = stepMatch.groupValues[1].toIntOrNull() ?: return null
        val modulus = stepMatch.groupValues[2].toIntOrNull() ?: return null
        if (modulus != BYTE_MODULUS) return null

        val feedbackModulus = Regex(
            """\b${Regex.escape(accumulatorVariable)}\s*=\s*\(\s*${Regex.escape(accumulatorVariable)}\s*\+\s*${Regex.escape(byteVariable)}\s*\)\s*%\s*(\d+)\s*;""",
        ).find(functionBody)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        if (feedbackModulus != modulus) return null

        if (!Regex(
                """String\.fromCharCode\s*\(\s*${Regex.escape(plainVariable)}\s*\)""",
            ).containsMatchIn(functionBody)
        ) return null

        val transformSource = functionBody.substring(0, byteMatch.range.first)
        val assignments = Regex(
            """\b${Regex.escape(resultVariable)}\s*=\s*([^;]+);""",
        ).findAll(transformSource)
        val simpleIdentifier = Regex("""[A-Za-z_$][\w$]*""")
        val base64 = Regex(
            """atob\s*\(\s*${Regex.escape(resultVariable)}\s*\)""",
        )
        val reverse = Regex(
            """${Regex.escape(resultVariable)}\.split\(\s*['"]\s*['"]\s*\)\s*\.reverse\(\s*\)\s*\.join\(\s*['"]\s*['"]\s*\)""",
        )
        var sawInitialization = false
        val operations = mutableListOf<DecoderOperation>()

        assignments.forEach { assignment ->
            val expression = assignment.groupValues[1].trim()
            when {
                base64.matches(expression) -> operations += DecoderOperation.BASE64_DECODE
                reverse.matches(expression) -> operations += DecoderOperation.REVERSE
                !sawInitialization && simpleIdentifier.matches(expression) -> sawInitialization = true
                else -> return null
            }
        }

        if (operations.none { it == DecoderOperation.BASE64_DECODE }) return null
        return RapidrameDecoder(
            operations = operations,
            accumulatorStart = accumulatorStart,
            accumulatorStep = accumulatorStep,
            modulus = modulus,
        )
    }

    private fun extractFunctionBody(source: String, functionName: String): String? {
        val signature = Regex(
            """\bfunction\s+${Regex.escape(functionName)}\s*\([^)]*\)\s*\{""",
        ).find(source) ?: return null
        val bodyStart = signature.range.last + 1
        var depth = 1
        var index = bodyStart
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false

        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)

            if (lineComment) {
                if (char == '\n' || char == '\r') lineComment = false
                index++
                continue
            }
            if (blockComment) {
                if (char == '*' && next == '/') {
                    blockComment = false
                    index += 2
                } else {
                    index++
                }
                continue
            }
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                index++
                continue
            }

            when {
                char == '/' && next == '/' -> {
                    lineComment = true
                    index += 2
                }
                char == '/' && next == '*' -> {
                    blockComment = true
                    index += 2
                }
                char == '\'' || char == '"' || char == '`' -> {
                    quote = char
                    index++
                }
                char == '{' -> {
                    depth++
                    index++
                }
                char == '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index)
                    index++
                }
                else -> index++
            }
        }
        return null
    }

    private fun parseCaptions(
        document: Document,
        embedOrigin: String,
    ): List<Video.Subtitle> {
        val trackArrays = document.select("script")
            .asSequence()
            .map { it.data().ifBlank { it.html() } }
            .flatMap { source ->
                Regex(
                    """tracks\s*:\s*(\[[^]]*])""",
                    RegexOption.DOT_MATCHES_ALL,
                ).findAll(source).map { it.groupValues[1] }
            }

        return trackArrays.flatMap { tracksJson ->
            runCatching {
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
                    val isDefault = track.get("default")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asBoolean
                        ?: false

                    Video.Subtitle(
                        label = label,
                        file = absolute(file, embedOrigin),
                        default = isDefault,
                        initialDefault = isDefault,
                    )
                }
            }.getOrDefault(emptyList()).asSequence()
        }.distinctBy { it.file }.toList()
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
        return lower.substringBefore('?').substringBefore('#').endsWith(".m3u8") &&
            value.toHttpUrlOrNull() != null
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

    private companion object {
        const val MAX_PACKER_LAYERS = 4
        const val BYTE_MODULUS = 256
    }
}
