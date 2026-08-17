package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.doramasflix.Content
import com.streamflixreborn.streamflix.models.doramasflix.ContentPage
import com.streamflixreborn.streamflix.models.doramasflix.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixGenreBrowserTest {

    private fun browser() = DoramasflixGenreBrowser(
        loadMoviePage = { ContentPage() },
        loadDoramaPage = { _, _ -> ContentPage() },
        mapMovie = { null },
        mapDorama = { null },
    )

    @Test
    fun `verified live Doramasflix genre registry includes politica`() {
        val browser = browser()
        assertEquals("Política", browser.genreName("politica"))
        assertTrue(browser.genres.any { it.id == "politica" && it.name == "Política" })
    }

    @Test
    fun `actual GraphQL genre metadata extends provider registry`() {
        val browser = browser()
        browser.registerGenres(
            listOf(
                Content(
                    genres = listOf(Tag(name = "Nuevo género", slug = "nuevo-genero")),
                )
            )
        )

        assertEquals("Nuevo género", browser.genreName("nuevo-genero"))
        assertTrue(browser.genres.any { it.id == "nuevo-genero" && it.name == "Nuevo género" })
    }
}
