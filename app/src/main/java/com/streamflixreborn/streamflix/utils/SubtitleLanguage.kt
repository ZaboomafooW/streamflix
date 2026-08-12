package com.streamflixreborn.streamflix.utils

import java.util.Locale

object SubtitleLanguage {

    private val iso3ToIso2: Map<String, String> by lazy {
        Locale.getAvailableLocales()
            .asSequence()
            .filter { it.language.length == 2 }
            .mapNotNull { locale ->
                runCatching { locale.getISO3Language().lowercase(Locale.ROOT) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { it to locale.language.lowercase(Locale.ROOT) }
            }
            .toMap()
    }

    fun normalize(value: String?): String? {
        val parts = value
            ?.trim()
            ?.replace('_', '-')
            ?.split('-')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (parts.isEmpty()) return null

        val rawLanguage = parts.first().lowercase(Locale.ROOT)
        val language = when (rawLanguage.length) {
            2 -> rawLanguage
            3 -> iso3ToIso2[rawLanguage] ?: rawLanguage
            else -> return null
        }
        if (language == "und") return null

        val region = parts.getOrNull(1)
            ?.takeIf { it.length == 2 || it.length == 3 && it.all(Char::isDigit) }
            ?.uppercase(Locale.ROOT)

        return if (region == null) language else "$language-$region"
    }

    fun displayName(
        language: String?,
        explicitName: String? = null,
    ): String {
        explicitName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val tag = normalize(language) ?: return "Unknown"
        val displayName = Locale.forLanguageTag(tag)
            .getDisplayLanguage(Locale.ENGLISH)
            .trim()
        return displayName.takeIf { it.isNotBlank() } ?: tag
    }
}
