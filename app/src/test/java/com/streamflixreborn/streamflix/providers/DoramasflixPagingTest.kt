package com.streamflixreborn.streamflix.providers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DoramasflixPagingTest {

    @Test
    fun `nonempty paging skips filtered empty source pages`() = runBlocking {
        val loadedPages = mutableListOf<Int>()
        val result = DoramasflixPaging.visiblePage(requestedPage = 2) { sourcePage ->
            loadedPages += sourcePage
            when (sourcePage) {
                1 -> DoramasflixPageBatch(
                    items = emptyList(),
                    hasNextPage = true,
                    sourceSignature = listOf("hidden-1"),
                )
                2 -> DoramasflixPageBatch(
                    items = listOf("visible-1"),
                    hasNextPage = true,
                    sourceSignature = listOf("visible-1"),
                )
                3 -> DoramasflixPageBatch(
                    items = emptyList(),
                    hasNextPage = true,
                    sourceSignature = listOf("hidden-2"),
                )
                else -> DoramasflixPageBatch(
                    items = listOf("visible-2"),
                    hasNextPage = false,
                    sourceSignature = listOf("visible-2"),
                )
            }
        }

        assertEquals(listOf("visible-2"), result)
        assertEquals(listOf(1, 2, 3, 4), loadedPages)
    }

    @Test
    fun `fixed size paging accumulates filtered unique items across source pages`() = runBlocking {
        val loadedPages = mutableListOf<Int>()
        val result = DoramasflixPaging.visiblePage(
            requestedPage = 2,
            pageSize = 2,
            identity = { value: String -> value },
        ) { sourcePage ->
            loadedPages += sourcePage
            when (sourcePage) {
                1 -> DoramasflixPageBatch(
                    items = emptyList(),
                    hasNextPage = true,
                    sourceSignature = listOf("hidden"),
                )
                2 -> DoramasflixPageBatch(
                    items = listOf("a", "b"),
                    hasNextPage = true,
                    sourceSignature = listOf("a", "b"),
                )
                3 -> DoramasflixPageBatch(
                    items = listOf("b", "c"),
                    hasNextPage = true,
                    sourceSignature = listOf("b", "c"),
                )
                else -> DoramasflixPageBatch(
                    items = listOf("d"),
                    hasNextPage = false,
                    sourceSignature = listOf("d"),
                )
            }
        }

        assertEquals(listOf("c", "d"), result)
        assertEquals(listOf(1, 2, 3, 4), loadedPages)
    }

    @Test
    fun `paging stops when the provider repeats the same raw page`() = runBlocking {
        var loadCount = 0
        val result = DoramasflixPaging.visiblePage(requestedPage = 2) {
            loadCount++
            DoramasflixPageBatch(
                items = emptyList<String>(),
                hasNextPage = true,
                sourceSignature = listOf("same-source-page"),
            )
        }

        assertEquals(emptyList<String>(), result)
        assertEquals(2, loadCount)
    }

    @Test
    fun `fixed size paging returns partial first page when source is exhausted`() = runBlocking {
        val result = DoramasflixPaging.visiblePage(
            requestedPage = 1,
            pageSize = 2,
            identity = { value: String -> value },
        ) {
            DoramasflixPageBatch(
                items = listOf("only"),
                hasNextPage = false,
                sourceSignature = listOf("only"),
            )
        }

        assertEquals(listOf("only"), result)
    }
}
