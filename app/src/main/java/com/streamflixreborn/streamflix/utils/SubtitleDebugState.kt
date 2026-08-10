package com.streamflixreborn.streamflix.utils

object SubtitleDebugState {
    data class Snapshot(
        val source: String,
        val preferredLanguage: String?,
        val rawAudioLines: List<String>,
        val rawSubtitleLines: List<String>,
        val patchedAudioLines: List<String>,
        val patchedSubtitleLines: List<String>,
    )

    @Volatile
    private var current: Snapshot? = null

    fun clear() {
        current = null
    }

    fun update(
        source: String,
        preferredLanguage: String?,
        rawAudioLines: List<String>,
        rawSubtitleLines: List<String>,
        patchedAudioLines: List<String>,
        patchedSubtitleLines: List<String>,
    ) {
        current = Snapshot(
            source = source,
            preferredLanguage = preferredLanguage,
            rawAudioLines = rawAudioLines.toList(),
            rawSubtitleLines = rawSubtitleLines.toList(),
            patchedAudioLines = patchedAudioLines.toList(),
            patchedSubtitleLines = patchedSubtitleLines.toList(),
        )
    }

    fun snapshot(): Snapshot? = current
}
