package com.streamflixreborn.streamflix.fragments.player

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.extractors.TokenManager
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.ServerAvailability
import com.streamflixreborn.streamflix.utils.SubDL
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode

    private val contentGeneration = AtomicLong(0)
    private var serverLoadJob: Job? = null
    private var videoLoadJob: Job? = null
    private var currentServers: List<Video.Server> = emptyList()
    private var rediscoveredAfterExhaustion = false
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null
    private var lastProvider: Provider? = null

    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }

    fun playPreviousEpisode() = playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() = playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }

    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(
        videoType: Video.Type,
        id: String,
        forceRefresh: Boolean = false,
        resetRecovery: Boolean = true,
    ): Job {
        val provider = UserPreferences.currentProvider
            ?: return viewModelScope.launch {
                _state.emit(State.FailedLoadingServers(Exception("No provider selected")))
            }

        val generation = contentGeneration.incrementAndGet()
        serverLoadJob?.cancel()
        videoLoadJob?.cancel()
        lastVideoType = videoType
        lastId = id
        lastProvider = provider
        currentServers = emptyList()
        if (resetRecovery) rediscoveredAfterExhaustion = false

        return viewModelScope.launch(Dispatchers.IO) {
            loadServers(provider, videoType, id, forceRefresh, generation)
        }.also { serverLoadJob = it }
    }

    private suspend fun loadServers(
        provider: Provider,
        videoType: Video.Type,
        id: String,
        forceRefresh: Boolean,
        generation: Long,
    ) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        _state.emit(State.LoadingServers)

        try {
            val result = ServerAvailability.getWorkingServers(
                provider = provider,
                id = id,
                videoType = videoType,
                forceRefresh = forceRefresh,
            )
            if (generation != contentGeneration.get()) return

            val servers = when (result) {
                is ServerAvailability.Result.Available -> result.servers
                is ServerAvailability.Result.RequiresInteraction -> result.candidates
                ServerAvailability.Result.Empty -> emptyList()
            }

            if (servers.isEmpty()) {
                throw Exception("No playable servers found for this content.")
            }

            currentServers = servers
            Log.i(
                "StreamFlixES",
                "[SERVERS LIST] -> Provider: ${provider.name}; available=${servers.size}; " +
                    "servers=${servers.joinToString { it.name }}"
            )
            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != contentGeneration.get()) return
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    fun getVideo(server: Video.Server): Job {
        videoLoadJob?.cancel()
        val generation = contentGeneration.get()
        val provider = lastProvider ?: UserPreferences.currentProvider
        val videoType = lastVideoType
        val id = lastId

        if (provider == null || videoType == null || id == null) {
            return viewModelScope.launch {
                _state.emit(State.FailedLoadingVideo(Exception("Playback context is unavailable"), server))
            }
        }

        return viewModelScope.launch(Dispatchers.IO) {
            val orderedServers = buildList {
                add(server)
                addAll(currentServers.filterNot { sameServer(it, server) })
            }

            var lastError: Exception = Exception("No playable source found")
            var lastFailedServer = server

            for (candidate in orderedServers) {
                if (generation != contentGeneration.get()) return@launch

                Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${candidate.name}")
                _state.emit(State.LoadingVideo(candidate))

                try {
                    val video = provider.getVideo(candidate)
                    if (video.source.isBlank()) {
                        throw Exception("No source found")
                    }
                    if (generation != contentGeneration.get()) return@launch

                    ServerAvailability.markAvailable(provider, id, videoType, candidate)
                    publishResolvedVideo(video, candidate)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    lastFailedServer = candidate
                    ServerAvailability.invalidate(provider, id, videoType, candidate)
                    Log.w(
                        "PlayerViewModel",
                        "Server ${candidate.name} stopped resolving: ${e.message}"
                    )
                }
            }

            if (generation != contentGeneration.get()) return@launch

            if (!rediscoveredAfterExhaustion) {
                rediscoveredAfterExhaustion = true
                Log.d("PlayerViewModel", "Cached servers exhausted; running fresh discovery")
                loadServers(
                    provider = provider,
                    videoType = videoType,
                    id = id,
                    forceRefresh = true,
                    generation = generation,
                )
                return@launch
            }

            val terminalServer = currentServers.lastOrNull() ?: lastFailedServer
            _state.emit(State.FailedLoadingVideo(lastError, terminalServer))
        }.also { videoLoadJob = it }
    }

    private suspend fun publishResolvedVideo(video: Video, server: Video.Server) {
        val currentProviderLang = lastProvider?.language ?: UserPreferences.currentProvider?.language.orEmpty()
        val hasDefaultAlready = video.subtitles.any { it.default }

        if (!hasDefaultAlready && currentProviderLang != "es") {
            if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                video.subtitles
                    .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                    ?.default = true
            }
        }

        activatePlaybackSession(video)
        Log.d("PlayerViewModel", "Estrazione video completata con successo")
        _state.emit(State.SuccessLoadingVideo(video, server))
    }

    private fun activatePlaybackSession(video: Video) {
        val tokenSession = video.tokenSession
        if (video.maintainToken && tokenSession != null) {
            TokenManager.start(tokenSession, viewModelScope)
        } else {
            TokenManager.stop()
        }
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        OpenSubtitles.search(
                            query = videoType.tvShow.title,
                            season = videoType.season.number,
                            episode = videoType.number,
                        )
                    }
                    is Video.Type.Movie -> {
                        OpenSubtitles.search(query = videoType.title)
                    }
                }.sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))

                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv"
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie"
                        )
                    }
                }

                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    override fun onCleared() {
        serverLoadJob?.cancel()
        videoLoadJob?.cancel()
        TokenManager.stop()
        super.onCleared()
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }

    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id, forceRefresh = true, resetRecovery = false)
    }

    private fun sameServer(first: Video.Server, second: Video.Server): Boolean {
        return first.id == second.id && first.name == second.name
    }
}
