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
import org.json.JSONArray
import org.json.JSONTokener
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
            val sourceCaptured = AtomicBoolean(false)
            val embedUrl = link.toHttpUrlOrNull()
                ?: run {
                    continuation.resumeWithException(Exception("Invalid embed URL"))
                    return@suspendCancellableCoroutine
                }
            val embedOrigin = "${embedUrl.scheme}://${embedUrl.host}"
            val webView = WebView(StreamFlixApp.instance.applicationContext)
            var timeoutRunnable: Runnable? = null
            var playbackKickRunnable: Runnable? = null
            var sourcePollRunnable: Runnable? = null

            fun removeCallbacks() {
                timeoutRunnable?.let(handler::removeCallbacks)
                playbackKickRunnable?.let(handler::removeCallbacks)
                sourcePollRunnable?.let(handler::removeCallbacks)
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

            fun subtitlesFromJavascript(result: String?): List<Video.Subtitle> {
                if (result.isNullOrBlank() || result == "null") return emptyList()
                return runCatching {
                    val decoded = JSONTokener(result).nextValue()
                    val array = when (decoded) {
                        is JSONArray -> decoded
                        is String -> JSONArray(decoded)
                        else -> JSONArray()
                    }
                    buildList {
                        for (index in 0 until array.length()) {
                            val row = array.optJSONObject(index) ?: continue
                            val file = row.optString("file").trim()
                            val language = row.optString("language").trim()
                            val label = row.optString("label").trim().ifBlank { language }
                            if (file.isBlank() || label.isBlank()) continue
                            add(
                                Video.Subtitle(
                                    label = label,
                                    file = file,
                                    default = row.optBoolean("default", false),
                                    initialDefault = row.optBoolean("default", false),
                                )
                            )
                        }
                    }.distinctBy { it.file }
                }.getOrDefault(emptyList())
            }

            fun capture(url: String, headers: Map<String, String>) {
                if (!isMediaRequest(url) || !sourceCaptured.compareAndSet(false, true)) return
                val path = url.substringBefore('?').substringBefore('#')
                val type = when {
                    path.endsWith(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                    path.endsWith(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
                    else -> null
                }

                handler.post {
                    if (completed.get()) return@post
                    webView.evaluateJavascript(
                        """
                        (function() {
                            var result = [];
                            document.querySelectorAll('track[kind="subtitles"],track[kind="captions"]').forEach(function(t) {
                                var file = t.src || t.getAttribute('src') || '';
                                var language = t.srclang || t.getAttribute('srclang') || '';
                                var label = t.label || t.getAttribute('label') || language;
                                if (file && label) {
                                    try { file = new URL(file, location.href).href; } catch (e) {}
                                    result.push({file:file,label:label,language:language,default:!!t.default});
                                }
                            });
                            return JSON.stringify(result);
                        })();
                        """.trimIndent(),
                    ) { subtitleResult ->
                        finish(
                            Video(
                                source = url,
                                subtitles = subtitlesFromJavascript(subtitleResult),
                                headers = headers,
                                type = type,
                            )
                        )
                    }
                }
            }

            fun defaultHeaders(url: String): Map<String, String> = linkedMapOf<String, String>().apply {
                put("User-Agent", NetworkClient.USER_AGENT)
                put("Referer", "$embedOrigin/")
                put("Origin", embedOrigin)
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("Cookie", it) }
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
                    if (completed.get() || sourceCaptured.get()) return null
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
                    header("Origin")?.let { headers["Origin"] = it }
                    CookieManager.getInstance().getCookie(requestUrl)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { headers["Cookie"] = it }

                    capture(requestUrl, headers)
                    return null
                }
            }

            sourcePollRunnable = object : Runnable {
                override fun run() {
                    if (completed.get() || sourceCaptured.get()) return
                    webView.evaluateJavascript(
                        """
                        (function() {
                            var urls = [];
                            function add(value) {
                                if (!value || typeof value !== 'string') return;
                                try { value = new URL(value, location.href).href; } catch (e) {}
                                if (urls.indexOf(value) === -1) urls.push(value);
                            }
                            document.querySelectorAll('video').forEach(function(v) {
                                add(v.currentSrc); add(v.src); add(v.getAttribute('src'));
                            });
                            document.querySelectorAll('video source,source[type*=video]').forEach(function(s) {
                                add(s.src); add(s.getAttribute('src'));
                            });
                            try {
                                performance.getEntriesByType('resource').forEach(function(entry) {
                                    if (/\.m3u8(?:[?#]|$)/i.test(entry.name || '')) add(entry.name);
                                });
                            } catch (e) {}
                            return JSON.stringify(urls);
                        })();
                        """.trimIndent(),
                    ) { result ->
                        if (!completed.get() && !sourceCaptured.get()) {
                            val urls = runCatching {
                                val decoded = JSONTokener(result).nextValue()
                                val array = when (decoded) {
                                    is JSONArray -> decoded
                                    is String -> JSONArray(decoded)
                                    else -> JSONArray()
                                }
                                buildList {
                                    for (index in 0 until array.length()) {
                                        array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                                    }
                                }
                            }.getOrDefault(emptyList())
                            urls.firstOrNull(isMediaRequest)?.let { capture(it, defaultHeaders(it)) }
                        }
                        if (!completed.get() && !sourceCaptured.get()) {
                            handler.postDelayed(this, 750L)
                        }
                    }
                }
            }.also { handler.postDelayed(it, 1_000L) }

            playbackKickRunnable = Runnable {
                if (!completed.get() && !sourceCaptured.get()) {
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
