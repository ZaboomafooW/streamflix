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

    private val summaries = ConcurrentHashMap<String, People>()

    fun remember(people: Iterable<People>) {
        people.forEach { person ->
            if (person.id.isNotBlank() && person.name.isNotBlank()) {
                summaries[person.id] = person
            }
        }
    }

    suspend fun getPeople(id: String, page: Int): People {
        if (page > 1) return People(id = id, name = "")

        val providerSummary = summaries[id]
        val tmdbId = DoramasflixPersonIdentity.tmdbId(id)
            ?: return providerSummary ?: People(id = id, name = "")
        if (!UserPreferences.enableTmdb) {
            return providerSummary ?: People(id = id, name = "")
        }

        val localized = personDetails(tmdbId, "es")
        val english = personDetails(tmdbId, "en")
        if (localized == null && english == null) {
            return providerSummary ?: People(id = id, name = "")
        }

        val localizedName = localized?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() && DoramasflixLogic.containsLatinLetter(it) }
        val englishName = english?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() && DoramasflixLogic.containsLatinLetter(it) }
        val providerName = providerSummary?.name?.trim()?.takeIf { it.isNotEmpty() }
        val name = providerName?.takeIf(DoramasflixLogic::containsLatinLetter)
            ?: localizedName
            ?: englishName
            ?: providerName
            ?: ""

        val filmography = resolveFilmography(localized, english)
        return People(
            id = id,
            name = name,
            image = providerSummary?.image
                ?: localized?.profilePath?.w500
                ?: english?.profilePath?.w500,
            biography = firstNonBlank(localized?.biography, english?.biography),
            placeOfBirth = firstNonBlank(localized?.placeOfBirth, english?.placeOfBirth),
            birthday = firstNonBlank(localized?.birthday, english?.birthday),
            deathday = firstNonBlank(localized?.deathday, english?.deathday),
            filmography = filmography,
        )
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

    private suspend fun resolveFilmography(
        localized: TMDb3.Person.Detail?,
        english: TMDb3.Person.Detail?,
    ): List<Show> = coroutineScope {
        val candidates = creditCandidates(
            localized?.combinedCredits?.cast.orEmpty(),
            english?.combinedCredits?.cast.orEmpty(),
        )
        val resolved = mutableListOf<Show>()

        for (chunk in candidates.chunked(4)) {
            val pending = chunk.map { candidate ->
                async { resolveCredit(candidate) }
            }
            pending.forEach { result ->
                result.await()?.let(resolved::add)
            }
        }

        resolved.distinctBy { show ->
            when (show) {
                is Movie -> "movie:${show.id}"
                is TvShow -> "tv:${show.id}"
            }
        }
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

    private fun firstNonBlank(vararg values: String?): String? =
        values.asSequence()
            .mapNotNull { value -> value?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()

    companion object {
        internal fun exactTmdbMatch(contents: Iterable<Content>, tmdbId: Int): Content? =
            contents.firstOrNull { content ->
                content.tmdbId?.trim()?.toIntOrNull() == tmdbId
            }
    }
}
