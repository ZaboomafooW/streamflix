package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.doramasflix.Content
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

internal class DoramasflixPeopleResolver(
    private val searchDoramas: suspend (String, Int) -> List<Content>,
    private val searchMovies: suspend (String, Int) -> List<Content>,
    private val mapMovie: (Content) -> Movie?,
    private val mapDorama: (Content) -> TvShow?,
) {

    private enum class MediaType {
        MOVIE,
        TV,
    }

    private data class CreditCandidate(
        val mediaType: MediaType,
        val tmdbId: Int,
        val titles: MutableList<String> = mutableListOf(),
    )

    private class PersonContext(
        val provider: People?,
        val localized: TMDb3.Person.Detail?,
        val english: TMDb3.Person.Detail?,
        val candidates: List<CreditCandidate>,
    ) {
        val providerFilmography = provider?.filmography.orEmpty()
        val mutex = Mutex()
        val pages = mutableListOf<List<Show>>()
        val emittedShowIds = mutableSetOf<String>()
        var nextProviderIndex = 0
        var nextCandidateIndex = 0
        var exhausted = providerFilmography.isEmpty() && candidates.isEmpty()
        var filterKey: String? = null
    }

    private val pageMetadata = DoramasflixPeoplePageMetadata()
    private val summaries = ConcurrentHashMap<String, People>()
    private val contexts = ConcurrentHashMap<String, PersonContext>()

    fun remember(people: Iterable<People>) {
        people.forEach { person ->
            if (person.id.isNotBlank() && person.name.isNotBlank()) {
                summaries[person.id] = person
            }
        }
    }

    suspend fun getPeople(id: String, page: Int): People {
        val requestedPage = page.coerceAtLeast(1)
        val providerSummary = summaries[id]
        val tmdbId = DoramasflixPersonIdentity.tmdbId(id)
        val context = getContext(
            id = id,
            tmdbId = tmdbId,
            tmdbEnabled = UserPreferences.enableTmdb,
        )
        if (context.provider == null && context.localized == null && context.english == null) {
            return providerPage(providerSummary, id, requestedPage)
        }

        val providerPageName = context.provider?.name?.trim()?.takeIf(String::isNotEmpty)
        val providerName = providerSummary?.name?.trim()?.takeIf(String::isNotEmpty)
        val localizedName = context.localized?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() && DoramasflixLogic.containsLatinLetter(it) }
        val englishName = context.english?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() && DoramasflixLogic.containsLatinLetter(it) }
        val name = providerPageName?.takeIf(DoramasflixLogic::containsLatinLetter)
            ?: providerName?.takeIf(DoramasflixLogic::containsLatinLetter)
            ?: localizedName
            ?: englishName
            ?: providerPageName
            ?: providerName
            ?: ""
        val filmography = resolveFilmographyPage(context, requestedPage)

        return People(
            id = id,
            name = name,
            image = context.provider?.image
                ?: providerSummary?.image
                ?: context.localized?.profilePath?.w500
                ?: context.english?.profilePath?.w500,
            biography = firstNonBlank(
                context.provider?.biography,
                context.localized?.biography,
                context.english?.biography,
            ),
            placeOfBirth = firstNonBlank(
                context.provider?.placeOfBirth,
                context.localized?.placeOfBirth,
                context.english?.placeOfBirth,
            ),
            birthday = firstNonBlank(
                context.provider?.birthday?.format("yyyy-MM-dd"),
                context.localized?.birthday,
                context.english?.birthday,
            ),
            deathday = firstNonBlank(
                context.provider?.deathday?.format("yyyy-MM-dd"),
                context.localized?.deathday,
                context.english?.deathday,
            ),
            filmography = filmography,
        )
    }

    private fun providerPage(providerSummary: People?, id: String, page: Int): People {
        if (page == 1) return providerSummary ?: People(id = id, name = "")
        return People(id = id, name = providerSummary?.name.orEmpty())
    }

    private suspend fun getContext(
        id: String,
        tmdbId: Int?,
        tmdbEnabled: Boolean,
    ): PersonContext {
        val key = "$id|tmdb:$tmdbEnabled"
        contexts[key]?.let { return it }

        val loaded = coroutineScope {
            val providerDeferred = async { pageMetadata.getPeople(id) }
            val localizedDeferred = async {
                if (tmdbEnabled && tmdbId != null) personDetails(tmdbId, "es") else null
            }
            val englishDeferred = async {
                if (tmdbEnabled && tmdbId != null) personDetails(tmdbId, "en") else null
            }
            val provider = providerDeferred.await()
            val localized = localizedDeferred.await()
            val english = englishDeferred.await()
            PersonContext(
                provider = provider,
                localized = localized,
                english = english,
                candidates = creditCandidates(
                    localized?.combinedCredits?.cast.orEmpty(),
                    english?.combinedCredits?.cast.orEmpty(),
                ),
            )
        }
        contexts.putIfAbsent(key, loaded)
        return contexts[key] ?: loaded
    }

    private suspend fun personDetails(id: Int, language: String): TMDb3.Person.Detail? = try {
        TMDb3.People.details(
            personId = id,
            appendToResponse = listOf(TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS),
            language = language,
        )
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    private suspend fun resolveFilmographyPage(
        context: PersonContext,
        requestedPage: Int,
    ): List<Show> {
        val currentFilterKey = filmographyFilterKey(DoramasflixContentPreferences.settings())
        context.mutex.lock()
        try {
            if (context.filterKey != currentFilterKey) {
                context.pages.clear()
                context.emittedShowIds.clear()
                context.nextProviderIndex = 0
                context.nextCandidateIndex = 0
                context.exhausted = context.providerFilmography.isEmpty() && context.candidates.isEmpty()
                context.filterKey = currentFilterKey
            }

            while (context.pages.size < requestedPage && !context.exhausted) {
                val nextPage = resolveNextFilmographyPage(context)
                if (nextPage.isEmpty()) break
                context.pages += nextPage
            }
            return context.pages.getOrNull(requestedPage - 1).orEmpty()
        } finally {
            context.mutex.unlock()
        }
    }

    private suspend fun resolveNextFilmographyPage(context: PersonContext): List<Show> {
        val resolvedPage = linkedMapOf<String, Show>()

        while (resolvedPage.isEmpty() && !context.exhausted) {
            val providerRange = candidateWindow(
                startIndex = context.nextProviderIndex,
                totalCount = context.providerFilmography.size,
            )
            if (providerRange != null) {
                val shows = context.providerFilmography.subList(
                    providerRange.first,
                    providerRange.last + 1,
                )
                context.nextProviderIndex = providerRange.last + 1
                resolveProviderWindow(shows).forEach { show ->
                    val identity = showIdentity(show)
                    if (context.emittedShowIds.add(identity)) {
                        resolvedPage[identity] = show
                    }
                }
                context.exhausted =
                    context.nextProviderIndex >= context.providerFilmography.size &&
                    context.nextCandidateIndex >= context.candidates.size
                continue
            }

            val candidateRange = candidateWindow(
                startIndex = context.nextCandidateIndex,
                totalCount = context.candidates.size,
            )
            if (candidateRange == null) {
                context.exhausted = true
                break
            }

            val candidates = context.candidates.subList(
                candidateRange.first,
                candidateRange.last + 1,
            )
            context.nextCandidateIndex = candidateRange.last + 1
            resolveCandidateWindow(candidates).forEach { show ->
                val identity = showIdentity(show)
                if (context.emittedShowIds.add(identity)) {
                    resolvedPage[identity] = show
                }
            }
            context.exhausted =
                context.nextProviderIndex >= context.providerFilmography.size &&
                context.nextCandidateIndex >= context.candidates.size
        }

        return resolvedPage.values.toList()
    }

    private suspend fun resolveProviderWindow(shows: List<Show>): List<Show> = coroutineScope {
        val resolved = mutableListOf<Show>()
        for (chunk in shows.chunked(maxConcurrentCreditLookups)) {
            val pending = chunk.map { show ->
                async { resolveProviderShow(show) }
            }
            pending.forEach { result ->
                result.await()?.let(resolved::add)
            }
        }
        resolved
    }

    private suspend fun resolveProviderShow(show: Show): Show? {
        val content = when (show) {
            is Movie -> findProviderContentByRoute(
                title = show.title,
                providerId = show.id,
                search = searchMovies,
            )
            is TvShow -> findProviderContentByRoute(
                title = show.title,
                providerId = show.id,
                search = searchDoramas,
            )
        } ?: return show

        return when (show) {
            is Movie -> mapMovie(content)
            is TvShow -> mapDorama(content)
        }
    }

    private suspend fun findProviderContentByRoute(
        title: String,
        providerId: String,
        search: suspend (String, Int) -> List<Content>,
    ): Content? {
        var page = 1
        var previousSignature: List<String>? = null

        while (true) {
            val items = search(title, page)
            exactProviderRouteMatch(items, providerId)?.let { return it }
            if (items.isEmpty()) return null

            val signature = items.map(Content::sourceSignature)
            if (signature == previousSignature) return null
            previousSignature = signature
            page++
        }
    }

    private suspend fun resolveCandidateWindow(
        candidates: List<CreditCandidate>,
    ): List<Show> = coroutineScope {
        val resolved = mutableListOf<Show>()
        for (chunk in candidates.chunked(maxConcurrentCreditLookups)) {
            val pending = chunk.map { candidate ->
                async { resolveCredit(candidate) }
            }
            pending.forEach { result ->
                result.await()?.let(resolved::add)
            }
        }
        resolved
    }

    private fun creditCandidates(
        localized: List<TMDb3.MultiItem>,
        english: List<TMDb3.MultiItem>,
    ): List<CreditCandidate> {
        val candidates = linkedMapOf<String, CreditCandidate>()

        fun add(item: TMDb3.MultiItem) {
            val mediaType: MediaType
            val tmdbId: Int
            val titles: List<String>
            when (item) {
                is TMDb3.Movie -> {
                    mediaType = MediaType.MOVIE
                    tmdbId = item.id
                    titles = listOf(item.title, item.originalTitle)
                }
                is TMDb3.Tv -> {
                    mediaType = MediaType.TV
                    tmdbId = item.id
                    titles = listOf(item.name, item.originalName)
                }
                else -> return
            }

            val key = "$mediaType:$tmdbId"
            val candidate = candidates.getOrPut(key) {
                CreditCandidate(mediaType = mediaType, tmdbId = tmdbId)
            }
            titles.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .filterNot(candidate.titles::contains)
                .forEach(candidate.titles::add)
        }

        localized.forEach(::add)
        english.forEach(::add)
        return candidates.values.toList()
    }

    private suspend fun resolveCredit(candidate: CreditCandidate): Show? {
        for (title in candidate.titles) {
            val content = when (candidate.mediaType) {
                MediaType.MOVIE -> findProviderContent(
                    title = title,
                    tmdbId = candidate.tmdbId,
                    search = searchMovies,
                )
                MediaType.TV -> findProviderContent(
                    title = title,
                    tmdbId = candidate.tmdbId,
                    search = searchDoramas,
                )
            } ?: continue

            return when (candidate.mediaType) {
                MediaType.MOVIE -> mapMovie(content)
                MediaType.TV -> mapDorama(content)
            }
        }
        return null
    }

    private suspend fun findProviderContent(
        title: String,
        tmdbId: Int,
        search: suspend (String, Int) -> List<Content>,
    ): Content? {
        var page = 1
        var previousSignature: List<String>? = null

        while (true) {
            val items = search(title, page)
            exactTmdbMatch(items, tmdbId)?.let { return it }
            if (items.isEmpty()) return null

            val signature = items.map(Content::sourceSignature)
            if (signature == previousSignature) return null
            previousSignature = signature
            page++
        }
    }

    private fun showIdentity(show: Show): String = when (show) {
        is Movie -> "movie:${show.id}"
        is TvShow -> "tv:${show.id}"
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.asSequence()
            .mapNotNull { value -> value?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()

    companion object {
        private const val filmographyCandidateWindowSize = 8
        private const val maxConcurrentCreditLookups = 4

        internal fun filmographyFilterKey(
            settings: DoramasflixContentPolicy.Settings,
        ): Int {
            var key = 0
            if (settings.showBl) key = key or 1
            if (settings.showGl) key = key or 2
            if (settings.showLgbt) key = key or 4
            if (settings.showAdult) key = key or 8
            return key
        }

        internal fun candidateWindow(
            startIndex: Int,
            totalCount: Int,
            windowSize: Int = filmographyCandidateWindowSize,
        ): IntRange? {
            if (windowSize <= 0 || totalCount <= 0 || startIndex !in 0 until totalCount) return null
            val endExclusive = minOf(startIndex + windowSize, totalCount)
            return startIndex until endExclusive
        }

        internal fun exactProviderRouteMatch(
            contents: Iterable<Content>,
            providerId: String,
        ): Content? {
            val slug = providerId
                .substringBefore('?')
                .substringBefore('#')
                .trimEnd('/')
                .substringAfterLast('/')
                .trim()
                .takeIf(String::isNotEmpty)
                ?: return null
            return contents.firstOrNull { content ->
                content.slug?.trim() == slug
            }
        }

        internal fun exactTmdbMatch(contents: Iterable<Content>, tmdbId: Int): Content? =
            contents.firstOrNull { content ->
                content.tmdbId?.trim()?.toIntOrNull() == tmdbId
            }
    }
}
