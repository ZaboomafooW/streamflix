package com.streamflixreborn.streamflix.kids

import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection

internal object EpisodeArtwork {
    private val bundled by lazy {
        StreamFlixApp.instance.assets.open("kidflix_episode_art.json").bufferedReader().use { JSONObject(it.readText()) }
    }
    fun key(name: String?) = name.orEmpty().lowercase().filter(Char::isLetterOrDigit)
    fun image(show: Int, episode: String?) = bundled.optJSONObject(show.toString())?.optString(key(episode))?.takeIf { it.isNotBlank() }

    private fun fetch(path: String): String {
        val connection = URL("https://api.tvmaze.com/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 7000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun lookup(tvdb: Int?, imdb: String?): Map<String, String> = withContext(Dispatchers.IO) {
        val query = tvdb?.let { "thetvdb=$it" } ?: imdb?.takeIf { it.matches(Regex("tt[0-9]+")) }?.let { "imdb=$it" }
            ?: return@withContext emptyMap()
        val cache = StreamFlixApp.instance.getSharedPreferences("kidflix_episode_images", 0)
        try {
            val cached = cache.getString(query, null)
            if (cached != null) {
                val json = JSONObject(cached)
                return@withContext json.keys().asSequence().associateWith { json.getString(it) }
            }
            val id = JSONObject(fetch("lookup/shows?$query")).getInt("id")
            val list = JSONArray(fetch("shows/$id/episodes"))
            val images = mutableMapOf<String, String>()
            for (index in 0 until list.length()) {
                val episode = list.getJSONObject(index)
                val url = episode.optJSONObject("image")?.optString("medium").orEmpty()
                val name = key(episode.optString("name"))
                if (name.isNotEmpty() && url.startsWith("https://")) images["${episode.getInt("season")}/$name"] = url
            }
            val counts = images.values.groupingBy { it }.eachCount()
            val unique = images.filterValues { counts[it] == 1 }
            if (unique.isNotEmpty()) cache.edit().putString(query, JSONObject(unique).toString()).apply()
            unique
        } catch (_: java.io.IOException) {
            emptyMap()
        } catch (_: org.json.JSONException) {
            emptyMap()
        }
    }
}
