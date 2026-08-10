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
        if (format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) return false
        if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) return false

        val trackLanguage = canonicalLanguage(format.language) ?: return false
        return trackLanguage == originalLanguage
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
}
