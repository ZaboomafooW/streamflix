package com.streamflixreborn.streamflix.utils

import android.net.Uri
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.SerienStreamProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object ServerAvailability {
    private const val TAG = "ServerAvailability"
    private const val MAX_CONCURRENT_VALIDATIONS = 3
    private const val RESOLVED_VIDEO_MAX_AGE_MS = 30_000L

    sealed class Result {
        data class Available(
            val servers: List<Video.Server>,
            val fromCache: Boolean,
        ) : Result()

        data class RequiresInteraction(
            val candidates: List<Video.Server>,
        ) : Result()

        data object Empty : Result()
    }

    private data class ContentKey(
        val providerName: String,
        val providerLanguage: String,
        val providerBaseUrl: String,
        val contentId: String,
        val contentType: String,
    )

    private data class DiscoverySession(
        val firstResult: CompletableDeferred<Result>,
        val job: Job,
    )

    private data class ResolvedVideoKey(
        val contentKey: ContentKey,
        val serverId: String,
        val serverName: String,
    )

    private data class ResolvedVideoEntry(
        val video: Video,
        val resolvedAtMillis: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val validationSlots = Semaphore(MAX_CONCURRENT_VALIDATIONS)
    private val workingServers = ConcurrentHashMap<ContentKey, List<Video.Server>>()
    private val serverFlows = ConcurrentHashMap<ContentKey, MutableStateFlow<List<Video.Server>>>()
    private val inFlight = ConcurrentHashMap<ContentKey, DiscoverySession>()
    private val resolvedVideos = ConcurrentHashMap<ResolvedVideoKey, ResolvedVideoEntry>()

    fun prefetch(provider: Provider, id: String, videoType: Video.Type) {
        val key = key(provider, id, videoType)
        if (!workingServers[key].isNullOrEmpty() || inFlight.containsKey(key)) return

        startDiscovery(
            provider = provider,
            id = id,
            videoType = videoType,
            key = key,
            forceRefresh = false,
        )
    }

    suspend fun getWorkingServers(
        provider: Provider,
        id: String,
        videoType: Video.Type,
        forceRefresh: Boolean = false,
    ): Result {
        val key = key(provider, id, videoType)

        if (!forceRefresh) {
            workingServers[key]
                ?.takeIf { it.isNotEmpty() }
                ?.let { return Result.Available(it, fromCache = true) }

            inFlight[key]?.let { return it.firstResult.await() }
        } else {
            inFlight.remove(key)?.job?.cancel()
        }

        return startDiscovery(
            provider = provider,
            id = id,
            videoType = videoType,
            key = key,
            forceRefresh = forceRefresh,
        ).firstResult.await()
    }

    fun observeWorkingServers(
        provider: Provider,
        id: String,
        videoType: Video.Type,
    ): StateFlow<List<Video.Server>> {
        val key = key(provider, id, videoType)
        return serverFlow(key).asStateFlow()
    }

    fun takeResolvedVideo(
        provider: Provider,
        id: String,
        videoType: Video.Type,
        server: Video.Server,
    ): Video? {
        val now = System.currentTimeMillis()
        pruneExpiredResolvedVideos(now)

        val entry = resolvedVideos.remove(resolvedVideoKey(key(provider, id, videoType), server))
            ?: return null

        return entry.video.takeIf {
            now - entry.resolvedAtMillis <= RESOLVED_VIDEO_MAX_AGE_MS
        }
    }

    fun markAvailable(
        provider: Provider,
        id: String,
        videoType: Video.Type,
        server: Video.Server,
    ) {
        val key = key(provider, id, videoType)
        val updated = workingServers.compute(key) { _, cached ->
            val current = cached.orEmpty()
            if (current.any { sameServer(it, server) }) current else current + server
        }.orEmpty()
        publishWorkingServers(key, updated)
    }

    fun invalidate(
        provider: Provider,
        id: String,
        videoType: Video.Type,
        server: Video.Server,
    ) {
        val key = key(provider, id, videoType)
        val updated = workingServers[key]
            .orEmpty()
            .filterNot { sameServer(it, server) }
        publishWorkingServers(key, updated)
        resolvedVideos.remove(resolvedVideoKey(key, server))
    }

    fun cachedServers(
        provider: Provider,
        id: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        return workingServers[key(provider, id, videoType)].orEmpty()
    }

    private fun startDiscovery(
        provider: Provider,
        id: String,
        videoType: Video.Type,
        key: ContentKey,
        forceRefresh: Boolean,
    ): DiscoverySession {
        inFlight[key]?.let { return it }

        val firstResult = CompletableDeferred<Result>()
        lateinit var created: DiscoverySession
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                discover(provider, id, videoType, key, firstResult)
            } catch (e: CancellationException) {
                if (!firstResult.isCompleted) firstResult.cancel(e)
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Discovery failed for ${provider.name}/$id: ${e.message}")
                if (!firstResult.isCompleted) firstResult.completeExceptionally(e)
            } finally {
                inFlight.remove(key, created)
            }
        }
        created = DiscoverySession(firstResult = firstResult, job = job)

        val existing = inFlight.putIfAbsent(key, created)
        if (existing != null) {
            job.cancel()
            return existing
        }

        if (forceRefresh) {
            publishWorkingServers(key, emptyList())
            clearResolvedVideos(key)
        }
        job.start()
        return created
    }

    private suspend fun discover(
        provider: Provider,
        id: String,
        videoType: Video.Type,
        key: ContentKey,
        firstResult: CompletableDeferred<Result>,
    ) {
        val candidates = provider.getServers(id, videoType)
        if (candidates.isEmpty()) {
            publishWorkingServers(key, emptyList())
            if (!firstResult.isCompleted) firstResult.complete(Result.Empty)
            return
        }

        if (requiresSerienStreamInteraction(provider, candidates)) {
            if (!firstResult.isCompleted) {
                firstResult.complete(Result.RequiresInteraction(candidates))
            }
            return
        }

        val successful = BooleanArray(candidates.size)
        val resultMutex = Mutex()

        coroutineScope {
            candidates.mapIndexed { index, server ->
                async {
                    validationSlots.withPermit {
                        try {
                            val video = provider.getVideo(server)
                            if (video.source.isBlank()) {
                                Log.d(TAG, "Unavailable ${provider.name} server ${server.name}: empty source")
                                return@withPermit
                            }

                            resultMutex.withLock {
                                if (successful[index]) return@withLock
                                successful[index] = true

                                rememberResolvedVideo(key, server, video)
                                val snapshot = candidates.filterIndexed { candidateIndex, _ ->
                                    successful[candidateIndex]
                                }
                                publishWorkingServers(key, snapshot)

                                if (!firstResult.isCompleted) {
                                    firstResult.complete(
                                        Result.Available(snapshot, fromCache = false)
                                    )
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(
                                TAG,
                                "Unavailable ${provider.name} server ${server.name}: ${e.message}"
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        if (!successful.any()) {
            publishWorkingServers(key, emptyList())
            if (!firstResult.isCompleted) firstResult.complete(Result.Empty)
        }
    }

    private fun rememberResolvedVideo(
        key: ContentKey,
        server: Video.Server,
        video: Video,
    ) {
        val now = System.currentTimeMillis()
        pruneExpiredResolvedVideos(now)
        resolvedVideos[resolvedVideoKey(key, server)] = ResolvedVideoEntry(
            video = video,
            resolvedAtMillis = now,
        )
    }

    private fun pruneExpiredResolvedVideos(now: Long) {
        resolvedVideos.forEach { (key, entry) ->
            if (now - entry.resolvedAtMillis > RESOLVED_VIDEO_MAX_AGE_MS) {
                resolvedVideos.remove(key, entry)
            }
        }
    }

    private fun clearResolvedVideos(key: ContentKey) {
        resolvedVideos.keys.forEach { resolvedKey ->
            if (resolvedKey.contentKey == key) {
                resolvedVideos.remove(resolvedKey)
            }
        }
    }

    private fun publishWorkingServers(key: ContentKey, servers: List<Video.Server>) {
        if (servers.isEmpty()) {
            workingServers.remove(key)
        } else {
            workingServers[key] = servers
        }
        serverFlow(key).value = servers
    }

    private fun serverFlow(key: ContentKey): MutableStateFlow<List<Video.Server>> {
        return serverFlows.getOrPut(key) {
            MutableStateFlow(workingServers[key].orEmpty())
        }
    }

    private fun resolvedVideoKey(
        key: ContentKey,
        server: Video.Server,
    ) = ResolvedVideoKey(
        contentKey = key,
        serverId = server.id,
        serverName = server.name,
    )

    private fun requiresSerienStreamInteraction(
        provider: Provider,
        candidates: List<Video.Server>,
    ): Boolean {
        if (provider !== SerienStreamProvider) return false

        val providerHost = runCatching { Uri.parse(provider.baseUrl).host }.getOrNull()
            ?: return false

        return candidates.any { server ->
            runCatching {
                Uri.parse(server.id).host.equals(providerHost, ignoreCase = true)
            }.getOrDefault(false)
        }
    }

    private fun key(provider: Provider, id: String, videoType: Video.Type): ContentKey {
        val type = when (videoType) {
            is Video.Type.Movie -> "movie"
            is Video.Type.Episode -> buildString {
                append("episode:")
                append(videoType.tvShow.id)
                append(':')
                append(videoType.season.number)
                append(':')
                append(videoType.number)
            }
        }

        return ContentKey(
            providerName = provider.name,
            providerLanguage = provider.language,
            providerBaseUrl = provider.baseUrl,
            contentId = id,
            contentType = type,
        )
    }

    private fun sameServer(first: Video.Server, second: Video.Server): Boolean {
        return first.id == second.id && first.name == second.name
    }
}
