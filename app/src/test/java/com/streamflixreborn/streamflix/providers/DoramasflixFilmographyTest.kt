package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixFilmographyTest {

    @Test
    fun `people filmography follows current actor section headings`() {
        val document = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">
                    {"@type":"Person","name":"Test Person"}
                  </script>
                </head><body>
                  <h2>Doramas de Test Person</h2>
                  <p>Actor description before the title grid.</p>
                  <div class="grid">
                    <a href="/doramas-online/first-show">
                      <img src="https://example.com/first.jpg" alt="First Show">
                    </a>
                    <a href="/doramas-online/second-show">
                      <img src="https://example.com/second.jpg">
                      <h3>Second Show</h3>
                    </a>
                  </div>
                  <h2>Peliculas de Test Person</h2>
                  <p>Movie description before the title grid.</p>
                  <div class="grid">
                    <a href="/peliculas-online/example-film">
                      <img src="https://example.com/film.jpg" alt="Example Film">
                    </a>
                  </div>
                  <h2>Popular</h2>
                  <div class="grid">
                    <a href="/doramas-online/unrelated-title">
                      <img src="https://example.com/unrelated.jpg" alt="Unrelated Title">
                    </a>
                  </div>
                </body></html>
            """.trimIndent(),
        )

        val people = DoramasflixPageMetadata.parsePeople(
            document = document,
            id = "test-person",
        )

        assertEquals(3, people.filmography.size)

        assertTrue(people.filmography[0] is TvShow)
        val firstShow = people.filmography[0] as TvShow
        assertEquals("doramas-online/first-show", firstShow.id)
        assertEquals("First Show", firstShow.title)

        assertTrue(people.filmography[1] is TvShow)
        val secondShow = people.filmography[1] as TvShow
        assertEquals("doramas-online/second-show", secondShow.id)
        assertEquals("Second Show", secondShow.title)

        assertTrue(people.filmography[2] is Movie)
        val movie = people.filmography[2] as Movie
        assertEquals("peliculas-online/example-film", movie.id)
        assertEquals("Example Film", movie.title)
    }
}
