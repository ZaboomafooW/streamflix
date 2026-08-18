package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.doramasflix.Content
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
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
        val localized: TMDb3.Person.Detail?,
        val english: TMDb3.Person.Detail?,
        val candidates: List<CreditCandidate>,
    ) {
        val mutex = Mutex()
        val pages = mutableListOf<List<Show>>()
        val emittedShowIds = mutableSetOf<String>()
        var nextCandidateIndex = 0
        var exhausted = candidates.isEmpty()
    }

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
            ?: return providerPage(providerSummary, id, requestedPage)
        if (!UserPreferences.enableTmdb) {
            return providerPage(providerSummary, id, requestedPage)
        }

        val context = getContext(id, tmdbId)
        if (context.localized == null && context.english == null) {
            return providerPage(providerSummary, id, requestedPage)
        }

        val localizedName = context.localized?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() && DoramasflixLogic.containsLatinLetter(it) }
        val englishName = context.english?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() && DoramasflixLogic.containsLatinLetter(it) }
        val providerName = providerSummary?.name?.trim()?.takeIf { it.isNotEmpty() }
        val name = providerName?.takeIf(DoramasflixLogic::containsLatinLetter)
            ?: localizedName
            ?: englishName
            ?: providerName
            ?: ""
        val filmography = resolveFilmographyPage(context, requestedPage)

        return People(
            id = id,
            name = name,
            image = providerSummary?.image
                ?: context.localized?.profilePath?.w500
                ?: context.english?.profilePath?.w500,
            biography = firstNonBlank(context.localized?.biography, context.english?.biography),
            placeOfBirth = firstNonBlank(
                context.localized?.placeOfBirth,
                context.english?.placeOfBirth,
            ),
            birthday = firstNonBlank(context.localized?.birthday, context.english?.birthday),
            deathday = firstNonBlank(context.localized?.deathday, context.english?.deathday),
            filmography = filmography,
        )
    }

    private fun providerPage(providerSummary: People?, id: String, page: Int): People {
        if (page == 1) return providerSummary ?: People(id = id, name = "")
        return People(id = id, name = providerSummary?.name.orEmpty())
    }

    private suspend fun getContext(id: String, tmdbId: Int): PersonContext {
        contexts[id]?.let { return it }

        val localized = personDetails(tmdbId, "es")
        val english = personDetails(tmdbId, "en")
        val loaded = PersonContext(
            localized = localized,
            english = english,
            candidates = creditCandidates(
                localized?.combinedCredits?.cast.orEmpty(),
                english?.combinedCredits?.cast.orEmpty(),
            ),
        )
        contexts.putIfAbsent(id, loaded)
        return contexts[id] ?: loaded
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
        context.mutex.lock()
        try {
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
            val range = candidateWindow(
                startIndex = context.nextCandidateIndex,
                totalCount = context.candidates.size,
            )
            if (range == null) {
                context.exhausted = true
                break
            }

            val candidates = context.candidates.subList(range.first, range.last + 1)
            context.nextCandidateIndex = range.last + 1
            if (context.nextCandidateIndex >= context.candidates.size) {
                context.exhausted = true
            }

            resolveCandidateWindow(candidates).forEach { show ->
                val identity = showIdentity(show)
                if (context.emittedShowIds.add(identity)) {
                    resolvedPage[identity] = show
                }
            }
        }

        return resolvedPage.values.toList()
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

        internal fun candidateWindow(
            startIndex: Int,
            totalCount: Int,
            windowSize: Int = filmographyCandidateWindowSize,
        ): IntRange? {
            if (windowSize <= 0 || totalCount <= 0 || startIndex !in 0 until totalCount) return null
            val endExclusive = minOf(startIndex + windowSize, totalCount)
            return startIndex until endExclusive
        }

        internal fun exactTmdbMatch(contents: Iterable<Content>, tmdbId: Int): Content? =
            contents.firstOrNull { content ->
                content.tmdbId?.trim()?.toIntOrNull() == tmdbId
            }
    }
}
