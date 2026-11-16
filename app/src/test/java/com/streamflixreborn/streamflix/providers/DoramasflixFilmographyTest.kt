package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.doramasflix.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoramasflixFilmographyTest {

    @Test
    fun `provider filmography route wins by exact Doramasflix slug`() {
        val wrongRoute = Content(
            id = "provider-wrong",
            slug = "different-route",
            name = "In Your Radiant Season",
        )
        val exactRoute = Content(
            id = "provider-exact",
            slug = "in-your-radiant-season",
            name = "En tu mejor momento",
        )

        val match = DoramasflixPeopleResolver.exactProviderRouteMatch(
            contents = listOf(wrongRoute, exactRoute),
            providerId = "doramas-online/in-your-radiant-season",
        )

        assertEquals("provider-exact", match?.id)
    }

    @Test
    fun `provider filmography route ignores query and fragment`() {
        val content = Content(
            id = "provider-exact",
            slug = "chef-of-antarctic",
            name = "Chef of Antarctic",
        )

        val match = DoramasflixPeopleResolver.exactProviderRouteMatch(
            contents = listOf(content),
            providerId = "doramas-online/chef-of-antarctic?source=people#cast",
        )

        assertEquals("provider-exact", match?.id)
    }

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

    @Test
    fun `filmography cache key changes for every independent content filter`() {
        val hidden = DoramasflixContentPolicy.Settings(
            showBl = false,
            showGl = false,
            showLgbt = false,
            showAdult = false,
        )
        val baseline = DoramasflixPeopleResolver.filmographyFilterKey(hidden)

        assertEquals(0, baseline)
        assertNotEquals(
            baseline,
            DoramasflixPeopleResolver.filmographyFilterKey(hidden.copy(showBl = true)),
        )
        assertNotEquals(
            baseline,
            DoramasflixPeopleResolver.filmographyFilterKey(hidden.copy(showGl = true)),
        )
        assertNotEquals(
            baseline,
            DoramasflixPeopleResolver.filmographyFilterKey(hidden.copy(showLgbt = true)),
        )
        assertNotEquals(
            baseline,
            DoramasflixPeopleResolver.filmographyFilterKey(hidden.copy(showAdult = true)),
        )
        assertEquals(
            15,
            DoramasflixPeopleResolver.filmographyFilterKey(
                DoramasflixContentPolicy.Settings(
                    showBl = true,
                    showGl = true,
                    showLgbt = true,
                    showAdult = true,
                )
            ),
        )
    }
}
