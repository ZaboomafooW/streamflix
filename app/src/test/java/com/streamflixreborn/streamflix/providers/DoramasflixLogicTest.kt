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
    fun `meaningful API episode artwork remains authoritative`() {
        assertEquals(
            "/episode-seven.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/episode-seven.jpg",
                backdrop = "/alternate.jpg",
                stillImage = "/image.jpg",
                websiteArtwork = "/website.jpg",
                tmdbArtwork = "/tmdb.jpg",
                genericArtwork = listOf("/series.jpg"),
            )
        )
    }

    @Test
    fun `series key art is treated as missing episode artwork`() {
        assertEquals(
            "/website.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/series.jpg",
                backdrop = "https://image.tmdb.org/t/p/w1280/series.jpg",
                stillImage = null,
                websiteArtwork = "/website.jpg",
                tmdbArtwork = "/tmdb.jpg",
                genericArtwork = listOf("https://image.tmdb.org/t/p/original/series.jpg"),
            )
        )
    }

    @Test
    fun `repeated episode artwork is not generic merely because it repeats`() {
        assertEquals(
            "/legitimate-shared-still.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/legitimate-shared-still.jpg",
                backdrop = null,
                stillImage = null,
                websiteArtwork = "/website.jpg",
                tmdbArtwork = "/tmdb.jpg",
                genericArtwork = listOf("/series-backdrop.jpg"),
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
    fun `generic Spooky episode labels are treated as missing`() {
        val seriesTitles = listOf("Spooky in Love", "Muertos de Amor")

        assertNull(
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "Muertos de Amor 1x7",
                seasonNumber = 1,
                episodeNumber = 7,
                seriesTitles = seriesTitles,
            )
        )
        assertNull(
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "Spooky in Love episodio 7",
                seasonNumber = 1,
                episodeNumber = 7,
                seriesTitles = seriesTitles,
            )
        )
        assertNull(
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "Episode 7",
                seasonNumber = 1,
                episodeNumber = 7,
                seriesTitles = seriesTitles,
            )
        )
        assertNull(
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "S1.E7",
                seasonNumber = 1,
                episodeNumber = 7,
                seriesTitles = seriesTitles,
            )
        )
    }

    @Test
    fun `meaningful episode title is preserved`() {
        assertEquals(
            "The Visitor at Midnight",
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "The Visitor at Midnight",
                seasonNumber = 1,
                episodeNumber = 7,
                seriesTitles = listOf("Spooky in Love"),
            )
        )
    }

    @Test
    fun `generic descriptions are treated as missing`() {
        assertNull(DoramasflixLogic.meaningfulOverview("Sin sinopsis"))
        assertNull(
            DoramasflixLogic.meaningfulOverview(
                "Ver Spooky in Love Episodio 7 Online Gratis en HD con audio Latino y Subtitulado."
            )
        )
        assertEquals(
            "Una heredera ve fantasmas y ayuda a resolver un asesinato.",
            DoramasflixLogic.meaningfulOverview(
                "Una heredera ve fantasmas y ayuda a resolver un asesinato."
            )
        )
    }

    @Test
    fun `generic image markers are treated as missing`() {
        assertNull(DoramasflixLogic.meaningfulImage("https://cdn.example/no-image.jpg"))
        assertNull(DoramasflixLogic.meaningfulImage("/assets/placeholder-poster.png"))
    }

    @Test
    fun `TMDb image sizes refer to the same underlying asset`() {
        assertTrue(
            DoramasflixLogic.sameImageAsset(
                "/abc123.jpg",
                "https://image.tmdb.org/t/p/w1280/abc123.jpg?cache=1",
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
    fun `Spanish website date is normalized to ISO`() {
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
