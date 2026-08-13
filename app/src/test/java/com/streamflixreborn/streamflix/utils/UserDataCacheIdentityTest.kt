package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Test

class UserDataCacheIdentityTest {

    @Test
    fun episodeCacheRoundTripPreservesParentProviderTmdbAndImdbIdentity() {
        val episode = Episode(
            id = "episode-native-id",
            number = 3,
            tvShow = TvShow(
                id = "show-native-id",
                title = "Example Show",
                imdbId = "tt7654321",
                tmdbId = 54321,
            ),
        )

        val restored = with(UserDataCache) {
            episode.toCached().toEpisode()
        }

        assertEquals("show-native-id", restored.tvShow?.id)
        assertEquals("tt7654321", restored.tvShow?.imdbId)
        assertEquals(54321, restored.tvShow?.tmdbId)
    }

    @Test
    fun episodeCacheRoundTripLeavesUnknownParentIdentityNull() {
        val episode = Episode(
            id = "episode-native-id",
            number = 3,
            tvShow = TvShow(
                id = "show-native-id",
                title = "Example Show",
            ),
        )

        val restored = with(UserDataCache) {
            episode.toCached().toEpisode()
        }

        assertEquals("show-native-id", restored.tvShow?.id)
        assertEquals(null, restored.tvShow?.imdbId)
        assertEquals(null, restored.tvShow?.tmdbId)
    }
}
