package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackExactTrackResolverTest {

    private data class Candidate(
        val identity: String?,
        val position: Int,
    )

    @Test
    fun restoresUniqueRawIdentityEvenIfPositionChanges() {
        val candidates = listOf(
            Candidate(identity = "English", position = 5),
            Candidate(identity = "Spanish", position = 1),
        )

        val selected = resolve(candidates, identity = "English", position = 0)

        assertEquals(candidates[0], selected)
    }

    @Test
    fun usesPositionToBreakDuplicateRawIdentityTie() {
        val candidates = listOf(
            Candidate(identity = "English", position = 0),
            Candidate(identity = "English", position = 1),
        )

        val selected = resolve(candidates, identity = "English", position = 1)

        assertEquals(candidates[1], selected)
    }

    @Test
    fun usesPositionForAnonymousTrack() {
        val candidates = listOf(
            Candidate(identity = null, position = 0),
            Candidate(identity = null, position = 1),
        )

        val selected = resolve(candidates, identity = null, position = 1)

        assertEquals(candidates[1], selected)
    }

    @Test
    fun doesNotRestoreWhenRawIdentityNoLongerExists() {
        val candidates = listOf(Candidate(identity = "Spanish", position = 0))

        val selected = resolve(candidates, identity = "English", position = 0)

        assertNull(selected)
    }

    private fun resolve(
        candidates: List<Candidate>,
        identity: String?,
        position: Int,
    ): Candidate? = PlaybackExactTrackResolver.resolve(
        candidates = candidates,
        hasRawIdentity = identity != null,
        rawIdentityMatches = { candidate -> candidate.identity == identity },
        savedPositionMatches = { candidate -> candidate.position == position },
    )
}
