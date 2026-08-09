package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import org.json.JSONObject

/**
 * Persists only explicit Audio/Subtitle choices made by the user.
 *
 * Preferences are scoped to provider + movie/TV show + playback source name.
 * Episodes therefore share a preference through their TV-show id, while a
 * different source or title is always independent.
 *
 * Restoration is deliberately exact. Track labels/languages are never
 * translated, normalized, or semantically inferred. If the saved track cannot
 * be identified exactly on the same source, the player is left alone.
 */
object PlaybackTrackPreferences {

    private data class ContentScope(
        val providerName: String,
        val contentId: String,
    )

    private data class TrackPreference(
        val label: String?,
        val language: String?,
        val roleFlags: Int,
        val sampleMimeType: String?,
        val forced: Boolean,
        val channelCount: Int,
        val sampleRate: Int,
        val groupIndex: Int,
        val trackIndex: Int,
    ) {
        fun matches(format: Format): Boolean {
            return label == format.label &&
                language == format.language &&
                roleFlags == format.roleFlags &&
                sampleMimeType == format.sampleMimeType &&
                forced == (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) &&
                channelCount == format.channelCount &&
                sampleRate == format.sampleRate
        }

        fun toJson(): String = JSONObject().apply {
            putNullableString(JSON_LABEL, label)
            putNullableString(JSON_LANGUAGE, language)
            put(JSON_ROLE_FLAGS, roleFlags)
            putNullableString(JSON_SAMPLE_MIME_TYPE, sampleMimeType)
            put(JSON_FORCED, forced)
            put(JSON_CHANNEL_COUNT, channelCount)
            put(JSON_SAMPLE_RATE, sampleRate)
            put(JSON_GROUP_INDEX, groupIndex)
            put(JSON_TRACK_INDEX, trackIndex)
        }.toString()

        companion object {
            fun fromJson(value: String): TrackPreference? = runCatching {
                val json = JSONObject(value)
                TrackPreference(
                    label = json.nullableString(JSON_LABEL),
                    language = json.nullableString(JSON_LANGUAGE),
                    roleFlags = json.getInt(JSON_ROLE_FLAGS),
                    sampleMimeType = json.nullableString(JSON_SAMPLE_MIME_TYPE),
                    forced = json.getBoolean(JSON_FORCED),
                    channelCount = json.getInt(JSON_CHANNEL_COUNT),
                    sampleRate = json.getInt(JSON_SAMPLE_RATE),
                    groupIndex = json.getInt(JSON_GROUP_INDEX),
                    trackIndex = json.getInt(JSON_TRACK_INDEX),
                )
            }.getOrNull()
        }
    }

    private sealed interface SubtitlePreference {
        data object Off : SubtitlePreference
        data class Track(val value: TrackPreference) : SubtitlePreference
    }

    private data class TrackMatch(
        val group: Tracks.Group,
        val groupIndex: Int,
        val trackIndex: Int,
    )

    private data class OverrideSignature(
        val groupIndex: Int,
        val trackIndices: List<Int>,
    )

    private data class SubtitleParameterState(
        val override: OverrideSignature?,
        val off: Boolean,
    )

    @Volatile
    private var currentContentScope: ContentScope? = null

    @Volatile
    private var currentScopeKey: String? = null

    @Volatile
    private var currentAudioPreference: TrackPreference? = null

    @Volatile
    private var currentSubtitlePreference: SubtitlePreference? = null

    private val preferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.playback_tracks",
            Context.MODE_PRIVATE,
        )
    }

    /** Establishes the movie/TV-show portion of the scope. */
    fun activate(videoType: Video.Type) {
        val providerName = UserPreferences.currentProvider?.name
        val contentId = when (videoType) {
            is Video.Type.Movie -> "movie:${videoType.id}"
            is Video.Type.Episode -> "tv:${videoType.tvShow.id}"
        }

        currentContentScope = providerName?.let { ContentScope(it, contentId) }
        currentScopeKey = null
        currentAudioPreference = null
        currentSubtitlePreference = null

        // The old global subtitle-name mechanism is intentionally not migrated.
        // A global value cannot be assigned to a particular title/source without
        // guessing, which is precisely what this persistence model avoids.
        UserPreferences.subtitleName = null
        clearObsoleteGlobalSubtitlePreference()
    }

    /** Adds the stable playback-source identity to the active scope. */
    fun activateSource(sourceName: String) {
        val contentScope = currentContentScope
        currentScopeKey = contentScope?.let {
            buildScopeKey(
                providerName = it.providerName,
                contentId = it.contentId,
                sourceName = sourceName,
            )
        }
        currentAudioPreference = currentScopeKey?.let(::loadAudio)
        currentSubtitlePreference = currentScopeKey?.let(::loadSubtitle)

        // Ignore any legacy write made elsewhere in the old subtitle path.
        UserPreferences.subtitleName = null
    }

    /**
     * Binds restoration to a player. Parameter changes are observed only so an
     * explicit settings override can be persisted. Automatic track selection
     * itself is never learned.
     */
    fun bind(player: Player): Player.Listener {
        val listener = object : Player.Listener {
            private var mediaItem: MediaItem? = null
            private var scopeKey: String? = null
            private var audioRestoreComplete = false
            private var subtitleRestoreComplete = false
            private var audioRestoreCancelled = false
            private var subtitleRestoreCancelled = false
            private var expectedParameters: TrackSelectionParameters? = null
            private var lastAudioOverride: OverrideSignature? = null
            private var lastSubtitleState = SubtitleParameterState(null, false)

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                prepareContext(mediaItem, player.currentTracks)
            }

            override fun onTracksChanged(tracks: Tracks) {
                prepareContext(player.currentMediaItem, tracks)
                restoreIfPossible(tracks)
                syncParameterState(player.trackSelectionParameters, tracks)
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                val tracks = player.currentTracks

                if (expectedParameters == parameters) {
                    expectedParameters = null
                    syncParameterState(parameters, tracks)
                    return
                }

                val audioOverride = findCurrentOverride(
                    parameters = parameters,
                    groups = tracks.groups,
                    trackType = C.TRACK_TYPE_AUDIO,
                )
                val audioSignature = audioOverride?.toOverrideSignature()
                if (audioSignature != lastAudioOverride) {
                    if (audioOverride != null) {
                        saveAudio(audioOverride.toPreference())
                        audioRestoreCancelled = true
                    }
                }

                val subtitleOverride = findCurrentOverride(
                    parameters = parameters,
                    groups = tracks.groups,
                    trackType = C.TRACK_TYPE_TEXT,
                )
                val subtitleState = SubtitleParameterState(
                    override = subtitleOverride?.toOverrideSignature(),
                    off = subtitleOverride == null && isSubtitleOff(parameters),
                )

                if (subtitleState != lastSubtitleState) {
                    when {
                        subtitleOverride != null -> {
                            saveSubtitle(subtitleOverride.toPreference())
                            subtitleRestoreCancelled = true
                        }

                        subtitleState.off -> {
                            saveSubtitleOff()
                            subtitleRestoreCancelled = true
                        }
                    }
                }

                syncParameterState(parameters, tracks)
            }

            private fun prepareContext(newMediaItem: MediaItem?, tracks: Tracks) {
                val newScopeKey = currentScopeKey ?: return
                val scopeChanged = newScopeKey != scopeKey
                val mediaItemChanged = newMediaItem !== mediaItem
                if (!scopeChanged && !mediaItemChanged) return

                val hadContext = scopeKey != null || mediaItem != null
                val externalSubtitleSelected = hasLocalDefaultSubtitle(newMediaItem)
                val builder = player.trackSelectionParameters.buildUpon()
                var changed = false

                if (hadContext && scopeChanged) {
                    builder
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setIgnoredTextSelectionFlags(DEFAULT_IGNORED_TEXT_SELECTION_FLAGS)
                    changed = true
                } else if (mediaItemChanged && externalSubtitleSelected) {
                    // Downloaded/local subtitles are current-playback choices only.
                    // Remove an embedded-text override so the external default can
                    // take effect, but never persist the external track itself.
                    builder
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setIgnoredTextSelectionFlags(DEFAULT_IGNORED_TEXT_SELECTION_FLAGS)
                    changed = true
                }

                mediaItem = newMediaItem
                scopeKey = newScopeKey
                audioRestoreCancelled = false
                subtitleRestoreCancelled = externalSubtitleSelected
                audioRestoreComplete = currentAudioPreference == null
                subtitleRestoreComplete = currentSubtitlePreference == null

                if (changed) {
                    applyParameters(builder.build())
                }
                syncParameterState(player.trackSelectionParameters, tracks)
            }

            private fun restoreIfPossible(tracks: Tracks) {
                var builder: TrackSelectionParameters.Builder? = null

                if (!audioRestoreComplete && !audioRestoreCancelled) {
                    val preference = currentAudioPreference
                    if (preference == null) {
                        audioRestoreComplete = true
                    } else {
                        findExactTrack(
                            groups = tracks.groups,
                            trackType = C.TRACK_TYPE_AUDIO,
                            preference = preference,
                        )?.let { match ->
                            builder = (builder ?: player.trackSelectionParameters.buildUpon())
                                .setOverrideForType(
                                    TrackSelectionOverride(
                                        match.group.mediaTrackGroup,
                                        listOf(match.trackIndex),
                                    )
                                )
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            audioRestoreComplete = true
                        }
                    }
                }

                if (!subtitleRestoreComplete && !subtitleRestoreCancelled) {
                    when (val preference = currentSubtitlePreference) {
                        null -> subtitleRestoreComplete = true

                        SubtitlePreference.Off -> {
                            builder = (builder ?: player.trackSelectionParameters.buildUpon())
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setIgnoredTextSelectionFlags(SUBTITLE_OFF_IGNORED_FLAGS)
                            subtitleRestoreComplete = true
                        }

                        is SubtitlePreference.Track -> {
                            findExactTrack(
                                groups = tracks.groups,
                                trackType = C.TRACK_TYPE_TEXT,
                                preference = preference.value,
                            )?.let { match ->
                                builder = (builder ?: player.trackSelectionParameters.buildUpon())
                                    .setOverrideForType(
                                        TrackSelectionOverride(
                                            match.group.mediaTrackGroup,
                                            listOf(match.trackIndex),
                                        )
                                    )
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setIgnoredTextSelectionFlags(DEFAULT_IGNORED_TEXT_SELECTION_FLAGS)
                                subtitleRestoreComplete = true
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

            private fun syncParameterState(
                parameters: TrackSelectionParameters,
                tracks: Tracks,
            ) {
                lastAudioOverride = findCurrentOverride(
                    parameters = parameters,
                    groups = tracks.groups,
                    trackType = C.TRACK_TYPE_AUDIO,
                )?.toOverrideSignature()

                val subtitleOverride = findCurrentOverride(
                    parameters = parameters,
                    groups = tracks.groups,
                    trackType = C.TRACK_TYPE_TEXT,
                )
                lastSubtitleState = SubtitleParameterState(
                    override = subtitleOverride?.toOverrideSignature(),
                    off = subtitleOverride == null && isSubtitleOff(parameters),
                )
            }
        }

        player.addListener(listener)
        if (!player.currentTracks.isEmpty()) {
            listener.onTracksChanged(player.currentTracks)
        }
        return listener
    }

    private fun loadAudio(scope: String): TrackPreference? {
        return preferences.getString(audioKey(scope), null)
            ?.let(TrackPreference::fromJson)
    }

    private fun loadSubtitle(scope: String): SubtitlePreference? {
        return when (preferences.getString(subtitleModeKey(scope), null)) {
            SUBTITLE_MODE_OFF -> SubtitlePreference.Off
            SUBTITLE_MODE_TRACK -> preferences.getString(subtitleTrackKey(scope), null)
                ?.let(TrackPreference::fromJson)
                ?.let(SubtitlePreference::Track)
            else -> null
        }
    }

    private fun saveAudio(preference: TrackPreference) {
        val scope = currentScopeKey ?: return
        if (preference == currentAudioPreference) return
        currentAudioPreference = preference
        preferences.edit()
            .putString(audioKey(scope), preference.toJson())
            .apply()
    }

    private fun saveSubtitle(preference: TrackPreference) {
        val scope = currentScopeKey ?: return
        val saved = SubtitlePreference.Track(preference)
        if (saved == currentSubtitlePreference) return
        currentSubtitlePreference = saved
        preferences.edit()
            .putString(subtitleModeKey(scope), SUBTITLE_MODE_TRACK)
            .putString(subtitleTrackKey(scope), preference.toJson())
            .apply()
    }

    private fun saveSubtitleOff() {
        val scope = currentScopeKey ?: return
        if (currentSubtitlePreference == SubtitlePreference.Off) return
        currentSubtitlePreference = SubtitlePreference.Off
        preferences.edit()
            .putString(subtitleModeKey(scope), SUBTITLE_MODE_OFF)
            .remove(subtitleTrackKey(scope))
            .apply()
    }

    private fun findCurrentOverride(
        parameters: TrackSelectionParameters,
        groups: List<Tracks.Group>,
        trackType: Int,
    ): TrackMatch? {
        val typeGroups = groups.filter { it.type == trackType }
        typeGroups.forEachIndexed { groupIndex, group ->
            val override = parameters.overrides[group.mediaTrackGroup] ?: return@forEachIndexed
            val trackIndex = override.trackIndices.firstOrNull() ?: return@forEachIndexed
            if (trackIndex in 0 until group.length) {
                return TrackMatch(group, groupIndex, trackIndex)
            }
        }
        return null
    }

    private fun findExactTrack(
        groups: List<Tracks.Group>,
        trackType: Int,
        preference: TrackPreference,
    ): TrackMatch? {
        val typeGroups = groups.filter { it.type == trackType }
        val matches = buildList {
            typeGroups.forEachIndexed { groupIndex, group ->
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    val format = group.getTrackFormat(trackIndex)
                    if (preference.matches(format)) {
                        add(TrackMatch(group, groupIndex, trackIndex))
                    }
                }
            }
        }

        if (matches.size == 1) return matches.single()
        return matches.firstOrNull {
            it.groupIndex == preference.groupIndex && it.trackIndex == preference.trackIndex
        }
    }

    private fun TrackMatch.toPreference(): TrackPreference {
        val format = group.getTrackFormat(trackIndex)
        return TrackPreference(
            label = format.label,
            language = format.language,
            roleFlags = format.roleFlags,
            sampleMimeType = format.sampleMimeType,
            forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            channelCount = format.channelCount,
            sampleRate = format.sampleRate,
            groupIndex = groupIndex,
            trackIndex = trackIndex,
        )
    }

    private fun TrackMatch.toOverrideSignature(): OverrideSignature {
        return OverrideSignature(
            groupIndex = groupIndex,
            trackIndices = listOf(trackIndex),
        )
    }

    private fun isSubtitleOff(parameters: TrackSelectionParameters): Boolean {
        return parameters.ignoredTextSelectionFlags == SUBTITLE_OFF_IGNORED_FLAGS
    }

    private fun hasLocalDefaultSubtitle(mediaItem: MediaItem?): Boolean {
        return mediaItem?.localConfiguration?.subtitleConfigurations?.any { subtitle ->
            val scheme = subtitle.uri.scheme?.lowercase()
            val local = scheme == "content" || scheme == "file"
            local && subtitle.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
        } == true
    }

    private fun buildScopeKey(
        providerName: String,
        contentId: String,
        sourceName: String,
    ): String {
        return buildString {
            appendComponent(providerName)
            appendComponent(contentId)
            appendComponent(sourceName)
        }
    }

    private fun StringBuilder.appendComponent(value: String) {
        append(value.length)
        append(':')
        append(value)
        append('|')
    }

    private fun audioKey(scope: String) = "audio::$scope"
    private fun subtitleModeKey(scope: String) = "subtitle_mode::$scope"
    private fun subtitleTrackKey(scope: String) = "subtitle_track::$scope"

    private fun clearObsoleteGlobalSubtitlePreference() {
        preferences.edit()
            .remove("subtitle_mode::global")
            .remove("subtitle_language::global")
            .remove("subtitle_label::global")
            .remove("subtitle_variant::global")
            .apply()
    }

    private fun JSONObject.putNullableString(name: String, value: String?) {
        if (value == null) put(name, JSONObject.NULL) else put(name, value)
    }

    private fun JSONObject.nullableString(name: String): String? {
        return if (!has(name) || isNull(name)) null else getString(name)
    }

    private const val SUBTITLE_MODE_OFF = "off"
    private const val SUBTITLE_MODE_TRACK = "track"

    private const val DEFAULT_IGNORED_TEXT_SELECTION_FLAGS = 0
    private const val SUBTITLE_OFF_IGNORED_FLAGS = C.SELECTION_FLAG_FORCED.inv()

    private const val JSON_LABEL = "label"
    private const val JSON_LANGUAGE = "language"
    private const val JSON_ROLE_FLAGS = "roleFlags"
    private const val JSON_SAMPLE_MIME_TYPE = "sampleMimeType"
    private const val JSON_FORCED = "forced"
    private const val JSON_CHANNEL_COUNT = "channelCount"
    private const val JSON_SAMPLE_RATE = "sampleRate"
    private const val JSON_GROUP_INDEX = "groupIndex"
    private const val JSON_TRACK_INDEX = "trackIndex"
}