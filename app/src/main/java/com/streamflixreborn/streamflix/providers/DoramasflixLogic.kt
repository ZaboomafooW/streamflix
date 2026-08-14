package com.streamflixreborn.streamflix.providers

import com.google.gson.JsonParser
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

internal data class DoramasflixRatingDecision(
    val rating: Double?,
    val useHtmlFallback: Boolean,
)

internal object DoramasflixLogic {

    private val genericOverviewValues = setOf(
        "n/a",
        "na",
        "sin sinopsis",
        "sin descripcion",
        "sin descripción",
        "sinopsis no disponible",
        "descripcion no disponible",
        "descripción no disponible",
        "no description",
        "no description available",
        "no overview",
        "no overview available",
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

    fun resolveRating(
        apiRating: Double?,
        apiRatingCount: Int?,
        websiteRating: Double?,
        tmdbRating: Double?,
    ): Double? {
        val api = resolveApiRating(apiRating, apiRatingCount)
        if (!api.useHtmlFallback) return api.rating

        return websiteRating?.takeIf { it > 0.0 }
            ?: tmdbRating
                ?.takeIf { it > 0.0 }
                ?.div(2.0)
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
        if (title.equals("Doramasflix", ignoreCase = true)) return null

        val slug = providerSlug
            ?.trim()
            ?.removePrefix("/")
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() }
        if (slug != null && slug.contains('-')) {
            val normalizedTitle = normalizeWords(title)
            val normalizedSlug = normalizeWords(slug.replace('-', ' '))
            if (normalizedTitle == normalizedSlug && title.contains('-')) return null
        }

        return title
    }

    fun meaningfulOverview(value: String?): String? {
        val overview = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = normalizeWords(overview)
        if (normalized in genericOverviewValues.map(::normalizeWords)) return null

        val lower = overview.lowercase(Locale.ROOT)
        if (
            lower.contains("episodio") &&
            (lower.contains("online gratis") || lower.contains("sub español") || lower.contains("subtitulado"))
        ) {
            return null
        }

        return overview
    }

    fun meaningfulEpisodeTitle(
        value: String?,
        seasonNumber: Int,
        episodeNumber: Int,
        seriesTitles: Collection<String?> = emptyList(),
    ): String? {
        val title = meaningfulTitle(value) ?: return null
        val normalized = normalizeWords(title)
        val season = seasonNumber.coerceAtLeast(0).toString()
        val episode = episodeNumber.coerceAtLeast(0).toString()
        val genericEpisode = Regex("^(?:episode|episodio|capitulo|chapter|ep)\\s*0*$episode$")
        val genericCode = Regex("^(?:s\\s*0*$season\\s*e\\s*0*$episode|0*$season\\s*x\\s*0*$episode)$")

        if (normalized == episode || genericEpisode.matches(normalized) || genericCode.matches(normalized)) {
            return null
        }

        val normalizedSeriesTitles = seriesTitles
            .mapNotNull { meaningfulTitle(it) }
            .map(::normalizeWords)
            .filter(String::isNotEmpty)
            .distinct()

        for (seriesTitle in normalizedSeriesTitles) {
            if (normalized == seriesTitle) return null
            if (!normalized.startsWith("$seriesTitle ")) continue

            val suffix = normalized.removePrefix("$seriesTitle ").trim()
            if (
                suffix == episode ||
                genericEpisode.matches(suffix) ||
                genericCode.matches(suffix)
            ) {
                return null
            }
        }

        return title
    }

    fun meaningfulImage(
        value: String?,
        genericArtwork: Collection<String?> = emptyList(),
    ): String? {
        val image = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (genericImagePattern.containsMatchIn(image.lowercase(Locale.ROOT))) return null

        if (genericArtwork.any { generic -> sameImageAsset(image, generic) }) return null
        return image
    }

    fun sameImageAsset(
        first: String?,
        second: String?,
    ): Boolean {
        val firstKey = imageAssetKey(first) ?: return false
        val secondKey = imageAssetKey(second) ?: return false
        return firstKey == secondKey
    }

    private fun imageAssetKey(value: String?): String? {
        var normalized = value
            ?.trim()
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val tmdbMarker = "/t/p/"
        if (normalized.contains(tmdbMarker, ignoreCase = true)) {
            normalized = normalized.substringAfter(tmdbMarker)
            normalized = normalized.substringAfter('/', normalized)
        } else if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized = normalized.substringAfter("://").substringAfter('/', "")
        }

        return normalized
            .trimStart('/')
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }
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

    fun doramaWebsitePath(
        slug: String,
        isTvShow: Boolean?,
    ): String = if (isTvShow == true) {
        "variedades-online/$slug"
    } else {
        "doramas-online/$slug"
    }

    fun episodeArtwork(
        stillPath: String?,
        backdrop: String?,
        stillImage: String?,
        websiteArtwork: String? = null,
        tmdbArtwork: String? = null,
        genericArtwork: Collection<String?> = emptyList(),
    ): String? = sequenceOf(
        stillPath,
        backdrop,
        stillImage,
        websiteArtwork,
        tmdbArtwork,
    ).mapNotNull { candidate -> meaningfulImage(candidate, genericArtwork) }
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

    private fun normalizeWords(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
