package com.streamflixreborn.streamflix.models

import com.streamflixreborn.streamflix.adapters.AppAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SeriesEqualityTest {
    private fun series(): TvShow {
        val show = TvShow(id = "show", title = "Show").apply {
            itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
        }
        val season = Season(id = "season", number = 1, tvShow = show).apply {
            itemType = AppAdapter.Type.SEASON_TV_ITEM
        }
        val episode = Episode(id = "episode", number = 1, tvShow = show, season = season).apply {
            itemType = AppAdapter.Type.EPISODE_TV_ITEM
        }
        show.seasons = listOf(season)
        season.episodes = listOf(episode)
        return show
    }

    @Test
    fun `equivalent series with parent links compare without recursion`() {
        val first = series()
        val second = series()
        assertEquals(first, second)
        assertEquals(first.seasons.single(), second.seasons.single())
        assertEquals(first.seasons.single().episodes.single(), second.seasons.single().episodes.single())
    }

    @Test
    fun `hashing linked series terminates and agrees with equality`() {
        val first = series()
        val second = series()
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first.seasons.single().hashCode(), second.seasons.single().hashCode())
        assertEquals(first.seasons.single().episodes.single().hashCode(), second.seasons.single().episodes.single().hashCode())
    }

    @Test
    fun `episode progress changes still update season and show equality`() {
        val first = series()
        val second = series()
        second.seasons.single().episodes.single().isWatched = true
        assertNotEquals(first, second)
        assertNotEquals(first.seasons.single(), second.seasons.single())
        assertNotEquals(first.seasons.single().episodes.single(), second.seasons.single().episodes.single())
    }

    @Test
    fun `episode metadata changes still update show equality`() {
        val first = series()
        val second = series()
        second.seasons.single().episodes.single().title = "Updated episode"
        assertNotEquals(first, second)
    }

    @Test
    fun `different parent identities distinguish episodes and seasons`() {
        val first = series()
        val second = series()
        second.id = "other-show"
        assertNotEquals(first.seasons.single(), second.seasons.single())
        assertNotEquals(first.seasons.single().episodes.single(), second.seasons.single().episodes.single())
        second.id = first.id
        second.seasons.single().id = "other-season"
        assertNotEquals(first.seasons.single().episodes.single(), second.seasons.single().episodes.single())
    }

    @Test
    fun `database parent stubs match fully linked parents`() {
        val linked = series().seasons.single()
        val stubbed = series().seasons.single()
        stubbed.tvShow = TvShow(id = "show", title = "")
        stubbed.episodes.single().tvShow = stubbed.tvShow
        stubbed.episodes.single().season = Season(id = "season")
        assertEquals(linked, stubbed)
        assertEquals(linked.hashCode(), stubbed.hashCode())
    }
}
