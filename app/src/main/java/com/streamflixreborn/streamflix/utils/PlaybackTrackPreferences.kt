package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import java.util.Locale

/**
 * Remembers explicit playback preferences that should carry between videos.
 *
 * Track groups themselves are specific to one media item, so preferences are
 * stored by language/label and matched again when the next video's tracks are
 * available. If a compatible track is not present, Media3 keeps the source's
 * normal/default selection instead of guessing.
 */
object PlaybackTrackPreferences {

    private enum class SubtitleVariant {
        REGULAR,
        ACCESSIBILITY,
        FORCED,
    }

    private data class TrackPreference(
        val language: String?,
        val label: String?,
        val subtitleVariant: SubtitleVariant = SubtitleVariant.REGULAR,
    )

    private sealed interface SubtitlePreference {
        data object Off : SubtitlePreference
        data class Track(val value: TrackPreference) : SubtitlePreference
    }

    private data class TrackMatch(
        val group: Tracks.Group,
        val index: Int,
    )

    private val preferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.playback_preferences",
            Context.MODE_PRIVATE,
        )
    }

    fun bind(player: Player): Player.Listener {
        val listener = object : Player.Listener {
            private var mediaItem: MediaItem? = null
            private var audioRestored = false
            private var subtitleRestored = false

            override fun onTracksChanged(tracks: Tracks) {
                val currentMediaItem = player.currentMediaItem
                if (currentMediaItem !== mediaItem) {
                    mediaItem = currentMediaItem
                    audioRestored = false
                    subtitleRestored = false
                }

                restoreTracks(player, tracks)
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                rememberTrackSelection(player.currentTracks, parameters)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                saveSpeed(playbackParameters.speed)
            }

            private fun restoreTracks(player: Player, tracks: Tracks) {
                val groups = tracks.groups
                val parameters = player.trackSelectionParameters
                val builder = parameters.buildUpon()
                var changed = false

                val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (!audioRestored && audioGroups.isNotEmpty()) {
                    loadAudio()?.let { preference ->
                        findBestAudioTrack(audioGroups, preference)?.let { match ->
                            builder.setOverrideForType(
                                TrackSelectionOverride(
                                    match.group.mediaTrackGroup,
                                    listOf(match.index),
                                )
                            )
                            builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            changed = true
                        }
                    }
                    audioRestored = true
                }

                if (!subtitleRestored) {
                    when (val preference = loadSubtitle()) {
                        SubtitlePreference.Off -> {
                            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            builder.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                            subtitleRestored = true
                            changed = true
                        }

                        is SubtitlePreference.Track -> {
                            val subtitleGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
                            if (subtitleGroups.isNotEmpty()) {
                                findBestSubtitleTrack(subtitleGroups, preference.value)?.let { match ->
                                    builder.setOverrideForType(
                                        TrackSelectionOverride(
                                            match.group.mediaTrackGroup,
                                            listOf(match.index),
                                        )
                                    )
                                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    builder.setIgnoredTextSelectionFlags(0)
                                    changed = true
                                }
                                subtitleRestored = true
                            }
                        }

                        null -> subtitleRestored = true
                    }
                }

                if (changed) {
                    player.trackSelectionParameters = builder.build()
                }
            }
        }

        player.addListener(listener)

        loadSpeed()?.let { speed ->
            if (player.playbackParameters.speed != speed) {
                player.playbackParameters = player.playbackParameters.withSpeed(speed)
            }
        }

        if (!player.currentTracks.isEmpty()) {
            listener.onTracksChanged(player.currentTracks)
        }

        return listener
    }

    private fun rememberTrackSelection(
        tracks: Tracks,
        parameters: TrackSelectionParameters,
    ) {
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        findCurrentOverride(parameters.overrides.values, audioGroups)?.let { match ->
            saveAudio(match.group.getTrackFormat(match.index))
        }

        val subtitleGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val subtitleOverride = findCurrentOverride(parameters.overrides.values, subtitleGroups)
        when {
            subtitleOverride != null -> {
                saveSubtitle(subtitleOverride.group.getTrackFormat(subtitleOverride.index))
            }

            isSubtitleOff(parameters.ignoredTextSelectionFlags) -> saveSubtitleOff()
        }
    }

    private fun loadAudio(): TrackPreference? {
        val language = preferences.getString(AUDIO_LANGUAGE, null)
        val label = preferences.getString(AUDIO_LABEL, null)
        if (language.isNullOrBlank() && label.isNullOrBlank()) return null

        return TrackPreference(
            language = canonicalLanguage(language),
            label = label?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun loadSubtitle(): SubtitlePreference? {
        return when (preferences.getString(SUBTITLE_MODE, null)) {
            SUBTITLE_MODE_OFF -> SubtitlePreference.Off
            SUBTITLE_MODE_TRACK -> {
                val language = preferences.getString(SUBTITLE_LANGUAGE, null)
                val label = preferences.getString(SUBTITLE_LABEL, null)
                val variant = preferences.getString(SUBTITLE_VARIANT, null)
                    ?.let { runCatching { SubtitleVariant.valueOf(it) }.getOrNull() }
                    ?: SubtitleVariant.REGULAR

                if (language.isNullOrBlank() && label.isNullOrBlank()) {
                    null
                } else {
                    SubtitlePreference.Track(
                        TrackPreference(
                            language = canonicalLanguage(language),
                            label = label?.trim()?.takeIf { it.isNotEmpty() },
                            subtitleVariant = variant,
                        )
                    )
                }
            }

            else -> {
                UserPreferences.subtitleName
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { legacy ->
                        SubtitlePreference.Track(
                            TrackPreference(
                                language = null,
                                label = legacy,
                                subtitleVariant = variantFromLabel(legacy),
                            )
                        )
                    }
            }
        }
    }

    private fun loadSpeed(): Float? {
        if (!preferences.contains(PLAYBACK_SPEED)) return null
        return preferences.getFloat(PLAYBACK_SPEED, 1F)
            .takeIf { it.isFinite() && it > 0F }
    }

    private fun saveAudio(format: Format) {
        val preference = TrackPreference(
            language = canonicalLanguage(format.language),
            label = meaningfulLabel(format.label),
        )

        if (preference.language == null && preference.label == null) return
        if (preference == loadAudio()) return

        preferences.edit {
            putOrRemove(AUDIO_LANGUAGE, preference.language)
            putOrRemove(AUDIO_LABEL, preference.label)
        }
    }

    private fun saveSubtitle(format: Format) {
        val preference = TrackPreference(
            language = canonicalLanguage(format.language),
            label = meaningfulLabel(format.label),
            subtitleVariant = subtitleVariant(format),
        )

        if (preference.language == null && preference.label == null) return
        if (SubtitlePreference.Track(preference) == loadSubtitle()) return

        preferences.edit {
            putString(SUBTITLE_MODE, SUBTITLE_MODE_TRACK)
            putOrRemove(SUBTITLE_LANGUAGE, preference.language)
            putOrRemove(SUBTITLE_LABEL, preference.label)
            putString(SUBTITLE_VARIANT, preference.subtitleVariant.name)
        }
    }

    private fun saveSubtitleOff() {
        if (loadSubtitle() == SubtitlePreference.Off) return

        preferences.edit {
            putString(SUBTITLE_MODE, SUBTITLE_MODE_OFF)
            remove(SUBTITLE_LANGUAGE)
            remove(SUBTITLE_LABEL)
            remove(SUBTITLE_VARIANT)
        }
    }

    private fun saveSpeed(speed: Float) {
        if (!speed.isFinite() || speed <= 0F) return
        if (loadSpeed() == speed) return
        preferences.edit { putFloat(PLAYBACK_SPEED, speed) }
    }

    private fun findCurrentOverride(
        overrides: Collection<TrackSelectionOverride>,
        groups: List<Tracks.Group>,
    ): TrackMatch? {
        overrides.forEach { override ->
            val group = groups.firstOrNull { it.mediaTrackGroup == override.mediaTrackGroup }
                ?: return@forEach
            val index = override.trackIndices.firstOrNull() ?: return@forEach
            if (index in 0 until group.length) return TrackMatch(group, index)
        }
        return null
    }

    private fun findBestAudioTrack(
        groups: List<Tracks.Group>,
        preference: TrackPreference,
    ): TrackMatch? {
        var best: TrackMatch? = null
        var bestScore = 0

        forEachSupportedTrack(groups) { match, format ->
            val score = audioScore(preference, format)
            if (score > bestScore) {
                best = match
                bestScore = score
            }
        }
        return best
    }

    private fun audioScore(preference: TrackPreference, format: Format): Int {
        if (exactTextMatch(preference.label, meaningfulLabel(format.label))) return 400

        val preferredLanguage = preference.language
        val candidateLanguage = canonicalLanguage(format.language)
        if (preferredLanguage != null && candidateLanguage == preferredLanguage) return 300

        if (sameLeadingLanguageLabel(preference.label, format.label)) return 200

        return 0
    }

    private fun findBestSubtitleTrack(
        groups: List<Tracks.Group>,
        preference: TrackPreference,
    ): TrackMatch? {
        var best: TrackMatch? = null
        var bestScore = 0

        forEachSupportedTrack(groups) { match, format ->
            val score = subtitleScore(preference, format)
            if (score > bestScore) {
                best = match
                bestScore = score
            }
        }
        return best
    }

    private fun subtitleScore(preference: TrackPreference, format: Format): Int {
        val candidateVariant = subtitleVariant(format)
        val exactLabel = exactTextMatch(preference.label, meaningfulLabel(format.label))
        val preferredLanguage = preference.language
        val candidateLanguage = canonicalLanguage(format.language)
        val formalLanguageMatch = preferredLanguage != null && candidateLanguage == preferredLanguage
        val labelLanguageMatch = sameLeadingLanguageLabel(preference.label, format.label)

        if (!exactLabel && !formalLanguageMatch && !labelLanguageMatch) return 0

        var score = when {
            exactLabel -> 1000
            formalLanguageMatch -> 600
            else -> 500
        }

        score += when (preference.subtitleVariant) {
            SubtitleVariant.REGULAR -> when (candidateVariant) {
                SubtitleVariant.REGULAR -> 300
                SubtitleVariant.ACCESSIBILITY -> 200
                SubtitleVariant.FORCED -> 50
            }

            SubtitleVariant.ACCESSIBILITY -> when (candidateVariant) {
                SubtitleVariant.ACCESSIBILITY -> 300
                SubtitleVariant.REGULAR -> 200
                SubtitleVariant.FORCED -> 50
            }

            SubtitleVariant.FORCED -> when (candidateVariant) {
                SubtitleVariant.FORCED -> 300
                SubtitleVariant.REGULAR -> 200
                SubtitleVariant.ACCESSIBILITY -> 150
            }
        }

        return score
    }

    private inline fun forEachSupportedTrack(
        groups: List<Tracks.Group>,
        block: (TrackMatch, Format) -> Unit,
    ) {
        groups.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                block(TrackMatch(group, index), group.getTrackFormat(index))
            }
        }
    }

    private fun subtitleVariant(format: Format): SubtitleVariant {
        if (
            format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 ||
            isForcedLabel(format.label)
        ) {
            return SubtitleVariant.FORCED
        }
        return variantFromLabel(format.label)
    }

    private fun variantFromLabel(label: String?): SubtitleVariant {
        val value = label?.lowercase(Locale.ROOT).orEmpty()
        return if (
            value.contains("[cc]") ||
            value.contains("(cc)") ||
            value.contains("closed caption") ||
            value.contains("closed-caption") ||
            value.contains("sdh") ||
            value.contains("hearing impaired") ||
            value.contains("hearing-impaired")
        ) {
            SubtitleVariant.ACCESSIBILITY
        } else if (isForcedLabel(label)) {
            SubtitleVariant.FORCED
        } else {
            SubtitleVariant.REGULAR
        }
    }

    private fun isForcedLabel(label: String?): Boolean {
        val value = label?.lowercase(Locale.ROOT).orEmpty()
        return value.contains("forced") ||
            value.contains("forzado") ||
            value.contains("forzato") ||
            value.contains("forzati") ||
            value.contains("forcé") ||
            value.contains("forcee")
    }

    private fun sameLeadingLanguageLabel(left: String?, right: String?): Boolean {
        val leftBase = baseLanguageLabel(left) ?: return false
        val rightBase = baseLanguageLabel(right) ?: return false
        return leftBase.equals(rightBase, ignoreCase = true)
    }

    private fun baseLanguageLabel(label: String?): String? {
        val value = meaningfulLabel(label) ?: return null

        return value
            .substringBefore('[')
            .substringBefore('(')
            .replace(
                Regex("\\b(forced|forzado|forzato|forzati|forcé|forcee|sdh)\\b", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(Regex("\\bclosed[ -]?captions?\\b", RegexOption.IGNORE_CASE), "")
            .trim(' ', '-', '_', '.')
            .takeIf { it.isNotEmpty() }
    }

    private fun meaningfulLabel(label: String?): String? {
        val value = label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return value.takeUnless(::isGenericTrackLabel)
    }

    private fun isGenericTrackLabel(label: String): Boolean {
        val value = label.trim().lowercase(Locale.ROOT)
        return value.matches(Regex("(audio\\s*)?track\\s*\\d+")) ||
            value.matches(Regex("audio\\s*\\d+")) ||
            value == "unknown" ||
            value == "und"
    }

    private fun canonicalLanguage(value: String?): String? {
        val raw = value?.trim()?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: return null
        val locale = Locale.forLanguageTag(raw)
        if (locale.language.isBlank()) return raw.lowercase(Locale.ROOT)
        return runCatching { locale.isO3Language }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: locale.language.lowercase(Locale.ROOT)
    }

    private fun exactTextMatch(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return left.trim().equals(right.trim(), ignoreCase = true)
    }

    private fun isSubtitleOff(ignoredTextSelectionFlags: Int): Boolean {
        return ignoredTextSelectionFlags == C.SELECTION_FLAG_FORCED.inv()
    }

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value.isNullOrBlank()) remove(key) else putString(key, value)
    }

    private const val AUDIO_LANGUAGE = "audio_language"
    private const val AUDIO_LABEL = "audio_label"

    private const val SUBTITLE_MODE = "subtitle_mode"
    private const val SUBTITLE_MODE_OFF = "off"
    private const val SUBTITLE_MODE_TRACK = "track"
    private const val SUBTITLE_LANGUAGE = "subtitle_language"
    private const val SUBTITLE_LABEL = "subtitle_label"
    private const val SUBTITLE_VARIANT = "subtitle_variant"

    private const val PLAYBACK_SPEED = "playback_speed"
}
