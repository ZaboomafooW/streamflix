package com.streamflixreborn.streamflix.providers

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class DoramasflixFilmographyTest {

    @Test
    fun `people filmography uses only actor Doramasflix sections`() {
        val document = Jsoup.parse(
            """
                <html><head>
                  <script type="application/ld+json">
                    {"@type":"Person","name":"Chae Jong-hyeop"}
                  </script>
                </head><body>
                  <h2>Doramas</h2>
                  <div class="grid">
                    <a href="/doramas-online/in-your-radiant-season">
                      <img src="https://image.tmdb.org/t/p/w342/radiant.jpg" alt="In Your Radiant Season">
                    </a>
                    <a href="/doramas-online/castaway-diva">
                      <img src="https://image.tmdb.org/t/p/w342/castaway.jpg">
                      <h3>Castaway Diva</h3>
                    </a>
                  </div>
                  <h2>Peliculas</h2>
                  <div class="grid">
                    <a href="/peliculas-online/example-film">
                      <img src="https://image.tmdb.org/t/p/w342/film.jpg" alt="Example Film">
                    </a>
                  </div>
                  <h2>Popular</h2>
                  <div class="grid">
                    <a href="/doramas-online/unrelated-title">
                      <img src="https://image.tmdb.org/t/p/w342/unrelated.jpg" alt="Unrelated Title">
                    </a>
                  </div>
                </body></html>
            """.trimIndent(),
        )

        val people = DoramasflixPageMetadata.parsePeople(
            document = document,
            id = "2934419-chae-jong-hyeop",
        )

        assertEquals(3, people.filmography.size)
        assertEquals("doramas-online/in-your-radiant-season", people.filmography[0].id)
        assertEquals("In Your Radiant Season", people.filmography[0].title)
        assertEquals("doramas-online/castaway-diva", people.filmography[1].id)
        assertEquals("Castaway Diva", people.filmography[1].title)
        assertEquals("peliculas-online/example-film", people.filmography[2].id)
        assertEquals("Example Film", people.filmography[2].title)
    }
}
