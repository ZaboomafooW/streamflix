package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.utils.TMDb3
import org.junit.Assert.assertEquals
import org.junit.Test

class DoramasflixTmdbMetadataTest {

    @Test
    fun `Doramasflix TMDb requests use Spanish locale`() {
        assertEquals("es-ES", DoramasflixTmdbMetadata.language)
    }

    @Test
    fun `Spanish artwork wins over TMDb fallback path`() {
        val images = listOf(
            image("/neutral.jpg", language = null, voteAverage = 9f),
            image("/spanish.jpg", language = "es", voteAverage = 7f),
        )

        assertEquals(
            "/spanish.jpg",
            DoramasflixTmdbMetadata.preferredImagePath(images, "/original.jpg"),
        )
    }

    @Test
    fun `localized TMDb fallback path wins when Spanish gallery artwork is unavailable`() {
        val images = listOf(
            image("/neutral.jpg", language = null, voteAverage = 9f),
        )

        assertEquals(
            "/localized-fallback.jpg",
            DoramasflixTmdbMetadata.preferredImagePath(images, "/localized-fallback.jpg"),
        )
    }

    @Test
    fun `neutral artwork is used only when Spanish and localized fallback are unavailable`() {
        val images = listOf(
            image("/neutral-low.jpg", language = null, voteAverage = 5f),
            image("/neutral-best.jpg", language = null, voteAverage = 8f),
        )

        assertEquals(
            "/neutral-best.jpg",
            DoramasflixTmdbMetadata.preferredImagePath(images, null),
        )
    }

    private fun image(
        path: String,
        language: String?,
        voteAverage: Float,
    ) = TMDb3.Images.FileImage(
        filePath = path,
        aspectRation = 1f,
        height = 100,
        width = 100,
        iso639 = language,
        voteAverage = voteAverage,
        voteCount = 1,
    )
}
