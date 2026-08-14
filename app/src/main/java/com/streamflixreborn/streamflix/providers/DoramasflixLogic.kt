package com.streamflixreborn.streamflix.providers

import com.google.gson.JsonParser
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

internal class DoramasflixContentNotFoundException(message: String) : Exception(message)

internal class DoramasflixUnavailableException(cause: Throwable? = null) :
    ProviderUnavailableException("Doramasflix", cause)

internal data class DoramasflixRatingDecision(
    val rating: Double?,
    val allowExternalFallback: Boolean,
)

internal object DoramasflixLogic {

    private val obviousImagePlaceholder = Regex(
        "(?:^|[/_.-])(placeholder|no[-_ ]?image|image[-_ ]?not[-_ ]?found|sin[-_ ]?imagen|missing[-_ ]?image)(?:[/_.-]|$)",
        RegexOption.IGNORE_CASE,
    )

    private val genericEpisodeOverviewFragments = listOf(
        "disfruta del capitulo",
        "selecciona tu servidor",
        "audio y subtitulos en espanol",
        "ver episodio online",
        "ver capitulo online",
        "reportar video",
    )

    private val genericEpisodeOverviewValues = setOf(
        "sinopsis pendiente",
        "proximamente",
        "sin descripcion",
        "no hay descripcion",
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

    fun isUnavailableHttpStatus(statusCode: Int): Boolean = statusCode in 500..599

    fun resolveApiRating(
        rating: Double?,
        ratingCount: Int?,
    ): DoramasflixRatingDecision {
        if (ratingCount != null && ratingCount < 0) {
            return DoramasflixRatingDecision(rating = null, allowExternalFallback = true)
        }
        if (ratingCount == 0) {
            return DoramasflixRatingDecision(rating = null, allowExternalFallback = false)
        }
        if (rating == 0.0) {
            return DoramasflixRatingDecision(rating = null, allowExternalFallback = false)
        }
        if (rating == null) {
            return DoramasflixRatingDecision(rating = null, allowExternalFallback = true)
        }
        if (!rating.isFinite() || rating < 0.0 || rating > 5.0) {
            return DoramasflixRatingDecision(rating = null, allowExternalFallback = true)
        }
        return DoramasflixRatingDecision(rating = rating, allowExternalFallback = false)
    }

    fun resolveRating(
        apiRating: Double?,
        apiRatingCount: Int?,
        tmdbRating: Double?,
    ): Double? {
        val api = resolveApiRating(apiRating, apiRatingCount)
        if (!api.allowExternalFallback) return api.rating

        return tmdbRating
            ?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
            ?.div(2.0)
    }

    fun nonBlank(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    fun firstNonBlank(vararg values: String?): String? =
        values.asSequence().mapNotNull(::nonBlank).firstOrNull()

    fun meaningfulImage(value: String?): String? {
        val image = nonBlank(value) ?: return null
        if (obviousImagePlaceholder.containsMatchIn(image)) return null
        return image
    }

    fun meaningfulEpisodeTitle(
        value: String?,
        showTitles: Iterable<String?>,
        seasonNumber: Int,
        episodeNumber: Int,
    ): String? {
        val title = nonBlank(value) ?: return null
        val normalized = normalizeWords(title)
        if (isGenericEpisodeMarker(normalized, seasonNumber, episodeNumber)) return null

        val isShowPrefixedMarker = showTitles
            .mapNotNull(::nonBlank)
            .map(::normalizeWords)
            .filter(String::isNotEmpty)
            .any { showTitle ->
                val suffix = normalized.removePrefix(showTitle).trim()
                suffix != normalized &&
                    isGenericEpisodeMarker(suffix, seasonNumber, episodeNumber)
            }
        return title.takeUnless { isShowPrefixedMarker }
    }

    fun meaningfulEpisodeOverview(
        value: String?,
        showOverview: String?,
        showTitles: Iterable<String?>,
        seasonNumber: Int,
        episodeNumber: Int,
    ): String? {
        val overview = nonBlank(value) ?: return null
        val normalized = normalizeWords(overview)
        val normalizedShowOverview = nonBlank(showOverview)?.let(::normalizeWords)
        if (normalizedShowOverview != null && normalized == normalizedShowOverview) return null
        if (normalized in genericEpisodeOverviewValues) return null
        if (genericEpisodeOverviewFragments.any(normalized::contains)) return null
        if (meaningfulEpisodeTitle(overview, showTitles, seasonNumber, episodeNumber) == null) {
            return null
        }
        return overview
    }

    fun meaningfulRuntime(value: Int?): Int? = value?.takeIf { it > 0 }

    fun normalizeDate(value: String?): String? {
        val raw = nonBlank(value) ?: return null
        normalizeAirDate(raw)?.let { return it }

        val spanish = Regex(
            "^(\\d{1,2})\\s+de\\s+([\\p{L}]+)\\s+de\\s+(\\d{4})$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(raw) ?: return null
        val day = spanish.groupValues[1].toIntOrNull() ?: return null
        val month = spanishMonths[normalizeWords(spanish.groupValues[2])] ?: return null
        val year = spanish.groupValues[3].toIntOrNull()?.takeIf { it > 1 } ?: return null
        return runCatching { LocalDate.of(year, month, day).toString() }.getOrNull()
    }

    fun normalizeAirDate(airDate: String?): String? {
        val value = nonBlank(airDate) ?: return null
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

        val isoDate = value.takeIf { it.length >= 10 }?.take(10) ?: return null
        return try {
            LocalDate.parse(isoDate).toString()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun episodeArtwork(
        stillPath: String?,
        backdrop: String?,
        stillImage: String?,
        genericArtwork: Iterable<String?> = emptyList(),
        tmdbArtwork: String? = null,
    ): String? {
        val genericKeys = genericArtwork
            .mapNotNull(::meaningfulImage)
            .map(::imageIdentity)
            .toSet()

        return sequenceOf(stillPath, stillImage, backdrop)
            .mapNotNull(::meaningfulImage)
            .firstOrNull { candidate -> imageIdentity(candidate) !in genericKeys }
            ?: meaningfulImage(tmdbArtwork)
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

        while (result.size < limit && (firstIndex < first.size || secondIndex < second.size)) {
            if (firstIndex < first.size && result.size < limit) result += first[firstIndex++]
            if (secondIndex < second.size && result.size < limit) result += second[secondIndex++]
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
        val value = nonBlank(trailer) ?: return null
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.matches(Regex("^[A-Za-z0-9_-]{11}$")) -> "https://www.youtube.com/watch?v=$value"
            else -> null
        }
    }

    fun normalizeServerName(name: String?): String? {
        val value = nonBlank(name) ?: return null
        return when (value.lowercase(Locale.ROOT)) {
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
        val language = nonBlank(languageCode)?.uppercase(Locale.ROOT)
        val subtitleType = nonBlank(type)?.uppercase(Locale.ROOT)
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
        val language = nonBlank(languageName) ?: nonBlank(languageCode)
        val subtitles = subtitleDescriptors
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }

        return listOfNotNull(nonBlank(serverName), language, subtitles).joinToString(" · ")
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

    private fun isGenericEpisodeMarker(
        value: String,
        seasonNumber: Int,
        episodeNumber: Int,
    ): Boolean {
        val season = seasonNumber.toString()
        val episode = episodeNumber.toString()
        return value.matches(Regex("(?:episode|episodio|capitulo)\\s+0*$episode")) ||
            value.matches(Regex("0*${season}x0*$episode")) ||
            value.matches(Regex("s0*$season\\s+e0*$episode"))
    }

    private fun imageIdentity(value: String): String = value
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('/')
        .substringAfterLast('/')
        .lowercase(Locale.ROOT)

    private fun normalizeWords(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
