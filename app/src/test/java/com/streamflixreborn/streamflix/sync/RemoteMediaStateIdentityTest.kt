package com.streamflixreborn.streamflix.sync

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import org.junit.Assert.assertEquals
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
    fun firstLoginMergeRetainsIdentityFromOlderSide() {
        val remote = RemoteMediaState(
            userId = "user",
            provider = "Provider",
            mediaType = "movie",
            mediaId = "provider-native-id",
            tmdbId = 12345,
            imdbId = "tt1234567",
            title = "Example",
            clientUpdatedAtMillis = 100L,
        )
        val local = remote.copy(
            tmdbId = null,
            imdbId = null,
            isFavorite = true,
            clientUpdatedAtMillis = 200L,
        )

        val merged = CloudSyncManager.mergeForFirstLogin(
            remote = listOf(remote),
            local = listOf(local),
            mergedAtMillis = 300L,
        ).single()

        assertEquals("provider-native-id", merged.mediaId)
        assertEquals(12345, merged.tmdbId)
        assertEquals("tt1234567", merged.imdbId)
        assertEquals(true, merged.isFavorite)
    }
}
