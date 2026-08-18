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
}
