package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTrackIdentityPolicyTest {

    @Test
    fun genericLanguageAndAccessibilityLabelsUseLanguagePreference() {
        val aliases = setOf("en", "eng", "english")

        assertFalse(
            PlaybackTrackIdentityPolicy.requiresExactSubtitleIdentity(
                label = "English",
                language = "en",
                languageAliases = aliases,
                sameLanguageVariantCount = 1,
            )
        )
        assertFalse(
            PlaybackTrackIdentityPolicy.requiresExactSubtitleIdentity(
                label = "English [CC]",
                language = "en",
                languageAliases = aliases,
                sameLanguageVariantCount = 1,
            )
        )
    }

    @Test
    fun qualifiedLabelsAndLanguageTagsRequireExactIdentity() {
        assertTrue(
            PlaybackTrackIdentityPolicy.requiresExactSubtitleIdentity(
                label = "Spanish (Latin American)",
                language = "es",
                languageAliases = setOf("es", "spa", "spanish"),
                sameLanguageVariantCount = 1,
            )
        )
        assertTrue(
            PlaybackTrackIdentityPolicy.requiresExactSubtitleIdentity(
                label = "Chinese Traditional",
                language = "zh",
                languageAliases = setOf("zh", "zho", "chinese"),
                sameLanguageVariantCount = 1,
            )
        )
        assertTrue(
            PlaybackTrackIdentityPolicy.requiresExactSubtitleIdentity(
                label = "Spanish",
                language = "es-419",
                languageAliases = setOf("es", "spa", "spanish"),
                sameLanguageVariantCount = 1,
            )
        )
    }

    @Test
    fun duplicateSameIntentTracksRequireExactIdentity() {
        assertTrue(PlaybackTrackIdentityPolicy.requiresExactAudioIdentity(2))
        assertFalse(PlaybackTrackIdentityPolicy.requiresExactAudioIdentity(1))
        assertTrue(
            PlaybackTrackIdentityPolicy.requiresExactSubtitleIdentity(
                label = "Spanish",
                language = "es",
                languageAliases = setOf("es", "spa", "spanish"),
                sameLanguageVariantCount = 2,
            )
        )
    }

    @Test
    fun crossSourceIdentityNeverGuessesBetweenDuplicates() {
        val candidates = listOf("Spanish (Latin American)", "Spanish (European)")

        assertEquals(
            "Spanish (European)",
            PlaybackTrackIdentityPolicy.resolveUniqueIdentity(candidates) {
                it == "Spanish (European)"
            }
        )
        assertNull(
            PlaybackTrackIdentityPolicy.resolveUniqueIdentity(
                candidates = listOf("Spanish", "Spanish"),
                identityMatches = { it == "Spanish" },
            )
        )
        assertNull(
            PlaybackTrackIdentityPolicy.resolveUniqueIdentity(candidates) {
                it == "Spanish (Mexican)"
            }
        )
    }
}
