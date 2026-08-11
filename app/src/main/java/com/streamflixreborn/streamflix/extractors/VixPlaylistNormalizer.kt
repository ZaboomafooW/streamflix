package com.streamflixreborn.streamflix.extractors

import java.util.Locale

/** Repairs only the malformed Forced-subtitle attributes used by Vix-family manifests. */
internal object VixPlaylistNormalizer {

    private val forcedYes = Regex("""\bFORCED=YES\b""", RegexOption.IGNORE_CASE)
    private val forcedAttribute =
        Regex("""\bFORCED=(?:YES|NO)\b""", RegexOption.IGNORE_CASE)
    private val forcedLabel =
        Regex("""(?:^|[^a-z])forced(?:[^a-z]|$)""", RegexOption.IGNORE_CASE)
    private val languageAttribute =
        Regex("""\bLANGUAGE="[^"]*"""", RegexOption.IGNORE_CASE)

    fun normalizeForcedSubtitleLine(line: String): String {
        if (!line.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES")) return line

        val name = quotedAttribute(line, "NAME")
        val rawLanguage = quotedAttribute(line, "LANGUAGE")
        val forcedLanguage = rawLanguage?.let(::canonicalForcedLanguage)
        val isForced = forcedYes.containsMatchIn(line) ||
            name?.let(forcedLabel::containsMatchIn) == true ||
            forcedLanguage != null

        if (!isForced) return line

        var normalized = if (forcedAttribute.containsMatchIn(line)) {
            forcedAttribute.replace(line, "FORCED=YES")
        } else {
            "$line,FORCED=YES"
        }

        if (forcedLanguage != null && languageAttribute.containsMatchIn(normalized)) {
            normalized = languageAttribute.replace(
                normalized,
                "LANGUAGE=\"$forcedLanguage\"",
            )
        }

        return normalized
    }

    private fun quotedAttribute(line: String, attribute: String): String? =
        Regex("""\b${Regex.escape(attribute)}="([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(line)
            ?.groupValues
            ?.getOrNull(1)

    private fun canonicalForcedLanguage(value: String): String? {
        val normalized = value.trim().lowercase(Locale.ROOT).replace('_', '-')
        val language = when {
            normalized.startsWith("forced-") -> normalized.removePrefix("forced-")
            normalized.endsWith("-forced") -> normalized.removeSuffix("-forced")
            else -> return null
        }
        val primary = language.substringBefore('-').takeIf { it.isNotBlank() } ?: return null

        return Locale.getISOLanguages().firstOrNull { languageCode ->
            languageCode.equals(primary, ignoreCase = true) ||
                runCatching {
                    Locale.forLanguageTag(languageCode).isO3Language.equals(primary, ignoreCase = true)
                }.getOrDefault(false)
        }
    }
}
