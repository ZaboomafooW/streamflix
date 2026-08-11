package com.streamflixreborn.streamflix.utils

/** Resolves a saved exact track without guessing between indistinguishable candidates. */
internal object PlaybackExactTrackResolver {

    fun <T> resolve(
        candidates: List<T>,
        hasRawIdentity: Boolean,
        rawIdentityMatches: (T) -> Boolean,
        savedPositionMatches: (T) -> Boolean,
    ): T? {
        val matchingCandidates = candidates.filter(rawIdentityMatches)
        if (hasRawIdentity && matchingCandidates.size == 1) {
            return matchingCandidates.single()
        }
        return matchingCandidates.firstOrNull(savedPositionMatches)
    }
}
