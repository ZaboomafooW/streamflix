package com.streamflixreborn.streamflix.extractors

import androidx.media3.common.MimeTypes
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.Request
import java.net.URI

class PrimeloadExtractor : Extractor() {

    override val name = "Primeload"
    override val mainUrl = PLAYER_API_ORIGIN

    override suspend fun extract(link: String): Video {
        val videoId = extractVideoId(link)
            ?: throw Exception("Primeload: invalid embed URL")
        val request = Request.Builder()
            .url("$PLAYER_API_ORIGIN/api/v1/player/$videoId")
            .header("Referer", link)
            .build()

        val responseBody = NetworkClient.default.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Primeload player API failed: HTTP ${response.code}")
            }
            response.body?.string()
                ?: throw Exception("Primeload player API returned an empty response")
        }

        return parsePlayerResponse(
            responseBody = responseBody,
            embedUrl = link,
        )
    }

    companion object {
        private const val PLAYER_API_ORIGIN = "https://primeload.co"

        internal fun extractVideoId(link: String): String? {
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (host != "primeload.co" && !host.endsWith(".primeload.co")) return null
            if (uri.scheme != "https" && uri.scheme != "http") return null

            val segments = uri.path
                ?.trim('/')
                ?.split('/')
                ?.filter(String::isNotBlank)
                .orEmpty()
            if (segments.size < 2) return null

            return segments.last()
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        internal fun parsePlayerResponse(
            responseBody: String,
            embedUrl: String,
        ): Video {
            val origin = origin(embedUrl)
                ?: throw Exception("Primeload: invalid embed URL")
            val json = runCatching { JsonParser.parseString(responseBody).asJsonObject }
                .getOrElse { throw Exception("Primeload player API returned invalid JSON", it) }
            val sources = json.getAsJsonArray("sources")
                ?: throw Exception("Primeload player API returned no sources")
            val source = sources
                .asSequence()
                .mapNotNull { item ->
                    runCatching {
                        item.asJsonObject.get("src")?.asString
                    }.getOrNull()
                }
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                ?: throw Exception("Primeload player API returned no playable source")
            val normalizedSource = normalizeSource(source, origin)
                ?: throw Exception("Primeload player API returned an invalid source URL")

            return Video(
                source = normalizedSource,
                headers = mapOf(
                    "Origin" to origin,
                    "Referer" to embedUrl,
                ),
                type = MimeTypes.APPLICATION_M3U8,
            )
        }

        private fun origin(link: String): String? {
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            if (host != "primeload.co" && !host.endsWith(".primeload.co")) return null
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            return "$scheme://$host$port"
        }

        private fun normalizeSource(source: String, origin: String): String? {
            val normalized = when {
                source.startsWith("//") -> "https:$source"
                source.startsWith("/") -> "$origin$source"
                else -> source
            }
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
            return normalized.takeIf {
                uri.host != null && (uri.scheme == "https" || uri.scheme == "http")
            }
        }
    }
}
