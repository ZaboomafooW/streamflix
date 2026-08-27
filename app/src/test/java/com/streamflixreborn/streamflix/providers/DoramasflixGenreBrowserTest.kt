package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.doramasflix.Content
import com.streamflixreborn.streamflix.models.doramasflix.ContentPage
import com.streamflixreborn.streamflix.models.doramasflix.Tag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixGenreBrowserTest {

    private fun browser() = DoramasflixGenreBrowser(
        loadMoviePage = { ContentPage() },
        loadDoramaPage = { _, _ -> ContentPage() },
        mapMovie = { null },
        mapDorama = { _, _ -> null },
    )

    @Test
    fun `verified live Doramasflix genre registry includes politica`() {
        val browser = browser()
        assertEquals("Política", browser.genreName("politica"))
        assertTrue(browser.genres.any { it.id == "politica" && it.name == "Política" })
    }

    @Test
    fun `unverified content tags are not promoted to browse categories`() {
        val browser = browser()

        assertNull(browser.genreName("talk"))
        assertFalse(browser.genres.any { it.id == "talk" })
    }

    @Test
    fun `mixed genre results preserve dorama and variety routes`() = runBlocking {
        val browser = DoramasflixGenreBrowser(
            loadMoviePage = { ContentPage() },
            loadDoramaPage = { _, isVariety ->
                if (isVariety) {
                    ContentPage(
                        listOf(
                            Content(
                                name = "Variety Show",
                                slug = "variety-show",
                                genres = listOf(Tag(slug = "comedia")),
                            )
                        )
                    )
                } else {
                    ContentPage(
                        listOf(
                            Content(
                                name = "Dorama Show",
                                slug = "dorama-show",
                                genres = listOf(Tag(slug = "comedia")),
                            )
                        )
                    )
                }
            },
            mapMovie = { null },
            mapDorama = { content, route ->
                TvShow(id = route.id(content.slug!!), title = content.name!!)
            },
        )

        val ids = browser.getShows("comedia", 1).map { it.id }

        assertEquals(
            listOf("doramas-online/dorama-show", "variedades-online/variety-show"),
            ids,
        )
    }
}
