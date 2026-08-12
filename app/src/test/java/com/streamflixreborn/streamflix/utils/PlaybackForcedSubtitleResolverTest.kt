package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackForcedSubtitleResolverTest {

    @Test
    fun unavailableRememberedSubtitleUsesAutomaticFallback() {
        assertTrue(
            PlaybackForcedSubtitleResolver.shouldUseAutomaticFallback(
                rememberedSubtitleLanguage = "es",
                standardSubtitleLanguages = listOf("en", "it"),
            )
        )
    }

    @Test
    fun availableRememberedSubtitleKeepsNormalSelection() {
        assertFalse(
            PlaybackForcedSubtitleResolver.shouldUseAutomaticFallback(
                rememberedSubtitleLanguage = "es",
                standardSubtitleLanguages = listOf("en", "es"),
            )
        )
    }

    @Test
    fun externalFallbackUsesOnlyActuallySelectedAudioLanguage() {
        assertEquals(
            "en",
            PlaybackForcedSubtitleResolver.externalFallbackLanguage(
                audioTracks = listOf(
                    track(groupIndex = 1, language = "it"),
                    track(groupIndex = 2, language = "en", selected = true),
                ),
                forcedSubtitleTracks = emptyList(),
            )
        )
    }

    @Test
    fun existingMatchingForcedTrackSuppressesExternalFallback() {
        assertNull(
            PlaybackForcedSubtitleResolver.externalFallbackLanguage(
                audioTracks = listOf(
                    track(groupIndex = 2, language = "en", selected = true),
                ),
                forcedSubtitleTracks = listOf(
                    track(groupIndex = 4, language = "en"),
                    track(groupIndex = 5, language = "it"),
                ),
            )
        )
    }

    @Test
    fun ambiguousSelectedAudioLanguagesDoNotRequestExternalFallback() {
        assertNull(
            PlaybackForcedSubtitleResolver.externalFallbackLanguage(
                audioTracks = listOf(
                    track(groupIndex = 1, language = "it", selected = true),
                    track(groupIndex = 2, language = "en", selected = true),
                ),
                forcedSubtitleTracks = emptyList(),
            )
        )
    }

    @Test
    fun preferredEnglishAudioRejectsSourceDefaultItalianForcedTrack() {
        val result = PlaybackForcedSubtitleResolver.resolve(
            audioOverride = null,
            preferredAudioLanguages = listOf("en"),
            audioTracks = listOf(
                track(groupIndex = 1, language = "it", selected = true),
                track(groupIndex = 2, language = "en"),
            ),
            forcedSubtitleTracks = listOf(
                track(groupIndex = 4, language = "it", selected = true),
            ),
        )

        assertNull(result)
    }

    @Test
    fun explicitItalianAudioSelectsItalianForcedTrack() {
        val italianAudio = position(1)
        val italianForced = position(4)

        val result = PlaybackForcedSubtitleResolver.resolve(
            audioOverride = italianAudio,
            preferredAudioLanguages = listOf("en"),
            audioTracks = listOf(
                track(groupIndex = 1, language = "it"),
                track(groupIndex = 2, language = "en", selected = true),
            ),
            forcedSubtitleTracks = listOf(
                track(groupIndex = 4, language = "it"),
            ),
        )

        assertEquals(italianForced, result)
    }

    @Test
    fun explicitEnglishAudioClearsPreviouslySelectedItalianForcedTrack() {
        val result = PlaybackForcedSubtitleResolver.resolve(
            audioOverride = position(2),
            preferredAudioLanguages = listOf("it"),
            audioTracks = listOf(
                track(groupIndex = 1, language = "it", selected = true),
                track(groupIndex = 2, language = "en"),
            ),
            forcedSubtitleTracks = listOf(
                track(groupIndex = 4, language = "it", selected = true),
            ),
        )

        assertNull(result)
    }

    private fun track(
        groupIndex: Int,
        language: String?,
        selected: Boolean = false,
    ) = PlaybackForcedSubtitleResolver.Candidate(
        position = position(groupIndex),
        language = language,
        selected = selected,
    )

    private fun position(groupIndex: Int) = PlaybackForcedSubtitleResolver.Position(
        groupIndex = groupIndex,
        trackIndex = 0,
    )
}
