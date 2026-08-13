package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitTmdbExtractorTest {

    @Test
    fun videasyRejectsMovieWithoutExplicitTmdbIdentity() {
        val movie = movieType(
            providerId = "12345",
            tmdbId = null,
        )

        assertTrue(VideasyExtractor().servers(movie, "en").isEmpty())
    }

    @Test
    fun videasyUsesExplicitTmdbIdInsteadOfNumericProviderId() {
        val movie = movieType(
            providerId = "99999",
            tmdbId = 12345,
        )

        val servers = VideasyExtractor().servers(movie, "en")

        assertTrue(servers.isNotEmpty())
        assertTrue(servers.all { it.src.contains("tmdbId=12345") })
        assertTrue(servers.none { it.src.contains("tmdbId=99999") })
    }

    @Test
    fun afterDarkRejectsMovieWithoutExplicitTmdbIdentityBeforeNetworkWork() = runBlocking {
        val movie = movieType(
            providerId = "12345",
            tmdbId = null,
        )

        assertTrue(AfterDarkExtractor().servers(movie).isEmpty())
    }

    @Test
    fun moflixRejectsMovieWithoutExplicitTmdbIdentityBeforeNetworkWork() = runBlocking {
        assertTrue(MoflixExtractor().servers(movieType("12345", null)).isEmpty())
    }

    @Test
    fun primeSrcRejectsMovieWithoutExplicitTmdbIdentityBeforeNetworkWork() = runBlocking {
        assertTrue(PrimeSrcExtractor().servers(movieType("12345", null)).isEmpty())
    }

    @Test
    fun frembedRejectsMovieWithoutExplicitTmdbIdentityBeforeNetworkWork() = runBlocking {
        assertTrue(FrembedExtractor().servers(movieType("12345", null)).isEmpty())
    }

    @Test
    fun vidsrcNetUsesExplicitTmdbIdInsteadOfNumericProviderId() {
        val server = VidsrcNetExtractor().server(
            movieType(
                providerId = "99999",
                tmdbId = 12345,
            ),
        )

        assertEquals("https://vidsrc-embed.ru/embed/movie?tmdb=12345", server.src)
    }

    @Test
    fun vidsrcNetRejectsMovieWithoutExplicitTmdbIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            VidsrcNetExtractor().server(movieType("12345", null))
        }
    }

    private fun movieType(
        providerId: String,
        tmdbId: Int?,
    ) = Video.Type.Movie(
        id = providerId,
        title = "Example",
        releaseDate = "2026-01-01",
        poster = "",
        imdbId = "tt1234567",
        tmdbId = tmdbId,
    )
}
