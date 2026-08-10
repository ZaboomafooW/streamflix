package com.streamflixreborn.streamflix.utils

import android.net.Uri
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.SerienStreamProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object ServerAvailability {
    private const val TAG = "ServerAvailability"

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workingServers = ConcurrentHashMap<ContentKey, List<Video.Server>>()
    private val inFlight = ConcurrentHashMap<ContentKey, Deferred<Result>>()

    fun prefetch(provider: Provider, id: String, videoType: Video.Type) {
        val key = key(provider, id, videoType)
        if (!workingServers[key].isNullOrEmpty() || inFlight.containsKey(key)) return

        scope.launch {
            runCatching {
                getWorkingServers(provider, id, videoType)
            }.onFailure {
                Log.d(TAG, "Prefetch failed for ${provider.name}/$id: ${it.message}")
            }
        }
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
        }

        val pending = inFlight[key]
        if (pending != null) return pending.await()

        val created = scope.async(start = CoroutineStart.LAZY) {
            discover(provider, id, videoType, key)
        }
        created.invokeOnCompletion {
            inFlight.remove(key, created)
        }

        val existing = inFlight.putIfAbsent(key, created)
        val deferred = existing ?: created.also { it.start() }

        if (existing != null) {
            created.cancel()
        }

        return deferred.await()
    }

    fun markAvailable(
        provider: Provider,
        id: String,
        videoType: Video.Type,
        server: Video.Server,
    ) {
        val key = key(provider, id, videoType)
        workingServers.compute(key) { _, cached ->
            val current = cached.orEmpty()
            if (current.any { sameServer(it, server) }) current else current + server
        }
    }

    fun invalidate(
        provider: Provider,
        id: String,
        videoType: Video.Type,
        server: Video.Server,
    ) {
        val key = key(provider, id, videoType)
        workingServers.computeIfPresent(key) { _, cached ->
            cached.filterNot { sameServer(it, server) }.takeIf { it.isNotEmpty() }
        }
    }

    fun cachedServers(
        provider: Provider,
        id: String,
        videoType: Video.Type,
    ): List<Video.Server> {
        return workingServers[key(provider, id, videoType)].orEmpty()
    }

    private suspend fun discover(
        provider: Provider,
        id: String,
        videoType: Video.Type,
        key: ContentKey,
    ): Result {
        val candidates = provider.getServers(id, videoType)
        if (candidates.isEmpty()) {
            workingServers.remove(key)
            return Result.Empty
        }

        if (requiresSerienStreamInteraction(provider, candidates)) {
            return Result.RequiresInteraction(candidates)
        }

        val available = mutableListOf<Video.Server>()
        for (server in candidates) {
            try {
                val video = provider.getVideo(server)
                if (video.source.isNotBlank()) {
                    available += server
                }
            } catch (e: Exception) {
                Log.d(TAG, "Unavailable ${provider.name} server ${server.name}: ${e.message}")
            }
        }

        if (available.isEmpty()) {
            workingServers.remove(key)
            return Result.Empty
        }

        val snapshot = available.toList()
        workingServers[key] = snapshot
        return Result.Available(snapshot, fromCache = false)
    }

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
