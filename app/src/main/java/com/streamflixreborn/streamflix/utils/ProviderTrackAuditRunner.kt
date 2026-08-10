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
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
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
import java.util.Locale

/** Temporary TV validation harness for automatically sampling a provider's home titles. */
object ProviderTrackAuditRunner {

    private const val MAX_TITLES = 10
    private const val EXTRACTION_TIMEOUT_MS = 20_000L
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
        if (provider is IptvProvider) return
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
        val items = topAuditableItems(categories)
        if (items.isEmpty()) {
            completedThisSession += provider.name
            runningProvider = null
            _progress.value = Progress(
                provider = provider.name,
                completed = 0,
                total = 0,
                title = "No movie/show titles on this home feed",
                phase = "Nothing to audit",
                failures = 0,
                finished = true,
            )
            return
        }

        var failures = 0
        val seenServerNames = mutableSetOf<String>()

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

                val videoType = runCatching {
                    withContext(Dispatchers.IO) { resolveVideoType(provider, item) }
                }.getOrNull()

                if (videoType == null) {
                    failures++
                    return@forEachIndexed
                }

                TrackAuditLogger.beginContent(videoType, originalLanguage(videoType))

                _progress.value = _progress.value?.copy(phase = "Finding servers")
                val servers = withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
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
                val serversToProbe = selectServersForProbe(servers, seenServerNames)

                for ((serverIndex, server) in serversToProbe.withIndex()) {
                    _progress.value = _progress.value?.copy(
                        phase = "Checking ${server.name} (${serverIndex + 1}/${serversToProbe.size})",
                    )
                    TrackAuditLogger.beginServer(server)

                    val videoResult = runCatching {
                        withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
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
                    val loaded = probeMedia3Tracks(context, video, server)
                    if (!loaded) failures++
                    seenServerNames += server.name.lowercase(Locale.ROOT)
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
                title = "Provider audit complete",
                phase = "Done",
                failures = failures,
                finished = true,
            )
        } finally {
            runningProvider = null
            job = null
        }
    }

    private fun topAuditableItems(categories: List<Category>): List<AppAdapter.Item> {
        val orderedCategories = categories.sortedBy { category ->
            when (category.name) {
                Category.FEATURED -> 0
                Category.CONTINUE_WATCHING,
                Category.FAVORITE_MOVIES,
                Category.FAVORITE_TV_SHOWS -> 2
                else -> 1
            }
        }

        return orderedCategories
            .asSequence()
            .filterNot { category ->
                category.name == Category.CONTINUE_WATCHING ||
                    category.name == Category.FAVORITE_MOVIES ||
                    category.name == Category.FAVORITE_TV_SHOWS
            }
            .flatMap { it.list.asSequence() }
            .filter { it is Movie || it is TvShow }
            .distinctBy(::itemKey)
            .take(MAX_TITLES)
            .toList()
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

    private fun selectServersForProbe(
        servers: List<Video.Server>,
        seenServerNames: Set<String>,
    ): List<Video.Server> {
        val first = servers.first()
        val unseen = servers.firstOrNull { server ->
            server.name.lowercase(Locale.ROOT) !in seenServerNames && server.id != first.id
        }
        val fallback = if (unseen == null) servers.getOrNull(1) else null
        return listOfNotNull(first, unseen ?: fallback).distinctBy { it.id }.take(2)
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

    private fun itemKey(item: AppAdapter.Item): String = when (item) {
        is Movie -> "movie:${item.id}"
        is TvShow -> "tv:${item.id}"
        else -> item.toString()
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
