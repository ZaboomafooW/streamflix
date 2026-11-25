package com.streamflixreborn.streamflix.utils

internal object HlsAudioPreference {
    private val attributes = Regex("""(?:^|,)([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attribute(line: String, name: String): String? =
        attributes.findAll(line.substringAfter(':')).firstOrNull {
            it.groupValues[1] == name
        }?.groupValues?.get(2)?.removeSurrounding("\"")

    private fun setFlag(line: String, name: String, value: String): String {
        val body = line.substringAfter(':')
        val existing = attributes.findAll(body).firstOrNull { it.groupValues[1] == name }
        val updated = if (existing == null) "$body,$name=$value"
            else body.replaceRange(existing.groups[2]!!.range, value)
        return line.substringBefore(':') + ":" + updated
    }

    private fun matches(line: String, preference: String): Boolean {
        val language = attribute(line, "LANGUAGE").orEmpty().lowercase().substringBefore('-')
        val name = attribute(line, "NAME").orEmpty()
        val codes = when (preference) {
            "en" -> setOf("en", "eng")
            "it" -> setOf("it", "ita")
            "es" -> setOf("es", "spa")
            else -> setOf(preference)
        }
        if (language in codes) return true
        if (language.isNotBlank() && language != "und") return false
        val labels = when (preference) {
            "en" -> listOf("English", "eng", "en")
            "it" -> listOf("Italian", "Italiano", "ita", "it")
            "es" -> listOf("Spanish", "Español", "Castellano", "spa", "es")
            else -> listOf(preference)
        }
        return labels.any { Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) }
    }

    fun prefer(lines: List<String>, language: String): List<String> {
        val preference = language.lowercase().substringBefore('-')
        val groups = lines.indices.filter {
            lines[it].startsWith("#EXT-X-MEDIA:") && attribute(lines[it], "TYPE") == "AUDIO" &&
                !attribute(lines[it], "GROUP-ID").isNullOrBlank()
        }.groupBy { attribute(lines[it], "GROUP-ID") }

        val result = lines.toMutableList()
        for (group in groups.values) {
            val candidates = group.filter { matches(lines[it], preference) }
            val chosen = candidates.firstOrNull { attribute(lines[it], "DEFAULT") == "YES" }
                ?: candidates.firstOrNull { attribute(lines[it], "CHARACTERISTICS")?.contains("describes-video") != true }
                ?: candidates.firstOrNull() ?: continue

            for (index in group) {
                if (index == chosen) {
                    result[index] = setFlag(setFlag(lines[index], "DEFAULT", "YES"), "AUTOSELECT", "YES")
                } else if (attribute(lines[index], "DEFAULT") == "YES") {
                    result[index] = setFlag(lines[index], "DEFAULT", "NO")
                }
            }
        }
        return result
    }
}
