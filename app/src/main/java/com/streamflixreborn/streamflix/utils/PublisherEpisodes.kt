package com.streamflixreborn.streamflix.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import org.json.JSONObject

internal object PublisherEpisodes {
    private val shows by lazy {
        StreamFlixApp.instance.assets.open("publisher_episodes.json").bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun key(name: String) = name.lowercase().filter(Char::isLetterOrDigit)

    private val episodes by lazy {
        shows.keys().asSequence().associateWith { showId ->
            val entries = shows.getJSONObject(showId)
            entries.keys().asSequence().associate { name -> key(name) to entries.getString(name) }
        }
    }

    fun videos(showId: String): List<Pair<String, String>> {
        val entries = shows.optJSONObject(showId) ?: return emptyList()
        return entries.keys().asSequence().sorted().map { name -> name to entries.getString(name) }.toList()
    }

    fun videoId(showId: String, name: String?): String? {
        return episodes[showId]?.get(key(name.orEmpty()))
    }

    fun videoId(type: Video.Type): String? = (type as? Video.Type.Episode)?.let { videoId(it.tvShow.id, it.title) }

    fun watch(context: Context, id: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Install YouTube to open this episode.", Toast.LENGTH_LONG).show()
        }
    }
}
