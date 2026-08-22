package com.streamflixreborn.streamflix.extractors

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimeloadExtractorTest {

    @Test
    fun `extracts video id from current embed route`() {
        assertEquals(
            "58rudt6xpqif",
            PrimeloadExtractor.extractVideoId("https://primeload.co/e/58rudt6xpqif"),
        )
        assertEquals(
            "58rudt6xpqif",
            PrimeloadExtractor.extractVideoId("https://primeload.co/e/58rudt6xpqif/"),
        )
    }

    @Test
    fun `rejects non Primeload and incomplete URLs`() {
        assertNull(PrimeloadExtractor.extractVideoId("https://example.com/e/58rudt6xpqif"))
        assertNull(PrimeloadExtractor.extractVideoId("https://primeload.co/e/"))
        assertNull(PrimeloadExtractor.extractVideoId("javascript:void(0)"))
    }

    @Test
    fun `player response preserves Primeload HLS request context`() {
        val video = PrimeloadExtractor.parsePlayerResponse(
            responseBody = """
                {
                  "sources": [
                    {
                      "src": "https://cdn.primeload.test/video/master.m3u8?token=signed",
                      "resolution": "1080p"
                    }
                  ]
                }
            """.trimIndent(),
            embedUrl = "https://primeload.co/e/58rudt6xpqif",
        )

        assertEquals(
            "https://cdn.primeload.test/video/master.m3u8?token=signed",
            video.source,
        )
        assertEquals("https://primeload.co", video.headers?.get("Origin"))
        assertEquals(
            "https://primeload.co/e/58rudt6xpqif",
            video.headers?.get("Referer"),
        )
        assertEquals(MimeTypes.APPLICATION_M3U8, video.type)
        assertEquals(false, video.maintainToken)
    }

    @Test
    fun `player response normalizes protocol relative HLS source`() {
        val video = PrimeloadExtractor.parsePlayerResponse(
            responseBody = """
                {
                  "sources": [
                    {"src": "//cdn.primeload.test/video/master.m3u8"}
                  ]
                }
            """.trimIndent(),
            embedUrl = "https://primeload.co/e/58rudt6xpqif",
        )

        assertEquals(
            "https://cdn.primeload.test/video/master.m3u8",
            video.source,
        )
    }
}
