package com.streamflixreborn.streamflix.providers

import com.google.gson.JsonParser
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

internal data class DoramasflixRatingDecision(
    val rating: Double?,
    val useHtmlFallback: Boolean,
)

internal object DoramasflixLogic {

    fun resolveApiRating(
        rating: Double?,
        ratingCount: Int?,
    ): DoramasflixRatingDecision {
        if (ratingCount != null) {
            if (ratingCount <= 0) {
                return DoramasflixRatingDecision(rating = null, useHtmlFallback = false)
            }

            val validRating = rating?.takeIf { it > 0.0 }
            return DoramasflixRatingDecision(
                rating = validRating,
                useHtmlFallback = validRating == null,
            )
        }

        val validRating = rating?.takeIf { it > 0.0 }
        return DoramasflixRatingDecision(
            rating = validRating,
            useHtmlFallback = validRating == null,
        )
    }

    fun episodeArtwork(
        stillPath: String?,
        backdrop: String?,
        stillImage: String?,
        seriesBackdropPath: String?,
        repeatedSeasonArtwork: String? = null,
    ): String? {
        val excludedArtwork = setOfNotNull(
            seriesBackdropPath?.trim()?.takeIf { it.isNotEmpty() },
            repeatedSeasonArtwork?.trim()?.takeIf { it.isNotEmpty() },
        )

        return listOf(stillPath, backdrop, stillImage)
            .asSequence()
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull { it !in excludedArtwork }
    }

    fun duplicatedEpisodeArtwork(artwork: List<String?>): Set<String> {
        val normalized = artwork
            .mapNotNull { value -> value?.trim()?.takeIf { it.isNotEmpty() } }
        if (normalized.size <= 1) return emptySet()

        val sharedArtwork = normalized.first()
        return if (normalized.all { it == sharedArtwork }) {
            setOf(sharedArtwork)
        } else {
            emptySet()
        }
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
            "ok", "okru", "ok.ru" -> "OK.ru"
            "voe" -> "VOE"
            "mixdrop" -> "MixDrop"
            "streamwish" -> "Streamwish"
            else -> value
        }
    }

    fun subtitleDescriptor(
        languageCode: String?,
        type: String?,
    ): String? {
        val language = languageCode
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase(Locale.ROOT)
        val subtitleType = type
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase(Locale.ROOT)

        return listOfNotNull(language, subtitleType)
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }
    }

    fun playbackSourceName(
        serverName: String,
        languageName: String?,
        languageCode: String?,
        subtitleDescriptors: List<String>,
    ): String {
        val language = languageName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: languageCode?.trim()?.takeIf { it.isNotEmpty() }

        val subtitles = subtitleDescriptors
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }

        return listOfNotNull(
            serverName.trim().takeIf { it.isNotEmpty() },
            language,
            subtitles,
        ).joinToString(" · ")
    }

    fun graphQlErrorMessage(body: String?): String? {
        val root = body
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw -> runCatching { JsonParser.parseString(raw) }.getOrNull() }
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null

        val errors = root.getAsJsonArray("errors") ?: return null
        return errors
            .asSequence()
            .mapNotNull { error ->
                error.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("message")
                    ?.let { message -> runCatching { message.asString }.getOrNull() }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            .distinct()
            .joinToString("; ")
            .takeIf { it.isNotEmpty() }
    }
}