package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import java.util.Locale

/**
 * Persists explicit playback track choices without relying on whichever track
 * an extractor happens to mark as default.
 *
 * Audio choices are scoped to provider + movie/show so a language choice can
 * carry across episodes without leaking into unrelated titles. Subtitle choice
 * is app-wide so selecting English (or subtitles off) carries to other shows,
 * movies, episodes, and providers whenever a compatible track exists.
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

    @Volatile
    private var currentAudioScopeKey: String? = null

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

    fun activate(videoType: Video.Type) {
        val provider = UserPreferences.currentProvider?.name
        val content = when (videoType) {
            is Video.Type.Movie -> "movie:${videoType.id}"
            is Video.Type.Episode -> "tv:${videoType.tvShow.id}"
        }

        currentAudioScopeKey = provider?.let { "provider:$it::$content" }
        currentAudioPreference = currentAudioScopeKey?.let(::loadAudio)
        currentSubtitlePreference = loadSubtitle()
    }

    /**
     * Restores preferences once when each new MediaItem exposes its tracks.
     * Subsequent track changes are treated as user interaction. Preference
     * writes are deduplicated, so routine Media3 track refreshes do not keep
     * rewriting the same SharedPreferences values.
     */
    fun bind(player: Player): Player.Listener {
        val listener = object : Player.Listener {
            private var mediaItem: MediaItem? = null
            private var audioInitialized = false
            private var subtitleInitialized = false

            override fun onTracksChanged(tracks: Tracks) {
                val currentMediaItem = player.currentMediaItem
                if (currentMediaItem !== mediaItem) {
                    mediaItem = currentMediaItem
                    audioInitialized = false
                    subtitleInitialized = false
                }

                val groups = tracks.groups
                val parameters = player.trackSelectionParameters
                val builder = parameters.buildUpon()
                var changed = false

                val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (!audioInitialized && audioGroups.isNotEmpty()) {
                    val currentOverride = findCurrentOverride(
                        overrides = parameters.overrides.values,
                        groups = audioGroups,
                    )

                    if (currentOverride != null) {
                        saveAudio(currentOverride.group.getTrackFormat(currentOverride.index))
                    } else {
                        currentAudioPreference?.let { preference ->
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
                    }
                    audioInitialized = true
                } else if (audioInitialized && audioGroups.isNotEmpty()) {
                    findCurrentOverride(parameters.overrides.values, audioGroups)?.let { match ->
                        saveAudio(match.group.getTrackFormat(match.index))
                    }
                }

                val subtitleGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (!subtitleInitialized && subtitleGroups.isNotEmpty()) {
                    val currentOverride = findCurrentOverride(
                        overrides = parameters.overrides.values,
                        groups = subtitleGroups,
                    )

                    if (currentOverride != null) {
                        saveSubtitle(currentOverride.group.getTrackFormat(currentOverride.index))
                    } else {
                        when (val preference = currentSubtitlePreference) {
                            SubtitlePreference.Off -> {
                                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                builder.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                                changed = true
                            }

                            is SubtitlePreference.Track -> {
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
                            }

                            null -> Unit
                        }
                    }
                    subtitleInitialized = true
                } else if (subtitleInitialized && subtitleGroups.isNotEmpty()) {
                    val currentOverride = findCurrentOverride(
                        overrides = parameters.overrides.values,
                        groups = subtitleGroups,
                    )

                    if (currentOverride != null) {
                        saveSubtitle(currentOverride.group.getTrackFormat(currentOverride.index))
                    } else if (isSubtitleOff(parameters.ignoredTextSelectionFlags)) {
                        saveSubtitleOff()
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

    private fun loadAudio(scope: String): TrackPreference? {
        val language = preferences.getString(key(scope, AUDIO_LANGUAGE), null)
        val label = preferences.getString(key(scope, AUDIO_LABEL), null)
        if (language.isNullOrBlank() && label.isNullOrBlank()) return null
        return TrackPreference(
            language = canonicalLanguage(language),
            label = label?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun loadSubtitle(): SubtitlePreference? {
        return when (preferences.getString(key(SUBTITLE_SCOPE, SUBTITLE_MODE), null)) {
            SUBTITLE_MODE_OFF -> SubtitlePreference.Off
            SUBTITLE_MODE_TRACK -> {
                val language = preferences.getString(key(SUBTITLE_SCOPE, SUBTITLE_LANGUAGE), null)
                val label = preferences.getString(key(SUBTITLE_SCOPE, SUBTITLE_LABEL), null)
                val variant = preferences.getString(key(SUBTITLE_SCOPE, SUBTITLE_VARIANT), null)
                    ?.let { runCatching { SubtitleVariant.valueOf(it) }.getOrNull() }
                    ?: SubtitleVariant.REGULAR

                if (language.isNullOrBlank() && label.isNullOrBlank()) null
                else SubtitlePreference.Track(
                    TrackPreference(
                        language = canonicalLanguage(language),
                        label = label?.trim()?.takeIf { it.isNotEmpty() },
                        subtitleVariant = variant,
                    )
                )
            }

            else -> {
                // Migrate the app's legacy global subtitle name lazily so existing
                // users keep their preference after updating.
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

    private fun saveAudio(format: Format) {
        val scope = currentAudioScopeKey ?: return
        val preference = TrackPreference(
            language = canonicalLanguage(format.language),
            label = format.label?.trim()?.takeIf { it.isNotEmpty() },
        )

        if (preference.language == null && preference.label == null) return
        if (preference == currentAudioPreference) return

        currentAudioPreference = preference
        preferences.edit {
            putOrRemove(key(scope, AUDIO_LANGUAGE), preference.language)
            putOrRemove(key(scope, AUDIO_LABEL), preference.label)
        }
    }

    private fun saveSubtitle(format: Format) {
        val preference = TrackPreference(
            language = canonicalLanguage(format.language),
            label = format.label?.trim()?.takeIf { it.isNotEmpty() },
            subtitleVariant = subtitleVariant(format),
        )

        if (preference.language == null && preference.label == null) return
        val saved = SubtitlePreference.Track(preference)
        if (saved == currentSubtitlePreference) return

        currentSubtitlePreference = saved
        preferences.edit {
            putString(key(SUBTITLE_SCOPE, SUBTITLE_MODE), SUBTITLE_MODE_TRACK)
            putOrRemove(key(SUBTITLE_SCOPE, SUBTITLE_LANGUAGE), preference.language)
            putOrRemove(key(SUBTITLE_SCOPE, SUBTITLE_LABEL), preference.label)
            putString(key(SUBTITLE_SCOPE, SUBTITLE_VARIANT), preference.subtitleVariant.name)
        }
    }

    private fun saveSubtitleOff() {
        if (currentSubtitlePreference == SubtitlePreference.Off) return

        currentSubtitlePreference = SubtitlePreference.Off
        preferences.edit {
            putString(key(SUBTITLE_SCOPE, SUBTITLE_MODE), SUBTITLE_MODE_OFF)
            remove(key(SUBTITLE_SCOPE, SUBTITLE_LANGUAGE))
            remove(key(SUBTITLE_SCOPE, SUBTITLE_LABEL))
            remove(key(SUBTITLE_SCOPE, SUBTITLE_VARIANT))
        }
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
        if (exactTextMatch(preference.label, format.label)) return 400

        val preferredLanguage = preference.language
        val candidateLanguage = canonicalLanguage(format.language)
        if (preferredLanguage != null && candidateLanguage == preferredLanguage) return 300

        // Some providers expose useful display labels ("Korean", "English")
        // without a BCP-47/ISO language code. Match those textually, but never
        // guess when all we have is a generic label such as "Track 1".
        if (
            !isGenericTrackLabel(preference.label) &&
            !isGenericTrackLabel(format.label) &&
            sameLeadingLanguageLabel(preference.label, format.label)
        ) {
            return 200
        }

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
        val exactLabel = exactTextMatch(preference.label, format.label)

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

    fun isForcedLabel(label: String?): Boolean {
        val value = label?.lowercase(Locale.ROOT).orEmpty()
        return value.contains("forced") ||
            value.contains("forzado") ||
            value.contains("forzato") ||
            value.contains("forzati") ||
            value.contains("forcé") ||
            value.contains("forcee")
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
            value.contains(" closed caption") ||
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

    private fun sameLeadingLanguageLabel(left: String?, right: String?): Boolean {
        val leftBase = baseLanguageLabel(left) ?: return false
        val rightBase = baseLanguageLabel(right) ?: return false
        return leftBase.equals(rightBase, ignoreCase = true)
    }

    private fun baseLanguageLabel(label: String?): String? {
        val value = label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isGenericTrackLabel(value)) return null

        return value
            .substringBefore('[')
            .substringBefore('(')
            .replace(Regex("\\b(forced|forzado|forzato|forzati|forcé|forcee|sdh)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bclosed[ -]?captions?\\b", RegexOption.IGNORE_CASE), "")
            .trim(' ', '-', '_', '.')
            .takeIf { it.isNotEmpty() }
    }

    private fun isGenericTrackLabel(label: String?): Boolean {
        val value = label?.trim()?.lowercase(Locale.ROOT) ?: return false
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

    private fun key(scope: String, field: String) = "$field::$scope"

    private const val AUDIO_LANGUAGE = "audio_language"
    private const val AUDIO_LABEL = "audio_label"

    private const val SUBTITLE_SCOPE = "global"
    private const val SUBTITLE_MODE = "subtitle_mode"
    private const val SUBTITLE_MODE_OFF = "off"
    private const val SUBTITLE_MODE_TRACK = "track"
    private const val SUBTITLE_LANGUAGE = "subtitle_language"
    private const val SUBTITLE_LABEL = "subtitle_label"
    private const val SUBTITLE_VARIANT = "subtitle_variant"
}
