package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Test

class FrembedIdentityMappingTest {

    @Test
    fun shortcutKeepsProviderPrimaryIdAndExplicitExternalIdsSeparate() {
        val item = FrembedProvider.FrembedShortCutItem(
            tmdb = "12345",
            id = 99999,
            imdb = "tt1234567",
            title = "Example",
            title_fr = null,
            name = null,
            director = "Director",
            cast = emptyList(),
            poster = null,
            poster_path = null,
            version = null,
            year = "2026",
            release_date = null,
            first_air_date = null,
            rating = null,
            sa = null,
            overview = null,
            overview_fr = null,
            trailer = null,
            media_type = "movie",
        )

        val movie = with(FrembedProvider) { item.toShow(movie = true) } as Movie

        assertEquals("12345", movie.id)
        assertEquals(12345, movie.tmdbId)
        assertEquals("tt1234567", movie.imdbId)
    }

    @Test
    fun shortcutDoesNotGuessTmdbFromFallbackProviderId() {
        val item = FrembedProvider.FrembedShortCutItem(
            tmdb = null,
            id = 99999,
            imdb = "tt7654321",
            title = "Example Show",
            title_fr = null,
            name = null,
            director = "Director",
            cast = emptyList(),
            poster = null,
            poster_path = null,
            version = null,
            year = "2026",
            release_date = null,
            first_air_date = null,
            rating = null,
            sa = 1,
            overview = null,
            overview_fr = null,
            trailer = null,
            media_type = "tv",
        )

        val show = with(FrembedProvider) { item.toShow(tvshow = true) } as TvShow

        assertEquals("99999", show.id)
        assertEquals(null, show.tmdbId)
        assertEquals("tt7654321", show.imdbId)
    }
}