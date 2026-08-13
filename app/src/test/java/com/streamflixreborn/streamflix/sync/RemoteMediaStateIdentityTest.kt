package com.streamflixreborn.streamflix.sync

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaStateIdentityTest {

    @Test
    fun movieStateCarriesExternalIdentityWithoutReplacingProviderId() {
        val state = RemoteMediaState.fromMovie(
            userId = "user",
            provider = "Provider",
            movie = Movie(
                id = "provider-native-id",
                title = "Example",
                imdbId = "tt1234567",
                tmdbId = 12345,
            ),
            now = 100L,
        )

        assertEquals("provider-native-id", state.mediaId)
        assertEquals("tt1234567", state.imdbId)
        assertEquals(12345, state.tmdbId)
    }

    @Test
    fun episodeStateCarriesParentShowIdentity() {
        val state = RemoteMediaState.fromEpisode(
            userId = "user",
            provider = "Provider",
            episode = Episode(
                id = "episode-native-id",
                number = 1,
                tvShow = TvShow(
                    id = "show-native-id",
                    title = "Example Show",
                    imdbId = "tt7654321",
                    tmdbId = 54321,
                ),
            ),
            now = 100L,
        )

        assertEquals("show-native-id", state.parentShowId)
        assertEquals("tt7654321", state.parentShowImdbId)
        assertEquals(54321, state.parentShowTmdbId)
    }

    @Test
    fun firstLoginMergeUsesRemoteIdentityOnlyToFillMissingLocalIdentity() {
        val remote = movieState(
            tmdbId = 12345,
            imdbId = "tt1234567",
            updatedAt = 100L,
        )
        val local = movieState(
            tmdbId = null,
            imdbId = null,
            updatedAt = 200L,
        ).copy(isFavorite = true)

        val merged = merge(remote, local)

        assertEquals(12345, merged.tmdbId)
        assertEquals("tt1234567", merged.imdbId)
        assertTrue(merged.isFavorite)
    }

    @Test
    fun firstLoginMergeDoesNotLetRemoteNullEraseKnownLocalIdentity() {
        val remote = movieState(
            tmdbId = null,
            imdbId = null,
            updatedAt = 300L,
        )
        val local = movieState(
            tmdbId = 12345,
            imdbId = "tt1234567",
            updatedAt = 100L,
        )

        val merged = merge(remote, local)

        assertEquals(12345, merged.tmdbId)
        assertEquals("tt1234567", merged.imdbId)
    }

    @Test
    fun firstLoginMergePreservesKnownLocalIdentityWhenRemoteConflicts() {
        val remote = movieState(
            tmdbId = 99999,
            imdbId = "tt9999999",
            updatedAt = 300L,
        )
        val local = movieState(
            tmdbId = 12345,
            imdbId = "tt1234567",
            updatedAt = 100L,
        )

        val merged = merge(remote, local)

        assertEquals(12345, merged.tmdbId)
        assertEquals("tt1234567", merged.imdbId)
    }

    @Test
    fun identityOnlyFirstLoginDifferenceIsPreserved() {
        val remote = movieState(
            tmdbId = 12345,
            imdbId = "tt1234567",
            updatedAt = 100L,
        )
        val local = movieState(
            tmdbId = null,
            imdbId = null,
            updatedAt = 100L,
        )

        val merged = merge(remote, local)

        assertEquals(12345, merged.tmdbId)
        assertEquals("tt1234567", merged.imdbId)
        assertFalse(merged.isFavorite)
        assertFalse(merged.isWatched)
    }

    @Test
    fun staleRemoteFavoriteCanEnrichIdentityWithoutRestoringFavoriteState() {
        val remote = movieState(
            tmdbId = 12345,
            imdbId = "tt1234567",
            updatedAt = 100L,
        ).copy(
            isFavorite = true,
            favoritedAtMillis = 90L,
        )
        val local = movieState(
            tmdbId = null,
            imdbId = null,
            updatedAt = 200L,
        ).copy(
            isFavorite = false,
            favoritedAtMillis = null,
        )

        val merged = merge(remote, local)

        assertEquals(12345, merged.tmdbId)
        assertEquals("tt1234567", merged.imdbId)
        assertFalse(merged.isFavorite)
        assertNull(merged.favoritedAtMillis)
    }

    @Test
    fun staleRemoteWatchedStateCanEnrichIdentityWithoutRestoringWatchedState() {
        val remote = movieState(
            tmdbId = 12345,
            imdbId = "tt1234567",
            updatedAt = 100L,
        ).copy(
            isWatched = true,
            watchedAtMillis = 90L,
        )
        val local = movieState(
            tmdbId = null,
            imdbId = null,
            updatedAt = 200L,
        ).copy(
            isWatched = false,
            watchedAtMillis = null,
        )

        val merged = merge(remote, local)

        assertEquals(12345, merged.tmdbId)
        assertEquals("tt1234567", merged.imdbId)
        assertFalse(merged.isWatched)
        assertNull(merged.watchedAtMillis)
    }

    @Test
    fun episodeParentIdentityMergePrefersKnownLocalAndFillsLocalNulls() {
        val remote = episodeState(
            parentTmdbId = 99999,
            parentImdbId = "tt9999999",
            updatedAt = 300L,
        )
        val local = episodeState(
            parentTmdbId = 54321,
            parentImdbId = null,
            updatedAt = 100L,
        )

        val merged = merge(remote, local)

        assertEquals("show-native-id", merged.parentShowId)
        assertEquals(54321, merged.parentShowTmdbId)
        assertEquals("tt9999999", merged.parentShowImdbId)
    }

    @Test
    fun absentIdentityOnBothSidesStaysNull() {
        val merged = merge(
            movieState(tmdbId = null, imdbId = null, updatedAt = 100L),
            movieState(tmdbId = null, imdbId = null, updatedAt = 200L),
        )

        assertNull(merged.tmdbId)
        assertNull(merged.imdbId)
    }

    private fun merge(remote: RemoteMediaState, local: RemoteMediaState): RemoteMediaState =
        CloudSyncManager.mergeForFirstLogin(
            remote = listOf(remote),
            local = listOf(local),
            mergedAtMillis = 400L,
        ).single()

    private fun movieState(
        tmdbId: Int?,
        imdbId: String?,
        updatedAt: Long,
    ) = RemoteMediaState(
        userId = "user",
        provider = "Provider",
        mediaType = "movie",
        mediaId = "provider-native-id",
        tmdbId = tmdbId,
        imdbId = imdbId,
        title = "Example",
        clientUpdatedAtMillis = updatedAt,
    )

    private fun episodeState(
        parentTmdbId: Int?,
        parentImdbId: String?,
        updatedAt: Long,
    ) = RemoteMediaState(
        userId = "user",
        provider = "Provider",
        mediaType = "episode",
        mediaId = "episode-native-id",
        parentShowId = "show-native-id",
        parentShowTmdbId = parentTmdbId,
        parentShowImdbId = parentImdbId,
        episodeNumber = 1,
        title = "Episode",
        clientUpdatedAtMillis = updatedAt,
    )
}
