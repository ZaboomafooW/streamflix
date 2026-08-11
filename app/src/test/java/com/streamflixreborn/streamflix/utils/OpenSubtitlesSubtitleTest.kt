package com.streamflixreborn.streamflix.utils

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesSubtitleTest {

    @Test
    fun `foreign-parts metadata classifies forced subtitle`() {
        val subtitle = Gson().fromJson(
            """{"SubForeignPartsOnly":"1","LanguageName":"English","ISO639":"en"}""",
            OpenSubtitles.Subtitle::class.java,
        )

        assertTrue(subtitle.isForced)
        assertEquals("English (Forced)", subtitle.displayLabel)
    }

    @Test
    fun `normal subtitle remains normal`() {
        val subtitle = Gson().fromJson(
            """{"SubForeignPartsOnly":"0","LanguageName":"Spanish","ISO639":"es"}""",
            OpenSubtitles.Subtitle::class.java,
        )

        assertFalse(subtitle.isForced)
        assertEquals("Spanish", subtitle.displayLabel)
    }

    @Test
    fun `missing or unexpected forced metadata is not treated as forced`() {
        assertFalse(OpenSubtitles.Subtitle(subForeignPartsOnly = null).isForced)
        assertFalse(OpenSubtitles.Subtitle(subForeignPartsOnly = "true").isForced)
        assertFalse(OpenSubtitles.Subtitle(subForeignPartsOnly = "2").isForced)
    }

    @Test
    fun `language name falls back to normalized ISO 639 metadata`() {
        val subtitle = OpenSubtitles.Subtitle(
            languageName = null,
            iso639 = "fr",
        )

        assertEquals("fr", subtitle.languageTag)
        assertEquals("French", subtitle.displayLanguage)
        assertEquals("French", subtitle.displayLabel)
    }

    @Test
    fun `language tag normalizes underscore region separator`() {
        val subtitle = OpenSubtitles.Subtitle(
            languageName = "Portuguese",
            iso639 = "pt_BR",
        )

        assertEquals("pt-BR", subtitle.languageTag)
        assertEquals("Portuguese", subtitle.displayLanguage)
    }
}
