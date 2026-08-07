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
     * A saved choice is restored once when a new media source exposes that
     * track type. Later track changes are treated as user interaction: explicit
     * overrides are saved and a cleared override is left cleared, so choosing
     * "Subtitles off" is not immediately undone by the persistence layer.
     */
    fun bind(player: Player): Player.Listener {
        val listener = object : Player.Listener {
            private var mediaKey: String? = null
            private var audioInitialized = false
            private var subtitleInitialized = false

            override fun onTracksChanged(tracks: Tracks) {
                if (currentScopeKey == null) return

                val currentMediaKey = player.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    ?.toString()
                    ?: player.currentMediaItem?.mediaId

                if (currentMediaKey != mediaKey) {
                    mediaKey = currentMediaKey
                    audioInitialized = false
                    subtitleInitialized = false
                }

                val groups = tracks.groups
                val parameters = player.trackSelectionParameters
                val builder = parameters.buildUpon()
                var changed = false

                val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audioGroups.isNotEmpty()) {
                    val currentAudio = findCurrentOverride(
                        overrides = parameters.overrides.values,
                        groups = audioGroups,
                    )

                    if (!audioInitialized) {
                        if (currentAudio != null) {
                            saveAudio(currentAudio.group.getTrackFormat(currentAudio.index))
                        } else {
                            preferredAudio()?.let { preference ->
                                findBestTrack(audioGroups) { format ->
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
                        audioInitialized = true
                    } else if (currentAudio != null) {
                        saveAudio(currentAudio.group.getTrackFormat(currentAudio.index))
                    }
                }

                val subtitleGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (subtitleGroups.isNotEmpty()) {
                    val currentSubtitle = findCurrentOverride(
                        overrides = parameters.overrides.values,
                        groups = subtitleGroups,
                    )

                    if (!subtitleInitialized) {
                        if (currentSubtitle != null) {
                            saveSubtitle(currentSubtitle.group.getTrackFormat(currentSubtitle.index))
                        } else {
                            preferredSubtitle()?.let { preference ->
                                findBestTrack(subtitleGroups) { format ->
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
                        subtitleInitialized = true
                    } else if (currentSubtitle != null) {
                        saveSubtitle(currentSubtitle.group.getTrackFormat(currentSubtitle.index))
                    }
                }

                if (changed) {
                    player.trackSelectionParameters = builder.build()
                }
            }
        }

        player.addListener(listener)
        if (!player.currentTracks.isEmpty()) {
            listener.onTracksChanged(player.currentTracks)
        }
        return listener
    }

    private fun preferredAudio(): TrackPreference? {
        val scope = currentScopeKey ?: return null
        val language = preferences.getString(key(scope, AUDIO_LANGUAGE), null)
        val label = preferences.getString(key(scope, AUDIO_LABEL), null)
        val name = preferences.getString(key(scope, AUDIO_NAME), null)
        if (language.isNullOrBlank() && label.isNullOrBlank() && name.isNullOrBlank()) return null
        return TrackPreference(language, label, name)
    }

    private fun preferredSubtitle(): TrackPreference? {
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

    private data class TrackMatch(
        val group: Tracks.Group,
        val index: Int,
    )

    private fun findCurrentOverride(
        overrides: Collection<TrackSelectionOverride>,
        groups: List<Tracks.Group>,
    ): TrackMatch? {
        overrides.forEach { override ->
            val group = groups.firstOrNull {
                it.mediaTrackGroup == override.mediaTrackGroup
            } ?: return@forEach
            val index = override.trackIndices.firstOrNull() ?: return@forEach
            if (index in 0 until group.length) {
                return TrackMatch(group, index)
            }
        }
        return null
    }

    private fun findBestTrack(
        groups: List<Tracks.Group>,
        predicate: (Format) -> Boolean,
    ): TrackMatch? {
        groups.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                if (predicate(group.getTrackFormat(index))) {
                    return TrackMatch(group, index)
                }
            }
        }
        return null
    }

    private fun matchesAudio(
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

    private fun matchesSubtitle(
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
