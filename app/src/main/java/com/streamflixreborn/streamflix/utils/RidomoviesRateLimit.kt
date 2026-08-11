package com.streamflixreborn.streamflix.utils

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

internal object RidomoviesRateLimit {

    private const val HOST = "ridomovies.su"
    private const val ARTWORK_PATH_PREFIX = "/uploads/"
    private val artworkIntervalNanos = TimeUnit.MILLISECONDS.toNanos(100)

    private val lock = Any()
    private var nextArtworkStartNanos = 0L
    private var blockedUntilNanos = 0L

    fun isArtworkRequest(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        return (host == HOST || host == "www.$HOST") &&
            url.encodedPath.startsWith(ARTWORK_PATH_PREFIX)
    }

    fun reserveArtworkDelayNanos(nowNanos: Long = System.nanoTime()): Long? =
        synchronized(lock) {
            if (blockedUntilNanos > nowNanos) return@synchronized null

            val startAt = maxOf(nowNanos, nextArtworkStartNanos)
            nextArtworkStartNanos = startAt + artworkIntervalNanos
            startAt - nowNanos
        }

    fun recordRetryAfter(value: String?, nowNanos: Long = System.nanoTime()) {
        val seconds = value?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val durationNanos = runCatching { TimeUnit.SECONDS.toNanos(seconds) }.getOrNull() ?: return
        val until = nowNanos + durationNanos
        if (until < nowNanos) return

        synchronized(lock) {
            blockedUntilNanos = maxOf(blockedUntilNanos, until)
            nextArtworkStartNanos = maxOf(nextArtworkStartNanos, blockedUntilNanos)
        }
    }

    fun remainingCooldownNanos(nowNanos: Long = System.nanoTime()): Long =
        synchronized(lock) { (blockedUntilNanos - nowNanos).coerceAtLeast(0L) }

    fun remainingCooldownSeconds(nowNanos: Long = System.nanoTime()): Long {
        val remaining = remainingCooldownNanos(nowNanos)
        if (remaining <= 0L) return 0L
        return TimeUnit.NANOSECONDS.toSeconds(remaining - 1L) + 1L
    }

    fun message(nowNanos: Long = System.nanoTime()): String {
        val seconds = remainingCooldownSeconds(nowNanos)
        if (seconds <= 0L) return "Ridomovies is temporarily rate limiting requests."

        return if (seconds >= 60L) {
            val minutes = (seconds + 59L) / 60L
            "Ridomovies is temporarily rate limiting requests. Try again in $minutes minute${if (minutes == 1L) "" else "s"}."
        } else {
            "Ridomovies is temporarily rate limiting requests. Try again in $seconds second${if (seconds == 1L) "" else "s"}."
        }
    }
}

internal class RidomoviesArtworkRateLimitInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!RidomoviesRateLimit.isArtworkRequest(request.url)) {
            return chain.proceed(request)
        }

        if (chain.call().isCanceled()) throw IOException("Canceled")

        val delayNanos = RidomoviesRateLimit.reserveArtworkDelayNanos()
            ?: throw IOException(RidomoviesRateLimit.message())
        if (delayNanos > 0L) {
            try {
                TimeUnit.NANOSECONDS.sleep(delayNanos)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while pacing Ridomovies artwork").apply {
                    initCause(error)
                }
            }
        }

        if (chain.call().isCanceled()) throw IOException("Canceled")
        if (RidomoviesRateLimit.remainingCooldownNanos() > 0L) {
            throw IOException(RidomoviesRateLimit.message())
        }

        return chain.proceed(request).also { response ->
            if (response.code == 429) {
                RidomoviesRateLimit.recordRetryAfter(response.header("Retry-After"))
            }
        }
    }
}
