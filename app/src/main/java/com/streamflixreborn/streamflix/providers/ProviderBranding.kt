package com.streamflixreborn.streamflix.providers

object ProviderBranding {
    private const val DORAMASFLIX_LOGO =
        "https://www.google.com/s2/favicons?domain=doramasflix.in&sz=256"

    fun logo(provider: Provider?): String? = when (provider) {
        DoramasflixProvider -> DORAMASFLIX_LOGO
        else -> provider?.logo
    }
}
