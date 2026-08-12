package com.streamflixreborn.streamflix.fragments.player

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.PlaybackLanguageContext
import com.streamflixreborn.streamflix.utils.PlaybackTrackPreferences
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

    private var subtitleSearchJob: Job? = null
    private var externalSubtitleDownloadJob: Job? = null
    private var automaticForcedDownloadJob: Job? = null
    private val externalForcedLock = Any()
    private var mediaGeneration = 0L
    private var subtitleContentGeneration = 0L
    private var openSubtitleResults: List<OpenSubtitles.Subtitle> = emptyList()
    private var pendingExternalForcedLanguage: String? = null
    private var automaticForcedRequestKey: String? = null
    private val externalForcedFallbackListener: (String?) -> Unit = ::requestExternalForcedSubtitle

    init {
        startPlayback(videoType, id)
        PlaybackTrackPreferences.setExternalForcedFallbackListener(externalForcedFallbackListener)
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
                imdbId = ep.tvShow.imdbId,
                originalLanguage = ep.tvShow.originalLanguage,
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
        startPlayback(episode, episode.id)
        getSubtitles(episode)
    }

    private fun startPlayback(videoType: Video.Type, id: String) {
        subtitleSearchJob?.cancel()
        externalSubtitleDownloadJob?.cancel()
        automaticForcedDownloadJob?.cancel()
        synchronized(externalForcedLock) {
            mediaGeneration += 1
            subtitleContentGeneration += 1
            openSubtitleResults = emptyList()
            pendingExternalForcedLanguage = null
            automaticForcedRequestKey = null
        }
        val originalLanguage = originalAudioLanguage(videoType)
        PlaybackLanguageContext.setOriginalAudioLanguage(originalLanguage)
        PlaybackTrackPreferences.activate(videoType, originalLanguage)
        getServers(videoType, id)
    }

    private fun originalAudioLanguage(videoType: Video.Type): String? {
        if (!UserPreferences.enableTmdb) return null

        return when (videoType) {
            is Video.Type.Movie -> videoType.originalLanguage
            is Video.Type.Episode -> videoType.tvShow.originalLanguage
        }
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        lastVideoType = videoType
        lastId = id
        _state.emit(State.LoadingServers)
        try {
            val servers = UserPreferences.currentProvider!!.getServers(id, videoType)
            if (servers.isEmpty()) throw Exception("No servers found")

            Log.i("StreamFlixES", "[SERVERS LIST] -> Provider: ${UserPreferences.currentProvider!!.name}")
            Log.i("StreamFlixES", "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}")

            Log.d("PlayerViewModel", "Ricerca server completata: ${servers.size} server trovati")
            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    fun getVideo(server: Video.Server) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
        synchronized(externalForcedLock) {
            mediaGeneration += 1
            pendingExternalForcedLanguage = null
            automaticForcedRequestKey = null
        }
        externalSubtitleDownloadJob?.cancel()
        automaticForcedDownloadJob?.cancel()
        _state.emit(State.LoadingVideo(server))
        try {
            val video = UserPreferences.currentProvider!!.getVideo(server)
            if (video.source.isEmpty()) throw Exception("No source found")

            PlaybackTrackPreferences.activateSource(server.name)

            Log.d("PlayerViewModel", "Estrazione video completata con successo")
            _state.emit(State.SuccessLoadingVideo(video, server))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore estrazione video: ", e)
            _state.emit(State.FailedLoadingVideo(e, server))
        }
    }

    fun getSubtitles(videoType: Video.Type) {
        subtitleSearchJob?.cancel()
        val contentGeneration = synchronized(externalForcedLock) { subtitleContentGeneration }
        subtitleSearchJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
            _subtitleState.emit(SubtitleState.Loading)

            val rawImdbId = when (videoType) {
                is Video.Type.Movie -> videoType.imdbId
                is Video.Type.Episode -> videoType.tvShow.imdbId
            }
            val imdbId = rawImdbId.takeIf { OpenSubtitles.normalizeImdbId(it) != null }
            val title = when (videoType) {
                is Video.Type.Movie -> videoType.title
                is Video.Type.Episode -> videoType.tvShow.title
            }
            val releaseYear = when (videoType) {
                is Video.Type.Movie -> videoType.releaseDate
                is Video.Type.Episode -> videoType.tvShow.releaseDate
            }?.take(4)?.toIntOrNull()

            launch {
                try {
                    Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                    val subtitles = when (videoType) {
                        is Video.Type.Episode -> {
                            OpenSubtitles.search(
                                imdbId = imdbId,
                                query = title.takeIf { imdbId == null },
                                season = videoType.season.number,
                                episode = videoType.number,
                            )
                        }
                        is Video.Type.Movie -> {
                            OpenSubtitles.search(
                                imdbId = imdbId,
                                query = title.takeIf { imdbId == null },
                            )
                        }
                    }.sortedWith(
                        compareBy<OpenSubtitles.Subtitle> { it.displayLanguage }
                            .thenByDescending { it.score ?: 0.0 }
                            .thenBy { it.displayRelease }
                    )

                    if (contentGeneration != synchronized(externalForcedLock) { subtitleContentGeneration }) {
                        return@launch
                    }
                    synchronized(externalForcedLock) {
                        openSubtitleResults = subtitles
                    }
                    maybeDownloadExternalForcedSubtitle()

                    val displaySubtitles = OpenSubtitles.displayResults(subtitles)
                    Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                    _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(displaySubtitles))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (contentGeneration != synchronized(externalForcedLock) { subtitleContentGeneration }) {
                        return@launch
                    }
                    Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                    _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
                }
            }

            launch {
                if (UserPreferences.subdlApiKey.isEmpty()) {
                    if (contentGeneration == synchronized(externalForcedLock) { subtitleContentGeneration }) {
                        _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(emptyList()))
                    }
                    return@launch
                }

                try {
                    Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                    val subtitles = when (videoType) {
                        is Video.Type.Episode -> {
                            SubDL.search(
                                imdbId = imdbId,
                                filmName = title.takeIf { imdbId == null },
                                seasonNumber = videoType.season.number,
                                episodeNumber = videoType.number,
                                type = "tv",
                                year = releaseYear,
                            )
                        }
                        is Video.Type.Movie -> {
                            SubDL.search(
                                imdbId = imdbId,
                                filmName = title.takeIf { imdbId == null },
                                type = "movie",
                                year = releaseYear,
                            )
                        }
                    }.sortedWith(
                        compareBy<SubDL.Subtitle> { it.displayLanguage }
                            .thenBy { it.displayRelease }
                    )

                    if (contentGeneration != synchronized(externalForcedLock) { subtitleContentGeneration }) {
                        return@launch
                    }
                    val displaySubtitles = SubDL.displayResults(subtitles)
                    Log.d("PlayerViewModel", "Ricerca SubDL completata: ${subtitles.size} risultati")
                    _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(displaySubtitles))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (contentGeneration != synchronized(externalForcedLock) { subtitleContentGeneration }) {
                        return@launch
                    }
                    Log.e("PlayerViewModel", "Errore SubDL: ", e)
                    _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
                }
            }
        }
    }

    private fun requestExternalForcedSubtitle(language: String?) {
        val changed = synchronized(externalForcedLock) {
            val changed = pendingExternalForcedLanguage != language
            pendingExternalForcedLanguage = language
            if (changed) automaticForcedRequestKey = null
            changed
        }

        if (language == null) {
            automaticForcedDownloadJob?.cancel()
            return
        }
        if (changed) automaticForcedDownloadJob?.cancel()
        maybeDownloadExternalForcedSubtitle()
    }

    private fun maybeDownloadExternalForcedSubtitle() {
        if (externalSubtitleDownloadJob?.isActive == true) return

        val subtitle = synchronized(externalForcedLock) {
            val language = pendingExternalForcedLanguage ?: return@synchronized null
            val candidate = OpenSubtitles.uniqueForcedForLanguage(
                subtitles = openSubtitleResults,
                language = language,
            ) ?: return@synchronized null
            val requestKey = "$mediaGeneration:${candidate.stableIdentity}"
            if (requestKey == automaticForcedRequestKey) return@synchronized null
            automaticForcedRequestKey = requestKey
            candidate
        } ?: return

        downloadAutomaticForcedSubtitle(subtitle)
    }

    private fun downloadAutomaticForcedSubtitle(subtitle: OpenSubtitles.Subtitle) {
        val generation = synchronized(externalForcedLock) { mediaGeneration }
        automaticForcedDownloadJob?.cancel()
        automaticForcedDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d("PlayerViewModel", "Inizio download sottotitolo Forced OpenSubtitles: ${subtitle.subFileName}")
            _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
            try {
                val uri = OpenSubtitles.download(subtitle)
                if (generation != synchronized(externalForcedLock) { mediaGeneration }) return@launch
                PlaybackTrackPreferences.registerExternalForcedSubtitle(uri)
                Log.d("PlayerViewModel", "Download Forced OpenSubtitles completato: $uri")
                _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != synchronized(externalForcedLock) { mediaGeneration }) return@launch
                synchronized(externalForcedLock) {
                    automaticForcedRequestKey = null
                }
                Log.e("PlayerViewModel", "Errore download Forced OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) {
        automaticForcedDownloadJob?.cancel()
        synchronized(externalForcedLock) {
            automaticForcedRequestKey = null
        }
        val generation = synchronized(externalForcedLock) { mediaGeneration }
        externalSubtitleDownloadJob?.cancel()
        externalSubtitleDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
            _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
            try {
                val uri = OpenSubtitles.download(subtitle)
                if (generation != synchronized(externalForcedLock) { mediaGeneration }) return@launch
                Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
                _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != synchronized(externalForcedLock) { mediaGeneration }) return@launch
                Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
                maybeDownloadExternalForcedSubtitle()
            }
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) {
        automaticForcedDownloadJob?.cancel()
        synchronized(externalForcedLock) {
            automaticForcedRequestKey = null
        }
        val generation = synchronized(externalForcedLock) { mediaGeneration }
        externalSubtitleDownloadJob?.cancel()
        externalSubtitleDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
            _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
            try {
                val uri = SubDL.download(subtitle)
                if (generation != synchronized(externalForcedLock) { mediaGeneration }) return@launch
                Log.d("PlayerViewModel", "Download SubDL completato: $uri")
                _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != synchronized(externalForcedLock) { mediaGeneration }) return@launch
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
                maybeDownloadExternalForcedSubtitle()
            }
        }
    }

    override fun onCleared() {
        PlaybackTrackPreferences.clearExternalForcedFallbackListener(externalForcedFallbackListener)
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

    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null

    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }
}
