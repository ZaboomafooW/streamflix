package com.streamflixreborn.streamflix.utils

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.CacheControl
import okhttp3.Call as OkHttpCall
import okhttp3.Callback as OkHttpCallback
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Temporary transport facade kept while AnimeOnlineNinjaProvider is migrated off its
 * former Cronet-specific call sites. Requests are handled entirely by StreamFlix's
 * shared OkHttp/Conscrypt stack; no Cronet engine or browser runtime is involved.
 */
object AnimeOnlineNinjaCronetClient {
    data class Response(
        val statusCode: Int,
        val finalUrl: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299

        fun bodyAsString(): String = body.toString(Charsets.UTF_8)
    }

    fun interface Callback {
        fun onComplete(result: Result<Response>)
    }

    class Call internal constructor() {
        @Volatile
        private var request: OkHttpCall? = null
        private val cancelled = AtomicBoolean(false)

        internal fun attach(request: OkHttpCall) {
            this.request = request
            if (cancelled.get()) request.cancel()
        }

        fun cancel() {
            cancelled.set(true)
            request?.cancel()
        }

        internal fun isCancelled(): Boolean = cancelled.get()
    }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    suspend fun get(
        context: Context,
        url: String,
        headers: Map<String, String>,
        useCache: Boolean = true,
    ): Response = suspendCancellableCoroutine { continuation ->
        val call = get(context, url, headers, useCache) { result ->
            if (!continuation.isActive) return@get
            result.fold(continuation::resume, continuation::resumeWithException)
        }
        continuation.invokeOnCancellation { call.cancel() }
    }

    fun get(
        @Suppress("UNUSED_PARAMETER") context: Context,
        url: String,
        headers: Map<String, String>,
        useCache: Boolean = true,
        callback: Callback,
    ): Call {
        val request = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (name, value) ->
                    if (value.isNotBlank()) header(name, value)
                }
                if (!useCache) cacheControl(CacheControl.FORCE_NETWORK)
            }
            .build()

        val wrapper = Call()
        val call = NetworkClient.default.newCall(request)
        wrapper.attach(call)
        call.enqueue(object : OkHttpCallback {
            override fun onFailure(call: OkHttpCall, e: IOException) {
                if (!wrapper.isCancelled()) callback.onComplete(Result.failure(e))
            }

            override fun onResponse(call: OkHttpCall, response: OkHttpResponse) {
                response.use {
                    if (wrapper.isCancelled()) return
                    val body = it.body?.bytes().orEmpty()
                    callback.onComplete(
                        Result.success(
                            Response(
                                statusCode = it.code,
                                finalUrl = it.request.url.toString(),
                                headers = it.headers.toMultimap(),
                                body = body,
                            )
                        )
                    )
                }
            }
        })
        return wrapper
    }
}
