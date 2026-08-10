package com.streamflixreborn.streamflix.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import java.util.Locale

class MediaLanguagePreference(
    context: Context,
    attrs: AttributeSet?,
) : ListPreference(context, attrs) {

    init {
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
            add(context.getString(R.string.settings_preferred_language_auto))
            addAll(languages.map { it.second })
        }.toTypedArray()

        entryValues = buildList<CharSequence> {
            add("")
            addAll(languages.map { it.first })
        }.toTypedArray()

        summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
            val index = preference.findIndexOfValue(preference.value)
            preference.entries.getOrNull(index)
                ?: context.getString(R.string.settings_preferred_language_auto)
        }
    }
}
