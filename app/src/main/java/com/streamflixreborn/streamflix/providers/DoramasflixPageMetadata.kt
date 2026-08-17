package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

internal class DoramasflixPageMetadata(
    baseUrl: String,
    client: OkHttpClient,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val service = Retrofit.Builder()
        .baseUrl("${this.baseUrl}/")
        .client(client)
        .addConverterFactory(JsoupConverterFactory.create())
        .build()
        .create(PageService::class.java)

    suspend fun getDoramaSeasonNumbers(slug: String): List<Int> = try {
        parseDoramaSeasonNumbers(
            service.getPage("$baseUrl/doramas-online/${slug.removePrefix("/")}")
        )
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w(
            "DoramasflixPageMetadata",
            "Dorama season fallback failed for '$slug'",
            error,
        )
        emptyList()
    }

    suspend fun getPeople(id: String): People = try {
        val people = parsePeople(
            document = service.getPage("$baseUrl/reparto/${id.removePrefix("/")}"),
            id = id,
        )
        enrichPeople(people)
    } catch (error: HttpException) {
        throw Exception("Doramasflix actor details failed: HTTP ${error.code()}", error)
    }

    private suspend fun enrichPeople(people: People): People {
        if (!UserPreferences.enableTmdb) return people
        val tmdbId = DoramasflixPersonIdentity.tmdbId(people.id) ?: return people

        suspend fun details(language: String): TMDb3.Person.Detail? = try {
            TMDb3.People.details(personId = tmdbId, language = language)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(
                "DoramasflixPageMetadata",
                "TMDb person enrichment failed for '${people.id}' in '$language'",
                error,
            )
            null
        }

        val localized = details("es")
        val localizedName = localized?.name
            ?.trim()
            ?.takeIf(DoramasflixLogic::containsLatinLetter)
        val english = if (
            localized == null ||
            localizedName == null ||
            localized.biography.isNullOrBlank()
        ) {
            details("en")
        } else {
            null
        }
        if (localized == null && english == null) return people

        val englishName = english?.name
            ?.trim()
            ?.takeIf(DoramasflixLogic::containsLatinLetter)

        return People(
            id = people.id,
            name = localizedName ?: englishName ?: people.name,
            image = people.image
                ?: localized?.profilePath?.w500
                ?: english?.profilePath?.w500,
            biography = firstNonBlank(
                people.biography,
                localized?.biography,
                english?.biography,
            ),
            placeOfBirth = firstNonBlank(
                people.placeOfBirth,
                localized?.placeOfBirth,
                english?.placeOfBirth,
            ),
            birthday = people.birthday?.format("yyyy-MM-dd")
                ?: firstNonBlank(localized?.birthday, english?.birthday),
            deathday = people.deathday?.format("yyyy-MM-dd")
                ?: firstNonBlank(localized?.deathday, english?.deathday),
            filmography = people.filmography,
        )
    }

    private interface PageService {
        @GET
        @Headers(
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent: Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36",
        )
        suspend fun getPage(@Url url: String): Document
    }

    companion object {
        private val episodeRoutePattern = Regex("""-(\d+)x\d+$""")

        internal fun parseDoramaSeasonNumbers(document: Document): List<Int> =
            document.select("a[href]")
                .asSequence()
                .mapNotNull { link ->
                    val href = link.attr("href")
                        .substringBefore('?')
                        .substringBefore('#')
                        .trimEnd('/')
                    if (!href.contains("episodios/")) return@mapNotNull null
                    episodeRoutePattern.find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                }
                .distinct()
                .sorted()
                .toList()

        internal fun parsePeople(
            document: Document,
            id: String,
        ): People {
            val structuredPerson = jsonLd(document)
                .mapNotNull { element -> findTypedObject(element, "Person") }
                .firstOrNull()

            val name = stringValue(structuredPerson?.get("name"))
                ?: document.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: throw Exception("Doramasflix actor details could not be loaded.")

            return People(
                id = id,
                name = name,
                image = imageValue(structuredPerson?.get("image")),
                biography = stringValue(structuredPerson?.get("description")),
                placeOfBirth = placeValue(structuredPerson?.get("birthPlace"))
                    ?: labeledValue(document, "Lugar de nacimiento"),
                birthday = stringValue(structuredPerson?.get("birthDate"))
                    ?: labeledDate(document, "Cumpleaños"),
                deathday = stringValue(structuredPerson?.get("deathDate")),
                filmography = peopleFilmography(document),
            )
        }

        private fun isFilmographyHeading(heading: Element): Boolean {
            val label = heading.text().trim()
            return label.equals("Doramas", ignoreCase = true) ||
                label.startsWith("Doramas de ", ignoreCase = true) ||
                label.equals("Películas", ignoreCase = true) ||
                label.equals("Peliculas", ignoreCase = true) ||
                label.startsWith("Películas de ", ignoreCase = true) ||
                label.startsWith("Peliculas de ", ignoreCase = true) ||
                label.equals("Variedades", ignoreCase = true) ||
                label.startsWith("Variedades de ", ignoreCase = true)
        }

        private fun peopleFilmography(document: Document): List<Show> =
            document.select("h2")
                .asSequence()
                .filter(::isFilmographyHeading)
                .flatMap { heading ->
                    heading.nextElementSiblings()
                        .asSequence()
                        .takeWhile { sibling -> !sibling.tagName().equals("h2", ignoreCase = true) }
                        .flatMap { container -> container.select("a[href]").asSequence() }
                }
                .mapNotNull { link ->
                    val path = link.attr("href")
                        .substringBefore('?')
                        .trim()
                        .removePrefix("/")
                    val isDorama = path.startsWith("doramas-online/")
                    val isVariety = path.startsWith("variedades-online/")
                    val isMovie = path.startsWith("peliculas-online/")
                    if (!isDorama && !isVariety && !isMovie) return@mapNotNull null

                    val title = link.selectFirst("h3")
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: link.selectFirst("img[alt]")
                            ?.attr("alt")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    val poster = link.selectFirst("img")?.let(::elementImage)

                    when {
                        isMovie -> Movie(id = path, title = title, poster = poster)
                        else -> {
                            val appId = if (isVariety) {
                                "doramas-online/${path.substringAfter("variedades-online/")}"
                            } else {
                                path
                            }
                            TvShow(id = appId, title = title, poster = poster)
                        }
                    }
                }
                .distinctBy { show ->
                    when (show) {
                        is Movie -> "movie:${show.id}"
                        is TvShow -> "tv:${show.id}"
                    }
                }
                .toList()

        private fun jsonLd(document: Document): Sequence<JsonElement> =
            document.select("script[type=application/ld+json]")
                .asSequence()
                .mapNotNull { script ->
                    val json = script.data().ifBlank { script.html() }
                    runCatching { JsonParser.parseString(json) }.getOrNull()
                }

        private fun findTypedObject(element: JsonElement, type: String): JsonObject? {
            if (element.isJsonObject) {
                val jsonObject = element.asJsonObject
                val typeElement = jsonObject.get("@type")
                val matches = when {
                    typeElement == null || typeElement.isJsonNull -> false
                    typeElement.isJsonArray -> typeElement.asJsonArray.any { value ->
                        stringValue(value).equals(type, ignoreCase = true)
                    }
                    else -> stringValue(typeElement).equals(type, ignoreCase = true)
                }
                if (matches) return jsonObject

                return jsonObject.entrySet()
                    .asSequence()
                    .mapNotNull { (_, value) -> findTypedObject(value, type) }
                    .firstOrNull()
            }

            if (element.isJsonArray) {
                return element.asJsonArray
                    .asSequence()
                    .mapNotNull { value -> findTypedObject(value, type) }
                    .firstOrNull()
            }
            return null
        }

        private fun stringValue(element: JsonElement?): String? = when {
            element == null || element.isJsonNull -> null
            element.isJsonPrimitive -> runCatching { element.asString }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            else -> null
        }

        private fun imageValue(element: JsonElement?): String? = when {
            element == null || element.isJsonNull -> null
            element.isJsonPrimitive -> stringValue(element)
            element.isJsonArray -> element.asJsonArray.asSequence().mapNotNull(::imageValue).firstOrNull()
            element.isJsonObject -> {
                val image = element.asJsonObject
                stringValue(image.get("url")) ?: stringValue(image.get("contentUrl"))
            }
            else -> null
        }

        private fun placeValue(element: JsonElement?): String? = when {
            element == null || element.isJsonNull -> null
            element.isJsonPrimitive -> stringValue(element)
            element.isJsonObject -> {
                val place = element.asJsonObject
                stringValue(place.get("name")) ?: stringValue(place.get("address"))
            }
            else -> null
        }

        private fun labeledDate(document: Document, label: String): String? =
            labeledValue(document, label)?.let(DoramasflixLogic::normalizeDate)

        private fun labeledValue(document: Document, label: String): String? {
            val normalizedLabel = label.lowercase()
            return document.getAllElements()
                .asSequence()
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { text ->
                    val separator = text.indexOf(':')
                    if (separator <= 0) return@mapNotNull null
                    val left = text.substring(0, separator).trim().lowercase()
                    if (left != normalizedLabel) return@mapNotNull null
                    text.substring(separator + 1).trim().takeIf { it.isNotEmpty() }
                }
                .firstOrNull()
        }

        private fun firstNonBlank(vararg values: String?): String? =
            values.asSequence()
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull()

        private fun elementImage(image: Element): String? {
            val absolute = image.absUrl("src").trim()
            val raw = image.attr("src").trim()
            val value = absolute.ifEmpty { raw }.takeIf { it.isNotEmpty() } ?: return null
            return if (value.startsWith("//")) "https:$value" else value
        }
    }
}
