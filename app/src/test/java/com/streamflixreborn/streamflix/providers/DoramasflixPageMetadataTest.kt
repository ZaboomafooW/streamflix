package com.streamflixreborn.streamflix.providers

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoramasflixPageMetadataTest {

    @Test
    fun `people page maps structured person biography and identity fields`() {
        val document = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">
                    {
                      "@context": "https://schema.org",
                      "@graph": [
                        {
                          "@type": "Person",
                          "name": "Chae Jong-hyeop",
                          "image": "https://image.tmdb.org/t/p/w500/chae.jpg",
                          "birthDate": "1993-05-19",
                          "birthPlace": "Seúl - Corea del sur",
                          "description": "Actor y modelo surcoreano.\n\nComenzó su carrera como modelo."
                        }
                      ]
                    }
                  </script>
                </head><body><h1>Fallback name</h1></body></html>
            """.trimIndent(),
        )

        val people = DoramasflixPageMetadata.parsePeople(
            document = document,
            id = "2934419-chae-jong-hyeop",
        )

        assertEquals("Chae Jong-hyeop", people.name)
        assertEquals("https://image.tmdb.org/t/p/w500/chae.jpg", people.image)
        assertEquals("Actor y modelo surcoreano.\n\nComenzó su carrera como modelo.", people.biography)
        assertEquals("Seúl - Corea del sur", people.placeOfBirth)
        assertEquals("1993-05-19", people.birthday?.let { calendar ->
            "%04d-%02d-%02d".format(
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
            )
        })
    }

    @Test
    fun `people page without structured description does not invent biography`() {
        val document = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">
                    {
                      "@type": "Person",
                      "name": "Oh Jong-hyuk",
                      "image": {"url": "https://image.tmdb.org/t/p/w500/oh.jpg"},
                      "birthDate": "1983-02-16",
                      "birthPlace": {"@type": "Place", "name": "Seoul, South Korea"}
                    }
                  </script>
                </head><body></body></html>
            """.trimIndent(),
        )

        val people = DoramasflixPageMetadata.parsePeople(
            document = document,
            id = "1591367-oh-jong-hyuk",
        )

        assertNull(people.biography)
        assertEquals("https://image.tmdb.org/t/p/w500/oh.jpg", people.image)
        assertEquals("Seoul, South Korea", people.placeOfBirth)
    }

    @Test
    fun `people page falls back to explicit Doramasflix identity fields`() {
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
