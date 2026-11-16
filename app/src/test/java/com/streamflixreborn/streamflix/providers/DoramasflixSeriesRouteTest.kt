package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class DoramasflixSeriesRouteTest {

    @Test
    fun `route builds canonical provider ids`() {
        assertEquals(
            "doramas-online/sample-show",
            DoramasflixSeriesRoute.DORAMAS.id("sample-show"),
        )
        assertEquals(
            "variedades-online/sample-show",
            DoramasflixSeriesRoute.VARIEDADES.id("sample-show"),
        )
    }

    @Test
    fun `route is recovered from normalized provider id`() {
        assertEquals(
            DoramasflixSeriesRoute.VARIEDADES,
            DoramasflixSeriesRoute.fromId("variedades-online/sample-show"),
        )
        assertEquals(
            DoramasflixSeriesRoute.DORAMAS,
            DoramasflixSeriesRoute.fromId("doramas-online/sample-show"),
        )
        assertEquals(
            DoramasflixSeriesRoute.DORAMAS,
            DoramasflixSeriesRoute.fromId("sample-show"),
        )
    }
}
