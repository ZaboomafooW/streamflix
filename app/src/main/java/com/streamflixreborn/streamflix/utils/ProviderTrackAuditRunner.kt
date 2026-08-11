package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Temporary TV validation harness for exhaustively auditing one TMDb title. */
object ProviderTrackAuditRunner {

    const val TARGET_TITLE = "Dune: Part Two"

    private const val REQUEST_TIMEOUT_MS = 20_000L
    private const val TRACK_TIMEOUT_MS = 15_000L

    data class Progress(
        val provider: String,
        val completed: Int,
        val total: Int,
        val title: String,
        val phase: String,
        val failures: Int,
        val finished: Boolean = false,
        val skipped: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private var job: Job? = null
    private var runningProvider: String? = null
    private val completedThisSession = mutableSetOf<String>()

    fun startIfNeeded(context: Context, provider: Provider, categories: List<Category>) {
        if (!isTargetProvider(provider)) return
        if (provider.name in completedThisSession) return
        if (runningProvider == provider.name && job?.isActive == true) return

        job?.cancel()
        runningProvider = provider.name
        job = scope.launch {
            auditProvider(context.applicationContext, provider, categories)
        }
    }

    fun skipCurrentProvider() {
        val providerName = runningProvider ?: return
        completedThisSession += providerName
        job?.cancel()
        job = null
        runningProvider = null
        _progress.value = _progress.value?.copy(
            phase = "Skipped",
            finished = true,
            skipped = true,
        )
    }

    fun dismissFinishedProgress() {
        if (_progress.value?.finished == true) _progress.value = null
    }

    private suspend fun auditProvider(
        context: Context,
        provider: Provider,
        categories: List<Category>,
    ) {
        _progress.value = Progress(
            provider = provider.name,
            completed = 0,
            total = 1,
            title = TARGET_TITLE,
            phase = "Searching for target",
            failures = 0,
        )

        val items = findTargetItems(provider, categories)
        if (items.isEmpty()) {
            completedThisSession += provider.name
            runningProvider = null
            _progress.value = Progress(
                provider = provider.name,
                completed = 0,
                total = 1,
                title = TARGET_TITLE,
                phase = "Target movie not found",
                failures = 1,
                finished = true,
            )
            return
        }

        var failures = 0

        try {
            items.forEachIndexed { index, item ->
                val displayTitle = itemTitle(item)
                _progress.value = Progress(
                    provider = provider.name,
                    completed = index,
                    total = items.size,
                    title = displayTitle,
                    phase = "Resolving title",
                    failures = failures,
                )

                val videoType = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    runCatching {
                        withContext(Dispatchers.IO) { resolveVideoType(provider, item) }
                    }.getOrNull()
                }

                if (videoType == null) {
                    failures++
                    return@forEachIndexed
                }

                TrackAuditLogger.beginContent(videoType, originalLanguage(videoType))

                _progress.value = _progress.value?.copy(phase = "Finding servers")
                val servers = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        provider.getServers(videoType.idForPlayback(), videoType)
                    }
                }

                if (servers.isNullOrEmpty()) {
                    failures++
                    TrackAuditLogger.recordServerFailure(
                        IllegalStateException(
                            if (servers == null) "Server lookup timed out" else "No servers found"
                        )
                    )
                    return@forEachIndexed
                }

                TrackAuditLogger.recordServers(servers)
                val serversToProbe = servers.distinctBy { it.id }

                for ((serverIndex, server) in serversToProbe.withIndex()) {
                    _progress.value = _progress.value?.copy(
                        phase = "Checking ${server.name} (${serverIndex + 1}/${serversToProbe.size})",
                    )
                    TrackAuditLogger.beginServer(server)

                    val videoResult = runCatching {
                        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                            withContext(Dispatchers.IO) { provider.getVideo(server) }
                        } ?: throw IllegalStateException("Extraction timed out")
                    }

                    if (videoResult.isFailure) {
                        failures++
                        TrackAuditLogger.recordExtractionFailure(
                            server,
                            videoResult.exceptionOrNull() ?: IllegalStateException("Unknown extraction failure"),
                        )
                        continue
                    }

                    val video = videoResult.getOrThrow()
                    TrackAuditLogger.recordExtractedVideo(video)
                    val loaded = probeMedia3Tracks(context, video, server)
                    if (!loaded) failures++
                }

                _progress.value = Progress(
                    provider = provider.name,
                    completed = index + 1,
                    total = items.size,
                    title = displayTitle,
                    phase = "Logged",
                    failures = failures,
                )
            }

            completedThisSession += provider.name
            _progress.value = Progress(
                provider = provider.name,
                completed = items.size,
                total = items.size,
                title = "$TARGET_TITLE audit complete",
                phase = "Done",
                failures = failures,
                finished = true,
            )
        } finally {
            runningProvider = null
            job = null
        }
    }

    private suspend fun findTargetItems(
        provider: Provider,
        categories: List<Category>,
    ): List<AppAdapter.Item> {
        val searchResults = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                withContext(Dispatchers.IO) { provider.search(TARGET_TITLE, page = 1) }
            }.getOrDefault(emptyList())
        }.orEmpty()

        val homeItems = categories.asSequence()
            .filterNot { isPersonalCategory(it.name) }
            .flatMap { it.list.asSequence() }

        val normalizedTarget = normalizeTitle(TARGET_TITLE)
        return (searchResults.asSequence() + homeItems)
            .filterIsInstance<Movie>()
            .filter { normalizeTitle(it.title) == normalizedTarget }
            .distinctBy { it.id }
            .take(1)
            .toList()
    }

    private fun normalizeTitle(title: String): String = buildString(title.length) {
        title.forEach { character ->
            if (character.isLetterOrDigit()) append(character.lowercaseChar())
        }
    }

    private fun isTargetProvider(provider: Provider): Boolean =
        provider is TmdbProvider &&
            provider.language.substringBefore('-').equals("en", ignoreCase = true)

    private fun isPersonalCategory(name: String): Boolean {
        val value = name.trim()
        return value.equals(Category.CONTINUE_WATCHING, ignoreCase = true) ||
            value.equals(Category.FAVORITE_MOVIES, ignoreCase = true) ||
            value.equals(Category.FAVORITE_TV_SHOWS, ignoreCase = true) ||
            value.contains("continue watching", ignoreCase = true) ||
            value.contains("favorite", ignoreCase = true)
    }

    private suspend fun resolveVideoType(provider: Provider, item: AppAdapter.Item): Video.Type? =
        when (item) {
            is Movie -> {
                val movie = runCatching { provider.getMovie(item.id) }.getOrDefault(item)
                Video.Type.Movie(
                    id = movie.id,
                    title = movie.title,
                    releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                    poster = movie.poster.orEmpty(),
                    imdbId = movie.imdbId,
                    originalLanguage = movie.originalLanguage,
                )
            }

            is TvShow -> {
                val show = runCatching { provider.getTvShow(item.id) }.getOrDefault(item)
                val season = show.seasons
                    .sortedWith(compareBy<Season> { it.number == 0 }.thenBy { it.number })
                    .firstOrNull()
                    ?: return null
                val episodes = runCatching { provider.getEpisodesBySeason(season.id) }
                    .getOrDefault(emptyList())
                val episode = episodes.sortedBy { it.number }.firstOrNull() ?: return null

                Video.Type.Episode(
                    id = episode.id,
                    number = episode.number,
                    title = episode.title,
                    poster = episode.poster,
                    overview = episode.overview,
                    tvShow = Video.Type.Episode.TvShow(
                        id = show.id,
                        title = show.title,
                        poster = show.poster,
                        banner = show.banner,
                        releaseDate = show.released?.format("yyyy-MM-dd"),
                        imdbId = show.imdbId,
                        originalLanguage = show.originalLanguage,
                    ),
                    season = Video.Type.Episode.Season(
                        number = season.number,
                        title = season.title,
                    ),
                )
            }

            else -> null
        }

    private suspend fun probeMedia3Tracks(
        context: Context,
        video: Video,
        server: Video.Server,
    ): Boolean {
        val httpFactory = OkHttpDataSource.Factory(NetworkClient.default).apply {
            setDefaultRequestProperties(
                mapOf("User-Agent" to NetworkClient.USER_AGENT) + (video.headers ?: emptyMap())
            )
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        val result = CompletableDeferred<Boolean>()

        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val hasRelevantTracks = tracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_AUDIO || group.type == C.TRACK_TYPE_TEXT
                }
                if (hasRelevantTracks && !result.isCompleted) {
                    TrackAuditLogger.recordTracks(player)
                    result.complete(true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!result.isCompleted) {
                    TrackAuditLogger.recordExtractionFailure(server, error)
                    result.complete(false)
                }
            }
        }

        return try {
            player.addListener(listener)
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(video.source)
                    .setMimeType(video.type)
                    .setSubtitleConfigurations(
                        video.subtitles.map { subtitle ->
                            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.file))
                                .setMimeType(subtitle.file.toSubtitleMimeType())
                                .setLabel(subtitle.label)
                                .setSelectionFlags(if (subtitle.default) C.SELECTION_FLAG_DEFAULT else 0)
                                .build()
                        }
                    )
                    .build()
            )
            player.prepare()
            player.playWhenReady = false

            val loaded = withTimeoutOrNull(TRACK_TIMEOUT_MS) { result.await() }
            if (loaded == null) {
                TrackAuditLogger.recordExtractionFailure(
                    server,
                    IllegalStateException("Timed out waiting for Media3 tracks"),
                )
            }
            loaded == true
        } finally {
            player.removeListener(listener)
            player.release()
        }
    }

    private fun itemTitle(item: AppAdapter.Item): String = when (item) {
        is Movie -> item.title
        is TvShow -> item.title
        else -> "Unknown"
    }

    private fun originalLanguage(videoType: Video.Type): String? = when (videoType) {
        is Video.Type.Movie -> videoType.originalLanguage
        is Video.Type.Episode -> videoType.tvShow.originalLanguage
    }

    private fun Video.Type.idForPlayback(): String = when (this) {
        is Video.Type.Movie -> id
        is Video.Type.Episode -> id
    }
}
