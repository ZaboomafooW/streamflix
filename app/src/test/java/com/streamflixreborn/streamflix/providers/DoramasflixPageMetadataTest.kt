package com.streamflixreborn.streamflix.providers

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoramasflixPageMetadataTest {

    @Test
    fun `content page uses genuine json ld aggregate rating`() {
        val document = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">
                    {
                      "@type": "TVSeries",
                      "name": "Meeting You",
                      "aggregateRating": {
                        "@type": "AggregateRating",
                        "ratingValue": 4.142857142857143,
                        "bestRating": 5,
                        "ratingCount": 14
                      }
                    }
                  </script>
                </head><body></body></html>
            """.trimIndent(),
        )

        assertEquals(
            4.142857142857143,
            DoramasflixPageMetadata.parseContent(document).rating,
        )
    }

    @Test
    fun `content page preserves structured description and image`() {
        val document = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">
                    {
                      "@type": "TVSeries",
                      "name": "Meeting You",
                      "description": "Una descripción real del título.",
                      "image": {"url": "https://image.tmdb.org/t/p/original/meeting.jpg"}
                    }
                  </script>
                </head><body></body></html>
            """.trimIndent(),
        )

        val metadata = DoramasflixPageMetadata.parseContent(document)
        assertEquals("Una descripción real del título.", metadata.overview)
        assertEquals("https://image.tmdb.org/t/p/original/meeting.jpg", metadata.image)
    }

    @Test
    fun `content page falls back to explicit social metadata`() {
        val document = Jsoup.parse(
            """
                <html><head>
                  <meta property="og:description" content="Descripción desde la página">
                  <meta property="og:image" content="https://doramasflix.in/image.jpg">
                </head><body></body></html>
            """.trimIndent(),
        )

        val metadata = DoramasflixPageMetadata.parseContent(document)
        assertEquals("Descripción desde la página", metadata.overview)
        assertEquals("https://doramasflix.in/image.jpg", metadata.image)
    }

    @Test
    fun `structured content stays ahead of social metadata`() {
        val document = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">
                    {
                      "@type": "Movie",
                      "description": "Structured overview",
                      "image": "https://doramasflix.in/structured.jpg"
                    }
                  </script>
                  <meta property="og:description" content="OG overview">
                  <meta property="og:image" content="https://doramasflix.in/og.jpg">
                </head><body></body></html>
            """.trimIndent(),
        )

        val metadata = DoramasflixPageMetada.parseContent(document)
        assertEquals("Structured overview", metadata.overview)
        assertEquals("https://doramasflix.in/structured.jpg", metadata.image)
    }

    @Test
    fun `unrated content does not manufacture a rating`() {
        val zeroCountDocument = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">
                    {
                      "@type": "Movie",
                      "aggregateRating": {
                        "ratingValue": 4.5,
                        "ratingCount": 0
                      }
                    }
                  </script>
                </head><body></body></html>
            """.trimIndent(),
        )
        val noRatingDocument = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">
                    {"@type":"Movie","name":"Just For Meeting You"}
                  </script>
                </head><body></body></html>
            """.trimIndent(),
        )

        assertNull(DoramasflixPageMetadata.parseContent(zeroCountDocument).rating)
        assertNull(DoramasflixPageMetadata.parseContent(noRatingDocument).rating)
    }

    @Test
    fun `invalid json ld does not prevent other metadata from being inspected`() {
        val document = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">not-json</script>
                  <script type="application/ld+json">
                    {
                      "@graph": [
                        {
                          "@type": "TVSeries",
                          "aggregateRating": {
                            "ratingValue": "4.8",
                            "ratingCount": "25"
                          }
                        }
                      ]
                    }
                  </script>
                </head><body></body></html>
            """.trimIndent(),
        )

        assertEquals(4.8, DoramasflixPageMetadata.parseContent(document).rating)
    }

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
