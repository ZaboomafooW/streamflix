package com.streamflixreborn.streamflix.models.doramasflix

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixModelsTest {

    private val gson = Gson()

    @Test
    fun `detail response keeps provider identity rating and cast navigation data`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "detailDorama": {
                      "_id": "5f999d21631e2550d18719b1",
                      "name": "Meeting You",
                      "name_es": "Reuniendome contigo",
                      "original_name": "谢谢让我遇见你",
                      "slug": "meeting-you",
                      "tmdb_id": 111762,
                      "rating": 4.142857142857143,
                      "rating_count": 14,
                      "cast": [
                        {
                          "name": "Guo Junchen",
                          "slug": "1599859-guo-junchen",
                          "profile_path": "/m6Ub6fRrw03atP60nCj4o55ojrO.jpg"
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        val detail = response.data?.detailDorama!!
        val cast = detail.cast.orEmpty().single()
        assertEquals("111762", detail.tmdbId)
        assertEquals("谢谢让我遇见你", detail.originalName)
        assertEquals(14, detail.ratingCount)
        assertEquals("1599859-guo-junchen", cast.slug)
        assertEquals("/m6Ub6fRrw03atP60nCj4o55ojrO.jpg", cast.profilePath)
    }

    @Test
    fun `non numeric provider tmdb id remains distinguishable from valid identity`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "detailMovie": {
                      "_id": "6a063522cf9003a05959be73",
                      "name": "The Roundup: No Way Out",
                      "slug": "the-roundup-no-way-out",
                      "tmdb_id": "955555-3"
                    }
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        assertEquals("955555-3", response.data?.detailMovie?.tmdbId)
    }

    @Test
    fun `episode pagination retains the continuation flag used by the provider`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "paginationEpisode": {
                      "pageInfo": {"hasNextPage": false},
                      "items": [{"_id":"ep28","slug":"meeting-you-1x28","episode_number":28}]
                    }
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        assertFalse(response.data?.paginationEpisode?.pageInfo?.hasNextPage == true)
        assertEquals(28, response.data?.paginationEpisode?.items?.single()?.episodeNumber)
    }

    @Test
    fun `content pages deserialize stable provider items without pagination metadata`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "searchFullDoramas": {
                      "items": [{"_id":"1","slug":"love-by-chance","name":"Love By Chance"}]
                    }
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        val item = response.data?.searchFullDoramas?.items.orEmpty().single()
        assertEquals("1", item.id)
        assertEquals("love-by-chance", item.slug)
    }

    @Test
    fun `playback response keeps provider language and hard subtitle descriptors`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "detailEpisode": {
                      "langs": [
                        {"name":"Mandarín","code":"zh","code_flix":"13111"}
                      ]
                    },
                    "getEpisodeLinks": {
                      "links_online": [
                        {
                          "server": "1230",
                          "lang": "13111",
                          "link": "https://example.test/embed",
                          "is_recommended": true,
                          "subtitles": [{"language_code":"es","type":"HARDSUB"}]
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        assertEquals("13111", response.data?.detailEpisode?.langs.orEmpty().single().codeFlix)
        val link = response.data?.getEpisodeLinks?.linksOnline.orEmpty().single()
        assertTrue(link.isRecommended == true)
        assertEquals("es", link.subtitles.orEmpty().single().languageCode)
        assertEquals("HARDSUB", link.subtitles.orEmpty().single().type)
    }

    @Test
    fun `similar title responses use the same stable provider identity model`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "similarsMovies": [
                      {
                        "_id": "62e5cd19b9bdfc7e7f29f156",
                        "name": "The Roundup",
                        "name_es": "Fuerza Bruta",
                        "original_name": "범죄도시 2",
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
        assertEquals("범죄도시 2", response.data?.similarsMovies.orEmpty().single().originalName)
        assertEquals("lovely-runner", response.data?.similarsDoramas.orEmpty().single().slug)
    }
}
