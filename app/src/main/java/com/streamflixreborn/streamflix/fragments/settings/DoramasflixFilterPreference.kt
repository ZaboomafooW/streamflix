package com.streamflixreborn.streamflix.fragments.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.SwitchPreferenceCompat
import com.streamflixreborn.streamflix.providers.DoramasflixContentPreferences

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
        return DoramasflixContentPreferences.set(preferenceKey, value)
    }
}
