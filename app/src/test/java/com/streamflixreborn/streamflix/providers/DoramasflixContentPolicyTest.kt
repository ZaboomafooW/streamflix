package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoramasflixContentPolicyTest {

    @Test
    fun `verified Doramasflix overview markers map to semantic classes and are removed`() {
        val cases = mapOf(
            "(BL) Historia" to DoramasflixContentPolicy.Marker.BL,
            "(Anime BL) Historia" to DoramasflixContentPolicy.Marker.BL,
            "[BL] Historia" to DoramasflixContentPolicy.Marker.BL,
            "|BL| Historia" to DoramasflixContentPolicy.Marker.BL,
            "[SERIE BL] Historia" to DoramasflixContentPolicy.Marker.BL,
            "BL Historia" to DoramasflixContentPolicy.Marker.BL,
            "[🌈BL] Historia" to DoramasflixContentPolicy.Marker.BL,
            "(🌈BL) Historia" to DoramasflixContentPolicy.Marker.BL,
            "(BL🌈) Historia" to DoramasflixContentPolicy.Marker.BL,
            "🌈 (BL) Historia" to DoramasflixContentPolicy.Marker.BL,
            "[BL] 🌈 Historia" to DoramasflixContentPolicy.Marker.BL,
            "🏳️‍🌈(BL) Historia" to DoramasflixContentPolicy.Marker.BL,
            "🏳️‍🌈SERIE BL🏳️‍🌈 Historia" to DoramasflixContentPolicy.Marker.BL,
            "/BL) Historia" to DoramasflixContentPolicy.Marker.BL,
            "[🌈GL] Historia" to DoramasflixContentPolicy.Marker.GL,
            "(GL) Historia" to DoramasflixContentPolicy.Marker.GL,
            "(LGBTQ+) Historia" to DoramasflixContentPolicy.Marker.LGBT,
            "(+18) Historia" to DoramasflixContentPolicy.Marker.ADULT,
            "🔞 Historia" to DoramasflixContentPolicy.Marker.ADULT,
        )

        cases.forEach { (overview, marker) ->
            val result = DoramasflixContentPolicy.analyzeOverview(overview)
            assertEquals(overview, setOf(marker), result.markers)
            assertEquals(overview, "Historia", result.cleanedOverview)
        }
    }

    @Test
    fun `compound verified prefixes require every semantic filter`() {
        val spaced = DoramasflixContentPolicy.analyzeOverview("(+18) (BL) Historia")
        val adjacent = DoramasflixContentPolicy.analyzeOverview("(BL)(+18) Historia")
        val expected = setOf(
            DoramasflixContentPolicy.Marker.ADULT,
            DoramasflixContentPolicy.Marker.BL,
        )

        assertEquals(expected, spaced.markers)
        assertEquals(expected, adjacent.markers)
        assertEquals("Historia", spaced.cleanedOverview)
        assertEquals("Historia", adjacent.cleanedOverview)

        assertFalse(
            DoramasflixContentPolicy.allows(
                spaced.markers,
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
                spaced.markers,
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
    fun `trailing adult emoji is classified and removed from synopsis`() {
        val result = DoramasflixContentPolicy.analyzeOverview("🌈 (BL) Historia 🔞")
        assertEquals(
            setOf(
                DoramasflixContentPolicy.Marker.BL,
                DoramasflixContentPolicy.Marker.ADULT,
            ),
            result.markers,
        )
        assertEquals("Historia", result.cleanedOverview)
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
    fun `trusted semantic labels classify without inferring related markers`() {
        assertEquals(
            setOf(
                DoramasflixContentPolicy.Marker.BL,
                DoramasflixContentPolicy.Marker.LGBT,
                DoramasflixContentPolicy.Marker.ADULT,
            ),
            DoramasflixContentPolicy.markersFromLabels(
                listOf("BL", "LGBTQ+", "+18", "18+", "🔞", "+16")
            ),
        )
        assertEquals(
            setOf(DoramasflixContentPolicy.Marker.BL),
            DoramasflixContentPolicy.markersFromLabels(listOf("BL")),
        )
    }

    @Test
    fun `ordinary synopsis text is not interpreted as provider classification`() {
        val bracketed = DoramasflixContentPolicy.analyzeOverview("[Melting Me Softly] Una historia")
        assertTrue(bracketed.markers.isEmpty())
        assertEquals("[Melting Me Softly] Una historia", bracketed.cleanedOverview)

        val incidentalAge = DoramasflixContentPolicy.analyzeOverview(
            "Los personajes vuelven a encontrarse 18+ años después."
        )
        assertTrue(incidentalAge.markers.isEmpty())
        assertEquals("Los personajes vuelven a encontrarse 18+ años después.", incidentalAge.cleanedOverview)
        assertNull(DoramasflixContentPolicy.analyzeOverview(null).cleanedOverview)
    }
}
