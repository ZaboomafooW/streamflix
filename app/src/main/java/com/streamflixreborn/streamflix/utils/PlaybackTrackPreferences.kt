package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import java.util.Locale

/**
 * Persists explicit audio/subtitle choices for a movie or TV show instead of
 * relying on whichever track a source happens to mark as default.
 *
 * Preferences are scoped by provider and content. TV episode preferences use
 * the parent show ID so the user's choice carries across episodes of the same
 * show. Movies use the movie ID. Provider scoping prevents unrelated providers
 * that happen to reuse the same IDs from sharing track choices.
 */
object PlaybackTrackPreferences {

    data class TrackPreference(
        val language: String?,
        val label: String?,
        val name: String?,
        val forced: Boolean = false,
    )

    @Volatile
    private var currentScopeKey: String? = null

    private val preferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.playback_tracks",
            Context.MODE_PRIVATE,
        )
    }

    fun activate(videoType: Video.Type) {
        val provider = UserPreferences.currentProvider?.name ?: return
        val content = when (videoType) {
            is Video.Type.Movie -> "movie:${videoType.id}"
            is Video.Type.Episode -> "tv:${videoType.tvShow.id}"
        }
        currentScopeKey = "provider:$provider::$content"
    }

    /**
     * Attaches provider-agnostic track persistence to a Media3 player.
     *
     * Only explicit Media3 track overrides are saved, so extractor/provider
     * defaults are not mistaken for user preferences. When a new video exposes
     * tracks, a saved preference is restored if a compatible track exists.
     */
    fun bind(player: Player): Player.Listener {
        val listener = object : Player.Listener {
            private var applyingSavedPreference = false

            override fun onTracksChanged(tracks: Tracks) {
                if (currentScopeKey == null) return

                if (!applyingSavedPreference) {
                    saveExplicitOverrides(player, tracks)
                }

                applyingSavedPreference = applySavedPreferences(player, tracks)
            }
        }

        player.addListener(listener)
        if (!player.currentTracks.isEmpty) {
            listener.onTracksChanged(player.currentTracks)
        }
        return listener
    }

    fun preferredAudio(): TrackPreference? {
        val scope = currentScopeKey ?: return null
        val language = preferences.getString(key(scope, AUDIO_LANGUAGE), null)
        val label = preferences.getString(key(scope, AUDIO_LABEL), null)
        val name = preferences.getString(key(scope, AUDIO_NAME), null)
        if (language.isNullOrBlank() && label.isNullOrBlank() && name.isNullOrBlank()) return null
        return TrackPreference(language, label, name)
    }

    fun preferredSubtitle(): TrackPreference? {
        val scope = currentScopeKey ?: return null
        val language = preferences.getString(key(scope, SUBTITLE_LANGUAGE), null)
        val label = preferences.getString(key(scope, SUBTITLE_LABEL), null)
        val name = preferences.getString(key(scope, SUBTITLE_NAME), null)
        if (language.isNullOrBlank() && label.isNullOrBlank() && name.isNullOrBlank()) return null
        return TrackPreference(
            language = language,
            label = label,
            name = name,
            forced = preferences.getBoolean(key(scope, SUBTITLE_FORCED), false),
        )
    }

    private fun saveExplicitOverrides(player: Player, tracks: Tracks) {
        val overrides = player.trackSelectionParameters.overrides.values
        val currentGroups = tracks.groups

        overrides.forEach { override ->
            val trackGroup = currentGroups.firstOrNull {
                it.mediaTrackGroup == override.mediaTrackGroup
            } ?: return@forEach
            val trackIndex = override.trackIndices.firstOrNull() ?: return@forEach
            if (trackIndex !in 0 until trackGroup.length) return@forEach

            val format = trackGroup.getTrackFormat(trackIndex)
            when (trackGroup.type) {
                C.TRACK_TYPE_AUDIO -> saveAudio(format)
                C.TRACK_TYPE_TEXT -> saveSubtitle(format)
            }
        }
    }

    private fun saveAudio(format: Format) {
        val scope = currentScopeKey ?: return
        preferences.edit {
            putOrRemove(key(scope, AUDIO_LANGUAGE), format.language)
            putOrRemove(key(scope, AUDIO_LABEL), format.label)
            putOrRemove(key(scope, AUDIO_NAME), format.label ?: format.language)
        }
    }

    private fun saveSubtitle(format: Format) {
        val scope = currentScopeKey ?: return
        preferences.edit {
            putOrRemove(key(scope, SUBTITLE_LANGUAGE), format.language)
            putOrRemove(key(scope, SUBTITLE_LABEL), format.label)
            putOrRemove(key(scope, SUBTITLE_NAME), format.label ?: format.language)
            putBoolean(key(scope, SUBTITLE_FORCED), isForced(format))
        }
    }

    private fun applySavedPreferences(player: Player, tracks: Tracks): Boolean {
        val parameters = player.trackSelectionParameters
        val groups = tracks.groups
        var changed = false
        val builder = parameters.buildUpon()

        if (!hasCurrentOverrideForType(parameters.overrides.values, groups, C.TRACK_TYPE_AUDIO)) {
            preferredAudio()?.let { preference ->
                findBestTrack(groups, C.TRACK_TYPE_AUDIO) { format ->
                    matchesAudio(
                        preference = preference,
                        language = format.language,
                        label = format.label,
                        name = format.label ?: format.language,
                    )
                }?.let { match ->
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
        }

        if (!hasCurrentOverrideForType(parameters.overrides.values, groups, C.TRACK_TYPE_TEXT)) {
            preferredSubtitle()?.let { preference ->
                findBestTrack(groups, C.TRACK_TYPE_TEXT) { format ->
                    matchesSubtitle(
                        preference = preference,
                        language = format.language,
                        label = format.label,
                        name = format.label ?: format.language,
                        forced = isForced(format),
                    )
                }?.let { match ->
                    builder.setOverrideForType(
                        TrackSelectionOverride(
                            match.group.mediaTrackGroup,
                            listOf(match.index),
                        )
                    )
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    changed = true
                }
            }
        }

        if (changed) {
            player.trackSelectionParameters = builder.build()
        }
        return changed
    }

    private fun hasCurrentOverrideForType(
        overrides: Collection<TrackSelectionOverride>,
        groups: List<Tracks.Group>,
        trackType: Int,
    ): Boolean {
        return overrides.any { override ->
            groups.any { group ->
                group.type == trackType && group.mediaTrackGroup == override.mediaTrackGroup
            }
        }
    }

    private data class TrackMatch(
        val group: Tracks.Group,
        val index: Int,
    )

    private fun findBestTrack(
        groups: List<Tracks.Group>,
        trackType: Int,
        predicate: (Format) -> Boolean,
    ): TrackMatch? {
        groups.filter { it.type == trackType }.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                if (predicate(group.getTrackFormat(index))) {
                    return TrackMatch(group, index)
                }
            }
        }
        return null
    }

    fun matchesAudio(
        preference: TrackPreference,
        language: String?,
        label: String?,
        name: String?,
    ): Boolean {
        if (exactTextMatch(preference.label, label) || exactTextMatch(preference.name, name)) {
            return true
        }

        val preferredLanguage = canonicalLanguage(preference.language)
        val candidateLanguage = canonicalLanguage(language)
        return preferredLanguage != null && candidateLanguage != null &&
            preferredLanguage == candidateLanguage
    }

    fun matchesSubtitle(
        preference: TrackPreference,
        language: String?,
        label: String?,
        name: String?,
        forced: Boolean,
    ): Boolean {
        if (preference.forced != forced) return false

        if (exactTextMatch(preference.label, label) || exactTextMatch(preference.name, name)) {
            return true
        }

        val preferredLanguage = canonicalLanguage(preference.language)
        val candidateLanguage = canonicalLanguage(language)
        return preferredLanguage != null && candidateLanguage != null &&
            preferredLanguage == candidateLanguage
    }

    fun isForcedLabel(label: String?): Boolean {
        val value = label?.lowercase(Locale.ROOT).orEmpty()
        return value.contains("forced") ||
            value.contains("forzado") ||
            value.contains("forzato") ||
            value.contains("forzati") ||
            value.contains("forcé") ||
            value.contains("forcee")
    }

    private fun isForced(format: Format): Boolean {
        return format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 ||
            isForcedLabel(format.label)
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

    private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value.isNullOrBlank()) remove(key) else putString(key, value)
    }

    private fun key(scope: String, field: String) = "$field::$scope"

    private const val AUDIO_LANGUAGE = "audio_language"
    private const val AUDIO_LABEL = "audio_label"
    private const val AUDIO_NAME = "audio_name"
    private const val SUBTITLE_LANGUAGE = "subtitle_language"
    private const val SUBTITLE_LABEL = "subtitle_label"
    private const val SUBTITLE_NAME = "subtitle_name"
    private const val SUBTITLE_FORCED = "subtitle_forced"
}
