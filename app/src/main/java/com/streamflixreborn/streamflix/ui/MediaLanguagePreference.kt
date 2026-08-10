package com.streamflixreborn.streamflix.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.PlaybackTrackPreferences
import java.util.Locale

abstract class MediaLanguagePreference(
    context: Context,
    attrs: AttributeSet?,
) : ListPreference(context, attrs) {

    protected fun configure(
        leadingEntries: List<Pair<String, String>>,
    ) {
        val displayLocale = Locale.getDefault()
        val languages = Locale.getISOLanguages()
            .map { languageTag ->
                languageTag to Locale.forLanguageTag(languageTag)
                    .getDisplayLanguage(displayLocale)
                    .replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase(displayLocale) else char.toString()
                    }
            }
            .filter { (_, displayName) -> displayName.isNotBlank() }
            .sortedBy { (_, displayName) -> displayName.lowercase(displayLocale) }

        entries = buildList<CharSequence> {
            addAll(leadingEntries.map { it.second })
            addAll(languages.map { it.second })
        }.toTypedArray()

        entryValues = buildList<CharSequence> {
            addAll(leadingEntries.map { it.first })
            addAll(languages.map { it.first })
        }.toTypedArray()

        summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
            val index = preference.findIndexOfValue(preference.value)
            preference.entries.getOrNull(index) ?: leadingEntries.first().second
        }
    }
}

class AudioLanguagePreference(
    context: Context,
    attrs: AttributeSet?,
) : MediaLanguagePreference(context, attrs) {

    init {
        configure(
            listOf(
                PlaybackTrackPreferences.AUDIO_LANGUAGE_ORIGINAL to
                    context.getString(R.string.settings_preferred_audio_language_original),
                "" to context.getString(R.string.settings_preferred_language_no_preference),
            )
        )
    }
}

class SubtitleLanguagePreference(
    context: Context,
    attrs: AttributeSet?,
) : MediaLanguagePreference(context, attrs) {

    init {
        configure(
            listOf(
                "" to context.getString(R.string.settings_preferred_language_no_preference),
            )
        )

        setOnPreferenceChangeListener { preference, _ ->
            PlaybackTrackPreferences.markGlobalLanguagePreferenceInitialized(preference.key)
            true
        }
    }
}
