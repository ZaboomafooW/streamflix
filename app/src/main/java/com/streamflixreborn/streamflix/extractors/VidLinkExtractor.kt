package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class VidLinkExtractor : Extractor() {

    override val name = "VidLink"
    override val mainUrl = "https://vidlink.pro"

    private val client = OkHttpClient.Builder().build()

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Movie -> "$mainUrl/movie/${videoType.id}"
                is Video.Type.Episode -> "$mainUrl/tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
            },
        )
    }

    override suspend fun extract(link: String): Video = withContext(Dispatchers.IO) {
        val uri = Uri.parse(link)
        val segments = uri.pathSegments
        val type = segments.firstOrNull() ?: throw Exception("Invalid VidLink URL")
        val tmdbId = segments.getOrNull(1) ?: throw Exception("Missing VidLink TMDB id")
        val encryptedId = encryptTmdbId(tmdbId)

        val apiUrl = when (type) {
            "movie" -> "$mainUrl/api/b/movie/${Uri.encode(encryptedId)}"
            "tv" -> {
                val season = segments.getOrNull(2) ?: throw Exception("Missing VidLink season")
                val episode = segments.getOrNull(3) ?: throw Exception("Missing VidLink episode")
                "$mainUrl/api/b/tv/${Uri.encode(encryptedId)}/$season/$episode"
            }
            else -> throw Exception("Unsupported VidLink media type: $type")
        }

        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "*/*")
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$mainUrl/")
            .header("Origin", mainUrl)
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("VidLink API returned HTTP ${response.code}")
            }
            response.body?.string() ?: throw Exception("VidLink API returned an empty body")
        }

        val stream = JSONObject(body).optJSONObject("stream")
            ?: throw Exception("VidLink stream data missing")

        val source = stream.optString("playlist").takeIf { it.isNotBlank() }
            ?: selectFileSource(stream.optJSONObject("qualities"))
            ?: throw Exception("VidLink returned no playable source")

        val subtitles = mutableListOf<Video.Subtitle>()
        stream.optJSONArray("captions")?.let { captions ->
            for (i in 0 until captions.length()) {
                val caption = captions.optJSONObject(i) ?: continue
                val file = caption.optString("url").ifBlank { caption.optString("id") }
                if (file.isBlank()) continue
                val label = caption.optString("language")
                    .ifBlank { caption.optString("label") }
                    .ifBlank { "Unknown" }
                subtitles += Video.Subtitle(label = label, file = file)
            }
        }

        val headers = mutableMapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )
        stream.optJSONObject("headers")?.let { responseHeaders ->
            responseHeaders.keys().forEach { key ->
                responseHeaders.optString(key).takeIf { it.isNotBlank() }?.let { value ->
                    headers[key] = value
                }
            }
        }

        Video(
            source = source,
            subtitles = subtitles,
            headers = headers,
            type = when {
                source.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                source.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                else -> null
            },
        )
    }

    private fun selectFileSource(qualities: JSONObject?): String? {
        if (qualities == null) return null

        return qualities.keys().asSequence()
            .mapNotNull { quality ->
                val entry = qualities.optJSONObject(quality) ?: return@mapNotNull null
                val url = entry.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val height = Regex("(\\d{3,4})").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                height to url
            }
            .maxByOrNull { it.first }
            ?.second
    }

    private fun encryptTmdbId(tmdbId: String): String {
        val request = Request.Builder()
            .url("https://enc-dec.app/api/enc-vidlink?text=${Uri.encode(tmdbId)}")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("VidLink id encryption returned HTTP ${response.code}")
            }
            response.body?.string() ?: throw Exception("VidLink id encryption returned an empty body")
        }

        return JSONObject(body).optString("result")
            .takeIf { it.isNotBlank() }
            ?: throw Exception("VidLink id encryption failed")
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    }
}
