package com.streamflixreborn.streamflix.providers

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
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

internal data class DoramasflixContentMetadata(
    val title: String? = null,
    val rating: Double? = null,
    val overview: String? = null,
    val image: String? = null,
    val released: String? = null,
    val runtime: Int? = null,
    val trailer: String? = null,
    val imdbId: String? = null,
)

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

    suspend fun getOptionalContent(path: String): DoramasflixContentMetadata = try {
        parseContent(service.getPage("$baseUrl/${path.removePrefix("/")}"))
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        DoramasflixContentMetadata()
    }

    suspend fun getPeople(id: String): People = try {
        parsePeople(
            document = service.getPage("$baseUrl/reparto/${id.removePrefix("/")}"),
            id = id,
        )
    } catch (error: HttpException) {
        throw Exception("Doramasflix actor details failed: HTTP ${error.code()}", error)
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
        private val imdbIdPattern = Regex("(?:imdb\\.com/title/)?(tt\\d{5,12})", RegexOption.IGNORE_CASE)

        internal fun parseContent(document: Document): DoramasflixContentMetadata {
            val structuredContent = jsonLd(document)
                .mapNotNull(::findStructuredContent)
                .firstOrNull()
            val title = stringValue(structuredContent?.get("name"))
                ?: document.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: metaContent(document, "meta[property=og:title]")
            val rating = jsonLd(document)
                .mapNotNull(::findAggregateRating)
                .firstOrNull()
                ?: visibleRating(document)
            val overview = stringValue(structuredContent?.get("description"))
                ?: metaContent(document, "meta[property=og:description]")
                ?: metaContent(document, "meta[name=description]")
                ?: visibleOverview(document)
            val image = imageValue(structuredContent?.get("image"))
                ?: metaContent(document, "meta[property=og:image]")
                ?: metaContent(document, "meta[name=twitter:image]")
            val released = sequenceOf(
                stringValue(structuredContent?.get("datePublished")),
                stringValue(structuredContent?.get("startDate")),
                stringValue(structuredContent?.get("dateCreated")),
                document.selectFirst("time[datetime]")?.attr("datetime"),
                labeledValue(document, "Estreno"),
            ).mapNotNull(DoramasflixLogic::normalizeDate)
                .firstOrNull()
            val runtime = durationMinutes(structuredContent?.get("duration"))
                ?: visibleRuntime(document)
            val trailer = videoValue(structuredContent?.get("trailer"))
                ?: videoValue(structuredContent?.get("video"))
                ?: metaContent(document, "meta[property=og:video]")
                ?: metaContent(document, "meta[property=og:video:url]")
            val imdbId = findImdbId(structuredContent)
                ?: document.select("a[href*=imdb.com/title/]")
                    .asSequence()
                    .mapNotNull { link -> imdbIdFrom(link.attr("href")) }
                    .firstOrNull()

            return DoramasflixContentMetadata(
                title = title,
                rating = rating,
                overview = overview,
                image = image,
                released = released,
                runtime = runtime,
                trailer = trailer,
                imdbId = imdbId,
            )
        }

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

                    val title = link.selectFirst("img[alt]")
                        ?.attr("alt")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: link.selectFirst("h3")
                            ?.text()
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    val poster = link.selectFirst("img")
                        ?.let(::elementImage)

                    when {
                        isMovie -> Movie(
                            id = path,
                            title = title,
                            poster = poster,
                        )
                        else -> {
                            val id = if (isVariety) {
                                "doramas-online/${path.substringAfter("variedades-online/")}"
                            } else {
                                path
                            }
                            TvShow(
                                id = id,
                                title = title,
                                poster = poster,
                            )
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

        private fun visibleRating(document: Document): Double? {
            val title = document.selectFirst("h1") ?: return null
            val elements = document.getAllElements()
            val titleIndex = elements.indexOf(title)
            if (titleIndex < 0) return null

            return elements
                .asSequence()
                .drop(titleIndex + 1)
                .take(40)
                .map { element -> element.ownText().trim() }
                .filter { text -> text.matches(Regex("""\d(?:\.\d+)?""")) }
                .mapNotNull(String::toDoubleOrNull)
                .firstOrNull { value -> value > 0.0 && value <= 5.0 }
        }

        private fun visibleOverview(document: Document): String? {
            val title = document.selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val exactPattern = title?.let {
                Regex(
                    "^Ver\\s+${Regex.escape(it)}\\s+online:\\s*(.+)$",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                )
            }
            val genericPattern = Regex(
                "^Ver\\s+.+?\\s+online:\\s*(.+)$",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )

            return document.getElementsContainingOwnText(" online:")
                .asSequence()
                .map { element -> element.text().trim() }
                .mapNotNull { text ->
                    val match = exactPattern?.matchEntire(text)
                        ?: genericPattern.matchEntire(text)
                    match
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
                .firstOrNull()
        }

        private fun visibleRuntime(document: Document): Int? {
            val hourMinute = Regex(
                "(?:(\\d{1,2})\\s*h\\s*)?(\\d{1,3})\\s*min(?:/ep)?",
                RegexOption.IGNORE_CASE,
            )

            return document.getAllElements()
                .asSequence()
                .map { element -> element.ownText().trim() }
                .filter(String::isNotEmpty)
                .mapNotNull { text ->
                    val match = hourMinute.find(text) ?: return@mapNotNull null
                    val hours = match.groupValues[1].toIntOrNull() ?: 0
                    val minutes = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    (hours * 60 + minutes).takeIf { it > 0 }
                }
                .firstOrNull()
        }

        private fun durationMinutes(element: JsonElement?): Int? {
            val value = stringValue(element) ?: return null
            val iso = Regex(
                "^P(?:\\d+D)?T(?:(\\d+)H)?(?:(\\d+)M)?(?:(?:\\d+(?:\\.\\d+)?)S)?$",
                RegexOption.IGNORE_CASE,
            ).matchEntire(value) ?: return null
            val hours = iso.groupValues[1].toIntOrNull() ?: 0
            val minutes = iso.groupValues[2].toIntOrNull() ?: 0
            return (hours * 60 + minutes).takeIf { it > 0 }
        }

        private fun videoValue(element: JsonElement?): String? = when {
            element == null || element.isJsonNull -> null
            element.isJsonObject -> {
                val video = element.asJsonObject
                stringValue(video.get("embedUrl"))
                    ?: stringValue(video.get("contentUrl"))
                    ?: stringValue(video.get("url"))
            }
            element.isJsonArray -> element.asJsonArray.asSequence().mapNotNull(::videoValue).firstOrNull()
            else -> stringValue(element)
        }

        private fun elementImage(image: Element): String? {
            val absolute = image.absUrl("src").trim()
            val raw = image.attr("src").trim()
            val value = absolute.ifEmpty { raw }.takeIf { it.isNotEmpty() } ?: return null
            return when {
                value.startsWith("//") -> "https:$value"
                else -> value
            }
        }

        private fun metaContent(
            document: Document,
            selector: String,
        ): String? = document.selectFirst(selector)
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        private fun jsonLd(document: Document): Sequence<JsonElement> =
            document.select("script[type=application/ld+json]")
                .asSequence()
                .mapNotNull { script ->
                    val json = script.data().ifBlank { script.html() }
                    runCatching { JsonParser.parseString(json) }.getOrNull()
                }

        private fun findStructuredContent(element: JsonElement): JsonObject? {
            val supportedTypes = setOf("Movie", "TVSeries", "Episode", "TVEpisode", "CreativeWorkSeries")
            if (element.isJsonObject) {
                val jsonObject = element.asJsonObject
                val typeElement = jsonObject.get("@type")
                val matches = when {
                    typeElement == null || typeElement.isJsonNull -> false
                    typeElement.isJsonArray -> typeElement.asJsonArray.any { value -> stringValue(value) in supportedTypes }
                    else -> stringValue(typeElement) in supportedTypes
                }
                if (matches) return jsonObject
                return jsonObject.entrySet().asSequence()
                    .mapNotNull { (_, value) -> findStructuredContent(value) }
                    .firstOrNull()
            }
            if (element.isJsonArray) {
                return element.asJsonArray.asSequence()
                    .mapNotNull(::findStructuredContent)
                    .firstOrNull()
            }
            return null
        }

        private fun findTypedObject(
            element: JsonElement,
            type: String,
        ): JsonObject? {
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

        private fun findAggregateRating(element: JsonElement): Double? {
            if (element.isJsonObject) {
                val jsonObject = element.asJsonObject
                val aggregateElement = jsonObject.get("aggregateRating")
                val aggregateRating = if (aggregateElement?.isJsonObject == true) {
                    aggregateElement.asJsonObject
                } else {
                    null
                }

                if (aggregateRating != null) {
                    val ratingValue = aggregateRating.get("ratingValue")
                        ?.let(::numberOrNull)
                        ?.takeIf { it > 0.0 }
                    val ratingCount = aggregateRating.get("ratingCount")
                        ?.let(::numberOrNull)
                        ?.takeIf { it > 0.0 }

                    if (ratingValue != null && ratingCount != null) {
                        return ratingValue
                    }
                }

                return jsonObject.entrySet()
                    .asSequence()
                    .mapNotNull { (_, value) -> findAggregateRating(value) }
                    .firstOrNull()
            }

            if (element.isJsonArray) {
                return element.asJsonArray
                    .asSequence()
                    .mapNotNull(::findAggregateRating)
                    .firstOrNull()
            }

            return null
        }

        private fun imageValue(element: JsonElement?): String? = when {
            element == null || element.isJsonNull -> null
            element.isJsonObject -> {
                val image = element.asJsonObject
                stringValue(image.get("url")) ?: stringValue(image.get("contentUrl"))
            }
            element.isJsonArray -> element.asJsonArray.asSequence().mapNotNull(::imageValue).firstOrNull()
            else -> stringValue(element)
        }

        private fun findImdbId(element: JsonElement?): String? {
            if (element == null || element.isJsonNull) return null
            if (!element.isJsonObject && !element.isJsonArray) {
                return stringValue(element)?.let(::imdbIdFrom)
            }
            if (element.isJsonArray) {
                return element.asJsonArray.asSequence().mapNotNull(::findImdbId).firstOrNull()
            }

            return element.asJsonObject.entrySet()
                .asSequence()
                .filter { (key, _) -> key.equals("sameAs", ignoreCase = true) || key.equals("url", ignoreCase = true) }
                .mapNotNull { (_, value) -> findImdbId(value) }
                .firstOrNull()
        }

        private fun imdbIdFrom(value: String): String? =
            imdbIdPattern.find(value)?.groupValues?.getOrNull(1)?.lowercase()

        private fun placeValue(element: JsonElement?): String? = when {
            element == null || element.isJsonNull -> null
            element.isJsonObject -> stringValue(element.asJsonObject.get("name"))
            else -> stringValue(element)
        }

        private fun stringValue(element: JsonElement?): String? =
            element
                ?.takeUnless { it.isJsonNull || it.isJsonObject || it.isJsonArray }
                ?.let { value -> runCatching { value.asString }.getOrNull() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun numberOrNull(element: JsonElement): Double? =
            runCatching { element.asDouble }.getOrNull()

        private fun labeledDate(
            document: Document,
            label: String,
        ): String? = labeledValue(document, label)
            ?.let { value -> Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b").find(value)?.value }

        private fun labeledValue(
            document: Document,
            label: String,
        ): String? {
            val pattern = Regex(
                "${Regex.escape(label)}\\s*:?\\s*(.+)",
                RegexOption.IGNORE_CASE,
            )

            return document.getElementsContainingOwnText(label)
                .asSequence()
                .mapNotNull { element ->
                    pattern.find(element.text())
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: element.nextElementSibling()
                            ?.text()
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() && !it.equals(label, ignoreCase = true) }
                }
                .firstOrNull()
        }
    }
}
