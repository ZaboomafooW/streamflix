package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.utils.UserDataCache.toCached
import com.streamflixreborn.streamflix.utils.UserDataCache.toMovie
import org.junit.Assert.assertEquals
import org.junit.Test

class UserDataCacheMovieTest {

    @Test
    fun preservesOriginalLanguageAcrossContinueWatchingCacheRoundTrip() {
        val movie = Movie(
            id = "movie-id",
            title = "Movie",
            originalLanguage = "ja",
        )

        val restored = movie.toCached().toMovie()

        assertEquals("ja", restored.originalLanguage)
    }
}
