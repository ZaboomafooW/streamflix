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

    fun videoId(showId: String, name: String?): String? {
        val title = name.orEmpty().lowercase().filter(Char::isLetterOrDigit)
        return shows.optJSONObject(showId)?.optString(title)?.takeIf { it.isNotBlank() }
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
