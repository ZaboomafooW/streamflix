package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DoramasflixPeoplePageMetadataTest {

    @Test
    fun `actor page keeps provider biography details and canonical filmography routes`() {
        val document = Jsoup.parse(
            """
                <html>
                  <head>
                    <script type="application/ld+json">
                      {
                        "@context": "https://schema.org",
                        "@type": "Person",
                        "name": "Chae Jong-hyeop",
                        "image": "https://image.tmdb.org/t/p/w500/chae.jpg",
                        "description": "Biografía de proveedor.",
                        "birthDate": "1993-05-19",
                        "birthPlace": {"@type":"Place","name":"Seúl - Corea del sur"}
                      }
                    </script>
                  </head>
                  <body>
                    <h1>Chae Jong-hyeop</h1>
                    <h2>Doramas de Chae Jong-hyeop</h2>
                    <div>
                      <a href="/doramas-online/in-your-radiant-season">
                        <img src="/images/radiant.jpg" alt="In Your Radiant Season">
                        <h3>In Your Radiant Season</h3>
                      </a>
                      <a href="/variedades-online/chef-of-antarctic">
                        <img src="/images/chef.jpg" alt="Chef of Antarctic">
                        <h3>Chef of Antarctic</h3>
                      </a>
                    </div>
                    <h2>Películas de Chae Jong-hyeop</h2>
                    <div>
                      <a href="/peliculas-online/sample-movie">
                        <img src="/images/movie.jpg" alt="Sample Movie">
                        <h3>Sample Movie</h3>
                      </a>
                    </div>
                  </body>
                </html>
            """.trimIndent(),
            "https://doramasflix.in/",
        )

        val people = DoramasflixPeoplePageMetadata.parsePeople(
            document = document,
            id = "2934419-chae-jong-hyeop",
        )

        assertEquals("Chae Jong-hyeop", people.name)
        assertEquals("Biografía de proveedor.", people.biography)
        assertEquals("Seúl - Corea del sur", people.placeOfBirth)
        assertNotNull(people.birthday)
        assertEquals(3, people.filmography.size)

        val dorama = people.filmography[0] as TvShow
        assertEquals("doramas-online/in-your-radiant-season", dorama.id)
        assertEquals("In Your Radiant Season", dorama.title)
        assertEquals("https://doramasflix.in/images/radiant.jpg", dorama.poster)

        val variety = people.filmography[1] as TvShow
        assertEquals("doramas-online/chef-of-antarctic", variety.id)

        val movie = people.filmography[2] as Movie
        assertEquals("peliculas-online/sample-movie", movie.id)
    }
}
