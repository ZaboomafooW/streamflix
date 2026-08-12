package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTrackDisplayNamesTest {

    @Test
    fun preservesTrustworthySourceDetail() {
        assertEquals(
            "English [CC]",
            PlaybackTrackDisplayNames.subtitleName(" English [CC] ", "English"),
        )
        assertEquals(
            "Spanish (Latin American)",
            PlaybackTrackDisplayNames.subtitleName("Spanish (Latin American)", "Spanish"),
        )
    }

    @Test
    fun fallsBackToMedia3ForMissingOrUnknownLabels() {
        assertEquals("English", PlaybackTrackDisplayNames.subtitleName(null, "English"))
        assertEquals("English", PlaybackTrackDisplayNames.subtitleName("Unknown", "English"))
        assertEquals("English", PlaybackTrackDisplayNames.subtitleName("und", "English"))
    }

    @Test
    fun numbersOnlyNamesThatRemainIdentical() {
        assertEquals(
            listOf(
                "English",
                "English (2)",
                "English [CC]",
                "Spanish (Latin American)",
                "English (3)",
            ),
            PlaybackTrackDisplayNames.disambiguate(
                listOf(
                    "English",
                    "English",
                    "English [CC]",
                    "Spanish (Latin American)",
                    "English",
                )
            ),
        )
    }
}
