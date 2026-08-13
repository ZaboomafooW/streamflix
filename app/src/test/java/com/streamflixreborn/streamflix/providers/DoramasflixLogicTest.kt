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
    fun `rating fallback follows API website then TMDb on Doramasflix scale`() {
        assertEquals(
            4.7,
            DoramasflixLogic.resolveRating(
                apiRating = null,
                apiRatingCount = null,
                websiteRating = 4.7,
                tmdbRating = 8.2,
            )
        )
        assertEquals(
            4.1,
            DoramasflixLogic.resolveRating(
                apiRating = null,
                apiRatingCount = null,
                websiteRating = null,
                tmdbRating = 8.2,
            )
        )
    }

    @Test
    fun `API rating remains ahead of website and TMDb`() {
        assertEquals(
            4.142857142857143,
            DoramasflixLogic.resolveRating(
                apiRating = 4.142857142857143,
                apiRatingCount = 14,
                websiteRating = 4.9,
                tmdbRating = 8.0,
            )
        )
    }

    @Test
    fun `explicit unrated API value does not inherit website or TMDb rating`() {
        assertNull(
            DoramasflixLogic.resolveRating(
                apiRating = 0.0,
                apiRatingCount = 0,
                websiteRating = 4.5,
                tmdbRating = 7.4,
            )
        )
    }

    @Test
    fun `API episode artwork is authoritative before website and TMDb`() {
        assertEquals(
            "/series.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/series.jpg",
                backdrop = "/alternate.jpg",
                stillImage = "/image.jpg",
                websiteArtwork = "/website.jpg",
                tmdbArtwork = "/tmdb.jpg",
            )
        )
    }

    @Test
    fun `episode artwork follows API fields before website and TMDb`() {
        assertEquals(
            "/backdrop.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = null,
                backdrop = "/backdrop.jpg",
                stillImage = "/still-image.jpg",
                websiteArtwork = "/website.jpg",
                tmdbArtwork = "/tmdb.jpg",
            )
        )
        assertEquals(
            "/still-image.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = null,
                backdrop = null,
                stillImage = "/still-image.jpg",
                websiteArtwork = "/website.jpg",
                tmdbArtwork = "/tmdb.jpg",
            )
        )
    }

    @Test
    fun `episode artwork uses website before TMDb when API has none`() {
        assertEquals(
            "/website.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = null,
                backdrop = null,
                stillImage = null,
                websiteArtwork = "/website.jpg",
                tmdbArtwork = "/tmdb.jpg",
            )
        )
        assertEquals(
            "/tmdb.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = null,
                backdrop = null,
                stillImage = null,
                websiteArtwork = null,
                tmdbArtwork = "/tmdb.jpg",
            )
        )
    }

    @Test
    fun `first nonblank metadata preserves fallback order`() {
        assertEquals("website", DoramasflixLogic.firstNonBlank(null, " ", "website", "tmdb"))
        assertNull(DoramasflixLogic.firstNonBlank(null, " "))
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
