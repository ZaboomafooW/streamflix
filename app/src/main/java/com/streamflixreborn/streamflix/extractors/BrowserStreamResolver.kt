package com.streamflixreborn.streamflix.extractors

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object BrowserStreamResolver {

    suspend fun resolve(
        link: String,
        referer: String,
        timeoutMs: Long = 30_000L,
        isMediaRequest: (String) -> Boolean,
    ): Video = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val completed = AtomicBoolean(false)
            val embedUrl = link.toHttpUrlOrNull()
                ?: run {
                    continuation.resumeWithException(Exception("Invalid embed URL"))
                    return@suspendCancellableCoroutine
                }
            val embedOrigin = "${embedUrl.scheme}://${embedUrl.host}"
            val webView = WebView(StreamFlixApp.instance.applicationContext)
            var timeoutRunnable: Runnable? = null
            var playbackKickRunnable: Runnable? = null

            fun removeCallbacks() {
                timeoutRunnable?.let(handler::removeCallbacks)
                playbackKickRunnable?.let(handler::removeCallbacks)
            }

            fun destroyWebView() {
                runCatching { webView.stopLoading() }
                runCatching { webView.loadUrl("about:blank") }
                runCatching { webView.destroy() }
            }

            fun finish(video: Video? = null, error: Throwable? = null) {
                if (!completed.compareAndSet(false, true)) return
                removeCallbacks()
                handler.post {
                    destroyWebView()
                    if (!continuation.isActive) return@post
                    if (video != null) {
                        continuation.resume(video)
                    } else {
                        continuation.resumeWithException(
                            error ?: Exception("No playable stream request was captured")
                        )
                    }
                }
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                userAgentString = NetworkClient.USER_AGENT
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            webView.webChromeClient = object : WebChromeClient() {}
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    if (completed.get()) return null
                    val requestUrl = request?.url?.toString().orEmpty()
                    if (requestUrl.isBlank() || !isMediaRequest(requestUrl)) {
                        return super.shouldInterceptRequest(view, request)
                    }

                    val requestHeaders = request?.requestHeaders.orEmpty()
                    fun header(name: String): String? = requestHeaders.entries
                        .firstOrNull { it.key.equals(name, ignoreCase = true) }
                        ?.value
                        ?.takeIf { it.isNotBlank() }

                    val headers = linkedMapOf<String, String>()
                    headers["User-Agent"] = header("User-Agent") ?: NetworkClient.USER_AGENT
                    headers["Referer"] = header("Referer") ?: "$embedOrigin/"
                    headers["Origin"] = header("Origin") ?: embedOrigin
                    CookieManager.getInstance().getCookie(requestUrl)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { headers["Cookie"] = it }

                    val path = requestUrl.substringBefore('?').substringBefore('#')
                    val type = when {
                        path.endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                        path.endsWith(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                        else -> null
                    }
                    finish(Video(source = requestUrl, headers = headers, type = type))
                    return null
                }
            }

            playbackKickRunnable = Runnable {
                if (!completed.get()) {
                    webView.evaluateJavascript(
                        "(function(){" +
                            "var v=document.querySelector('video');" +
                            "if(v){try{v.play();}catch(e){}}" +
                            "document.querySelectorAll('[class*=play],[id*=play]').forEach(function(e){try{e.click();}catch(x){}});" +
                            "return true;" +
                        "})();",
                        null,
                    )
                }
            }.also { handler.postDelayed(it, 5_000L) }

            timeoutRunnable = Runnable {
                finish(error = Exception("Timed out waiting for a playable stream request"))
            }.also { handler.postDelayed(it, timeoutMs) }

            webView.loadUrl(
                link,
                mapOf(
                    "Referer" to referer,
                    "User-Agent" to NetworkClient.USER_AGENT,
                ),
            )

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    removeCallbacks()
                    handler.post { destroyWebView() }
                }
            }
        }
    }
}
