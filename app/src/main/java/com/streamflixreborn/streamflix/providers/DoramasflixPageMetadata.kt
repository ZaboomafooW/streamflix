package com.streamflixreborn.streamflix.providers

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.People
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

internal data class DoramasflixContentMetadata(
    val rating: Double? = null,
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

    suspend fun getPeople(id: String): People =
        parsePeople(
            document = service.getPage("$baseUrl/reparto/${id.removePrefix("/")}"),
            id = id,
        )

    private interface PageService {
        @GET
        @Headers(
            "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent: Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36",
        )
        suspend fun getPage(@Url url: String): Document
    }

    companion object {
        internal fun parseContent(document: Document): DoramasflixContentMetadata {
            val rating = document.select("script[type=application/ld+json]")
                .asSequence()
                .mapNotNull { script ->
                    val json = script.data().ifBlank { script.html() }
                    runCatching { JsonParser.parseString(json) }.getOrNull()
                }
                .mapNotNull(::findAggregateRating)
                .firstOrNull()

            return DoramasflixContentMetadata(rating = rating)
        }

        internal fun parsePeople(
            document: Document,
            id: String,
        ): People {
            val name = document.selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw Exception("Doramasflix actor details could not be loaded.")

            return People(
                id = id,
                name = name,
                birthday = labeledDate(document, "Cumpleaños"),
                placeOfBirth = labeledValue(document, "Lugar de nacimiento"),
            )
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
                }
                .firstOrNull()
        }
    }
}
