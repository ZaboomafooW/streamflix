package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixContentPolicyTest {

    @Test
    fun `recognized overview prefixes map to semantic markers and are removed`() {
        val cases = mapOf(
            "(BL) Historia" to DoramasflixContentPolicy.Marker.BL,
            "[BL] Historia" to DoramasflixContentPolicy.Marker.BL,
            "|BL| Historia" to DoramasflixContentPolicy.Marker.BL,
            "[SERIE BL] Historia" to DoramasflixContentPolicy.Marker.BL,
            "BL Historia" to DoramasflixContentPolicy.Marker.BL,
            "[🌈BL] Historia" to DoramasflixContentPolicy.Marker.BL,
            "(BL🌈) Historia" to DoramasflixContentPolicy.Marker.BL,
            "🌈 (BL) Historia" to DoramasflixContentPolicy.Marker.BL,
            "[BL] 🌈 Historia" to DoramasflixContentPolicy.Marker.BL,
            "/BL) Historia" to DoramasflixContentPolicy.Marker.BL,
            "[🌈GL] Historia" to DoramasflixContentPolicy.Marker.GL,
            "(GL) Historia" to DoramasflixContentPolicy.Marker.GL,
            "(+18) Historia" to DoramasflixContentPolicy.Marker.ADULT,
            "🔞 Historia" to DoramasflixContentPolicy.Marker.ADULT,
        )

        cases.forEach { (overview, marker) ->
            val result = DoramasflixContentPolicy.analyzeOverview(overview)
            assertEquals(setOf(marker), result.markers)
            assertEquals("Historia", result.cleanedOverview)
        }
    }

    @Test
    fun `compound prefixes require every semantic filter`() {
        val result = DoramasflixContentPolicy.analyzeOverview("(+18) (BL) Historia")
        assertEquals(
            setOf(
                DoramasflixContentPolicy.Marker.ADULT,
                DoramasflixContentPolicy.Marker.BL,
            ),
            result.markers,
        )
        assertEquals("Historia", result.cleanedOverview)

        assertFalse(
            DoramasflixContentPolicy.allows(
                result.markers,
                DoramasflixContentPolicy.Settings(
                    showBl = true,
                    showGl = false,
                    showLgbt = false,
                    showAdult = false,
                ),
            )
        )
        assertTrue(
            DoramasflixContentPolicy.allows(
                result.markers,
                DoramasflixContentPolicy.Settings(
                    showBl = true,
                    showGl = false,
                    showLgbt = false,
                    showAdult = true,
                ),
            )
        )
    }

    @Test
    fun `adult emoji is classified even when provider leaves it after synopsis text`() {
        val result = DoramasflixContentPolicy.analyzeOverview("🌈 (BL) Historia 🔞")
        assertEquals(
            setOf(
                DoramasflixContentPolicy.Marker.BL,
                DoramasflixContentPolicy.Marker.ADULT,
            ),
            result.markers,
        )
        assertEquals("Historia 🔞", result.cleanedOverview)
    }

    @Test
    fun `all recognized classified content is hidden by default`() {
        val defaults = DoramasflixContentPolicy.Settings(
            showBl = false,
            showGl = false,
            showLgbt = false,
            showAdult = false,
        )
        DoramasflixContentPolicy.Marker.entries.forEach { marker ->
            assertFalse(DoramasflixContentPolicy.allows(setOf(marker), defaults))
        }
        assertTrue(DoramasflixContentPolicy.allows(emptySet(), defaults))
    }

    @Test
    fun `exact semantic labels classify without inferring related markers`() {
        assertEquals(
            setOf(
                DoramasflixContentPolicy.Marker.BL,
                DoramasflixContentPolicy.Marker.LGBT,
                DoramasflixContentPolicy.Marker.ADULT,
            ),
            DoramasflixContentPolicy.markersFromLabels(
                listOf("BL", "LGBT", "+18", "🔞", "+16")
            ),
        )
        assertEquals(
            setOf(DoramasflixContentPolicy.Marker.BL),
            DoramasflixContentPolicy.markersFromLabels(listOf("BL")),
        )
    }

    @Test
    fun `ordinary bracketed synopsis text is not stripped`() {
        val result = DoramasflixContentPolicy.analyzeOverview("[Melting Me Softly] Una historia")
        assertTrue(result.markers.isEmpty())
        assertEquals("[Melting Me Softly] Una historia", result.cleanedOverview)
        assertNull(DoramasflixContentPolicy.analyzeOverview(null).cleanedOverview)
    }
}
