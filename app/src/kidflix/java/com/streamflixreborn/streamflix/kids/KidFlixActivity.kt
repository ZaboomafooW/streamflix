package com.streamflixreborn.streamflix.kids

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.fragment.NavHostFragment
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.UserPreferences

class KidFlixActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (UserPreferences.currentProvider?.name != "TMDb (en)") {
            UserPreferences.currentProvider = TmdbProvider("en")
        }
        val container = FragmentContainerView(this).apply { id = R.id.kids_graph }
        setContentView(container)
        if (savedInstanceState == null) {
            val host = NavHostFragment.create(R.navigation.kids_graph)
            supportFragmentManager.beginTransaction().replace(container.id, host)
                .setPrimaryNavigationFragment(host).commitNow()
        }
    }
}
