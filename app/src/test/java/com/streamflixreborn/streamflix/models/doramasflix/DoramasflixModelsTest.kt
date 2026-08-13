package com.streamflixreborn.streamflix.models.doramasflix

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class DoramasflixModelsTest {

    private val gson = Gson()

    @Test
    fun `detail response keeps cast route identity and profile image`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "detailDorama": {
                      "_id": "5f999d21631e2550d18719b1",
                      "name": "Meeting You",
                      "slug": "meeting-you",
                      "cast": [
                        {
                          "name": "Guo Junchen",
                          "slug": "1599859-guo-junchen",
                          "character": "Nan Xi",
                          "profile_path": "/m6Ub6fRrw03atP60nCj4o55ojrO.jpg",
                          "ref": "5f5be9b63a7580194185697b"
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        val cast = response.data?.detailDorama?.cast.orEmpty().single()
        assertEquals("1599859-guo-junchen", cast.slug)
        assertEquals("Guo Junchen", cast.name)
        assertEquals("/m6Ub6fRrw03atP60nCj4o55ojrO.jpg", cast.profilePath)
    }

    @Test
    fun `similar title responses deserialize through shared content model`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "similarsMovies": [
                      {
                        "_id": "62e5cd19b9bdfc7e7f29f156",
                        "name": "The Roundup",
                        "name_es": "Fuerza Bruta",
                        "slug": "the-roundup",
                        "poster_path": "/poster.jpg"
                      }
                    ],
                    "similarsDoramas": [
                      {
                        "_id": "6613ed365d8b776a1d3cd4a2",
                        "name": "Lovely Runner",
                        "name_es": "Corredora encantadora",
                        "slug": "lovely-runner",
                        "poster_path": "/runner.jpg"
                      }
                    ]
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        assertEquals("the-roundup", response.data?.similarsMovies.orEmpty().single().slug)
        assertEquals("lovely-runner", response.data?.similarsDoramas.orEmpty().single().slug)
    }
}
