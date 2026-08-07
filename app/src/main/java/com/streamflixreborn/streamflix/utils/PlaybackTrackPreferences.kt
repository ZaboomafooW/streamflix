package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.core.content.edit
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import java.util.Locale

/**
 * Persists audio/subtitle choices for a movie or TV show instead of relying on
 * whichever track a source happens to mark as default.
 *
 * TV episode preferences are scoped to the parent show so a choice made on one
 * episode carries over to the next episode. Movie preferences are scoped to the
 * movie itself.
 */
object PlaybackTrackPreferences {

    data class TrackPreference(
        val language: String?,
        val label: String?,
        val name: String?,
        val forced: Boolean = false,
    )

    data class SubtitlePreference(
        val disabled: Boolean,
        val track: TrackPreference? = null,
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
        currentScopeKey = when (videoType) {
            is Video.Type.Movie -> "movie:${videoType.id}"
            is Video.Type.Episode -> "tv:${videoType.tvShow.id}"
        }
    }

    fun preferredAudio(): TrackPreference? {
        val scope = currentScopeKey ?: return null
        val language = preferences.getString(key(scope, AUDIO_LANGUAGE), null)
        val label = preferences.getString(key(scope, AUDIO_LABEL), null)
        val name = preferences.getString(key(scope, AUDIO_NAME), null)
        if (language.isNullOrBlank() && label.isNullOrBlank() && name.isNullOrBlank()) return null
        return TrackPreference(language, label, name)
    }

    fun setPreferredAudio(language: String?, label: String?, name: String?) {
        val scope = currentScopeKey ?: return
        preferences.edit {
            putOrRemove(key(scope, AUDIO_LANGUAGE), language)
            putOrRemove(key(scope, AUDIO_LABEL), label)
            putOrRemove(key(scope, AUDIO_NAME), name)
        }
    }

    fun preferredSubtitle(): SubtitlePreference? {
        val scope = currentScopeKey ?: return null
        val disabledKey = key(scope, SUBTITLE_DISABLED)
        val languageKey = key(scope, SUBTITLE_LANGUAGE)
        val labelKey = key(scope, SUBTITLE_LABEL)
        val nameKey = key(scope, SUBTITLE_NAME)
        val forcedKey = key(scope, SUBTITLE_FORCED)

        val hasPreference = preferences.contains(disabledKey) ||
            preferences.contains(languageKey) ||
            preferences.contains(labelKey) ||
            preferences.contains(nameKey)
        if (!hasPreference) return null

        val disabled = preferences.getBoolean(disabledKey, false)
        if (disabled) return SubtitlePreference(disabled = true)

        return SubtitlePreference(
            disabled = false,
            track = TrackPreference(
                language = preferences.getString(languageKey, null),
                label = preferences.getString(labelKey, null),
                name = preferences.getString(nameKey, null),
                forced = preferences.getBoolean(forcedKey, false),
            ),
        )
    }

    fun setPreferredSubtitle(
        language: String?,
        label: String?,
        name: String?,
        forced: Boolean,
    ) {
        val scope = currentScopeKey ?: return
        preferences.edit {
            putBoolean(key(scope, SUBTITLE_DISABLED), false)
            putOrRemove(key(scope, SUBTITLE_LANGUAGE), language)
            putOrRemove(key(scope, SUBTITLE_LABEL), label)
            putOrRemove(key(scope, SUBTITLE_NAME), name)
            putBoolean(key(scope, SUBTITLE_FORCED), forced)
        }
    }

    fun setSubtitlesOff() {
        val scope = currentScopeKey ?: return
        preferences.edit {
            putBoolean(key(scope, SUBTITLE_DISABLED), true)
            remove(key(scope, SUBTITLE_LANGUAGE))
            remove(key(scope, SUBTITLE_LABEL))
            remove(key(scope, SUBTITLE_NAME))
            remove(key(scope, SUBTITLE_FORCED))
        }
    }

    fun matchesAudio(
        preference: TrackPreference,
        language: String?,
        label: String?,
        name: String?,
    ): Boolean {
        val preferredLanguage = canonicalLanguage(preference.language)
        val candidateLanguage = canonicalLanguage(language)
        if (preferredLanguage != null && candidateLanguage != null) {
            return preferredLanguage == candidateLanguage
        }

        return exactTextMatch(preference.label, label) ||
            exactTextMatch(preference.name, name)
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

    fun matchesVideoSubtitle(preference: TrackPreference, label: String): Boolean {
        if (preference.forced != isForcedLabel(label)) return false
        if (exactTextMatch(preference.label, label) || exactTextMatch(preference.name, label)) {
            return true
        }

        val preferredLanguage = preference.language ?: return false
        val aliases = languageAliases(preferredLanguage)
        val normalizedLabel = label.trim().lowercase(Locale.ROOT)
        return aliases.any { alias ->
            normalizedLabel == alias || normalizedLabel.startsWith("$alias ")
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

    private fun canonicalLanguage(value: String?): String? {
        val raw = value?.trim()?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: return null
        val locale = Locale.forLanguageTag(raw)
        if (locale.language.isBlank()) return raw.lowercase(Locale.ROOT)
        return runCatching { locale.isO3Language }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: locale.language.lowercase(Locale.ROOT)
    }

    private fun languageAliases(value: String): Set<String> {
        val raw = value.trim().replace('_', '-')
        val locale = Locale.forLanguageTag(raw)
        return buildSet {
            add(raw.lowercase(Locale.ROOT))
            locale.language.takeIf { it.isNotBlank() }?.let { add(it.lowercase(Locale.ROOT)) }
            runCatching { locale.isO3Language }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it.lowercase(Locale.ROOT)) }
            locale.getDisplayLanguage(Locale.ENGLISH)
                .takeIf { it.isNotBlank() }
                ?.let { add(it.lowercase(Locale.ROOT)) }
        }
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
    private const val SUBTITLE_DISABLED = "subtitle_disabled"
    private const val SUBTITLE_LANGUAGE = "subtitle_language"
    private const val SUBTITLE_LABEL = "subtitle_label"
    private const val SUBTITLE_NAME = "subtitle_name"
    private const val SUBTITLE_FORCED = "subtitle_forced"
}
