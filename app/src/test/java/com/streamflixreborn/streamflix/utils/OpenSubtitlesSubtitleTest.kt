package com.streamflixreborn.streamflix.utils

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `OpenSubtitles movie hash uses file size and little endian chunks`() {
        val firstChunk = ByteArray(64 * 1024)
        val lastChunk = ByteArray(64 * 1024)
        firstChunk[0] = 1

        assertEquals(
            "0000000000020001",
            OpenSubtitles.computeMovieHash(
                movieByteSize = 128 * 1024L,
                firstChunk = firstChunk,
                lastChunk = lastChunk,
            ),
        )
    }

    @Test
    fun `OpenSubtitles movie hash rejects incomplete chunks`() {
        assertNull(
            OpenSubtitles.computeMovieHash(
                movieByteSize = 128 * 1024L,
                firstChunk = ByteArray(1024),
                lastChunk = ByteArray(64 * 1024),
            )
        )
    }

    @Test
    fun `exact forced match requires hash size forced flag and download link`() {
        val fingerprint = OpenSubtitles.VideoFingerprint(
            movieHash = "abcdef0123456789",
            movieByteSize = 123456789L,
        )
        val valid = forcedSubtitle(
            id = "valid",
            movieHash = fingerprint.movieHash,
            movieByteSize = fingerprint.movieByteSize,
            language = "en",
        )
        val wrongHash = forcedSubtitle(
            id = "wrong-hash",
            movieHash = "0000000000000000",
            movieByteSize = fingerprint.movieByteSize,
            language = "en",
        )
        val wrongSize = forcedSubtitle(
            id = "wrong-size",
            movieHash = fingerprint.movieHash,
            movieByteSize = fingerprint.movieByteSize + 1,
            language = "en",
        )
        val normal = valid.copy(
            idSubtitleFile = "normal",
            subForeignPartsOnly = "0",
        )
        val bad = valid.copy(
            idSubtitleFile = "bad",
            subBad = "1",
        )
        val missingDownload = valid.copy(
            idSubtitleFile = "missing-download",
            subDownloadLink = "",
        )

        assertEquals(
            listOf(valid),
            OpenSubtitles.exactForcedMatches(
                subtitles = listOf(valid, wrongHash, wrongSize, normal, bad, missingDownload),
                fingerprint = fingerprint,
            ),
        )
    }

    @Test
    fun `forced subtitle language follows selected audio language`() {
        val fingerprint = OpenSubtitles.VideoFingerprint(
            movieHash = "abcdef0123456789",
            movieByteSize = 123456789L,
        )
        val english = forcedSubtitle(
            id = "english",
            movieHash = fingerprint.movieHash,
            movieByteSize = fingerprint.movieByteSize,
            language = "en",
        )
        val spanish = forcedSubtitle(
            id = "spanish",
            movieHash = fingerprint.movieHash,
            movieByteSize = fingerprint.movieByteSize,
            language = "es",
        )

        assertEquals(
            english,
            OpenSubtitles.selectExactForcedSubtitle(
                subtitles = listOf(spanish, english),
                fingerprint = fingerprint,
                audioLanguage = "eng",
            ),
        )
        assertEquals(
            spanish,
            OpenSubtitles.selectExactForcedSubtitle(
                subtitles = listOf(english, spanish),
                fingerprint = fingerprint,
                audioLanguage = "spa",
            ),
        )
        assertNull(
            OpenSubtitles.selectExactForcedSubtitle(
                subtitles = listOf(english, spanish),
                fingerprint = fingerprint,
                audioLanguage = "fra",
            )
        )
    }

    @Test
    fun `exact same-language matches prefer trusted subtitle`() {
        val fingerprint = OpenSubtitles.VideoFingerprint(
            movieHash = "abcdef0123456789",
            movieByteSize = 123456789L,
        )
        val popular = forcedSubtitle(
            id = "popular",
            movieHash = fingerprint.movieHash,
            movieByteSize = fingerprint.movieByteSize,
            language = "en",
        ).copy(
            subRating = "10.0",
            subDownloadsCnt = "99999",
        )
        val trusted = forcedSubtitle(
            id = "trusted",
            movieHash = fingerprint.movieHash,
            movieByteSize = fingerprint.movieByteSize,
            language = "en",
        ).copy(
            subFromTrusted = "1",
            subRating = "1.0",
            subDownloadsCnt = "1",
        )

        assertEquals(
            trusted,
            OpenSubtitles.selectExactForcedSubtitle(
                subtitles = listOf(popular, trusted),
                fingerprint = fingerprint,
                audioLanguage = "en-US",
            ),
        )
    }

    @Test
    fun `two and three letter language codes normalize to same language`() {
        assertEquals("en", OpenSubtitles.normalizeLanguageCode("eng"))
        assertEquals("es", OpenSubtitles.normalizeLanguageCode("spa"))
        assertTrue(OpenSubtitles.languagesMatch("en-US", "eng"))
        assertTrue(OpenSubtitles.languagesMatch("es", "spa"))
        assertFalse(OpenSubtitles.languagesMatch("en", "spa"))
    }

    private fun forcedSubtitle(
        id: String,
        movieHash: String,
        movieByteSize: Long,
        language: String,
    ) = OpenSubtitles.Subtitle(
        idSubtitleFile = id,
        movieHash = movieHash,
        movieByteSize = movieByteSize.toString(),
        iso639 = language,
        subLanguageID = language,
        subForeignPartsOnly = "1",
        subDownloadLink = "https://example.invalid/$id.gz",
        subFileName = "$id.srt",
    )
}
