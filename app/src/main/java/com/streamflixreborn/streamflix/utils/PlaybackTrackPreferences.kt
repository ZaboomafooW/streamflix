package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.preference.PreferenceManager
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import org.json.JSONObject

/**
 * Remembers explicit Audio/Subtitle choices for exactly one playback context:
 * provider + movie/TV show + playback-source name.
 *
 * Global language preferences are delegated to Media3's built-in track selection.
 * Exact per-title choices are restored above those preferences when available.
 * Track position is used only to disambiguate otherwise-identical tracks.
 */
object PlaybackTrackPreferences {

    private data class ContentScope(
        val provider: String,
        val content: String,
    )

    private data class SavedTrack(
        val label: String?,
        val language: String?,
        val roleFlags: Int,
        val forced: Boolean,
        val groupIndex: Int,
        val trackIndex: Int,
    ) {
        val hasRawIdentity: Boolean
            get() = label != null || language != null || roleFlags != 0 || forced

        fun matches(format: Format): Boolean =
            label == format.label &&
                language == format.language &&
                roleFlags == format.roleFlags &&
                forced == (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0)

        fun encode(): String = JSONObject().apply {
            putNullable(LABEL, label)
            putNullable(LANGUAGE, language)
            put(ROLE_FLAGS, roleFlags)
            put(FORCED, forced)
            put(GROUP_INDEX, groupIndex)
            put(TRACK_INDEX, trackIndex)
        }.toString()

        companion object {
            fun decode(value: String): SavedTrack? = runCatching {
                val json = JSONObject(value)
                SavedTrack(
                    label = json.nullableString(LABEL),
                    language = json.nullableString(LANGUAGE),
                    roleFlags = json.getInt(ROLE_FLAGS),
                    forced = json.getBoolean(FORCED),
                    groupIndex = json.getInt(GROUP_INDEX),
                    trackIndex = json.getInt(TRACK_INDEX),
                )
            }.getOrNull()
        }
    }

    private sealed interface SavedSubtitle {
        data object Off : SavedSubtitle
        data class Track(val value: SavedTrack) : SavedSubtitle
    }

    private data class TrackRef(
        val group: Tracks.Group,
        val groupIndex: Int,
        val trackIndex: Int,
    ) {
        val position: Pair<Int, Int>
            get() = groupIndex to trackIndex

        fun saved(): SavedTrack {
            val format = group.getTrackFormat(trackIndex)
            return SavedTrack(
                label = format.label,
                language = format.language,
                roleFlags = format.roleFlags,
                forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                groupIndex = groupIndex,
                trackIndex = trackIndex,
            )
        }
    }

    @Volatile
    private var contentScope: ContentScope? = null

    @Volatile
    private var scopeKey: String? = null

    @Volatile
    private var savedAudio: SavedTrack? = null

    @Volatile
    private var savedSubtitle: SavedSubtitle? = null

    private val prefs by lazy {
        StreamFlixApp.instance.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.playback_tracks",
            Context.MODE_PRIVATE,
        )
    }

    private val settingsPrefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(StreamFlixApp.instance)
    }

    fun activate(videoType: Video.Type) {
        val provider = UserPreferences.currentProvider?.name
        val content = when (videoType) {
            is Video.Type.Movie -> "movie:${videoType.id}"
            is Video.Type.Episode -> "tv:${videoType.tvShow.id}"
        }

        contentScope = provider?.let { ContentScope(it, content) }
        scopeKey = null
        savedAudio = null
        savedSubtitle = null
    }

    /** The stable server/source name is intentionally part of the preference key. */
    fun activateSource(sourceName: String) {
        scopeKey = contentScope?.let { scope ->
            keyOf(scope.provider, scope.content, sourceName)
        }
        savedAudio = scopeKey?.let(::loadAudio)
        savedSubtitle = scopeKey?.let(::loadSubtitle)
    }

    fun bind(player: Player): Player.Listener {
        applyPreferredLanguages(player)

        val listener = object : Player.Listener {
            private var mediaItem: MediaItem? = null
            private var activeScope: String? = null
            private var audioRestored = false
            private var subtitleRestored = false
            private var audioCancelled = false
            private var subtitleCancelled = false
            private var expectedParameters: TrackSelectionParameters? = null
            private var lastAudioPosition: Pair<Int, Int>? = null
            private var lastSubtitlePosition: Pair<Int, Int>? = null
            private var lastSubtitleOff = false

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                resetForContext(mediaItem, player.currentTracks)
            }

            override fun onTracksChanged(tracks: Tracks) {
                resetForContext(player.currentMediaItem, tracks)
                restore(tracks)
                captureState(player.trackSelectionParameters, tracks)
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                val tracks = player.currentTracks

                if (expectedParameters == parameters) {
                    expectedParameters = null
                    captureState(parameters, tracks)
                    return
                }

                val audio = currentOverride(parameters, tracks, C.TRACK_TYPE_AUDIO)
                if (audio?.position != lastAudioPosition && audio != null) {
                    saveAudio(audio.saved())
                    audioCancelled = true
                }

                val subtitle = currentOverride(parameters, tracks, C.TRACK_TYPE_TEXT)
                val subtitleOff = subtitle == null && isSubtitleOff(parameters)

                when {
                    subtitle?.position != lastSubtitlePosition && subtitle != null -> {
                        saveSubtitle(subtitle.saved())
                        subtitleCancelled = true
                    }

                    subtitleOff && !lastSubtitleOff -> {
                        saveSubtitleOff()
                        subtitleCancelled = true
                    }
                }

                captureState(parameters, tracks)
            }

            private fun resetForContext(newItem: MediaItem?, tracks: Tracks) {
                val newScope = scopeKey ?: return
                val scopeChanged = activeScope != null && activeScope != newScope
                val itemChanged = newItem !== mediaItem
                if (!scopeChanged && !itemChanged) return

                val externalSubtitle = hasLocalDefaultSubtitle(newItem)
                var builder: TrackSelectionParameters.Builder? = null

                if (scopeChanged) {
                    builder = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
                } else if (externalSubtitle) {
                    // Downloaded/local subtitles are playback-only choices. Clear
                    // any embedded override and do not restore over the external
                    // default for this MediaItem.
                    builder = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
                }

                mediaItem = newItem
                activeScope = newScope
                audioRestored = savedAudio == null
                subtitleRestored = savedSubtitle == null
                audioCancelled = false
                subtitleCancelled = externalSubtitle

                builder?.build()?.let(::applyParameters)
                captureState(player.trackSelectionParameters, tracks)
            }

            private fun restore(tracks: Tracks) {
                var builder: TrackSelectionParameters.Builder? = null

                if (!audioRestored && !audioCancelled) {
                    savedAudio?.let { saved ->
                        exactTrack(tracks, C.TRACK_TYPE_AUDIO, saved)?.let { track ->
                            builder = (builder ?: player.trackSelectionParameters.buildUpon())
                                .setOverrideForType(track.override())
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            audioRestored = true
                        }
                    }
                }

                if (!subtitleRestored && !subtitleCancelled) {
                    when (val saved = savedSubtitle) {
                        null -> subtitleRestored = true

                        SavedSubtitle.Off -> {
                            builder = (builder ?: player.trackSelectionParameters.buildUpon())
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setIgnoredTextSelectionFlags(SUBTITLE_OFF_FLAGS)
                            subtitleRestored = true
                        }

                        is SavedSubtitle.Track -> {
                            exactTrack(tracks, C.TRACK_TYPE_TEXT, saved.value)?.let { track ->
                                builder = (builder ?: player.trackSelectionParameters.buildUpon())
                                    .setOverrideForType(track.override())
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
                                subtitleRestored = true
                            }
                        }
                    }
                }

                builder?.build()?.let(::applyParameters)
            }

            private fun applyParameters(parameters: TrackSelectionParameters) {
                if (parameters == player.trackSelectionParameters) return
                expectedParameters = parameters
                player.trackSelectionParameters = parameters
            }

            private fun captureState(parameters: TrackSelectionParameters, tracks: Tracks) {
                lastAudioPosition = currentOverride(
                    parameters,
                    tracks,
                    C.TRACK_TYPE_AUDIO,
                )?.position

                val subtitle = currentOverride(
                    parameters,
                    tracks,
                    C.TRACK_TYPE_TEXT,
                )
                lastSubtitlePosition = subtitle?.position
                lastSubtitleOff = subtitle == null && isSubtitleOff(parameters)
            }
        }

        player.addListener(listener)
        if (!player.currentTracks.isEmpty()) {
            listener.onTracksChanged(player.currentTracks)
        }
        return listener
    }

    private fun applyPreferredLanguages(player: Player) {
        val preferredAudioLanguage = settingsPrefs
            .getString(PREFERRED_AUDIO_LANGUAGE, null)
            ?.takeIf { it.isNotBlank() }
        val preferredSubtitleLanguage = settingsPrefs
            .getString(PREFERRED_SUBTITLE_LANGUAGE, null)
            ?.takeIf { it.isNotBlank() }

        if (preferredAudioLanguage == null && preferredSubtitleLanguage == null) return

        val builder = player.trackSelectionParameters.buildUpon()
        preferredAudioLanguage?.let(builder::setPreferredAudioLanguage)
        preferredSubtitleLanguage?.let(builder::setPreferredTextLanguage)

        val parameters = builder.build()
        if (parameters != player.trackSelectionParameters) {
            player.trackSelectionParameters = parameters
        }
    }

    private fun TrackRef.override() = TrackSelectionOverride(
        group.mediaTrackGroup,
        listOf(trackIndex),
    )

    private fun currentOverride(
        parameters: TrackSelectionParameters,
        tracks: Tracks,
        type: Int,
    ): TrackRef? {
        tracks.groups.filter { it.type == type }.forEachIndexed { groupIndex, group ->
            val override = parameters.overrides[group.mediaTrackGroup] ?: return@forEachIndexed
            val trackIndex = override.trackIndices.firstOrNull() ?: return@forEachIndexed
            if (trackIndex in 0 until group.length) {
                return TrackRef(group, groupIndex, trackIndex)
            }
        }
        return null
    }

    /**
     * Raw metadata must match exactly. Position is consulted only if more than
     * one track has the same raw metadata, or if the track exposes no raw
     * identity at all. That prevents a generic anonymous track from being
     * guessed merely because it is the only candidate in a later episode.
     */
    private fun exactTrack(tracks: Tracks, type: Int, saved: SavedTrack): TrackRef? {
        val matches = buildList {
            tracks.groups.filter { it.type == type }.forEachIndexed { groupIndex, group ->
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    if (saved.matches(group.getTrackFormat(trackIndex))) {
                        add(TrackRef(group, groupIndex, trackIndex))
                    }
                }
            }
        }

        if (saved.hasRawIdentity && matches.size == 1) return matches.single()
        return matches.firstOrNull {
            it.groupIndex == saved.groupIndex && it.trackIndex == saved.trackIndex
        }
    }

    private fun loadAudio(scope: String): SavedTrack? =
        prefs.getString(audioKey(scope), null)?.let(SavedTrack::decode)

    private fun loadSubtitle(scope: String): SavedSubtitle? =
        when (prefs.getString(subtitleModeKey(scope), null)) {
            MODE_OFF -> SavedSubtitle.Off
            MODE_TRACK -> prefs.getString(subtitleTrackKey(scope), null)
                ?.let(SavedTrack::decode)
                ?.let(SavedSubtitle::Track)
            else -> null
        }

    private fun saveAudio(value: SavedTrack) {
        val scope = scopeKey ?: return
        savedAudio = value
        prefs.edit().putString(audioKey(scope), value.encode()).apply()
    }

    private fun saveSubtitle(value: SavedTrack) {
        val scope = scopeKey ?: return
        savedSubtitle = SavedSubtitle.Track(value)
        prefs.edit()
            .putString(subtitleModeKey(scope), MODE_TRACK)
            .putString(subtitleTrackKey(scope), value.encode())
            .apply()
    }

    private fun saveSubtitleOff() {
        val scope = scopeKey ?: return
        savedSubtitle = SavedSubtitle.Off
        prefs.edit()
            .putString(subtitleModeKey(scope), MODE_OFF)
            .remove(subtitleTrackKey(scope))
            .apply()
    }

    private fun isSubtitleOff(parameters: TrackSelectionParameters) =
        parameters.ignoredTextSelectionFlags == SUBTITLE_OFF_FLAGS

    private fun hasLocalDefaultSubtitle(mediaItem: MediaItem?) =
        mediaItem?.localConfiguration?.subtitleConfigurations?.any { subtitle ->
            val scheme = subtitle.uri.scheme?.lowercase()
            (scheme == "file" || scheme == "content") &&
                subtitle.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
        } == true

    private fun keyOf(provider: String, content: String, source: String) =
        buildString {
            appendPart(provider)
            appendPart(content)
            appendPart(source)
        }

    private fun StringBuilder.appendPart(value: String) {
        append(value.length).append(':').append(value).append('|')
    }

    private fun audioKey(scope: String) = "audio::$scope"
    private fun subtitleModeKey(scope: String) = "subtitle_mode::$scope"
    private fun subtitleTrackKey(scope: String) = "subtitle_track::$scope"

    private fun JSONObject.putNullable(name: String, value: String?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private const val MODE_OFF = "off"
    private const val MODE_TRACK = "track"
    private const val DEFAULT_TEXT_FLAGS = 0
    private const val SUBTITLE_OFF_FLAGS = C.SELECTION_FLAG_FORCED.inv()

    private const val PREFERRED_AUDIO_LANGUAGE = "PREFERRED_AUDIO_LANGUAGE"
    private const val PREFERRED_SUBTITLE_LANGUAGE = "PREFERRED_SUBTITLE_LANGUAGE"

    private const val LABEL = "label"
    private const val LANGUAGE = "language"
    private const val ROLE_FLAGS = "roleFlags"
    private const val FORCED = "forced"
    private const val GROUP_INDEX = "groupIndex"
    private const val TRACK_INDEX = "trackIndex"
}
