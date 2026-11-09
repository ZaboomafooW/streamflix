package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ProviderChangeNotifierTest {

    @Test
    fun `provider changes are broadcast to active collectors`() = runBlocking {
        val firstCollector = async(start = CoroutineStart.UNDISPATCHED) {
            ProviderChangeNotifier.providerChangeFlow.first()
        }
        val secondCollector = async(start = CoroutineStart.UNDISPATCHED) {
            ProviderChangeNotifier.providerChangeFlow.first()
        }

        ProviderChangeNotifier.notifyProviderChanged()

        withTimeout(1_000) {
            firstCollector.await()
            secondCollector.await()
        }
    }
}
