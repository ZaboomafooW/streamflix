package com.streamflixreborn.streamflix.providers

internal object DoramasflixPersonIdentity {

    fun tmdbId(providerPersonId: String?): Int? {
        val value = providerPersonId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val numericPrefix = value.substringBefore('-')
        if (numericPrefix.isEmpty() || !numericPrefix.all(Char::isDigit)) return null
        if ('-' in value && value.substringAfter('-').isBlank()) return null
        return numericPrefix.toIntOrNull()?.takeIf { it > 0 }
    }
}
