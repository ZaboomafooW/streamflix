package com.streamflixreborn.streamflix.providers

import android.content.Context
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp

internal object DoramasflixContentPreferences {

    const val KEY_SHOW_BL = "DORAMASFLIX_SHOW_BL"
    const val KEY_SHOW_GL = "DORAMASFLIX_SHOW_GL"
    const val KEY_SHOW_LGBT = "DORAMASFLIX_SHOW_LGBT"
    const val KEY_SHOW_ADULT = "DORAMASFLIX_SHOW_ADULT"

    private val preferences
        get() = StreamFlixApp.instance.getSharedPreferences(
            "${BuildConfig.APPLICATION_ID}.preferences",
            Context.MODE_PRIVATE,
        )

    fun settings() = DoramasflixContentPolicy.Settings(
        showBl = get(KEY_SHOW_BL),
        showGl = get(KEY_SHOW_GL),
        showLgbt = get(KEY_SHOW_LGBT),
        showAdult = get(KEY_SHOW_ADULT),
    )

    fun get(key: String): Boolean = when (key) {
        KEY_SHOW_BL,
        KEY_SHOW_GL,
        KEY_SHOW_LGBT,
        KEY_SHOW_ADULT -> preferences.getBoolean(key, false)
        else -> false
    }

    fun set(key: String, value: Boolean): Boolean = when (key) {
        KEY_SHOW_BL,
        KEY_SHOW_GL,
        KEY_SHOW_LGBT,
        KEY_SHOW_ADULT -> preferences.edit().putBoolean(key, value).commit()
        else -> false
    }
}
