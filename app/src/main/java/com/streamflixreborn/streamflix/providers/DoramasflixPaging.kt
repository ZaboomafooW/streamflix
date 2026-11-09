package com.streamflixreborn.streamflix.providers

internal data class DoramasflixPageBatch<T>(
    val items: List<T>,
    val hasNextPage: Boolean,
    val sourceSignature: List<String> = emptyList(),
)

internal object DoramasflixPaging {

    suspend fun <T> visiblePage(
        requestedPage: Int,
        loadBatch: suspend (sourcePage: Int) -> DoramasflixPageBatch<T>,
    ): List<T> {
        val targetPage = requestedPage.coerceAtLeast(1)
        val seenSourcePages = mutableSetOf<List<String>>()
        var sourcePage = 1
        var visiblePage = 0

        while (true) {
            val batch = loadBatch(sourcePage)
            if (
                batch.sourceSignature.isNotEmpty() &&
                !seenSourcePages.add(batch.sourceSignature)
            ) {
                return emptyList()
            }

            if (batch.items.isNotEmpty()) {
                visiblePage++
                if (visiblePage == targetPage) return batch.items
            }

            if (!batch.hasNextPage) return emptyList()
            sourcePage++
        }
    }

    suspend fun <T> visiblePage(
        requestedPage: Int,
        pageSize: Int,
        identity: (T) -> String,
        loadBatch: suspend (sourcePage: Int) -> DoramasflixPageBatch<T>,
    ): List<T> {
        require(pageSize > 0)

        val targetPage = requestedPage.coerceAtLeast(1)
        val startIndex = (targetPage - 1) * pageSize
        val endIndex = targetPage * pageSize
        val visibleItems = linkedMapOf<String, T>()
        val seenSourcePages = mutableSetOf<List<String>>()
        var sourcePage = 1

        while (visibleItems.size < endIndex) {
            val batch = loadBatch(sourcePage)
            if (
                batch.sourceSignature.isNotEmpty() &&
                !seenSourcePages.add(batch.sourceSignature)
            ) {
                break
            }

            batch.items.forEach { item ->
                visibleItems.putIfAbsent(identity(item), item)
            }

            if (!batch.hasNextPage) break
            sourcePage++
        }

        return visibleItems.values.drop(startIndex).take(pageSize)
    }

    suspend fun <T> collectAll(
        identity: (T) -> String,
        loadBatch: suspend (sourcePage: Int) -> DoramasflixPageBatch<T>,
    ): List<T> {
        val items = linkedMapOf<String, T>()
        val seenSourcePages = mutableSetOf<List<String>>()
        var sourcePage = 1

        while (true) {
            val batch = loadBatch(sourcePage)
            if (
                batch.sourceSignature.isNotEmpty() &&
                !seenSourcePages.add(batch.sourceSignature)
            ) {
                break
            }
            if (batch.items.isEmpty()) break

            batch.items.forEach { item ->
                items.putIfAbsent(identity(item), item)
            }

            if (!batch.hasNextPage) break
            sourcePage++
        }

        return items.values.toList()
    }
}
