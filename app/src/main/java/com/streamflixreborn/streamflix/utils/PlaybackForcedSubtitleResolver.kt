package com.streamflixreborn.streamflix.utils

/** Resolves Forced-only playback from already-canonical track languages. */
internal object PlaybackForcedSubtitleResolver {

    data class Position(
        val groupIndex: Int,
        val trackIndex: Int,
    )

    data class Candidate(
        val position: Position,
        val language: String?,
        val selected: Boolean,
    )

    fun shouldUseAutomaticFallback(
        rememberedSubtitleLanguage: String?,
        standardSubtitleLanguages: List<String?>,
    ): Boolean = rememberedSubtitleLanguage == null || standardSubtitleLanguages.none { language ->
        language == rememberedSubtitleLanguage
    }

    fun resolve(
        audioOverride: Position?,
        preferredAudioLanguages: List<String>,
        audioTracks: List<Candidate>,
        forcedSubtitleTracks: List<Candidate>,
    ): Position? {
        val audioTrack = audioOverride
            ?.let { position -> audioTracks.firstOrNull { it.position == position } }
            ?: preferredAudioLanguages.firstNotNullOfOrNull { language ->
                audioTracks.firstOrNull { track -> track.language == language }
            }
            ?: audioTracks.firstOrNull { it.selected }
            ?: return null
        val audioLanguage = audioTrack.language ?: return null
        val matchingForcedTracks = forcedSubtitleTracks.filter { track ->
            track.language == audioLanguage
        }

        return matchingForcedTracks.firstOrNull { it.selected }?.position
            ?: matchingForcedTracks.firstOrNull()?.position
    }
}
