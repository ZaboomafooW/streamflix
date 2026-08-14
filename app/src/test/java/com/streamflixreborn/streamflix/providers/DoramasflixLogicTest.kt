package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixLogicTest {

    @Test
    fun `server errors mark the provider unavailable`() {
        assertTrue(DoramasflixLogic.isUnavailableHttpStatus(500))
        assertTrue(DoramasflixLogic.isUnavailableHttpStatus(503))
        assertFalse(DoramasflixLogic.isUnavailableHttpStatus(429))
        assertFalse(DoramasflixLogic.isUnavailableHttpStatus(404))
    }

    @Test
    fun `provider unavailable error has stable user facing message`() {
        assertEquals(
            "Doramasflix is currently unavailable. Please try again later.",
            DoramasflixUnavailableException().message,
        )
    }

    @Test
    fun `rated API value is authoritative`() {
        val decision = DoramasflixLogic.resolveApiRating(4.25, 12)
        assertEquals(4.25, decision.rating)
        assertFalse(decision.allowExternalFallback)
    }

    @Test
    fun `zero API rating count is terminal unrated`() {
        val decision = DoramasflixLogic.resolveApiRating(null, 0)
        assertNull(decision.rating)
        assertFalse(decision.allowExternalFallback)
        assertNull(
            DoramasflixLogic.resolveRating(
                apiRating = null,
                apiRatingCount = 0,
                tmdbRating = 9.2,
            )
        )
    }

    @Test
    fun `zero API rating is terminal unrated`() {
        val decision = DoramasflixLogic.resolveApiRating(0.0, 4)
        assertNull(decision.rating)
        assertFalse(decision.allowExternalFallback)
        assertNull(
            DoramasflixLogic.resolveRating(
                apiRating = 0.0,
                apiRatingCount = 4,
                tmdbRating = 9.2,
            )
        )
    }

    @Test
    fun `missing API rating can fall back to TMDb`() {
        val decision = DoramasflixLogic.resolveApiRating(null, null)
        assertNull(decision.rating)
        assertTrue(decision.allowExternalFallback)
        assertEquals(
            4.5,
            DoramasflixLogic.resolveRating(
                apiRating = null,
                apiRatingCount = null,
                tmdbRating = 9.0,
            )
        )
    }

    @Test
    fun `invalid API ratings are eligible for external fallback`() {
        assertTrue(DoramasflixLogic.resolveApiRating(-1.0, 3).allowExternalFallback)
        assertTrue(DoramasflixLogic.resolveApiRating(5.1, 3).allowExternalFallback)
        assertTrue(DoramasflixLogic.resolveApiRating(Double.NaN, 3).allowExternalFallback)
        assertTrue(DoramasflixLogic.resolveApiRating(4.0, -1).allowExternalFallback)
    }

    @Test
    fun `invalid TMDb rating is not used`() {
        assertNull(DoramasflixLogic.resolveRating(null, null, 11.0))
        assertNull(DoramasflixLogic.resolveRating(null, null, Double.NaN))
    }

    @Test
    fun `nonblank provider metadata is preserved`() {
        assertEquals("Episodio 1", DoramasflixLogic.nonBlank(" Episodio 1 "))
        assertEquals("Sinopsis pendiente", DoramasflixLogic.nonBlank(" Sinopsis pendiente "))
        assertNull(DoramasflixLogic.nonBlank("   "))
    }

    @Test
    fun `obvious image placeholders are unusable`() {
        assertNull(DoramasflixLogic.meaningfulImage("https://cdn.example/no-image.jpg"))
        assertNull(DoramasflixLogic.meaningfulImage("/assets/placeholder-poster.png"))
        assertEquals("/episode.jpg", DoramasflixLogic.meaningfulImage("/episode.jpg"))
    }

    @Test
    fun `generic provider episode titles are eligible for TMDb fallback`() {
        val showTitles = listOf("Our Sticky Love", "Acaramelados", "우리 영화")

        assertNull(
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "Acaramelados 1x1",
                showTitles = showTitles,
                seasonNumber = 1,
                episodeNumber = 1,
            )
        )
        assertNull(
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "Episodio 1",
                showTitles = showTitles,
                seasonNumber = 1,
                episodeNumber = 1,
            )
        )
        assertNull(
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "Capítulo 1",
                showTitles = showTitles,
                seasonNumber = 1,
                episodeNumber = 1,
            )
        )
        assertEquals(
            "Una visita inesperada",
            DoramasflixLogic.meaningfulEpisodeTitle(
                value = "Una visita inesperada",
                showTitles = showTitles,
                seasonNumber = 1,
                episodeNumber = 1,
            )
        )
    }

    @Test
    fun `specific provider episode overview remains authoritative`() {
        val overview = "Go Eun-sae despierta en el hospital sin recordar quién es. Pero su desconcierto aumenta cuando Jang Tae-ha asegura ser su novio."
        assertEquals(
            overview,
            DoramasflixLogic.meaningfulEpisodeOverview(
                value = overview,
                showOverview = "Una fiscal ambiciosa pierde la memoria.",
                showTitles = listOf("Our Sticky Love", "Acaramelados"),
                seasonNumber = 1,
                episodeNumber = 1,
            )
        )
    }

    @Test
    fun `generic provider episode overview is eligible for TMDb fallback`() {
        assertNull(
            DoramasflixLogic.meaningfulEpisodeOverview(
                value = "Una fiscal ambiciosa pierde la memoria.",
                showOverview = "Una fiscal ambiciosa pierde la memoria.",
                showTitles = listOf("Our Sticky Love", "Acaramelados"),
                seasonNumber = 1,
                episodeNumber = 1,
            )
        )
        assertNull(
            DoramasflixLogic.meaningfulEpisodeOverview(
                value = "Disfruta del capítulo 1 online. Selecciona tu servidor favorito.",
                showOverview = null,
                showTitles = listOf("Our Sticky Love", "Acaramelados"),
                seasonNumber = 1,
                episodeNumber = 1,
            )
        )
        assertNull(
            DoramasflixLogic.meaningfulEpisodeOverview(
                value = "Acaramelados 1x1",
                showOverview = null,
                showTitles = listOf("Our Sticky Love", "Acaramelados"),
                seasonNumber = 1,
                episodeNumber = 1,
            )
        )
    }

    @Test
    fun `episode artwork rejects show level art before falling back to TMDb`() {
        assertEquals(
            "/tmdb-episode.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/show-backdrop.jpg",
                backdrop = "https://image.tmdb.org/t/p/w1280/show-backdrop.jpg",
                stillImage = null,
                genericArtwork = listOf("/show-backdrop.jpg", "/show-poster.jpg"),
                tmdbArtwork = "/tmdb-episode.jpg",
            )
        )
    }

    @Test
    fun `episode artwork keeps a distinct provider still before TMDb`() {
        assertEquals(
            "/api-still.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/api-still.jpg",
                backdrop = "/show-backdrop.jpg",
                stillImage = "/api-image.jpg",
                genericArtwork = listOf("/show-backdrop.jpg"),
                tmdbArtwork = "/tmdb.jpg",
            )
        )
    }

    @Test
    fun `episode still image beats a generic backdrop and TMDb`() {
        assertEquals(
            "/api-image.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = null,
                backdrop = "/show-backdrop.jpg",
                stillImage = "/api-image.jpg",
                genericArtwork = listOf("/show-backdrop.jpg"),
                tmdbArtwork = "/tmdb.jpg",
            )
        )
    }

    @Test
    fun `repeated episode art is not rejected merely for repeating`() {
        assertEquals(
            "/shared-legitimate-still.jpg",
            DoramasflixLogic.episodeArtwork(
                stillPath = "/shared-legitimate-still.jpg",
                backdrop = null,
                stillImage = null,
                genericArtwork = listOf("/show-backdrop.jpg", "/season-poster.jpg"),
                tmdbArtwork = "/tmdb.jpg",
            )
        )
    }

    @Test
    fun `first nonblank metadata preserves source order`() {
        assertEquals("Doramasflix", DoramasflixLogic.firstNonBlank("Doramasflix", "TMDb"))
        assertEquals("TMDb", DoramasflixLogic.firstNonBlank(null, " ", "TMDb"))
        assertNull(DoramasflixLogic.firstNonBlank(null, " "))
    }

    @Test
    fun `home carousel mixes doramas and movies in provider order`() {
        assertEquals(
            listOf("D1", "M1", "D2", "D3"),
            DoramasflixLogic.mixAlternating(
                first = listOf("D1", "D2", "D3"),
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
        assertEquals("2020-10-13", DoramasflixLogic.normalizeAirDate("1602565200000"))
    }

    @Test
    fun `Spanish date is normalized to ISO`() {
        assertEquals("2026-07-18", DoramasflixLogic.normalizeDate("18 de julio de 2026"))
    }

    @Test
    fun `invalid calendar dates are rejected`() {
        assertNull(DoramasflixLogic.normalizeDate("2026-02-31"))
        assertNull(DoramasflixLogic.normalizeDate("31 de febrero de 2026"))
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