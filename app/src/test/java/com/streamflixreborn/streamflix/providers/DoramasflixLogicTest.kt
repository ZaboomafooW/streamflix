package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixLogicTest {

    @Test
    fun `rated API value is preserved`() {
        val decision = DoramasflixLogic.resolveApiRating(4.142857142857143, 14)
        assertEquals(4.142857142857143, decision.rating)
        assertFalse(decision.useHtmlFallback)
    }

    @Test
    fun `zero API rating count means unrated`() {
        val decision = DoramasflixLogic.resolveApiRating(0.0, 0)
        assertNull(decision.rating)
        assertFalse(decision.useHtmlFallback)
    }

    @Test
    fun `missing API rating remains missing`() {
        val decision = DoramasflixLogic.resolveApiRating(null, null)
        assertNull(decision.rating)
        assertTrue(decision.useHtmlFallback)
    }

    @Test
    fun `provider episode artwork preserves provider field order`() {
        assertEquals(
            "/episode-seven.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/episode-seven.jpg",
                backdrop = "/alternate.jpg",
                stillImage = "/image.jpg",
            )
        )
        assertEquals(
            "/alternate.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = null,
                backdrop = "/alternate.jpg",
                stillImage = "/image.jpg",
            )
        )
    }

    @Test
    fun `first nonblank metadata preserves source order without judging generic labels`() {
        assertEquals("Episodio 1", DoramasflixLogic.firstNonBlank(null, " ", "Episodio 1", "Episode One"))
        assertNull(DoramasflixLogic.firstNonBlank(null, " "))
    }

    @Test
    fun `obvious image placeholders are unusable`() {
        assertNull(DoramasflixLogic.meaningfulImage("https://cdn.example/no-image.jpg"))
        assertNull(DoramasflixLogic.meaningfulImage("/assets/placeholder-poster.png"))
        assertEquals("/episode.jpg", DoramasflixLogic.meaningfulImage("/episode.jpg"))
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
    fun `generic trailer token is treated as missing`() {
        assertNull(DoramasflixLogic.normalizeTrailer("N/A"))
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
    fun `short numeric placeholder is not interpreted as epoch date`() {
        assertNull(DoramasflixLogic.normalizeDate("2026"))
    }

    @Test
    fun `Spanish date is normalized to ISO`() {
        assertEquals("2026-07-18", DoramasflixLogic.normalizeDate("18 de julio de 2026"))
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
