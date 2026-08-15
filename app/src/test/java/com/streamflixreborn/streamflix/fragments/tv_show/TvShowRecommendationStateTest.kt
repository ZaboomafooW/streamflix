package com.streamflixreborn.streamflix.fragments.tv_show

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvShowRecommendationStateTest {

    @Test
    fun `metadata-only movie persistence does not change recommendation state`() {
        val recommendation = Movie(id = "movie", title = "Recommendation")
        val persisted = Movie(
            id = "movie",
            title = "Loaded detail",
            overview = "Full metadata loaded after opening the recommendation",
        )

        assertTrue(
            TvShowRecommendationState.changedMovies(
                recommendations = listOf(recommendation),
                persisted = listOf(persisted),
            ).isEmpty()
        )
    }

    @Test
    fun `metadata-only tv show persistence does not change recommendation state`() {
        val recommendation = TvShow(id = "show", title = "Recommendation")
        val persisted = TvShow(
            id = "show",
            title = "Loaded detail",
            overview = "Full metadata loaded after opening the recommendation",
        )

        assertTrue(
            TvShowRecommendationState.changedTvShows(
                recommendations = listOf(recommendation),
                persisted = listOf(persisted),
            ).isEmpty()
        )
    }

    @Test
    fun `movie user state changes still update recommendations`() {
        val recommendation = Movie(id = "movie", title = "Recommendation")
        val persisted = Movie(id = "movie", title = "Loaded detail").apply {
            isFavorite = true
            favoritedAtMillis = 42L
        }

        val changed = TvShowRecommendationState.changedMovies(
            recommendations = listOf(recommendation),
            persisted = listOf(persisted),
        )

        assertEquals(listOf(persisted), changed)
    }

    @Test
    fun `tv show user state changes still update recommendations`() {
        val recommendation = TvShow(id = "show", title = "Recommendation")
        val persisted = TvShow(id = "show", title = "Loaded detail").apply {
            isWatching = false
        }

        val changed = TvShowRecommendationState.changedTvShows(
            recommendations = listOf(recommendation),
            persisted = listOf(persisted),
        )

        assertEquals(listOf(persisted), changed)
    }

    @Test
    fun `movie comparison ignores metadata but detects user state changes`() {
        val first = Movie(id = "movie", title = "First title").apply {
            isFavorite = true
            favoritedAtMillis = 42L
        }
        val sameUserState = Movie(id = "movie", title = "Different title").apply {
            isFavorite = true
            favoritedAtMillis = 42L
        }
        val differentUserState = Movie(id = "movie", title = "Different title")

        assertTrue(
            TvShowRecommendationState.sameMovieUserState(
                listOf(first),
                listOf(sameUserState),
            )
        )
        assertFalse(
            TvShowRecommendationState.sameMovieUserState(
                listOf(first),
                listOf(differentUserState),
            )
        )
    }

    @Test
    fun `tv show comparison ignores metadata but detects user state changes`() {
        val first = TvShow(id = "show", title = "First title").apply {
            isFavorite = true
            favoritedAtMillis = 42L
        }
        val sameUserState = TvShow(id = "show", title = "Different title").apply {
            isFavorite = true
            favoritedAtMillis = 42L
        }
        val differentUserState = TvShow(id = "show", title = "Different title")

        assertTrue(
            TvShowRecommendationState.sameTvShowUserState(
                listOf(first),
                listOf(sameUserState),
            )
        )
        assertFalse(
            TvShowRecommendationState.sameTvShowUserState(
                listOf(first),
                listOf(differentUserState),
            )
        )
    }
}
