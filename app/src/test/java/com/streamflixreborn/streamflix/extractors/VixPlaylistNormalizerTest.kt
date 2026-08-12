package com.streamflixreborn.streamflix.extractors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VixPlaylistNormalizerTest {

    @Test
    fun normalizesForcedLanguagePrefix() {
        val line = subtitleLine(
            name = "Italian [Forced]",
            forced = "NO",
            language = "forced-ita",
        )

        val normalized = VixPlaylistNormalizer.normalizeForcedSubtitleLine(line)

        assertTrue(normalized.contains("FORCED=YES"))
        assertTrue(normalized.contains("LANGUAGE=\"it\""))
        assertTrue(normalized.contains("DEFAULT=NO,AUTOSELECT=NO"))
    }

    @Test
    fun normalizesForcedLanguageSuffix() {
        val line = subtitleLine(
            name = "Italian [Forced]",
            forced = "NO",
            language = "ita-forced",
        )

        val normalized = VixPlaylistNormalizer.normalizeForcedSubtitleLine(line)

        assertTrue(normalized.contains("FORCED=YES"))
        assertTrue(normalized.contains("LANGUAGE=\"it\""))
        assertFalse(normalized.contains("ita-forced"))
    }

    @Test
    fun preservesValidLanguageWhenOnlyNameMarksForced() {
        val line = subtitleLine(
            name = "Italian [Forced]",
            forced = "NO",
            language = "ita",
        )

        val normalized = VixPlaylistNormalizer.normalizeForcedSubtitleLine(line)

        assertTrue(normalized.contains("FORCED=YES"))
        assertTrue(normalized.contains("LANGUAGE=\"ita\""))
    }

    @Test
    fun leavesStandardSubtitleAndAudioLinesUntouched() {
        val subtitle = subtitleLine(
            name = "English [CC]",
            forced = "NO",
            language = "eng",
        )
        val audio = "#EXT-X-MEDIA:TYPE=AUDIO,NAME=\"English\",FORCED=NO,LANGUAGE=\"eng\""

        assertEquals(subtitle, VixPlaylistNormalizer.normalizeForcedSubtitleLine(subtitle))
        assertEquals(audio, VixPlaylistNormalizer.normalizeForcedSubtitleLine(audio))
    }

    @Test
    fun doesNotTreatUnforcedLabelAsForced() {
        val line = subtitleLine(
            name = "English Unforced",
            forced = "NO",
            language = "eng",
        )

        assertEquals(line, VixPlaylistNormalizer.normalizeForcedSubtitleLine(line))
    }

    private fun subtitleLine(name: String, forced: String, language: String) =
        "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"$name\"," +
            "DEFAULT=NO,AUTOSELECT=NO,FORCED=$forced,LANGUAGE=\"$language\",URI=\"sub.m3u8\""
}
