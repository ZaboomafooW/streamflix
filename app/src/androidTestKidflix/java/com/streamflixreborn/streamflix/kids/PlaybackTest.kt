package com.streamflixreborn.streamflix.kids

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.media3.ui.PlayerView
import androidx.navigation.fragment.NavHostFragment
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.extractors.VidsrcRuExtractor
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.ServerAvailability
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PlaybackTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        val result = runCatching {
            cancelWebViewLookupFromWorker()
            report.putString("webviewCancellation", "passed")
            checkedMovieReachesTheTvPlayer()
            report.putString("moviePlayback", "passed")
            checkedEpisodeReachesTheTvPlayer()
            report.putString("episodePlayback", "passed")
        }
        result.exceptionOrNull()?.let { report.putString("failure", it.stackTraceToString()) }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }

    private fun cancelWebViewLookupFromWorker() = runBlocking {
        val lookup = withContext(Dispatchers.Main) {
            // Start the extractor on the WebView thread before cancelling on a worker.
            this@runBlocking.launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
                VidsrcRuExtractor().extract("about:blank")
            }
        }
        withContext(Dispatchers.IO) { lookup.cancelAndJoin() }
        check(lookup.isCancelled)
    }

    private fun checkedMovieReachesTheTvPlayer() {
        playFixture(Video.Type.Movie("test-movie", "Playback test", "", "", null))
    }

    private fun checkedEpisodeReachesTheTvPlayer() {
        playFixture(Video.Type.Episode("test-episode", 1, "Episode", null, null,
            Video.Type.Episode.TvShow("test-series", "Series", null, null, null, null),
            Video.Type.Episode.Season(1, "Season 1")))
    }

    private fun playFixture(title: Video.Type) = runBlocking {
        val instrumentation = this@PlaybackTest
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "playback-test.mp4")
        instrumentation.context.assets.open("playback.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val id = when (title) {
            is Video.Type.Movie -> title.id
            is Video.Type.Episode -> title.id
        }
        val source = Video(Uri.fromFile(file).toString(), type = "video/mp4")
        val server = Video.Server("test", "Test source")
        val provider = object : Provider by TmdbProvider("en") {
            override suspend fun getServers(id: String, videoType: Video.Type) = listOf(server)
            override suspend fun getVideo(server: Video.Server) = source
        }
        val activity = instrumentation.startActivitySync(Intent(context, KidFlixActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as FragmentActivity
        try {
            val session = ServerAvailability.prefetch(provider, id, title, refresh = true)
            val checked = withTimeout(90_000) { session.first { it.finished } }
            check(checked.servers.map { it.id } == listOf(server.id)) { "Fixture failed source verification: $checked" }
            withContext(Dispatchers.Main) {
                if (title is Video.Type.Episode) EpisodeManager.addEpisodes(listOf(title))
                val host = activity.supportFragmentManager.findFragmentById(R.id.kids_graph) as NavHostFragment
                host.navController.navigate(R.id.player, Bundle().apply {
                    putString("id", id)
                    putString("title", "Playback test")
                    putString("subtitle", "")
                    putParcelable("videoType", title)
                    putString("preferredServerName", server.name)
                })
            }
            withTimeout(90_000) {
                while (!withContext(Dispatchers.Main) {
                    val current = StreamFlixApp.currentActivity as? KidFlixActivity
                    val player = current?.findViewById<PlayerView>(R.id.pv_player)?.player
                    player != null && player.currentPosition > 0 && player.videoSize.width > 0
                }) delay(100)
            }
        } finally {
            withContext(Dispatchers.Main) {
                (StreamFlixApp.currentActivity as? KidFlixActivity ?: activity).finish()
            }
        }
    }
}
