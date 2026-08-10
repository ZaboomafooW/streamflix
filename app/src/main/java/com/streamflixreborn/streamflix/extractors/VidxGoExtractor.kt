package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object TokenManager {
    @Volatile
    var latestQuery: String? = null
        private set

    private var refreshJob: Job? = null
    private var activeSession: Video.TokenSession? = null

    @Synchronized
    fun start(session: Video.TokenSession, scope: CoroutineScope) {
        if (activeSession == session && refreshJob?.isActive == true) {
            return
        }

        stop()
        activeSession = session
        latestQuery = session.initialQuery

        val refreshUrl = session.refreshUrl ?: return
        refreshJob = scope.launch(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .dns(DnsResolver.doh)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            var expiresAtMillis = session.expiresAtMillis

            while (isActive) {
                val delayMs = expiresAtMillis?.let { expiresAt ->
                    (expiresAt - System.currentTimeMillis() - 15_000L).coerceAtLeast(5_000L)
                } ?: 150_000L

                delay(delayMs)
                if (!isActive) break

                try {
                    val request = Request.Builder()
                        .url(refreshUrl)
                        .header("Referer", session.referer)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                        .header("sec-fetch-dest", "empty")
                        .build()

                    val response = client.newCall(request).execute()
                    val body = response.use {
                        if (!it.isSuccessful) {
                            Log.w("TokenManager", "Token refresh HTTP ${it.code}")
                        }
                        it.body?.string()
                    }
                    if (!isActive) break
                    if (body.isNullOrBlank()) continue

                    expiresAtMillis = Regex("\"expire\"\\s*:\\s*(\\d+)")
                        .find(body)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()

                    val refreshedUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                        .find(body)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.replace("\\/", "/")

                    if (!refreshedUrl.isNullOrBlank() && isActive) {
                        latestQuery = Uri.parse(refreshedUrl).encodedQuery
                        Log.d("TokenManager", "Playback token refreshed")
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.e("TokenManager", "Playback token refresh failed", e)
                    expiresAtMillis = System.currentTimeMillis() + 150_000L
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        activeSession = null
        latestQuery = null
    }
}

class VidxGoExtractor : Extractor() {
    override val name = "VidxGo"
    override val mainUrl = "https://v.vidxgo.co"

    override suspend fun extract(link: String): Video {
        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val uri = Uri.parse(link)
        val referer = "${uri.scheme}://${uri.host}/"
        val requestBuilder = Request.Builder()
            .url(link)
            .header("Referer", referer)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

        if (!link.contains("/t/")) {
            requestBuilder.header("sec-fetch-dest", "iframe")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val html = response.use {
            it.body?.string() ?: throw Exception("Failed to get HTML from VidxGo")
        }

        if (link.contains("/t/")) {
            val videoUrlRaw = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?: throw Exception("VidxGo: Could not find url in TV series response")
            val videoUrl = videoUrlRaw.replace("\\/", "/")
            val expireTime = Regex("\"expire\"\\s*:\\s*(\\d+)")
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()

            return buildVideo(
                videoUrl = videoUrl,
                refreshUrl = link,
                referer = referer,
                expiresAtMillis = expireTime,
            )
        }

        val scriptRegex = Regex(
            "<script[\\s\\S]*?>[\\s\\S]*?\\(function\\(\\)\\s*\\{[\\s\\S]*?\\}\\s*\\)\\(\\);[\\s\\S]*?</script>",
            RegexOption.IGNORE_CASE
        )
        val scriptMatches = scriptRegex.findAll(html).toList()

        if (scriptMatches.size < 5) {
            Log.e("VidxGoExtractor", "Could not find enough encrypted scripts. Found: ${scriptMatches.size}")
            throw Exception("VidxGo: Could not find fifth encrypted script")
        }

        val targetScript = scriptMatches[4].value
        val key = Regex("var\\s+k\\s*=\\s*['\"]([^'\"]+)['\"]")
            .find(targetScript)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw Exception("VidxGo: Could not find key 'k'")
        val data = Regex("atob\\(['\"]([^'\"]+)['\"]\\)")
            .find(targetScript)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw Exception("VidxGo: Could not find data 'd'")

        val decoded = Base64.decode(data, Base64.DEFAULT)
        val decrypted = ByteArray(decoded.size)
        for (i in decoded.indices) {
            decrypted[i] = ((decoded[i].toInt() and 0xFF) xor (key[i % key.length].code and 0xFF)).toByte()
        }

        val decryptedText = String(decrypted)
        val videoUrlRaw = Regex("currentSrc\\s*=\\s*['\"]([^'\"]+)['\"]")
            .find(decryptedText)
            ?.groupValues
            ?.getOrNull(1)
            ?: throw Exception("VidxGo: Could not find currentSrc in decrypted script")
        val videoUrl = videoUrlRaw.replace("\\/", "/")

        val initialExpireTime = Regex("let\\s+currentExpire\\s*=\\s*(\\d+)")
            .find(decryptedText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        val filmId = uri.pathSegments.firstOrNull()
        val refreshUrl = filmId?.let { "$mainUrl/t/$it" }

        return buildVideo(
            videoUrl = videoUrl,
            refreshUrl = refreshUrl,
            referer = referer,
            expiresAtMillis = initialExpireTime,
        )
    }

    private fun buildVideo(
        videoUrl: String,
        refreshUrl: String?,
        referer: String,
        expiresAtMillis: Long?,
    ): Video {
        return Video(
            source = videoUrl,
            headers = mapOf(
                "origin" to mainUrl,
                "referer" to "$mainUrl/",
                "sec-fetch-dest" to "empty",
                "sec-fetch-site" to "cross-site"
            ),
            maintainToken = true,
            tokenSession = Video.TokenSession(
                initialQuery = Uri.parse(videoUrl).encodedQuery,
                refreshUrl = refreshUrl,
                referer = referer,
                expiresAtMillis = expiresAtMillis,
            )
        )
    }
}
