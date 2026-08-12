package com.streamflixreborn.streamflix.utils

internal object PlaybackTrackDisplayNames {

    fun subtitleName(sourceLabel: String?, media3Name: String): String {
        val label = sourceLabel?.trim().orEmpty()
        return label.takeUnless {
            it.isBlank() || it.equals("und", ignoreCase = true) ||
                it.equals("unknown", ignoreCase = true)
        } ?: media3Name.trim()
    }

    fun disambiguate(names: List<String>): List<String> {
        val occurrences = mutableMapOf<String, Int>()
        return names.map { rawName ->
            val name = rawName.trim()
            val occurrence = occurrences.getOrDefault(name, 0) + 1
            occurrences[name] = occurrence
            if (occurrence == 1) name else "$name ($occurrence)"
        }
    }
}
