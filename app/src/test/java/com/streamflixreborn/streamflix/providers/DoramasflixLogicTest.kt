package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixLogicTest {

    @Test
    fun `rated API value is authoritative`() {
        val decision = DoramasflixLogic.resolveApiRating(4.142857142857143, 14)
        assertEquals(4.142857142857143, decision.rating)
        assertFalse(decision.useHtmlFallback)
    }

    @Test
    fun `zero API rating count means unrated and does not fall through`() {
        val decision = DoramasflixLogic.resolveApiRating(0.0, 0)
        assertNull(decision.rating)
        assertFalse(decision.useHtmlFallback)
        assertNull(
            DoramasflixLogic.resolveRating(
                apiRating = 0.0,
                apiRatingCount = 0,
                websiteRating = 4.8,
                tmdbRating = 9.2,
            )
        )
    }

    @Test
    fun `zero API rating means unrated even when count is positive`() {
        val decision = DoramasflixLogic.resolveApiRating(0.0, 4)
        assertNull(decision.rating)
        assertFalse(decision.useHtmlFallback)
        assertNull(
            DoramasflixLogic.resolveRating(
                apiRating = 0.0,
                apiRatingCount = 4,
                websiteRating = 4.8,
                tmdbRating = 9.2,
            )
        )
    }

    @Test
    fun `missing API rating is eligible for website fallback`() {
        val decision = DoramasflixLogic.resolveApiRating(null, null)
        assertNull(decision.rating)
        assertTrue(decision.useHtmlFallback)
        assertEquals(
            4.6,
            DoramasflixLogic.resolveRating(
                apiRating = null,
                apiRatingCount = null,
                websiteRating = 4.6,
                tmdbRating = 9.8,
            )
        )
    }

    @Test
    fun `TMDb rating is only used after API and website are missing`() {
        assertEquals(
            4.5,
            DoramasflixLogic.resolveRating(
                apiRating = null,
                apiRatingCount = null,
                websiteRating = null,
                tmdbRating = 9.0,
            )
        )
    }

    @Test
    fun `nonblank provider metadata is accepted without semantic guessing`() {
        assertEquals("Episodio 1", DoramasflixLogic.meaningfulTitle(" Episodio 1 "))
        assertEquals("Sinopsis pendiente", DoramasflixLogic.meaningfulOverview(" Sinopsis pendiente "))
        assertEquals(
            "Episodio 1",
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "Episodio 1",
                seasonNumber = 1,
                episodeNumber = 1,
                seriesTitles = listOf("Acaramelados"),
            )
        )
        assertNull(DoramasflixLogic.meaningfulTitle("   "))
        assertNull(DoramasflixLogic.meaningfulOverview(null))
    }

    @Test
    fun `provider artwork is accepted even when it matches parent artwork`() {
        assertEquals(
            "/shared.jpg",
            DoramasflixLogic.meaningfulImage(
                value = "/shared.jpg",
                genericArtwork = listOf("/shared.jpg"),
            )
        )
    }

    @Test
    fun `obvious image placeholders are unusable`() {
        assertNull(DoramasflixLogic.meaningfulImage("https://cdn.example/no-image.jpg"))
        assertNull(DoramasflixLogic.meaningfulImage("/assets/placeholder-poster.png"))
        assertEquals("/episode.jpg", DoramasflixLogic.meaningfulImage("/episode.jpg"))
    }

    @Test
    fun `episode artwork follows API then website then TMDb order`() {
        assertEquals(
            "/api-still.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/api-still.jpg",
                backdrop = "/api-backdrop.jpg",
                stillImage = "/api-image.jpg",
                websiteArtwork = "/website.jpg",
                tmdbArtwork = "/tmdb.jpg",
            )
        )
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
    fun `first nonblank metadata preserves source order`() {
        assertEquals("Doramasflix", DoramasflixLogic.firstNonBlank("Doramasflix", "Website", "TMDb"))
        assertEquals("Website", DoramasflixLogic.firstNonBlank(null, " ", "Website", "TMDb"))
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
    fun `protocol relative playback URL is normalized to https`() {
        assertEquals(
            "https://ok.ru/videoembed/123",
            DoramasflixLogic.normalizePlaybackTarget("//ok.ru/videoembed/123"),
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
    fun `epoch millisecond air date is converted to ISO date`() {
        assertEquals(
            "2020-10-13",
            DoramasflixLogic.normalizeAirDate("1602565200000"),
        )
    }

    @Test
    fun `Spanish date is normalized to ISO`() {
        assertEquals("2026-07-18", DoramasflixLogic.normalizeDate("18 de julio de 2026"))
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
}
