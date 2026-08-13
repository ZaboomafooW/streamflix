package com.streamflixreborn.streamflix.providers

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class DoramasflixPageMetadataTest {

    @Test
    fun `cast uses Doramasflix reparto links as people identities`() {
        val document = Jsoup.parse(
            """
                <html><body>
                  <a href="/reparto/3590388-Bao-Shangen">Bao Shangen,</a>
                  <a href="https://doramasflix.in/reparto/2986719-Daniel-Zhou?from=cast">Daniel Zhou</a>
                  <a href="/reparto/3590388-Bao-Shangen">Bao Shangen</a>
                  <a href="/productoras/netflix">Netflix</a>
                </body></html>
            """.trimIndent(),
            "https://doramasflix.in/doramas-online/never-ending-summer",
        )

        val cast = DoramasflixPageMetadata.parseCast(document)

        assertEquals(
            listOf("3590388-Bao-Shangen", "2986719-Daniel-Zhou"),
            cast.map { it.id },
        )
        assertEquals(
            listOf("Bao Shangen", "Daniel Zhou"),
            cast.map { it.name },
        )
    }

    @Test
    fun `people page maps explicit Doramasflix identity fields`() {
        val document = Jsoup.parse(
            """
                <html><body>
                  <h1>Bao Shangen</h1>
                  <p>Cumpleaños: 2002-05-23</p>
                  <p>Lugar de nacimiento: Shenzhen, Guangdong Province, China</p>
                </body></html>
            """.trimIndent(),
        )

        val people = DoramasflixPageMetadata.parsePeople(
            document = document,
            id = "3590388-Bao-Shangen",
        )

        assertEquals("3590388-Bao-Shangen", people.id)
        assertEquals("Bao Shangen", people.name)
        assertEquals("2002-05-23", people.birthday?.let { calendar ->
            "%04d-%02d-%02d".format(
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
            )
        })
        assertEquals("Shenzhen, Guangdong Province, China", people.placeOfBirth)
    }
}
