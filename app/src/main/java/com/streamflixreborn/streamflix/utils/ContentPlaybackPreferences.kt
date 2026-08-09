package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.core.content.edit
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Persists playback choices that belong to one movie or TV show.
 *
 * This deliberately represents content-specific overrides, not global/profile defaults. A future
 * profile preference layer can therefore sit below these overrides without changing their meaning.
 */
object ContentPlaybackPreferences {

    data class TrackDescriptor(
        val language: String?,
        val label: String?,
        val name: String,
        val roleFlags: Int,
        val trackIndex: Int,
        val metadataMissing: Boolean,
    )

    data class TrackPreference(
        val providerName: String,
        val language: String?,
        val label: String?,
        val name: String,
        val roleFlags: Int,
        val trackIndex: Int,
        val layoutSignature: String,
        val metadataMissing: Boolean,
    )

    sealed interface SubtitlePreference {
        data object None : SubtitlePreference
        data class Track(val value: TrackPreference) : SubtitlePreference
    }

    private data class ActiveContent(
        val key: String,
        val providerName: String,
        val activationToken: Long,
    )

    private val preferences by lazy {
        StreamFlixApp.instance.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.content_playback_preferences",
            Context.MODE_PRIVATE,
        )
    }

    private val nextActivationToken = AtomicLong(0L)

    @Volatile
    private var activeContent: ActiveContent? = null

    /**
     * Sets the content whose player is currently being configured and returns an ownership token.
     * TV episodes share the TV-show key; movies use their movie key.
     */
    fun activate(videoType: Video.Type, providerName: String?): Long? {
        val provider = providerName?.trim().orEmpty()
        val key = when (videoType) {
            is Video.Type.Movie -> contentKey(
                type = "movie",
                imdbId = videoType.imdbId,
                providerName = provider,
                providerContentId = videoType.id,
            )

            is Video.Type.Episode -> contentKey(
                type = "tv",
                imdbId = videoType.tvShow.imdbId,
                providerName = provider,
                providerContentId = videoType.tvShow.id,
            )
        }

        if (key == null) {
            activeContent = null
            return null
        }

        val token = nextActivationToken.incrementAndGet()
        activeContent = ActiveContent(
            key = key,
            providerName = provider,
            activationToken = token,
        )
        return token
    }

    /** Clears only the context owned by [activationToken], never a newer player's context. */
    fun deactivate(activationToken: Long?) {
        if (activationToken == null) return
        if (activeContent?.activationToken == activationToken) {
            activeContent = null
        }
    }

    /** Changes for every movie/episode activation, even when episodes share the same TV-show key. */
    fun currentActivationToken(): Long? = activeContent?.activationToken

    fun rememberAudio(
        selected: TrackDescriptor,
        availableTracks: List<TrackDescriptor>,
    ) {
        val active = activeContent ?: return
        update(active) { json ->
            json.put(
                AUDIO,
                trackToJson(
                    TrackPreference(
                        providerName = active.providerName,
                        language = canonicalLanguage(selected.language),
                        label = normalizedText(selected.label),
                        name = selected.name.trim(),
                        roleFlags = selected.roleFlags,
                        trackIndex = selected.trackIndex,
                        layoutSignature = layoutSignature(availableTracks),
                        metadataMissing = selected.metadataMissing,
                    )
                )
            )
        }
    }

    fun rememberSubtitle(
        selected: TrackDescriptor,
        availableTracks: List<TrackDescriptor>,
    ) {
        val active = activeContent ?: return
        update(active) { json ->
            json.put(
                SUBTITLE,
                JSONObject()
                    .put(MODE, MODE_TRACK)
                    .put(
                        TRACK,
                        trackToJson(
                            TrackPreference(
                                providerName = active.providerName,
                                language = canonicalLanguage(selected.language),
                                label = normalizedText(selected.label),
                                name = selected.name.trim(),
                                roleFlags = selected.roleFlags,
                                trackIndex = selected.trackIndex,
                                layoutSignature = layoutSignature(availableTracks),
                                metadataMissing = selected.metadataMissing,
                            )
                        )
                    )
            )
        }
    }

    fun rememberSubtitleNone() {
        val active = activeContent ?: return
        update(active) { json ->
            json.put(SUBTITLE, JSONObject().put(MODE, MODE_NONE))
        }
    }

    fun rememberSpeed(speed: Float) {
        if (!speed.isFinite() || speed <= 0F) return
        val active = activeContent ?: return
        update(active) { json -> json.put(SPEED, speed.toDouble()) }
    }

    fun audio(): TrackPreference? {
        val json = loadActive() ?: return null
        return json.optJSONObject(AUDIO)?.let(::trackFromJson)
    }

    fun subtitle(): SubtitlePreference? {
        val json = loadActive() ?: return null
        val subtitle = json.optJSONObject(SUBTITLE) ?: return null
        return when (subtitle.optString(MODE)) {
            MODE_NONE -> SubtitlePreference.None
            MODE_TRACK -> subtitle.optJSONObject(TRACK)
                ?.let(::trackFromJson)
                ?.let(SubtitlePreference::Track)
            else -> null
        }
    }

    fun speed(): Float? {
        val json = loadActive() ?: return null
        if (!json.has(SPEED)) return null
        return json.optDouble(SPEED, Double.NaN)
            .toFloat()
            .takeIf { it.isFinite() && it > 0F }
    }

    /**
     * Returns the safely matching track index in [availableTracks], or null when matching would
     * require guessing. Language metadata may travel between providers. Opaque labels never do.
     */
    fun findTrack(
        saved: TrackPreference,
        availableTracks: List<TrackDescriptor>,
    ): Int? {
        if (availableTracks.isEmpty()) return null

        val savedLanguage = canonicalLanguage(saved.language)
        if (savedLanguage != null) {
            val exactLanguageMatches = availableTracks.withIndex()
                .filter { canonicalLanguage(it.value.language) == savedLanguage }
            val languageMatches = when {
                exactLanguageMatches.isNotEmpty() -> exactLanguageMatches
                hasLanguageSpecificity(savedLanguage) -> emptyList()
                else -> availableTracks.withIndex().filter {
                    baseLanguage(it.value.language) == savedLanguage
                }
            }
            if (languageMatches.isEmpty()) return null

            uniqueMatch(languageMatches) {
                sameText(saved.label, it.label) && saved.roleFlags == it.roleFlags
            }?.let { return it }

            uniqueMatch(languageMatches) {
                sameText(saved.name, it.name) && saved.roleFlags == it.roleFlags
            }?.let { return it }

            return uniqueMatch(languageMatches) { saved.roleFlags == it.roleFlags }
        }

        val active = activeContent ?: return null
        if (saved.providerName != active.providerName) return null

        if (
            saved.metadataMissing &&
            saved.layoutSignature != layoutSignature(availableTracks)
        ) {
            return null
        }

        val exactMatches = availableTracks.withIndex().filter { indexed ->
            val candidate = indexed.value
            val textMatches = when {
                saved.label != null -> sameText(saved.label, candidate.label)
                else -> sameText(saved.name, candidate.name)
            }
            textMatches && saved.roleFlags == candidate.roleFlags
        }

        return exactMatches.singleOrNull()?.index
    }

    /**
     * Normalizes a BCP-47-like track language while preserving script/region specificity.
     * The base language is normalized to ISO-639-2/3 where Android can provide it so `en` and
     * `eng` compare consistently, but `pt-BR` and `pt-PT` remain distinct.
     */
    fun canonicalLanguage(value: String?): String? {
        val raw = value
            ?.trim()
            ?.replace('_', '-')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (raw.equals("und", ignoreCase = true) || raw.equals("unknown", ignoreCase = true)) {
            return null
        }

        val locale = Locale.forLanguageTag(raw)
        val localeLanguage = locale.language
            .takeIf { it.isNotBlank() && it != "und" }
            ?: return null
        val base = runCatching { locale.isO3Language }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?: localeLanguage

        return buildList {
            add(base.lowercase(Locale.ROOT))
            locale.script.takeIf { it.isNotBlank() }
                ?.let { add(it.lowercase(Locale.ROOT)) }
            locale.country.takeIf { it.isNotBlank() }
                ?.let { add(it.lowercase(Locale.ROOT)) }
            locale.variant.takeIf { it.isNotBlank() }
                ?.let { add(it.lowercase(Locale.ROOT)) }
        }.joinToString("-")
    }

    private fun baseLanguage(value: String?): String? =
        canonicalLanguage(value)?.substringBefore('-')

    private fun hasLanguageSpecificity(language: String): Boolean = '-' in language

    private fun contentKey(
        type: String,
        imdbId: String?,
        providerName: String,
        providerContentId: String,
    ): String? {
        imdbId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "v1:$type:imdb:$it"
        }

        val id = providerContentId.trim().takeIf { it.isNotEmpty() } ?: return null
        if (providerName.isEmpty()) return null
        return "v1:$type:provider:$providerName:id:$id"
    }

    private fun loadActive(): JSONObject? {
        val active = activeContent ?: return null
        return load(active)
    }

    private fun load(active: ActiveContent): JSONObject? {
        val raw = preferences.getString(active.key, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private inline fun update(active: ActiveContent, block: (JSONObject) -> Unit) {
        val json = load(active) ?: JSONObject().put(VERSION, 1)
        block(json)
        preferences.edit { putString(active.key, json.toString()) }
    }

    private fun trackToJson(track: TrackPreference) = JSONObject().apply {
        put(PROVIDER, track.providerName)
        track.language?.let { put(LANGUAGE, it) }
        track.label?.let { put(LABEL, it) }
        put(NAME, track.name)
        put(ROLE_FLAGS, track.roleFlags)
        put(TRACK_INDEX, track.trackIndex)
        put(LAYOUT, track.layoutSignature)
        put(METADATA_MISSING, track.metadataMissing)
    }

    private fun trackFromJson(json: JSONObject): TrackPreference? {
        val name = json.stringOrNull(NAME) ?: return null
        return TrackPreference(
            providerName = json.optString(PROVIDER),
            language = canonicalLanguage(json.stringOrNull(LANGUAGE)),
            label = normalizedText(json.stringOrNull(LABEL)),
            name = name,
            roleFlags = json.optInt(ROLE_FLAGS, 0),
            trackIndex = json.optInt(TRACK_INDEX, -1),
            layoutSignature = json.optString(LAYOUT),
            metadataMissing = json.optBoolean(METADATA_MISSING, false),
        )
    }

    private fun layoutSignature(tracks: List<TrackDescriptor>): String {
        return tracks
            .map { track ->
                listOf(
                    track.trackIndex.toString(),
                    canonicalLanguage(track.language).orEmpty(),
                    normalizedText(track.label).orEmpty().lowercase(Locale.ROOT),
                    track.name.trim().lowercase(Locale.ROOT),
                    track.roleFlags.toString(),
                    track.metadataMissing.toString(),
                ).joinToString("\u001f")
            }
            .sorted()
            .joinToString("\u001e")
    }

    private fun uniqueMatch(
        candidates: List<IndexedValue<TrackDescriptor>>,
        predicate: (TrackDescriptor) -> Boolean,
    ): Int? {
        return candidates.filter { predicate(it.value) }.singleOrNull()?.index
    }

    private fun sameText(left: String?, right: String?): Boolean {
        val first = normalizedText(left) ?: return false
        val second = normalizedText(right) ?: return false
        return first.equals(second, ignoreCase = true)
    }

    private fun normalizedText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeIf { it.isNotEmpty() } else null

    private const val VERSION = "version"
    private const val AUDIO = "audio"
    private const val SUBTITLE = "subtitle"
    private const val SPEED = "speed"
    private const val MODE = "mode"
    private const val MODE_NONE = "none"
    private const val MODE_TRACK = "track"
    private const val TRACK = "track"
    private const val PROVIDER = "provider"
    private const val LANGUAGE = "language"
    private const val LABEL = "label"
    private const val NAME = "name"
    private const val ROLE_FLAGS = "role_flags"
    private const val TRACK_INDEX = "track_index"
    private const val LAYOUT = "layout"
    private const val METADATA_MISSING = "metadata_missing"
}
