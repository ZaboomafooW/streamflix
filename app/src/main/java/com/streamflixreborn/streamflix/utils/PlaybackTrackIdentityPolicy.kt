package com.streamflixreborn.streamflix.utils

import java.util.Locale

/** Decides when language alone cannot safely represent a subtitle selection. */
internal object PlaybackTrackIdentityPolicy {

    fun requiresExactAudioIdentity(sameLanguageTrackCount: Int): Boolean =
        sameLanguageTrackCount > 1

    fun requiresExactSubtitleIdentity(
        label: String?,
        language: String?,
        languageAliases: Set<String>,
        sameLanguageVariantCount: Int,
    ): Boolean = sameLanguageVariantCount > 1 ||
        hasQualifiedLanguageTag(language) ||
        labelHasQualifier(label, languageAliases)

    fun <T> resolveUniqueIdentity(
        candidates: List<T>,
        identityMatches: (T) -> Boolean,
    ): T? = candidates.filter(identityMatches).singleOrNull()

    private fun hasQualifiedLanguageTag(language: String?): Boolean {
        val normalized = language
            ?.trim()
            ?.replace('_', '-')
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return normalized.substringAfter('-', missingDelimiterValue = "").isNotBlank()
    }

    private fun labelHasQualifier(label: String?, languageAliases: Set<String>): Boolean {
        var remainder = normalize(label.orEmpty())
        if (remainder.isBlank()) return false

        val genericParts = buildSet {
            addAll(languageAliases.map(::normalize))
            addAll(GENERIC_SUBTITLE_PARTS)
        }.filter { it.isNotBlank() }
            .sortedByDescending(String::length)

        genericParts.forEach { part ->
            remainder = removePhrase(remainder, part)
        }
        return remainder.isNotBlank()
    }

    private fun removePhrase(value: String, phrase: String): String =
        " $value ".replace(" $phrase ", " ").trim()

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(NON_WORD, " ")
        .trim()

    private val GENERIC_SUBTITLE_PARTS = setOf(
        "cc",
        "sdh",
        "closed caption",
        "closed captions",
        "caption",
        "captions",
        "subtitle",
        "subtitles",
    )
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
