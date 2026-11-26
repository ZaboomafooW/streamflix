package com.streamflixreborn.streamflix.extractors

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object VidSparkPlayer {
    private const val origin = "https://vidspark.to"
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).build()
    val titlePath = Regex("/(?:movie/[0-9]+|tv/[0-9]+/[0-9]+/[0-9]+)")

    suspend fun resolve(path: String): Video = withContext(Dispatchers.Main.immediate) {
        require(titlePath.matches(path))
        val page = origin + path
        val lookup = "$origin/api/vidora/v1$path"
        val result = CompletableDeferred<Video>()
        val view = WebView(StreamFlixApp.instance)
        val agent = view.settings.userAgentString
        try {
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(true)
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.isForMainFrame && request.url.toString() != page

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.method != "GET" || request.url.toString() != lookup || !result.isActive) return null
                    // Resolve only the title lookup made by the provider's current player.
                    try {
                        val builder = Request.Builder().url(lookup)
                        request.requestHeaders.forEach { (name, value) -> builder.header(name, value) }
                        client.newCall(builder.build()).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("VidSpark returned HTTP ${response.code}")
                            val json = JSONObject(response.body?.string() ?: throw IOException("Empty VidSpark response"))
                            val source = json.optJSONArray("sources")?.optJSONObject(0)
                                ?: throw IOException("VidSpark has no direct source for this title")
                            val url = source.optString("url").toHttpUrlOrNull()
                            if (url == null || !url.isHttps) throw IOException("Invalid VidSpark media URL")
                            val tracks = source.optJSONArray("tracks")
                            val subtitles = buildList {
                                for (i in 0 until (tracks?.length() ?: 0)) {
                                    val track = tracks?.optJSONObject(i) ?: continue
                                    val file = track.optString("file").takeIf { it.isNotBlank() } ?: continue
                                    val media = page.toHttpUrl().resolve(file) ?: continue
                                    if (media.isHttps && track.optString("kind") != "thumbnails") {
                                        add(Video.Subtitle(track.optString("label"), media.toString()))
                                    }
                                }
                            }
                            result.complete(Video(source = url.toString(), subtitles = subtitles,
                                headers = mapOf("Referer" to "$origin/", "Origin" to origin, "User-Agent" to agent),
                                type = if (url.encodedPath.endsWith(".m3u8", true)) MimeTypes.APPLICATION_M3U8 else null))
                        }
                    } catch (error: Exception) {
                        result.completeExceptionally(error)
                    }
                    return WebResourceResponse("application/json", "utf-8", "{}".byteInputStream())
                }
            }
            view.loadUrl(page)
            withTimeoutOrNull(25_000) { result.await() } ?: throw IOException("VidSpark lookup timed out")
        } finally {
            result.cancel()
            view.stopLoading()
            view.destroy()
        }
    }
}
