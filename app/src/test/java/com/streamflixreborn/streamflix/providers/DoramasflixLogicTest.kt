package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixLogicTest {

    @Test
    fun `positive link count marks an episode available`() {
        assertTrue(DoramasflixLogic.isEpisodeAvailable(1))
        assertTrue(DoramasflixLogic.isEpisodeAvailable(6))
    }

    @Test
    fun `zero or missing link count marks an episode unavailable`() {
        assertFalse(DoramasflixLogic.isEpisodeAvailable(0))
        assertFalse(DoramasflixLogic.isEpisodeAvailable(null))
    }

    @Test
    fun `series backdrop is not presented as an episode still`() {
        assertNull(
            DoramasflixLogic.episodeArtwork(
                stillPath = "/series.jpg",
                backdrop = null,
                stillImage = null,
                seriesBackdropPath = "/series.jpg",
            )
        )
    }

    @Test
    fun `distinct episode still is preserved`() {
        assertEquals(
            "/episode.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/episode.jpg",
                backdrop = null,
                stillImage = null,
                seriesBackdropPath = "/series.jpg",
            )
        )
    }

    @Test
    fun `distinct alternate artwork is used when still path is series fallback`() {
        assertEquals(
            "/episode-alt.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/series.jpg",
                backdrop = "/episode-alt.jpg",
                stillImage = "/episode-image.jpg",
                seriesBackdropPath = "/series.jpg",
            )
        )
    }

    @Test
    fun `protocol relative playback URL is normalized to https`() {
        assertEquals(
            "https://ok.ru/videoembed/123",
            DoramasflixLogic.normalizePlaybackTarget("//ok.ru/videoembed/123"),
        )
    }

    @Test
    fun `http playback URLs are preserved`() {
        assertEquals(
            "https://voe.sx/e/test",
            DoramasflixLogic.normalizePlaybackTarget("https://voe.sx/e/test"),
        )
    }

    @Test
    fun `non http playback targets are rejected`() {
        assertNull(DoramasflixLogic.normalizePlaybackTarget("javascript:void(0)"))
    }

    @Test
    fun `zero rating is treated as unrated while positive rating is preserved`() {
        assertNull(DoramasflixLogic.normalizeRating(0.0))
        assertNull(DoramasflixLogic.normalizeRating(null))
        assertEquals(4.142857142857143, DoramasflixLogic.normalizeRating(4.142857142857143))
    }

    @Test
    fun `trailer video id is normalized to youtube URL`() {
        assertEquals(
            "https://www.youtube.com/watch?v=3OAJckfWgiY",
            DoramasflixLogic.normalizeTrailer("3OAJckfWgiY"),
        )
    }

    @Test
    fun `existing trailer URL is preserved`() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            DoramasflixLogic.normalizeTrailer("https://www.youtube.com/watch?v=abc"),
        )
    }

    @Test
    fun `epoch millisecond air date is converted to ISO date`() {
        assertEquals(
            "2020-10-13",
            DoramasflixLogic.normalizeAirDate("1602565200000"),
        )
    }

    @Test
    fun `server registry names are normalized only where StreamFlix extractor names differ`() {
        assertEquals("DoodStream", DoramasflixLogic.normalizeServerName("Dood"))
        assertEquals("Okru", DoramasflixLogic.normalizeServerName("Ok"))
        assertEquals("VOE", DoramasflixLogic.normalizeServerName("Voe"))
        assertEquals("VidHide", DoramasflixLogic.normalizeServerName("VidHide"))
    }
}
