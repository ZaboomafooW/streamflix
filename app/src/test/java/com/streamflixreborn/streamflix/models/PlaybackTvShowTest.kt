package com.streamflixreborn.streamflix.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTvShowTest {

    @Test
    fun preservesOriginalLanguageForDirectPlayback() {
        val tvShow = TvShow(
            id = "3625-ted-lasso",
            title = "Ted Lasso",
            released = "2020-08-14",
            poster = "poster",
            banner = "banner",
            imdbId = "tt10986410",
            originalLanguage = "en",
        )

        val playback = tvShow.toPlaybackTvShow()

        assertEquals(tvShow.id, playback.id)
        assertEquals(tvShow.title, playback.title)
        assertEquals("2020-08-14", playback.releaseDate)
        assertEquals(tvShow.poster, playback.poster)
        assertEquals(tvShow.banner, playback.banner)
        assertEquals(tvShow.imdbId, playback.imdbId)
        assertEquals("en", playback.originalLanguage)
    }

    @Test
    fun leavesUnavailableOriginalLanguageUnknown() {
        val playback = TvShow(id = "live", title = "Live").toPlaybackTvShow()

        assertNull(playback.originalLanguage)
    }
}
