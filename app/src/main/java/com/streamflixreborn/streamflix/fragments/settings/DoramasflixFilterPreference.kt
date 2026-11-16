package com.streamflixreborn.streamflix.fragments.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.SwitchPreferenceCompat
import com.streamflixreborn.streamflix.providers.DoramasflixContentPreferences
import com.streamflixreborn.streamflix.providers.DoramasflixProvider
import com.streamflixreborn.streamflix.utils.HomeCacheStore
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier

class DoramasflixFilterPreference(
    context: Context,
    attrs: AttributeSet?,
) : SwitchPreferenceCompat(context, attrs) {

    override fun getPersistedBoolean(defaultReturnValue: Boolean): Boolean {
        val preferenceKey = key ?: return defaultReturnValue
        return DoramasflixContentPreferences.get(preferenceKey)
    }

    override fun persistBoolean(value: Boolean): Boolean {
        val preferenceKey = key ?: return false
        val changed = DoramasflixContentPreferences.get(preferenceKey) != value
        val persisted = DoramasflixContentPreferences.set(preferenceKey, value)
        if (persisted && changed) {
            HomeCacheStore.clear(context, DoramasflixProvider)
            ProviderChangeNotifier.notifyProviderChanged()
        }
        return persisted
    }
}
