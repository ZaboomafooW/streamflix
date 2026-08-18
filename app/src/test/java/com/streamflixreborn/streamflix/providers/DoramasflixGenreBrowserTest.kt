package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.doramasflix.ContentPage
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
        mapDorama = { null },
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
}
