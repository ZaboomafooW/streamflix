package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.doramasflix.Episode

internal object DoramasflixLogic {

    fun filterAvailableEpisodes(
        episodes: List<Episode>,
        availabilityBySlug: Map<String, Int?>,
    ): List<Episode> {
        if (availabilityBySlug.isEmpty()) return episodes

        return episodes.filter { episode ->
            if (!availabilityBySlug.containsKey(episode.slug)) {
                true
            } else {
                (availabilityBySlug[episode.slug] ?: 0) > 0
            }
        }
    }

    fun sharedStillPath(episodes: List<Episode>): String? {
        if (episodes.size <= 1) return null

        val paths = episodes.map { episode ->
            episode.stillPath?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
        }

        return paths.distinct().singleOrNull()
    }

    fun normalizePlaybackTarget(link: String): String? {
        val normalized = link.trim()
        return when {
            normalized.startsWith("//") -> "https:$normalized"
            normalized.startsWith("https://") || normalized.startsWith("http://") -> normalized
            else -> null
        }
    }
}
