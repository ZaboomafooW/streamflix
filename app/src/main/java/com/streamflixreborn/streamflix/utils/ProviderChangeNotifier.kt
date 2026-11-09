package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Utility class to notify ViewModels when the current provider changes
 */
object ProviderChangeNotifier {
    private val _providerChangeFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val providerChangeFlow: Flow<Unit> = _providerChangeFlow
    
    /**
     * Notify all listeners that the provider has changed
     */
    fun notifyProviderChanged() {
        _providerChangeFlow.tryEmit(Unit)
    }
}
