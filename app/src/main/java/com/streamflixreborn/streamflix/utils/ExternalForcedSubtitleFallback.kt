package com.streamflixreborn.streamflix.utils

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

object ExternalForcedSubtitleFallback {

    fun selectedAudioLanguage(player: Player): String? {
        player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .forEach { group ->
                for (trackIndex in 0 until group.length) {
                    if (group.isTrackSelected(trackIndex)) {
                        return group.getTrackFormat(trackIndex).language
                    }
                }
            }
        return null
    }

    fun hasSelectedNormalSubtitle(player: Player): Boolean {
        return player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .any { group ->
                (0 until group.length).any { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    group.isTrackSelected(trackIndex) &&
                        format.selectionFlags and C.SELECTION_FLAG_FORCED == 0
                }
            }
    }

    fun hasMatchingSourceForcedSubtitle(
        player: Player,
        audioLanguage: String,
    ): Boolean {
        val currentItemHasMatch = player.currentMediaItem
            ?.localConfiguration
            ?.subtitleConfigurations
            .orEmpty()
            .any { subtitle ->
                subtitle.selectionFlags and C.SELECTION_FLAG_FORCED != 0 &&
                    OpenSubtitles.languagesMatch(subtitle.language, audioLanguage)
            }
        if (currentItemHasMatch) return true

        return player.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .any { group ->
                (0 until group.length).any { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 &&
                        (group.isTrackSelected(trackIndex) ||
                            OpenSubtitles.languagesMatch(format.language, audioLanguage))
                }
            }
    }
}

fun ExoPlayer.appendSubtitleConfiguration(
    subtitleConfiguration: MediaItem.SubtitleConfiguration,
): Boolean {
    val currentItem = currentMediaItem ?: return false
    val currentPosition = currentPosition
    val shouldPlay = playWhenReady
    val subtitleConfigurations =
        currentItem.localConfiguration?.subtitleConfigurations.orEmpty()

    setMediaItem(
        currentItem.buildUpon()
            .setSubtitleConfigurations(subtitleConfigurations + subtitleConfiguration)
            .build()
    )
    seekTo(currentPosition)
    prepare()
    playWhenReady = shouldPlay
    return true
}
