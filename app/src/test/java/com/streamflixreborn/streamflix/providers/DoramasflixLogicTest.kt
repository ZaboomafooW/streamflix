package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.doramasflix.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoramasflixLogicTest {

    @Test
    fun `availability removes only episodes explicitly known to have no links`() {
        val episodes = listOf(
            episode("one"),
            episode("two"),
            episode("three"),
            episode("four"),
        )

        val filtered = DoramasflixLogic.filterAvailableEpisodes(
            episodes = episodes,
            availabilityBySlug = mapOf(
                "one" to 3,
                "two" to 0,
                "three" to null,
            ),
        )

        assertEquals(listOf("one", "four"), filtered.map { it.slug })
    }

    @Test
    fun `missing availability data preserves the canonical episode list`() {
        val episodes = listOf(episode("one"), episode("two"))

        assertEquals(
            episodes,
            DoramasflixLogic.filterAvailableEpisodes(
                episodes = episodes,
                availabilityBySlug = emptyMap(),
            ),
        )
    }

    @Test
    fun `one shared still across a season is treated as non episode specific`() {
        val episodes = listOf(
            episode("one", "/shared.jpg"),
            episode("two", "/shared.jpg"),
            episode("three", "/shared.jpg"),
        )

        assertEquals("/shared.jpg", DoramasflixLogic.sharedStillPath(episodes))
    }

    @Test
    fun `different episode stills are preserved`() {
        val episodes = listOf(
            episode("one", "/one.jpg"),
            episode("two", "/two.jpg"),
        )

        assertNull(DoramasflixLogic.sharedStillPath(episodes))
    }

    @Test
    fun `protocol relative playback URLs are normalized to https`() {
        assertEquals(
            "https://ok.ru/videoembed/123",
            DoramasflixLogic.normalizePlaybackTarget("//ok.ru/videoembed/123"),
        )
    }

    @Test
    fun `non http playback targets are rejected`() {
        assertNull(DoramasflixLogic.normalizePlaybackTarget("javascript:void(0)"))
    }

    private fun episode(slug: String, stillPath: String? = null) = Episode(
        id = slug,
        slug = slug,
        stillPath = stillPath,
    )
}
