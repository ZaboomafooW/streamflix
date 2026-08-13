package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixLogicTest {

    @Test
    fun `rated API value is authoritative without html fallback`() {
        val decision = DoramasflixLogic.resolveApiRating(4.142857142857143, 14)
        assertEquals(4.142857142857143, decision.rating)
        assertFalse(decision.useHtmlFallback)
    }

    @Test
    fun `zero API rating count means unrated without html fallback`() {
        val decision = DoramasflixLogic.resolveApiRating(0.0, 0)
        assertNull(decision.rating)
        assertFalse(decision.useHtmlFallback)
    }

    @Test
    fun `missing API rating metadata requests html fallback`() {
        val decision = DoramasflixLogic.resolveApiRating(null, null)
        assertNull(decision.rating)
        assertTrue(decision.useHtmlFallback)
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
    fun `repeated season artwork is suppressed while distinct alternate artwork survives`() {
        assertEquals(
            "/episode-alt.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/repeated.jpg",
                backdrop = "/episode-alt.jpg",
                stillImage = null,
                seriesBackdropPath = "/series.jpg",
                repeatedSeasonArtwork = "/repeated.jpg",
            )
        )
    }

    @Test
    fun `only one shared nonblank artwork across a multi episode season is repeated`() {
        assertEquals(
            "/same.jpg",
            DoramasflixLogic.repeatedEpisodeArtwork(
                listOf("/same.jpg", " /same.jpg ", "/same.jpg")
            )
        )
        assertNull(DoramasflixLogic.repeatedEpisodeArtwork(listOf("/a.jpg", "/b.jpg")))
        assertNull(DoramasflixLogic.repeatedEpisodeArtwork(listOf("/same.jpg", null)))
        assertNull(DoramasflixLogic.repeatedEpisodeArtwork(listOf("/same.jpg")))
    }

    @Test
    fun `home carousel mixes doramas and movies in Doramasflix order`() {
        assertEquals(
            listOf("D1", "M1", "D2", "D3", "D4", "D5", "D6"),
            DoramasflixLogic.mixAlternating(
                first = listOf("D1", "D2", "D3", "D4", "D5", "D6"),
                second = listOf("M1"),
            ),
        )
    }

    @Test
    fun `home carousel preserves the remaining feed when the other is exhausted`() {
        assertEquals(
            listOf("D1", "M1", "M2"),
            DoramasflixLogic.mixAlternating(
                first = listOf("D1"),
                second = listOf("M1", "M2"),
            ),
        )
    }

    @Test
    fun `graphql error body returns concise distinct messages`() {
        assertEquals(
            "Variable limit has the wrong type.; Another validation failure.",
            DoramasflixLogic.graphQlErrorMessage(
                """
                    {
                      "errors": [
                        {"message": "Variable limit has the wrong type."},
                        {"message": "Variable limit has the wrong type."},
                        {"message": "Another validation failure."}
                      ]
                    }
                """.trimIndent(),
            )
        )
    }

    @Test
    fun `invalid graphql error body has no user detail`() {
        assertNull(DoramasflixLogic.graphQlErrorMessage("not-json"))
        assertNull(DoramasflixLogic.graphQlErrorMessage("{\"data\":{}}"))
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
    fun `server registry names are normalized to human readable service names`() {
        assertEquals("DoodStream", DoramasflixLogic.normalizeServerName("Dood"))
        assertEquals("OK.ru", DoramasflixLogic.normalizeServerName("Ok"))
        assertEquals("OK.ru", DoramasflixLogic.normalizeServerName("Okru"))
        assertEquals("VOE", DoramasflixLogic.normalizeServerName("Voe"))
        assertEquals("VidHide", DoramasflixLogic.normalizeServerName("VidHide"))
    }

    @Test
    fun `hard subtitle descriptor preserves language and type`() {
        assertEquals("ES HARDSUB", DoramasflixLogic.subtitleDescriptor("es", "HARDSUB"))
    }

    @Test
    fun `playback label uses provider language before raw numeric code`() {
        assertEquals(
            "VOE · Mandarín · ES HARDSUB",
            DoramasflixLogic.playbackSourceName(
                serverName = "VOE",
                languageName = "Mandarín",
                languageCode = "13111",
                subtitleDescriptors = listOf("ES HARDSUB"),
            ),
        )
    }

    @Test
    fun `playback label keeps unknown provider language code`() {
        assertEquals(
            "VOE · 999",
            DoramasflixLogic.playbackSourceName(
                serverName = "VOE",
                languageName = null,
                languageCode = "999",
                subtitleDescriptors = emptyList(),
            ),
        )
    }
}
