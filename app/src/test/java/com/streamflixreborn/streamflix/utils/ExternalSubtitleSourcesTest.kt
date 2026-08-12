package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSubtitleSourcesTest {

    @Test
    fun `OpenSubtitles imdb id uses legacy numeric format`() {
        assertEquals("0133093", OpenSubtitles.normalizeImdbId("tt0133093"))
        assertEquals("15239678", OpenSubtitles.normalizeImdbId("tt15239678"))
        assertNull(OpenSubtitles.normalizeImdbId("not-an-imdb-id"))
    }

    @Test
    fun `OpenSubtitles preserves forced metadata as display metadata`() {
        val subtitle = OpenSubtitles.Subtitle(
            iso639 = "eng",
            languageName = "English",
            subForeignPartsOnly = "1",
        )

        assertTrue(subtitle.isForced)
        assertEquals("en", subtitle.languageTag)
        assertEquals("English (Forced)", subtitle.displayLabel)
    }

    @Test
    fun `subtitle language normalization handles iso3 and regions`() {
        assertEquals("en", SubtitleLanguage.normalize("eng"))
        assertEquals("pt-BR", SubtitleLanguage.normalize("pt_BR"))
        assertEquals("Spanish", SubtitleLanguage.displayName("es"))
        assertNull(SubtitleLanguage.normalize("Unknown language"))
    }

    @Test
    fun `SubDL imdb id keeps expected tt format`() {
        assertEquals("tt1375666", SubDL.normalizeImdbId("tt1375666"))
        assertEquals("tt1375666", SubDL.normalizeImdbId("1375666"))
        assertNull(SubDL.normalizeImdbId("bad-id"))
    }

    @Test
    fun `SubDL packed season results expose only requested episode file`() {
        val packed = SubDL.Subtitle(
            releaseName = "Season pack",
            name = "Season.Pack.zip",
            url = "/subtitle/pack.zip",
            fullSeason = true,
            unpackFiles = listOf(
                SubDL.UnpackedFile(
                    name = "Show.S01E01.srt",
                    releaseName = "Episode 1",
                    season = 1,
                    episode = 1,
                    language = "EN",
                    format = "srt",
                    url = "/subtitle/pack/e1",
                ),
                SubDL.UnpackedFile(
                    name = "Show.S01E02.srt",
                    releaseName = "Episode 2",
                    season = 1,
                    episode = 2,
                    language = "EN",
                    format = "srt",
                    url = "/subtitle/pack/e2",
                ),
            ),
        )

        val result = SubDL.expandSubtitle(
            subtitle = packed,
            seasonNumber = 1,
            episodeNumber = 2,
        )

        assertEquals(1, result.size)
        assertEquals("Show.S01E02.srt", result.single().name)
        assertEquals("/subtitle/pack/e2", result.single().url)
        assertTrue(result.single().directFile)
    }

    @Test
    fun `SubDL season pack without exact unpacked episode is not guessed`() {
        val packed = SubDL.Subtitle(
            releaseName = "Season pack",
            url = "/subtitle/pack.zip",
            fullSeason = true,
            unpackFiles = listOf(
                SubDL.UnpackedFile(
                    name = "Show.S01E01.srt",
                    season = 1,
                    episode = 1,
                    language = "EN",
                    format = "srt",
                    url = "/subtitle/pack/e1",
                )
            ),
        )

        assertTrue(
            SubDL.expandSubtitle(
                subtitle = packed,
                seasonNumber = 1,
                episodeNumber = 2,
            ).isEmpty()
        )
    }

    @Test
    fun `SubDL normal single subtitle stays selectable`() {
        val subtitle = SubDL.Subtitle(
            releaseName = "WEB-DL",
            name = "Movie.en.srt",
            lang = "EN",
            url = "/subtitle/movie.zip",
        )

        val result = SubDL.expandSubtitle(
            subtitle = subtitle,
            seasonNumber = null,
            episodeNumber = null,
        )

        assertEquals(listOf(subtitle), result)
        assertFalse(result.single().directFile)
        assertEquals("English", result.single().displayLabel)
    }

    @Test
    fun `SubDL hearing impaired metadata remains visible`() {
        val subtitle = SubDL.Subtitle(
            lang = "EN",
            hi = true,
            url = "/subtitle/movie.zip",
        )

        assertEquals("English [HI]", subtitle.displayLabel)
    }
}
