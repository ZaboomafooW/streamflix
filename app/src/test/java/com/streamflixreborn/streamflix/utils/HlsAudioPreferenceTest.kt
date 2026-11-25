package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAudioPreferenceTest {
    private fun track(group: String, language: String, name: String, default: String = "NO") =
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"$group\",LANGUAGE=\"$language\",NAME=\"$name\",DEFAULT=$default,AUTOSELECT=YES,URI=\"$name.m3u8\""

    @Test
    fun englishPreferredWithoutRemovingTracks() {
        val lines = listOf(track("a", "it", "Italian", "YES"), track("a", "en-US", "English"),
            track("a", "eng", "English 2"), "#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"a\"", "video.m3u8")
        val result = HlsAudioPreference.prefer(lines, "en")
        assertEquals(lines.size, result.size)
        assertTrue(result[0].contains("DEFAULT=NO"))
        assertTrue(result[1].contains("DEFAULT=YES"))
        assertEquals(lines[2], result[2])
        assertEquals(lines.drop(3), result.drop(3))
    }

    @Test
    fun originalDefaultPreservedWithoutEnglish() {
        val lines = listOf(track("a", "it", "Italian", "YES"), track("a", "ko", "Korean"))
        assertEquals(lines, HlsAudioPreference.prefer(lines, "en"))
    }

    @Test
    fun groupsHandledIndependently() {
        val lines = listOf(track("a", "it", "Italian", "YES"), track("a", "eng", "English"),
            track("b", "ko", "Korean", "YES"), track("c", "en", "English", "YES"))
        val result = HlsAudioPreference.prefer(lines, "en")
        assertTrue(result[1].contains("DEFAULT=YES"))
        assertEquals(lines[2], result[2])
        assertEquals(lines[3], result[3])
    }
}
