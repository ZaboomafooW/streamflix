package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VidrockExtractor : Extractor() {

    override val name = "Vidrock"
    override val mainUrl = "https://vidrock.net"

    private val passphrase = "x7k9mPqT2rWvY8zA5bC3nF6hJ2lK4mN9"

    suspend fun servers(videoType: Video.Type): List<Video.Server> {
        val encoded = when (videoType) {
            is Video.Type.Movie -> encryptAndEncode(videoType.id)
            is Video.Type.Episode -> encryptAndEncode("${videoType.tvShow.id}_${videoType.season.number}_${videoType.number}")
        }

        val apiUrl = when (videoType) {
            is Video.Type.Episode -> "$mainUrl/api/tv/$encoded"
            is Video.Type.Movie -> "$mainUrl/api/movie/$encoded"
        }

        return try {
            val response = Service.build(mainUrl).getStreams(apiUrl)
            response.mapNotNull { (serverName, data) ->
                val videoUrl = data["url"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Video.Server(
                    id = "$serverName-$videoUrl (Vidrock)",
                    name = "$serverName (Vidrock)",
                    src = "$apiUrl#$serverName",
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun server(videoType: Video.Type): Video.Server? {
        return servers(videoType).firstOrNull()
    }

    override suspend fun extract(link: String): Video {
        val serverName = link.substringAfter("#", "").takeIf { it != link }
        val apiLink = link.substringBefore("#")
        val service = Service.build(mainUrl)
        val response = service.getStreams(apiLink)

        val serverEntry = if (!serverName.isNullOrBlank()) {
            response.entries.find { it.key.equals(serverName, ignoreCase = true) }
        } else {
            response.entries.find { it.value["url"]?.isNotBlank() == true }
        } ?: throw Exception("No Vidrock video sources found")

        val initialUrl = serverEntry.value["url"]
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Vidrock server returned an empty URL")

        val resolved = if (
            initialUrl.contains("hls2.vdrk.site", ignoreCase = true) ||
            serverEntry.key.equals("Atlas", ignoreCase = true)
        ) {
            resolveCdnSource(service, initialUrl) ?: ResolvedSource(initialUrl, defaultHeaders(initialUrl))
        } else {
            ResolvedSource(initialUrl, defaultHeaders(initialUrl))
        }

        return Video(
            source = resolved.url,
            headers = resolved.headers,
            type = if (resolved.url.contains(".mp4", ignoreCase = true)) {
                MimeTypes.VIDEO_MP4
            } else {
                MimeTypes.APPLICATION_M3U8
            },
        )
    }

    private suspend fun resolveCdnSource(service: Service, url: String): ResolvedSource? {
        val qualities = try {
            service.getQualities(url)
        } catch (_: Exception) {
            emptyList()
        }

        val selected = qualities
            .filter { it.url.isNotBlank() }
            .maxByOrNull { it.resolution }
            ?: return null

        val finalUrl = if (selected.url.startsWith(PROXY_PREFIX)) {
            val encodedPath = selected.url.removePrefix(PROXY_PREFIX).removePrefix("/")
            URLDecoder.decode(encodedPath, Charsets.UTF_8.name())
        } else {
            selected.url
        }

        return ResolvedSource(
            url = finalUrl,
            headers = mapOf(
                "Referer" to "https://lok-lok.cc/",
                "Origin" to "https://lok-lok.cc",
            ),
        )
    }

    private fun defaultHeaders(url: String): Map<String, String> {
        return if (url.contains("67streams", ignoreCase = true)) {
            mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
            )
        } else {
            mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
            )
        }
    }

    private fun encryptAndEncode(data: String): String {
        val key = passphrase.toByteArray(Charsets.UTF_8)
        val iv = key.copyOfRange(0, 16)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

        return Base64.encodeToString(encrypted, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder().build()
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET
        suspend fun getStreams(@Url url: String): Map<String, Map<String, String>>

        @GET
        suspend fun getQualities(@Url url: String): List<QualitySource>
    }

    data class QualitySource(
        val resolution: Int = 0,
        val url: String = "",
    )

    private data class ResolvedSource(
        val url: String,
        val headers: Map<String, String>,
    )

    companion object {
        private const val PROXY_PREFIX = "https://proxy.vidrock.store/"
    }
}
