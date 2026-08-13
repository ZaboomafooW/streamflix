package com.streamflixreborn.streamflix.models

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaIdentityTest {

    @Test
    fun providerNativeIdRemainsIndependentFromExternalIds() {
        val movie = Movie(
            id = "provider-native-slug",
            title = "Example",
            imdbId = "tt1234567",
            tmdbId = 12345,
        )

        assertEquals("provider-native-slug", movie.id)
        assertEquals("tt1234567", movie.imdbId)
        assertEquals(12345, movie.tmdbId)
    }

    @Test
    fun movieMergePreservesKnownIdentityWhenIncomingIdentityIsMissing() {
        val incoming = Movie(
            id = "provider-native-slug",
            title = "Fresh metadata",
        )
        val stored = Movie(
            id = "provider-native-slug",
            title = "Stored metadata",
            imdbId = "tt1234567",
            tmdbId = 12345,
        )

        incoming.merge(stored)

        assertEquals("provider-native-slug", incoming.id)
        assertEquals("tt1234567", incoming.imdbId)
        assertEquals(12345, incoming.tmdbId)
    }

    @Test
    fun movieMergeDoesNotReplaceKnownIncomingIdentity() {
        val incoming = Movie(
            id = "provider-native-slug",
            title = "Fresh metadata",
            imdbId = "tt1111111",
            tmdbId = 11111,
        )
        val stored = Movie(
            id = "provider-native-slug",
            title = "Stored metadata",
            imdbId = "tt2222222",
            tmdbId = 22222,
        )

        incoming.merge(stored)

        assertEquals("tt1111111", incoming.imdbId)
        assertEquals(11111, incoming.tmdbId)
    }

    @Test
    fun tvShowCopyCarriesAllIdentityNamespaces() {
        val show = TvShow(
            id = "provider-native-show-id",
            title = "Example Show",
            imdbId = "tt7654321",
            tmdbId = 54321,
        )

        val copy = show.copy(title = "Updated title")

        assertEquals("provider-native-show-id", copy.id)
        assertEquals("tt7654321", copy.imdbId)
        assertEquals(54321, copy.tmdbId)
    }

    @Test
    fun playbackMovieKeepsProviderIdAndTmdbIdSeparate() {
        val playback = Video.Type.Movie(
            id = "provider-native-slug",
            title = "Example",
            releaseDate = "2026-01-01",
            poster = "",
            imdbId = "tt1234567",
            tmdbId = 12345,
        )

        assertEquals("provider-native-slug", playback.id)
        assertEquals(12345, playback.tmdbId)
    }
}
