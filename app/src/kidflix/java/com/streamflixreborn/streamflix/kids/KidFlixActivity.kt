package com.streamflixreborn.streamflix.kids

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.streamflixreborn.streamflix.fragments.player.PlayerTvFragment
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
        val host = if (savedInstanceState == null) {
            NavHostFragment.create(R.navigation.kids_graph).also {
                supportFragmentManager.beginTransaction().replace(container.id, it)
                    .setPrimaryNavigationFragment(it).commitNow()
            }
        } else {
            supportFragmentManager.findFragmentById(container.id) as NavHostFragment
        }
        val navigation = host.navController
        val playerBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val player = host.childFragmentManager.primaryNavigationFragment as? PlayerTvFragment
                if (player?.view == null || !player.onBackPressed()) navigation.navigateUp()
            }
        }
        onBackPressedDispatcher.addCallback(this, playerBack)
        navigation.addOnDestinationChangedListener { _, destination, _ ->
            playerBack.isEnabled = destination.id == R.id.player
        }
    }
}
