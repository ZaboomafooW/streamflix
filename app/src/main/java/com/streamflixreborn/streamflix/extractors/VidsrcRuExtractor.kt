package com.streamflixreborn.streamflix.extractors

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeoutOrNull

class VidsrcRuExtractor : Extractor() {

    override val name = "Vidsrc.Ru"
    override val mainUrl = "https://vidsrc.ru"

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

    override suspend fun extract(link: String): Video {
        return withContext(Dispatchers.Main) {
            val webView = WebView(StreamFlixApp.instance.applicationContext)
            val main = Handler(Looper.getMainLooper())
            try {
                withTimeoutOrNull(30_000) {
                    suspendCancellableCoroutine<Video> { continuation ->
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                                val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                                val path = uri.path.orEmpty()
                                if (path.contains("/file2/") && path.endsWith(".m3u8", ignoreCase = true)) {
                                    val url = uri.toString()
                                    val headers = request.requestHeaders.filterKeys { it.equals("Referer", true) || it.equals("User-Agent", true) || it.equals("Origin", true) }
                                    main.post {
                                        if (continuation.isActive) {
                                            continuation.resume(Video(source = url, headers = headers, type = MimeTypes.APPLICATION_M3U8))
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        webView.loadUrl(link)
                    }
                } ?: throw IllegalStateException("Timeout waiting for VidsrcRu stream")
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }
}
