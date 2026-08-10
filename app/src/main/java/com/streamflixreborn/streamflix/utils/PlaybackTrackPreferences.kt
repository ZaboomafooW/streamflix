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
import java.util.Locale

/**
 * Persists playback intent that Media3 cannot carry across Player recreation.
 *
 * Media3 remains responsible for normal language matching and ranking. StreamFlix only adds:
 * - a per-title Audio language chosen explicitly by the user;
 * - the known original Audio language;
 * - a learned global Audio fallback from choices on other titles;
 * - one learned global Subtitle state (Off or language + same-language variant);
 * - narrow source-specific exact fallbacks for tracks whose intent cannot be represented by
 *   language alone, such as anonymous tracks or commentary/descriptive Audio.
 *
 * Audio precedence is: title choice -> original language -> learned fallback -> existing defaults.
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

    private data class SavedSubtitleTrack(
        val value: SavedTrack,
        val globalRevision: Int,
    )

    private enum class SubtitleVariant {
        STANDARD,
        CC,
        SDH,
        FORCED,
    }

    private sealed interface SubtitlePreference {
        data object Unset : SubtitlePreference
        data object Off : SubtitlePreference
        data class Language(
            val value: String,
            val variant: SubtitleVariant,
        ) : SubtitlePreference
    }

    private data class TrackRef(
        val group: Tracks.Group,
        val groupIndex: Int,
        val trackIndex: Int,
    ) {
        val position: Pair<Int, Int>
            get() = groupIndex to trackIndex

        val format: Format
            get() = group.getTrackFormat(trackIndex)

        fun saved(): SavedTrack = SavedTrack(
            label = format.label,
            language = format.language,
            roleFlags = format.roleFlags,
            forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            groupIndex = groupIndex,
            trackIndex = trackIndex,
        )
    }

    @Volatile
    private var contentScope: ContentScope? = null

    @Volatile
    private var contentPreferenceKey: String? = null

    @Volatile
    private var scopeKey: String? = null

    @Volatile
    private var originalAudioLanguage: String? = null

    @Volatile
    private var titleAudioLanguage: String? = null

    @Volatile
    private var globalAudioFallbackLanguage: String? = null

    @Volatile
    private var globalSubtitlePreference: SubtitlePreference = SubtitlePreference.Unset

    @Volatile
    private var globalSubtitleRevision: Int = 0

    @Volatile
    private var savedAudio: SavedTrack? = null

    @Volatile
    private var savedSubtitle: SavedSubtitleTrack? = null

    private val prefs by lazy {
        StreamFlixApp.instance.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.playback_tracks",
            Context.MODE_PRIVATE,
        )
    }

    fun activate(videoType: Video.Type, originalLanguage: String? = null) {
        migratePreferencesIfNeeded()

        val provider = UserPreferences.currentProvider?.name
        val content = contentOf(videoType)

        contentScope = provider?.let { ContentScope(it, content) }
        contentPreferenceKey = contentScope?.let { scope ->
            contentKeyOf(scope.provider, scope.content)
        }
        originalAudioLanguage = canonicalLanguage(originalLanguage)
        titleAudioLanguage = contentPreferenceKey?.let(::loadTitleAudioLanguage)
        globalAudioFallbackLanguage = loadGlobalAudioFallbackLanguage()
        globalSubtitlePreference = loadGlobalSubtitlePreference()
        globalSubtitleRevision = prefs.getInt(GLOBAL_SUBTITLE_REVISION, 0)

        // Choosing the known original language means "follow original" rather than keeping a
        // redundant title override. It must not change the learned fallback from other titles.
        if (
            titleAudioLanguage != null &&
            originalAudioLanguage != null &&
            languageMatches(titleAudioLanguage!!, originalAudioLanguage!!)
        ) {
            clearTitleAudioLanguage()
        }

        scopeKey = null
        savedAudio = null
        savedSubtitle = null
    }

    /** The stable server/source name is intentionally part of exact fallback identity. */
    fun activateSource(sourceName: String) {
        scopeKey = contentScope?.let { scope ->
            keyOf(scope.provider, scope.content, sourceName)
        }
        savedAudio = scopeKey?.let(::loadAudio)
        savedSubtitle = scopeKey?.let(::loadSubtitle)
    }

    fun bind(player: Player): Player.Listener {
        val initialParameters = player.trackSelectionParameters
        val existingAudioLanguages = initialParameters.preferredAudioLanguages.toList()
        val existingSubtitleLanguages = initialParameters.preferredTextLanguages.toList()

        applyBaselinePreferences(
            player = player,
            existingAudioLanguages = existingAudioLanguages,
            existingSubtitleLanguages = existingSubtitleLanguages,
        )

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
                    rememberManualAudioSelection(audio)
                    audioCancelled = true
                }

                val subtitle = currentOverride(parameters, tracks, C.TRACK_TYPE_TEXT)
                val subtitleOff = subtitle == null && isSubtitleOff(parameters)

                when {
                    subtitle?.position != lastSubtitlePosition && subtitle != null -> {
                        rememberManualSubtitleSelection(subtitle)
                        subtitleCancelled = true

                        // The stock Off action leaves forced-subtitle flags behind. Once the user
                        // explicitly selects a subtitle track, normalize the parameters back to a
                        // normal enabled text state.
                        if (parameters.ignoredTextSelectionFlags != DEFAULT_TEXT_FLAGS) {
                            applyParameters(
                                parameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
                                    .build()
                            )
                        }
                    }

                    subtitleOff && !lastSubtitleOff -> {
                        clearSubtitleExact()
                        saveGlobalSubtitleOff()
                        subtitleCancelled = true

                        // "Off" in the learned model means all captions are off, including forced
                        // tracks. Apply that immediately instead of waiting for the next Player.
                        applyParameters(
                            parameters.buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setPreferredTextLanguages(*emptyArray())
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
                                .build()
                        )
                    }
                }

                captureState(player.trackSelectionParameters, tracks)
            }

            private fun resetForContext(newItem: MediaItem?, tracks: Tracks) {
                val newScope = scopeKey ?: return
                val scopeChanged = activeScope != null && activeScope != newScope
                val itemChanged = newItem !== mediaItem
                if (!scopeChanged && !itemChanged) return

                val externalSubtitle = hasLocalDefaultSubtitle(newItem)
                val builder = if (scopeChanged) {
                    player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                } else {
                    player.trackSelectionParameters.buildUpon()
                }

                builder.setPreferredAudioLanguages(
                    *effectiveAudioLanguages(existingAudioLanguages).toTypedArray()
                )
                applySubtitlePreference(
                    builder = builder,
                    existingSubtitleLanguages = existingSubtitleLanguages,
                    externalSubtitle = externalSubtitle,
                )

                mediaItem = newItem
                activeScope = newScope
                audioRestored = savedAudio == null
                subtitleRestored = savedSubtitle == null
                audioCancelled = false
                subtitleCancelled = externalSubtitle

                applyParameters(builder.build())
                captureState(player.trackSelectionParameters, tracks)
            }

            private fun restore(tracks: Tracks) {
                var builder: TrackSelectionParameters.Builder? = null
                var exactAudioApplied = false
                var exactSubtitleApplied = false

                if (!audioRestored && !audioCancelled) {
                    savedAudio?.let { saved ->
                        exactTrack(tracks, C.TRACK_TYPE_AUDIO, saved)?.let { track ->
                            builder = (builder ?: player.trackSelectionParameters.buildUpon())
                                .setOverrideForType(track.override())
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            audioRestored = true
                            exactAudioApplied = true
                        }
                    }
                }

                if (
                    globalSubtitlePreference !is SubtitlePreference.Off &&
                    !subtitleRestored &&
                    !subtitleCancelled
                ) {
                    savedSubtitle?.let { saved ->
                        exactTrack(tracks, C.TRACK_TYPE_TEXT, saved.value)?.let { track ->
                            builder = (builder ?: player.trackSelectionParameters.buildUpon())
                                .setOverrideForType(track.override())
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
                            subtitleRestored = true
                            exactSubtitleApplied = true
                        }
                    }
                }

                if (
                    !audioCancelled &&
                    !exactAudioApplied &&
                    currentOverride(player.trackSelectionParameters, tracks, C.TRACK_TYPE_AUDIO) == null
                ) {
                    labelFallbackTrack(
                        tracks = tracks,
                        type = C.TRACK_TYPE_AUDIO,
                        preferredLanguages = effectiveAudioLanguages(existingAudioLanguages),
                    )?.let { track ->
                        builder = (builder ?: player.trackSelectionParameters.buildUpon())
                            .setOverrideForType(track.override())
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    }
                }

                val subtitlePreference = globalSubtitlePreference as? SubtitlePreference.Language
                if (
                    !subtitleCancelled &&
                    !exactSubtitleApplied &&
                    subtitlePreference != null &&
                    !isSubtitleOff(player.trackSelectionParameters)
                ) {
                    subtitleVariantTrack(
                        tracks = tracks,
                        language = subtitlePreference.value,
                        variant = subtitlePreference.variant,
                    )?.let { track ->
                        if (!track.group.isTrackSelected(track.trackIndex)) {
                            builder = (builder ?: player.trackSelectionParameters.buildUpon())
                                .setOverrideForType(track.override())
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
                        }
                        exactSubtitleApplied = true
                    }
                }

                if (
                    !subtitleCancelled &&
                    !exactSubtitleApplied &&
                    subtitlePreference != null &&
                    currentOverride(player.trackSelectionParameters, tracks, C.TRACK_TYPE_TEXT) == null &&
                    !isSubtitleOff(player.trackSelectionParameters)
                ) {
                    labelFallbackTrack(
                        tracks = tracks,
                        type = C.TRACK_TYPE_TEXT,
                        preferredLanguages = preferredLanguageOrder(
                            subtitlePreference.value,
                            existingSubtitleLanguages,
                        ),
                    )?.let { track ->
                        builder = (builder ?: player.trackSelectionParameters.buildUpon())
                            .setOverrideForType(track.override())
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
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

    private fun applyBaselinePreferences(
        player: Player,
        existingAudioLanguages: List<String>,
        existingSubtitleLanguages: List<String>,
    ) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*effectiveAudioLanguages(existingAudioLanguages).toTypedArray())

        applySubtitlePreference(
            builder = builder,
            existingSubtitleLanguages = existingSubtitleLanguages,
            externalSubtitle = false,
        )

        val parameters = builder.build()
        if (parameters != player.trackSelectionParameters) {
            player.trackSelectionParameters = parameters
        }
    }

    private fun applySubtitlePreference(
        builder: TrackSelectionParameters.Builder,
        existingSubtitleLanguages: List<String>,
        externalSubtitle: Boolean,
    ) {
        if (externalSubtitle) {
            builder
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguages(*emptyArray())
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
            return
        }

        when (val preference = globalSubtitlePreference) {
            SubtitlePreference.Unset -> builder
                .setPreferredTextLanguages(*existingSubtitleLanguages.toTypedArray())
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)

            SubtitlePreference.Off -> builder
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguages(*emptyArray())
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)

            is SubtitlePreference.Language -> builder
                .setPreferredTextLanguages(
                    *preferredLanguageOrder(
                        preference.value,
                        existingSubtitleLanguages,
                    ).toTypedArray()
                )
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)
        }
    }

    private fun effectiveAudioLanguages(existingLanguages: List<String>): List<String> = buildList {
        titleAudioLanguage?.let(::add)
        originalAudioLanguage?.let(::add)
        globalAudioFallbackLanguage?.let(::add)
        addAll(existingLanguages)
    }.mapNotNull { language ->
        canonicalLanguage(language) ?: language.takeIf { it.isNotBlank() }
    }.distinctBy { it.lowercase(Locale.ROOT) }

    private fun rememberManualAudioSelection(track: TrackRef) {
        val language = eligibleAudioLanguage(track.format)
        if (language == null) {
            saveAudioExact(track.saved())
            return
        }

        clearAllAudioExactForContent()

        if (
            originalAudioLanguage != null &&
            languageMatches(language, originalAudioLanguage!!)
        ) {
            clearTitleAudioLanguage()
            return
        }

        saveTitleAudioLanguage(language)
        saveGlobalAudioFallbackLanguage(language)
    }

    private fun rememberManualSubtitleSelection(track: TrackRef) {
        val language = canonicalLanguage(track.format.language)
            ?: languageFromLabel(track.format.label)

        if (language == null) {
            saveSubtitleExact(track.saved())
            return
        }

        clearSubtitleExact()
        saveGlobalSubtitleLanguage(
            language = language,
            variant = subtitleVariant(track.format),
        )
    }

    private fun eligibleAudioLanguage(format: Format): String? {
        if (isNonPrimaryAudio(format)) return null
        return canonicalLanguage(format.language) ?: languageFromLabel(format.label)
    }

    private fun canonicalLanguage(language: String?): String? {
        val primary = language
            ?.trim()
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?: return null

        return Locale.getISOLanguages().firstOrNull { languageCode ->
            languageCode.equals(primary, ignoreCase = true) ||
                runCatching {
                    Locale.forLanguageTag(languageCode).isO3Language.equals(primary, ignoreCase = true)
                }.getOrDefault(false)
        }
    }

    private fun languageFromLabel(label: String?): String? {
        val normalizedLabel = normalizeLabel(label ?: return null)
        if (normalizedLabel.isBlank()) return null

        val matches = Locale.getISOLanguages().mapNotNull { languageCode ->
            val score = languageAliases(languageCode).maxOfOrNull { alias ->
                when {
                    alias.length <= 2 && normalizedLabel == alias -> alias.length
                    alias.length > 2 && containsWord(normalizedLabel, alias) -> alias.length
                    else -> 0
                }
            } ?: 0
            if (score > 0) languageCode to score else null
        }

        val bestScore = matches.maxOfOrNull { it.second } ?: return null
        return matches
            .filter { it.second == bestScore }
            .map { it.first }
            .distinct()
            .singleOrNull()
    }

    private fun isNonPrimaryAudio(format: Format): Boolean {
        if (format.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) return true
        if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) return true

        val label = normalizeLabel(format.label.orEmpty())
        return containsWord(label, "commentary") ||
            containsWord(label, "descriptive") ||
            label.contains("audio description") ||
            label.contains("audio described")
    }

    private fun preferredLanguageOrder(
        preferredLanguage: String,
        existingLanguages: List<String>,
    ): List<String> = buildList {
        add(preferredLanguage)
        addAll(existingLanguages)
    }.mapNotNull { language ->
        canonicalLanguage(language) ?: language.takeIf { it.isNotBlank() }
    }.distinctBy { it.lowercase(Locale.ROOT) }

    /**
     * Preserve the user's same-language subtitle variant when that variant exists. If it does not,
     * leave selection to Media3 so it can fall back to another track in the remembered language.
     */
    private fun subtitleVariantTrack(
        tracks: Tracks,
        language: String,
        variant: SubtitleVariant,
    ): TrackRef? = allSupportedTracks(tracks, C.TRACK_TYPE_TEXT)
        .filter { ref -> trackMatchesLanguage(ref.format, language) }
        .filter { ref -> subtitleVariant(ref.format) == variant }
        .let { candidates ->
            candidates.firstOrNull { it.group.isTrackSelected(it.trackIndex) }
                ?: candidates.firstOrNull()
        }

    private fun subtitleVariant(format: Format): SubtitleVariant {
        if (isForcedSubtitle(format)) return SubtitleVariant.FORCED

        val label = normalizeLabel(format.label.orEmpty())
        return when {
            containsWord(label, "sdh") -> SubtitleVariant.SDH
            containsWord(label, "cc") || label.contains("closed caption") -> SubtitleVariant.CC
            else -> SubtitleVariant.STANDARD
        }
    }

    /**
     * Media3 owns language matching whenever tracks expose language metadata. Some extractor tracks
     * only expose a label. In that limited case, infer enough from the label to select a track.
     */
    private fun labelFallbackTrack(
        tracks: Tracks,
        type: Int,
        preferredLanguages: List<String>,
    ): TrackRef? {
        if (preferredLanguages.isEmpty()) return null

        val refs = allSupportedTracks(tracks, type)

        preferredLanguages.forEach { preferredLanguage ->
            if (refs.any { ref ->
                    val language = ref.format.language
                    hasUsableLanguage(language) && languageMatches(language!!, preferredLanguage)
                }
            ) {
                return null
            }

            val candidates = refs.filter { ref ->
                val format = ref.format
                !hasUsableLanguage(format.language) &&
                    labelMatchesLanguage(format.label, preferredLanguage) &&
                    (type != C.TRACK_TYPE_TEXT || !isForcedSubtitle(format))
            }

            if (candidates.any { it.group.isTrackSelected(it.trackIndex) }) return null
            candidates.minByOrNull { fallbackPenalty(it, type) }?.let { return it }
        }

        return null
    }

    private fun allSupportedTracks(tracks: Tracks, type: Int): List<TrackRef> = buildList {
        tracks.groups.filter { it.type == type }.forEachIndexed { groupIndex, group ->
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                add(TrackRef(group, groupIndex, trackIndex))
            }
        }
    }

    private fun fallbackPenalty(track: TrackRef, type: Int): Int {
        val label = normalizeLabel(track.format.label.orEmpty())
        return when (type) {
            C.TRACK_TYPE_TEXT -> when {
                containsWord(label, "forced") -> 2
                containsWord(label, "cc") || containsWord(label, "sdh") -> 1
                else -> 0
            }

            C.TRACK_TYPE_AUDIO -> if (
                containsWord(label, "commentary") ||
                containsWord(label, "descriptive") ||
                label.contains("audio description")
            ) 1 else 0

            else -> 0
        }
    }

    private fun isForcedSubtitle(format: Format): Boolean =
        format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 ||
            containsWord(normalizeLabel(format.label.orEmpty()), "forced")

    private fun trackMatchesLanguage(format: Format, preferredLanguage: String): Boolean {
        val metadataLanguage = canonicalLanguage(format.language)
        return if (metadataLanguage != null) {
            languageMatches(metadataLanguage, preferredLanguage)
        } else {
            labelMatchesLanguage(format.label, preferredLanguage)
        }
    }

    private fun labelMatchesLanguage(label: String?, preferredLanguage: String): Boolean {
        val normalizedLabel = normalizeLabel(label ?: return false)
        if (normalizedLabel.isBlank()) return false
        return languageAliases(preferredLanguage).any { alias ->
            normalizedLabel == alias ||
                normalizedLabel.startsWith("$alias ") ||
                normalizedLabel.endsWith(" $alias") ||
                normalizedLabel.contains(" $alias ")
        }
    }

    private fun languageAliases(languageTag: String): Set<String> {
        val canonical = canonicalLanguage(languageTag) ?: return emptySet()
        val locale = Locale.forLanguageTag(canonical)

        return buildSet {
            add(locale.language)
            runCatching { locale.isO3Language }.getOrNull()?.let(::add)
            add(locale.getDisplayLanguage(Locale.ENGLISH))
            add(locale.getDisplayLanguage(locale))
            add(locale.getDisplayLanguage(Locale.getDefault()))
        }.map(::normalizeLabel)
            .filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun languageMatches(first: String, second: String): Boolean {
        val firstCanonical = canonicalLanguage(first)
        val secondCanonical = canonicalLanguage(second)
        return firstCanonical != null && firstCanonical == secondCanonical
    }

    private fun hasUsableLanguage(language: String?): Boolean =
        canonicalLanguage(language) != null

    private fun normalizeLabel(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(NON_WORD, " ")
        .trim()

    private fun containsWord(normalizedLabel: String, word: String): Boolean =
        normalizedLabel == word ||
            normalizedLabel.startsWith("$word ") ||
            normalizedLabel.endsWith(" $word") ||
            normalizedLabel.contains(" $word ")

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
     * Raw metadata must match exactly. Position is only used to disambiguate duplicate metadata or
     * anonymous tracks, avoiding guesses when episodes expose different anonymous tracks.
     */
    private fun exactTrack(tracks: Tracks, type: Int, saved: SavedTrack): TrackRef? {
        val matches = allSupportedTracks(tracks, type)
            .filter { saved.matches(it.format) }

        if (saved.hasRawIdentity && matches.size == 1) return matches.single()
        return matches.firstOrNull {
            it.groupIndex == saved.groupIndex && it.trackIndex == saved.trackIndex
        }
    }

    private fun loadAudio(scope: String): SavedTrack? =
        prefs.getString(audioKey(scope), null)?.let(SavedTrack::decode)

    private fun loadSubtitle(scope: String): SavedSubtitleTrack? {
        if (prefs.getString(subtitleModeKey(scope), null) != MODE_TRACK) return null
        val revision = prefs.getInt(subtitleRevisionKey(scope), -1)
        if (revision != globalSubtitleRevision) return null
        val track = prefs.getString(subtitleTrackKey(scope), null)
            ?.let(SavedTrack::decode)
            ?: return null
        return SavedSubtitleTrack(track, revision)
    }

    private fun loadTitleAudioLanguage(contentKey: String): String? =
        prefs.getString(titleAudioLanguageKey(contentKey), null)
            ?.let(::canonicalLanguage)

    private fun loadGlobalAudioFallbackLanguage(): String? =
        prefs.getString(GLOBAL_AUDIO_FALLBACK_LANGUAGE, null)
            ?.let(::canonicalLanguage)

    private fun loadGlobalSubtitlePreference(): SubtitlePreference {
        return when (prefs.getString(GLOBAL_SUBTITLE_MODE, null)) {
            MODE_OFF -> SubtitlePreference.Off
            MODE_LANGUAGE -> {
                val language = prefs.getString(GLOBAL_SUBTITLE_LANGUAGE, null)
                    ?.let(::canonicalLanguage)

                if (language == null) {
                    SubtitlePreference.Unset
                } else {
                    val variant = prefs.getString(GLOBAL_SUBTITLE_VARIANT, null)
                        ?.let { value ->
                            runCatching { SubtitleVariant.valueOf(value) }.getOrNull()
                        }
                        ?: SubtitleVariant.STANDARD
                    SubtitlePreference.Language(language, variant)
                }
            }

            else -> SubtitlePreference.Unset
        }
    }

    private fun saveAudioExact(value: SavedTrack) {
        val scope = scopeKey ?: return
        savedAudio = value
        prefs.edit().putString(audioKey(scope), value.encode()).apply()
    }

    private fun clearAllAudioExactForContent() {
        val contentKey = contentPreferenceKey ?: return
        val prefix = audioKey(contentKey)
        val editor = prefs.edit()
        prefs.all.keys
            .filter { key -> key.startsWith(prefix) }
            .forEach(editor::remove)
        editor.apply()
        savedAudio = null
    }

    private fun saveSubtitleExact(value: SavedTrack) {
        val scope = scopeKey ?: return
        val saved = SavedSubtitleTrack(value, globalSubtitleRevision)
        savedSubtitle = saved
        prefs.edit()
            .putString(subtitleModeKey(scope), MODE_TRACK)
            .putString(subtitleTrackKey(scope), value.encode())
            .putInt(subtitleRevisionKey(scope), globalSubtitleRevision)
            .apply()
    }

    private fun clearSubtitleExact() {
        val scope = scopeKey ?: return
        savedSubtitle = null
        prefs.edit()
            .remove(subtitleModeKey(scope))
            .remove(subtitleTrackKey(scope))
            .remove(subtitleRevisionKey(scope))
            .apply()
    }

    private fun saveTitleAudioLanguage(language: String) {
        val contentKey = contentPreferenceKey ?: return
        titleAudioLanguage = language
        prefs.edit().putString(titleAudioLanguageKey(contentKey), language).apply()
    }

    private fun clearTitleAudioLanguage() {
        val contentKey = contentPreferenceKey ?: return
        titleAudioLanguage = null
        prefs.edit().remove(titleAudioLanguageKey(contentKey)).apply()
    }

    private fun saveGlobalAudioFallbackLanguage(language: String) {
        globalAudioFallbackLanguage = language
        prefs.edit().putString(GLOBAL_AUDIO_FALLBACK_LANGUAGE, language).apply()
    }

    private fun saveGlobalSubtitleLanguage(
        language: String,
        variant: SubtitleVariant,
    ) {
        globalSubtitleRevision += 1
        globalSubtitlePreference = SubtitlePreference.Language(language, variant)
        prefs.edit()
            .putString(GLOBAL_SUBTITLE_MODE, MODE_LANGUAGE)
            .putString(GLOBAL_SUBTITLE_LANGUAGE, language)
            .putString(GLOBAL_SUBTITLE_VARIANT, variant.name)
            .putInt(GLOBAL_SUBTITLE_REVISION, globalSubtitleRevision)
            .apply()
    }

    private fun saveGlobalSubtitleOff() {
        globalSubtitleRevision += 1
        globalSubtitlePreference = SubtitlePreference.Off
        prefs.edit()
            .putString(GLOBAL_SUBTITLE_MODE, MODE_OFF)
            .remove(GLOBAL_SUBTITLE_LANGUAGE)
            .remove(GLOBAL_SUBTITLE_VARIANT)
            .putInt(GLOBAL_SUBTITLE_REVISION, globalSubtitleRevision)
            .apply()
    }

    private fun isSubtitleOff(parameters: TrackSelectionParameters) =
        parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
            parameters.ignoredTextSelectionFlags == LEGACY_SUBTITLE_OFF_FLAGS

    private fun hasLocalDefaultSubtitle(mediaItem: MediaItem?) =
        mediaItem?.localConfiguration?.subtitleConfigurations?.any { subtitle ->
            val scheme = subtitle.uri.scheme?.lowercase()
            (scheme == "file" || scheme == "content") &&
                subtitle.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
        } == true

    private fun contentOf(videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie -> "movie:${videoType.id}"
        is Video.Type.Episode -> "tv:${videoType.tvShow.id}"
    }

    private fun contentKeyOf(provider: String, content: String) =
        buildString {
            appendPart(provider)
            appendPart(content)
        }

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
    private fun subtitleRevisionKey(scope: String) = "subtitle_revision::$scope"
    private fun titleAudioLanguageKey(contentKey: String) = "title_audio_language::$contentKey"

    private fun migratePreferencesIfNeeded() {
        val currentVersion = prefs.getInt(SCHEMA_VERSION_KEY, 0)
        if (currentVersion >= CURRENT_SCHEMA_VERSION) return

        val editor = prefs.edit()
        prefs.all.keys.forEach { key ->
            if (
                key.startsWith("audio::") ||
                key.startsWith("subtitle_mode::") ||
                key.startsWith("subtitle_track::") ||
                key.startsWith("subtitle_revision::") ||
                key.startsWith("title_subtitle_language::")
            ) {
                editor.remove(key)
            }
        }

        if (
            prefs.getString(GLOBAL_SUBTITLE_MODE, null) == MODE_LANGUAGE &&
            prefs.getString(GLOBAL_SUBTITLE_VARIANT, null) == null
        ) {
            editor.putString(GLOBAL_SUBTITLE_VARIANT, SubtitleVariant.STANDARD.name)
        }

        editor.putInt(SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION).apply()
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private const val CURRENT_SCHEMA_VERSION = 3
    private const val SCHEMA_VERSION_KEY = "schema_version"

    private const val GLOBAL_AUDIO_FALLBACK_LANGUAGE = "global_audio_fallback_language"
    private const val GLOBAL_SUBTITLE_MODE = "global_subtitle_mode"
    private const val GLOBAL_SUBTITLE_LANGUAGE = "global_subtitle_language"
    private const val GLOBAL_SUBTITLE_VARIANT = "global_subtitle_variant"
    private const val GLOBAL_SUBTITLE_REVISION = "global_subtitle_revision"

    private const val MODE_OFF = "off"
    private const val MODE_TRACK = "track"
    private const val MODE_LANGUAGE = "language"
    private const val DEFAULT_TEXT_FLAGS = 0
    private const val LEGACY_SUBTITLE_OFF_FLAGS = C.SELECTION_FLAG_FORCED.inv()

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    private const val LABEL = "label"
    private const val LANGUAGE = "language"
    private const val ROLE_FLAGS = "roleFlags"
    private const val FORCED = "forced"
    private const val GROUP_INDEX = "groupIndex"
    private const val TRACK_INDEX = "trackIndex"
}
