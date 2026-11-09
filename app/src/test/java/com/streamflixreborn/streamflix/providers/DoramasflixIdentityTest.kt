package com.streamflixreborn.streamflix.providers

import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.doramasflix.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoramasflixIdentityTest {

    private val gson = Gson()

    @Test
    fun `provider records with same tmdb id remain distinct`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "paginationDorama": {
                      "items": [
                        {"_id":"provider-a","slug":"first","tmdb_id":"100"},
                        {"_id":"provider-b","slug":"second","tmdb_id":"100"}
                      ]
                    }
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        assertEquals(2, response.data?.paginationDorama?.items.orEmpty().size)
    }

    @Test
    fun `duplicate provider id remains deduplicated`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "paginationMovie": {
                      "items": [
                        {"_id":"same-provider-id","slug":"first"},
                        {"_id":"same-provider-id","slug":"second"}
                      ]
                    }
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        val items = response.data?.paginationMovie?.items.orEmpty()
        assertEquals(1, items.size)
        assertEquals("first", items.single().slug)
    }

    @Test
    fun `duplicate provider route remains deduplicated`() {
        val response = gson.fromJson(
            """
                {
                  "data": {
                    "paginationMovie": {
                      "items": [
                        {"_id":"provider-a","slug":"same-route","name":"First"},
                        {"_id":"provider-b","slug":"same-route","name":"Second"}
                      ]
                    }
                  }
                }
            """.trimIndent(),
            ApiResponse::class.java,
        )

        val items = response.data?.paginationMovie?.items.orEmpty()
        assertEquals(1, items.size)
        assertEquals("provider-a", items.single().id)
    }

    @Test
    fun `Doramasflix actor slug exposes numeric supplemental tmdb id`() {
        assertEquals(1599859, DoramasflixPersonIdentity.tmdbId("1599859-guo-junchen"))
        assertEquals(1251581, DoramasflixPersonIdentity.tmdbId("1251581-kim-soo-hyun"))
        assertEquals(42, DoramasflixPersonIdentity.tmdbId("42"))
        assertNull(DoramasflixPersonIdentity.tmdbId("guo-junchen"))
        assertNull(DoramasflixPersonIdentity.tmdbId("1599859-"))
        assertNull(DoramasflixPersonIdentity.tmdbId("0-invalid"))
    }
}
