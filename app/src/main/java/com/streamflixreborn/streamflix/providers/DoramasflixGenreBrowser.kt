package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.doramasflix.Content
import com.streamflixreborn.streamflix.models.doramasflix.ContentPage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

internal class DoramasflixGenreBrowser(
    private val loadMoviePage: suspend (Int) -> ContentPage,
    private val loadDoramaPage: suspend (Int, Boolean) -> ContentPage,
    private val mapMovie: (Content) -> Movie?,
    private val mapDorama: (Content) -> TvShow?,
) {

    companion object {
        private const val PAGE_SIZE = 20

        private val verifiedGenres = listOf(
            Genre("accion", "Acción"),
            Genre("aventura", "Aventura"),
            Genre("animacion", "Animación"),
            Genre("comedia", "Comedia"),
            Genre("crimen", "Crimen"),
            Genre("documental", "Documental"),
            Genre("drama", "Drama"),
            Genre("familia", "Familia"),
            Genre("fantasia", "Fantasía"),
            Genre("history", "History"),
            Genre("misterio", "Misterio"),
            Genre("music", "Music"),
            Genre("politica", "Política"),
            Genre("reality", "Reality"),
            Genre("romance", "Romance"),
            Genre("ciencia-ficcion", "Ciencia ficción"),
            Genre("soap", "Soap"),
            Genre("terror", "Terror"),
            Genre("thriller", "Thriller"),
            Genre("war", "War"),
        )
        private val verifiedById = verifiedGenres.associateBy { it.id }
    }

    private val discoveredGenres = ConcurrentHashMap<String, String>()

    val genres: List<Genre>
        get() = buildList {
            addAll(verifiedGenres)
            discoveredGenres.entries
                .asSequence()
                .filter { (id) -> id !in verifiedById }
                .sortedBy { (_, name) -> name }
                .mapTo(this) { (id, name) -> Genre(id, name) }
        }

    fun registerGenres(contents: Iterable<Content>) {
        for (content in contents) {
            for (tag in content.genres.orEmpty()) {
                val id = DoramasflixLogic.nonBlank(tag.slug) ?: continue
                val name = DoramasflixLogic.nonBlank(tag.name) ?: continue
                discoveredGenres[id] = name
            }
        }
    }

    suspend fun getShows(id: String, page: Int): List<Show> {
        if (genreName(id) == null) throw Exception("Unknown Doramasflix category: $id")

        return DoramasflixPaging.visiblePage(
            requestedPage = page,
            pageSize = PAGE_SIZE,
            identity = ::showIdentity,
        ) { sourcePage ->
            coroutineScope {
                val moviesDeferred = async { loadMoviePage(sourcePage) }
                val doramasDeferred = async { loadDoramaPage(sourcePage, false) }
                val variedadesDeferred = async { loadDoramaPage(sourcePage, true) }

                val movies = moviesDeferred.await()
                val doramas = doramasDeferred.await()
                val variedades = variedadesDeferred.await()
                registerGenres(movies.items)
                registerGenres(doramas.items)
                registerGenres(variedades.items)

                val movieShows = movies.items
                    .asSequence()
                    .filter { content -> hasGenre(content, id) }
                    .mapNotNull(mapMovie)
                    .toList()
                val doramaShows = doramas.items
                    .asSequence()
                    .filter { content -> hasGenre(content, id) }
                    .mapNotNull(mapDorama)
                    .toList()
                val varietyShows = variedades.items
                    .asSequence()
                    .filter { content -> hasGenre(content, id) }
                    .mapNotNull(mapDorama)
                    .toList()

                DoramasflixPageBatch(
                    items = interleave(doramaShows, movieShows, varietyShows),
                    hasNextPage = listOf(movies, doramas, variedades)
                        .any { it.pageInfo?.hasNextPage == true },
                    sourceSignature = sourceSignature(movies, doramas, variedades),
                )
            }
        }
    }

    fun genreName(id: String): String? =
        verifiedById[id]?.name ?: discoveredGenres[id]

    private fun hasGenre(content: Content, genreSlug: String): Boolean =
        content.genres.orEmpty().any { genre ->
            DoramasflixLogic.nonBlank(genre.slug)?.equals(genreSlug, ignoreCase = true) == true
        }

    private fun interleave(vararg groups: List<Show>): List<Show> {
        val largest = groups.maxOfOrNull { it.size } ?: return emptyList()
        val result = mutableListOf<Show>()
        val seen = mutableSetOf<String>()

        repeat(largest) { index ->
            groups.forEach { group ->
                val show = group.getOrNull(index) ?: return@forEach
                if (seen.add(showIdentity(show))) result += show
            }
        }
        return result
    }

    private fun showIdentity(show: Show): String = when (show) {
        is Movie -> "movie:${show.id}"
        is TvShow -> "tv:${show.id}"
    }

    private fun sourceSignature(
        movies: ContentPage,
        doramas: ContentPage,
        variedades: ContentPage,
    ): List<String> = buildList {
        movies.items.mapTo(this) { "movie:${it.sourceSignature()}" }
        doramas.items.mapTo(this) { "dorama:${it.sourceSignature()}" }
        variedades.items.mapTo(this) { "variety:${it.sourceSignature()}" }
    }
}
