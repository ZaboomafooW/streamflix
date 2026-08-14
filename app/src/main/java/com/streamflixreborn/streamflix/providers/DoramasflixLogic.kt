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

    private val genericTitleNormalizedValues = setOf(
        "n a",
        "na",
        "no title available",
        "sin titulo disponible",
    )

    private val genericImagePattern = Regex(
        "(?:^|[/_.-])(placeholder|no[-_ ]?image|image[-_ ]?not[-_ ]?found|sin[-_ ]?imagen|missing[-_ ]?image)(?:[/_.-]|$)",
        RegexOption.IGNORE_CASE,
    )

    private val spanishMonths = mapOf(
        "enero" to 1,
        "febrero" to 2,
        "marzo" to 3,
        "abril" to 4,
        "mayo" to 5,
        "junio" to 6,
        "julio" to 7,
        "agosto" to 8,
        "septiembre" to 9,
        "setiembre" to 9,
        "octubre" to 10,
        "noviembre" to 11,
        "diciembre" to 12,
    )

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

    fun firstNonBlank(vararg values: String?): String? =
        values.asSequence()
            .mapNotNull { value -> value?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull()

    fun meaningfulTitle(
        value: String?,
        providerSlug: String? = null,
    ): String? {
        val title = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedTitle = normalizeWords(title)
        if (normalizedTitle in genericTitleNormalizedValues) return null
        if (title.equals("Doramasflix", ignoreCase = true)) return null

        val slug = providerSlug
            ?.trim()
            ?.removePrefix("/")
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() }
        if (slug != null && slug.contains('-')) {
            val normalizedSlug = normalizeWords(slug.replace('-', ' '))
            if (normalizedTitle == normalizedSlug && title.contains('-')) return null
        }

        return title
    }

    fun meaningfulImage(value: String?): String? {
        val image = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (genericImagePattern.containsMatchIn(image.lowercase(Locale.ROOT))) return null
        return image
    }

    fun meaningfulRuntime(value: Int?): Int? = value?.takeIf { it > 0 }

    fun normalizeDate(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        normalizeAirDate(raw)?.let { normalized ->
            if (!normalized.startsWith("0000-") && !normalized.startsWith("0001-")) {
                return normalized
            }
        }

        val spanish = Regex(
            "^(\\d{1,2})\\s+de\\s+([\\p{L}]+)\\s+de\\s+(\\d{4})$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(raw) ?: return null
        val day = spanish.groupValues[1].toIntOrNull() ?: return null
        val monthName = normalizeWords(spanish.groupValues[2])
        val month = spanishMonths[monthName] ?: return null
        val year = spanish.groupValues[3].toIntOrNull()?.takeIf { it > 1 } ?: return null
        if (day !in 1..31) return null
        return "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)
    }

    fun episodeArtwork(
        stillPath: String?,
        backdrop: String?,
        stillImage: String?,
    ): String? = sequenceOf(
        stillPath,
        backdrop,
        stillImage,
    ).mapNotNull(::meaningfulImage)
        .firstOrNull()

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
            value.matches(Regex("^[A-Za-z0-9_-]{11}$")) -> "https://www.youtube.com/watch?v=$value"
            else -> null
        }
    }

    fun normalizeAirDate(airDate: String?): String? {
        val value = airDate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val numeric = value.toLongOrNull()
        if (numeric != null && value.all(Char::isDigit)) {
            return when (value.length) {
                10 -> runCatching {
                    Instant.ofEpochSecond(numeric)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .toString()
                }.getOrNull()
                in 11..17 -> runCatching {
                    Instant.ofEpochMilli(numeric)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .toString()
                }.getOrNull()
                else -> null
            }
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

    private fun normalizeWords(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
