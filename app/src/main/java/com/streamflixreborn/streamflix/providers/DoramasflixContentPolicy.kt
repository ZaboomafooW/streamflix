package com.streamflixreborn.streamflix.providers

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
        pattern = """^\s*(?:(🔞)|(?:🌈\s*)?\((BL|GL|LGBT|\+18)(?:\s*🌈)?\)(?:\s*🌈)?|(?:🌈\s*)?\[(?:🌈\s*)?(BL|GL|LGBT|\+18)(?:\s*🌈)?\](?:\s*🌈)?|\[\s*SERIE\s+(BL|GL)\s*\](?:\s*🌈)?|\|(BL|GL|LGBT|\+18)\|(?:\s*🌈)?|/(BL|GL)\)|(?:(BL|GL|LGBT)(?=\s|[:\-–—])))\s*""",
        option = RegexOption.IGNORE_CASE,
    )

    fun analyzeOverview(overview: String?): Analysis {
        val original = overview?.trim().orEmpty()
        var remaining = original
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

        if (original.contains("🔞")) {
            markers += Marker.ADULT
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
            when (label?.trim()?.uppercase()) {
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

    private fun markerForToken(token: String): Marker? = when (token.trim().uppercase()) {
        "BL" -> Marker.BL
        "GL" -> Marker.GL
        "LGBT" -> Marker.LGBT
        "+18", "🔞" -> Marker.ADULT
        else -> null
    }
}
