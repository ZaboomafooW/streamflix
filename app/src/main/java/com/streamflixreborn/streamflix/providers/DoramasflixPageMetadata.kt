package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.People
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
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
