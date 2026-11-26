package com.streamflixreborn.streamflix.utils

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.SerienStreamProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.internal.userAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object ServerAvailability {
    data class Result(val servers: List<Video.Server> = emptyList(), val finished: Boolean = false,
                      val error: Exception? = null)

    private data class Key(val provider: String, val language: String, val id: String, val series: String?, val season: Int?, val episode: Int?)
    private data class Session(val key: Key, val started: Long, val result: MutableStateFlow<Result>, val job: Job)

    private const val EXTRACTION_TIMEOUT_MS = 35_000L
    private const val PRELOAD_TIMEOUT_MS = 15_000L
    private const val TAG = "ServerAvailability"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(3)
    private val bufferSlot = Semaphore(1)
    private var latest: Session? = null

    @Synchronized
    fun prefetch(provider: Provider, id: String, videoType: Video.Type, refresh: Boolean = false): StateFlow<Result> {
        val episode = videoType as? Video.Type.Episode
        val key = Key(provider.name, provider.language, id, episode?.tvShow?.id, episode?.season?.number, episode?.number)
        val now = SystemClock.elapsedRealtime()
        latest?.let {
            if (!refresh && it.key == key && (!it.result.value.finished || now - it.started < 60_000)) return it.result
            if (it.result.subscriptionCount.value == 0) it.job.cancel()
        }
        val result = MutableStateFlow(Result())
        val job = scope.launch {
            try {
                val candidates = kotlinx.coroutines.withTimeoutOrNull(60_000L) { provider.getServers(id, videoType) }
                    ?: throw java.io.IOException("Source discovery timed out")
                if (provider === SerienStreamProvider && candidates.any {
                        android.net.Uri.parse(it.id).host?.equals(android.net.Uri.parse(provider.baseUrl).host, true) == true
                    }) {
                    // These entries start the provider's existing interactive verification.
                    result.value = Result(candidates, finished = true)
                    return@launch
                }
                coroutineScope {
                    candidates.distinctBy { it.id to it.name }.forEach { server ->
                        launch {
                            val video = slots.withPermit {
                                try {
                                    withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                                        provider.getVideo(server)
                                    }.also {
                                        if (it == null) Log.w(TAG, "${server.name}: extraction timed out")
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    Log.w(TAG, "${server.name}: extraction failed (${error.javaClass.simpleName})")
                                    null
                                }
                            } ?: return@launch

                            if (video.source.isBlank()) {
                                Log.w(TAG, "${server.name}: empty media source")
                                return@launch
                            }

                            val sources = sequenceOf(video) + video.alternateSources.asSequence()
                            val working = sources.firstOrNull { candidate ->
                                bufferSlot.withPermit { playable(server.name, candidate) }
                            }
                            if (working != null) {
                                val checked = server.copy().also { it.video = working }
                                result.update { it.copy(servers = it.servers + checked) }
                            }
                        }
                    }
                }
                result.update { it.copy(finished = true) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                result.update { it.copy(finished = true, error = error) }
            }
        }
        latest = Session(key, now, result, job)
        return result
    }

    private suspend fun playable(name: String, video: Video): Boolean = withContext(Dispatchers.Main.immediate) {
        val context = StreamFlixApp.instance
        val http = PlaybackHttp.factory { video }
            .setDefaultRequestProperties(mapOf("User-Agent" to userAgent) + video.headers.orEmpty())
        val data = DefaultDataSource.Factory(context, http)
        val preloadManager = DefaultPreloadManager.Builder(context) {
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000L)
        }
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .build()
        try {
            val ready = CompletableDeferred<Boolean>()
            preloadManager.addListener(object : PreloadManagerListener {
                override fun onCompleted(mediaItem: MediaItem) {
                    ready.complete(true)
                }

                override fun onError(exception: PreloadException) {
                    Log.w(TAG, "$name: preload failed (${exception.javaClass.simpleName}; cause=${exception.cause?.javaClass?.simpleName})")
                    ready.complete(false)
                }
            })
            val mediaItem = MediaItem.Builder()
                .setUri(video.source)
                .setMimeType(video.type)
                .build()
            preloadManager.add(mediaItem, 0)
            preloadManager.invalidate()
            val result = withTimeoutOrNull(PRELOAD_TIMEOUT_MS) { ready.await() }
            if (result == null) Log.w(TAG, "$name: preload timed out")
            if (result == true) Log.d(TAG, "$name: preload passed")
            result == true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "$name: validation failed (${error.javaClass.simpleName})")
            false
        } finally {
            preloadManager.release()
        }
    }
}
