package com.streamflixreborn.streamflix.providers

open class ProviderUnavailableException(
    providerName: String,
    cause: Throwable? = null,
) : Exception("$providerName is currently unavailable. Please try again later.", cause)
