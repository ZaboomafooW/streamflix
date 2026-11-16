package com.streamflixreborn.streamflix.utils

import java.util.concurrent.TimeUnit

internal object RidomoviesRateLimit {

    private val lock = Any()
    private var blockedUntilNanos = 0L

    fun recordRetryAfter(value: String?, nowNanos: Long = System.nanoTime()) {
        val seconds = value?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val durationNanos = runCatching { TimeUnit.SECONDS.toNanos(seconds) }.getOrNull() ?: return
        val until = nowNanos + durationNanos
        if (until < nowNanos) return

        synchronized(lock) {
            blockedUntilNanos = maxOf(blockedUntilNanos, until)
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
