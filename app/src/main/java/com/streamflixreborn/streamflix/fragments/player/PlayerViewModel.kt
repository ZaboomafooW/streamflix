package com.streamflixreborn.streamflix.fragments.player

import com.streamflixreborn.streamflix.utils.ServerAvailability
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.CustomTabHelper
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import com.streamflixreborn.streamflix.utils.SubDL

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
    private val _availableServers = MutableStateFlow<List<Video.Server>>(emptyList())
    val availableServers = _availableServers.asStateFlow()
    private var discovery: Job? = null
    private var videoRequest: Job? = null
    private val failedServers = mutableSetOf<Video.Server>()
    private val discoveryFinished = MutableStateFlow(false)
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null
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
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String, refresh: Boolean = false) {
        discovery?.cancel()
        videoRequest?.cancel()
        failedServers.clear()
        discoveryFinished.value = false
        lastVideoType = videoType
        lastId = id
        _availableServers.value = emptyList()
        _state.value = State.LoadingServers
        val provider = UserPreferences.currentProvider ?: run {
            _state.value = State.FailedLoadingServers(IllegalStateException("No provider selected"))
            return
        }
        discovery = viewModelScope.launch {
            var started = false
            ServerAvailability.prefetch(provider, id, videoType, refresh).collect { result ->
                val available = result.servers.filterNot { it in failedServers }
                _availableServers.value = available
                discoveryFinished.value = result.finished
                if (!started && available.isNotEmpty()) {
                    started = true
                    _state.value = State.SuccessLoadingServers(available)
                } else if (!started && result.finished) {
                    _state.value = result.error?.let { State.FailedLoadingServers(it) }
                        ?: State.SuccessLoadingServers(emptyList())
                }
            }
        }
    }

    fun isCurrentFailure(server: Video.Server): Boolean =
        (_state.value as? State.FailedLoadingVideo)?.server == server

    suspend fun nextServerAfterFailure(server: Video.Server): Video.Server? {
        if (!isCurrentFailure(server)) return null
        failedServers.add(server)
        _availableServers.value = _availableServers.value.filterNot { it in failedServers }

        val failure = _state.value
        val next = withTimeoutOrNull(30_000L) {
            combine(_availableServers, discoveryFinished, _state) { available, finished, state ->
                Triple(available, finished, state)
            }.first { (available, finished, state) ->
                available.isNotEmpty() || finished || state !== failure
            }
        }
        if (next == null) Log.w("PlayerViewModel", "Timed out waiting for remaining sources")
        return if (_state.value === failure) _availableServers.value.firstOrNull() else null
    }

    fun reportPlaybackFailure(server: Video.Server, error: Exception) {
        val state = _state.value as? State.SuccessLoadingVideo ?: return
        if (state.server != server) return
        _state.value = State.FailedLoadingVideo(error, server)
    }

    fun getVideo(server: Video.Server) {
        if (server in failedServers) return
        videoRequest?.cancel()
        _state.value = State.LoadingVideo(server)
        videoRequest = viewModelScope.launch(Dispatchers.IO) {
            Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
            try {
                val video = server.video ?: UserPreferences.currentProvider!!.getVideo(server)
                if (video.source.isEmpty()) throw Exception("No source found")

                // LOGICA SOTTOTITOLI GLOBALE: 
                // Se il provider non ha già impostato un default (es. i "forced" in spagnolo),
                // allora proviamo ad attivare l'ultimo sottotitolo usato dall'utente.
                // MA: se siamo su un provider spagnolo e non ci sono forced, non dobbiamo attivare nulla.
                val currentProviderLang = UserPreferences.currentProvider?.language ?: ""
                val hasDefaultAlready = video.subtitles.any { it.default }

                if (!hasDefaultAlready && currentProviderLang != "es") {
                    if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                        video.subtitles
                            .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                            ?.default = true
    		}
                }

                Log.d("PlayerViewModel", "Estrazione video completata con successo")
                _state.emit(State.SuccessLoadingVideo(video, server))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore estrazione video: ", e)
                _state.emit(State.FailedLoadingVideo(e, server))
            }
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
        getServers(type, id, refresh = true)
    }
}
