package com.streamflixreborn.streamflix.utils

import androidx.media3.common.C
import androidx.media3.common.Format
import java.util.Locale

/**
 * Ephemeral language metadata for the item currently loaded in the player.
 *
 * This is display context only. It does not persist user preferences or influence track selection.
 */
object PlaybackLanguageContext {

    @Volatile
    private var originalAudioLanguage: String? = null

    fun setOriginalAudioLanguage(language: String?) {
        originalAudioLanguage = canonicalLanguage(language)
    }

    fun isOriginalAudioTrack(format: Format): Boolean {
        val originalLanguage = originalAudioLanguage ?: return false
        if (isAlternateAudio(format)) return false

        val metadataLanguage = canonicalLanguage(format.language)
        if (metadataLanguage != null) return metadataLanguage == originalLanguage

        return labelMatchesLanguage(format.label, originalLanguage)
    }

    private fun isAlternateAudio(format: Format): Boolean {
        if (format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) return true
        if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) return true

        val label = normalizeLabel(format.label.orEmpty())
        return containsWord(label, "commentary") ||
            containsWord(label, "descriptive") ||
            label.contains("audio description") ||
            label.contains("audio described")
    }

    private fun labelMatchesLanguage(label: String?, language: String): Boolean {
        val normalizedLabel = normalizeLabel(label ?: return false)
        if (normalizedLabel.isBlank()) return false

        return languageAliases(language).any { alias ->
            normalizedLabel == alias ||
                normalizedLabel.startsWith("$alias ") ||
                normalizedLabel.endsWith(" $alias") ||
                normalizedLabel.contains(" $alias ")
        }
    }

    private fun languageAliases(language: String): Set<String> {
        val canonical = canonicalLanguage(language) ?: return emptySet()
        val locale = Locale.forLanguageTag(canonical)

        return buildSet {
            add(locale.language)
            runCatching { locale.isO3Language }.getOrNull()?.let(::add)
            add(locale.getDisplayLanguage(Locale.ENGLISH))
            add(locale.getDisplayLanguage(locale))
            add(locale.getDisplayLanguage(Locale.getDefault()))
        }.map(::normalizeLabel)
            .filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun canonicalLanguage(language: String?): String? {
        val primary = language
            ?.trim()
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?: return null

        return Locale.getISOLanguages().firstOrNull { languageCode ->
            languageCode.equals(primary, ignoreCase = true) ||
                runCatching {
                    Locale.forLanguageTag(languageCode).isO3Language.equals(primary, ignoreCase = true)
                }.getOrDefault(false)
        }
    }

    private fun normalizeLabel(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(NON_WORD, " ")
        .trim()

    private fun containsWord(normalizedLabel: String, word: String): Boolean =
        normalizedLabel == word ||
            normalizedLabel.startsWith("$word ") ||
            normalizedLabel.endsWith(" $word") ||
            normalizedLabel.contains(" $word ")

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
