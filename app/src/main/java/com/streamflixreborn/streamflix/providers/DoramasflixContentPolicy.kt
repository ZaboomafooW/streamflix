package com.streamflixreborn.streamflix.providers

import java.util.Locale

internal object DoramasflixContentPolicy {

    enum class Marker {
        BL,
        GL,
        LGBT,
        ADULT,
    }

    data class Settings(
        val showBl: Boolean,
        val showGl: Boolean,
        val showLgbt: Boolean,
        val showAdult: Boolean,
    )

    data class Analysis(
        val markers: Set<Marker>,
        val cleanedOverview: String?,
    )

    private val prefixPattern = Regex(
        pattern = """^\s*(?:(🔞)|(?:(?:🌈|🏳️‍🌈)\s*)?(?:\(\s*(?:(?:🌈|🏳️‍🌈)\s*)?(ANIME\s+BL|BL|GL|LGBTQ\+|LGBTQ|LGBT|\+18)\s*(?:(?:🌈|🏳️‍🌈)\s*)?\)|\[\s*(?:(?:🌈|🏳️‍🌈)\s*)?(BL|GL|LGBTQ\+|LGBTQ|LGBT|\+18)\s*(?:(?:🌈|🏳️‍🌈)\s*)?\]|\[\s*SERIE\s+(BL|GL)\s*\]|SERIE\s+(BL|GL)|\|(BL|GL|LGBTQ\+|LGBTQ|LGBT|\+18)\||/(BL|GL)\)|(BL|GL|LGBTQ\+|LGBTQ|LGBT)(?=\s|[:\-–—]))\s*(?:(?:🌈|🏳️‍🌈)\s*)?)\s*""",
        option = RegexOption.IGNORE_CASE,
    )
    private val trailingAdultPattern = Regex("""\s*🔞\s*$""")

    fun analyzeOverview(overview: String?): Analysis {
        var remaining = overview?.trim().orEmpty()
        if (remaining.isEmpty()) return Analysis(emptySet(), null)

        val markers = linkedSetOf<Marker>()
        while (true) {
            val match = prefixPattern.find(remaining) ?: break
            if (match.range.first != 0) break
            match.groupValues.drop(1)
                .firstOrNull { it.isNotBlank() }
                ?.let(::markerForToken)
                ?.let(markers::add)
            remaining = remaining.removeRange(match.range).trimStart()
        }

        trailingAdultPattern.find(remaining)?.let { marker ->
            markers += Marker.ADULT
            remaining = remaining.removeRange(marker.range).trimEnd()
        }

        if (markers.isNotEmpty()) {
            remaining = remaining.replaceFirst(
                Regex("""^[:\-–—]\s*"""),
                "",
            ).trim()
        }

        return Analysis(
            markers = markers,
            cleanedOverview = remaining.takeIf { it.isNotBlank() },
        )
    }

    fun markersFromLabels(labels: Iterable<String?>): Set<Marker> = labels
        .mapNotNull { label ->
            when (label?.trim()?.uppercase(Locale.ROOT)) {
                "BL" -> Marker.BL
                "GL" -> Marker.GL
                "LGBT", "LGBTQ", "LGBTQ+" -> Marker.LGBT
                "+18", "18+", "🔞" -> Marker.ADULT
                else -> null
            }
        }
        .toSet()

    fun allows(markers: Set<Marker>, settings: Settings): Boolean = markers.all { marker ->
        when (marker) {
            Marker.BL -> settings.showBl
            Marker.GL -> settings.showGl
            Marker.LGBT -> settings.showLgbt
            Marker.ADULT -> settings.showAdult
        }
    }

    private fun markerForToken(token: String): Marker? = when (token.trim().uppercase(Locale.ROOT)) {
        "BL", "ANIME BL" -> Marker.BL
        "GL" -> Marker.GL
        "LGBT", "LGBTQ", "LGBTQ+" -> Marker.LGBT
        "+18", "🔞" -> Marker.ADULT
        else -> null
    }
}
