package com.streamflixreborn.streamflix.providers

import java.time.Instant
import java.time.ZoneOffset

internal object DoramasflixLogic {

    fun isEpisodeAvailable(countLinks: Int?): Boolean =
        countLinks != null && countLinks > 0

    fun episodeArtwork(
        stillPath: String?,
        backdrop: String?,
        stillImage: String?,
        seriesBackdropPath: String?,
    ): String? {
        val seriesBackdrop = seriesBackdropPath
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return listOf(stillPath, backdrop, stillImage)
            .asSequence()
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull { it != seriesBackdrop }
    }

    fun <T> mixAlternating(
        first: List<T>,
        second: List<T>,
        limit: Int = first.size + second.size,
    ): List<T> {
        if (limit <= 0) return emptyList()

        val result = ArrayList<T>(minOf(limit, first.size + second.size))
        var firstIndex = 0
        var secondIndex = 0

        while (
            result.size < limit &&
            (firstIndex < first.size || secondIndex < second.size)
        ) {
            if (firstIndex < first.size && result.size < limit) {
                result += first[firstIndex++]
            }
            if (secondIndex < second.size && result.size < limit) {
                result += second[secondIndex++]
            }
        }

        return result
    }

    fun normalizePlaybackTarget(link: String): String? {
        val normalized = link.trim()
        return when {
            normalized.startsWith("//") -> "https:$normalized"
            normalized.startsWith("https://") || normalized.startsWith("http://") -> normalized
            else -> null
        }
    }

    fun normalizeRating(rating: Double?): Double? =
        rating?.takeIf { it > 0.0 }

    fun normalizeTrailer(trailer: String?): String? {
        val value = trailer?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            else -> "https://www.youtube.com/watch?v=$value"
        }
    }

    fun normalizeAirDate(airDate: String?): String? {
        val value = airDate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val epochMillis = value.toLongOrNull()
        if (epochMillis != null) {
            return runCatching {
                Instant.ofEpochMilli(epochMillis)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString()
            }.getOrNull()
        }

        return value
            .takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}.*""")) }
            ?.take(10)
    }

    fun normalizeServerName(name: String?): String? {
        val value = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (value.lowercase()) {
            "dood" -> "DoodStream"
            "ok" -> "Okru"
            "voe" -> "VOE"
            "mixdrop" -> "MixDrop"
            "streamwish" -> "Streamwish"
            else -> value
        }
    }
}
