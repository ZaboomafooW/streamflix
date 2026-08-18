package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.doramasflix.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoramasflixFilmographyTest {

    @Test
    fun `filmography reconciliation keeps only exact Doramasflix tmdb identity`() {
        val wrongIdentity = Content(
            id = "provider-wrong",
            slug = "same-title-wrong",
            name = "Same Title",
            tmdbId = "999",
        )
        val exactIdentity = Content(
            id = "provider-exact",
            slug = "same-title-exact",
            name = "Same Title",
            tmdbId = "1251581",
        )

        val match = DoramasflixPeopleResolver.exactTmdbMatch(
            contents = listOf(wrongIdentity, exactIdentity),
            tmdbId = 1251581,
        )

        assertEquals("same-title-exact", match?.slug)
    }

    @Test
    fun `filmography reconciliation rejects malformed provider tmdb identity`() {
        val malformed = Content(
            id = "provider-malformed",
            slug = "malformed",
            tmdbId = "1251581-3",
        )

        assertNull(
            DoramasflixPeopleResolver.exactTmdbMatch(
                contents = listOf(malformed),
                tmdbId = 1251581,
            )
        )
    }

    @Test
    fun `filmography candidate windows advance without overlap and include the tail`() {
        assertEquals(0..7, DoramasflixPeopleResolver.candidateWindow(0, 20))
        assertEquals(8..15, DoramasflixPeopleResolver.candidateWindow(8, 20))
        assertEquals(16..19, DoramasflixPeopleResolver.candidateWindow(16, 20))
        assertNull(DoramasflixPeopleResolver.candidateWindow(20, 20))
    }

    @Test
    fun `filmography candidate window rejects invalid bounds`() {
        assertNull(DoramasflixPeopleResolver.candidateWindow(-1, 20))
        assertNull(DoramasflixPeopleResolver.candidateWindow(0, 0))
        assertNull(DoramasflixPeopleResolver.candidateWindow(0, 20, windowSize = 0))
    }
}
