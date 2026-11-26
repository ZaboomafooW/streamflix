package com.streamflixreborn.streamflix.providers

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.google.gson.JsonObject
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.TMDb3
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

internal object LinkedArchive {
    private interface Service {
        @GET
        suspend fun json(@Url url: String): JsonObject
    }

    private val service = Retrofit.Builder().baseUrl("https://archive.org/")
        .addConverterFactory(GsonConverterFactory.create()).build().create(Service::class.java)
    private val episodeCode = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?!\d)""")
    private fun key(text: String) = text.lowercase().filter(Char::isLetterOrDigit)

    suspend fun servers(type: Video.Type): List<Video.Server> {
        val entity = when (type) {
            is Video.Type.Movie -> TMDb3.Movies.details(type.id.toIntOrNull() ?: return emptyList(),
                listOf(TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS)).externalIds?.wikidata
            is Video.Type.Episode -> TMDb3.TvSeries.details(type.tvShow.id.toIntOrNull() ?: return emptyList(),
                listOf(TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS)).externalIds?.wikidata
        } ?: return emptyList()
        if (!entity.matches(Regex("Q[0-9]+"))) return emptyList()
        val claims = service.json("https://www.wikidata.org/wiki/Special:EntityData/$entity.json")
            .getAsJsonObject("entities")?.getAsJsonObject(entity)?.getAsJsonObject("claims")
            ?.getAsJsonArray("P724") ?: return emptyList()
        return buildList {
            for (claim in claims) {
                val statement = claim.asJsonObject
                if (statement.get("rank")?.asString == "deprecated") continue
                val item = statement.getAsJsonObject("mainsnak")?.getAsJsonObject("datavalue")
                    ?.get("value")?.asString ?: continue
                if (!item.matches(Regex("[A-Za-z0-9_.-]+"))) continue
                val record = try { service.json("metadata/$item") }
                    catch (_: retrofit2.HttpException) { continue }
                    catch (_: java.io.IOException) { continue }
                if (record.getAsJsonObject("metadata")?.get("mediatype")?.asString != "movies") continue
                for (entry in record.getAsJsonArray("files") ?: continue) {
                    val file = entry.asJsonObject.get("name")?.asString ?: continue
                    if (!file.endsWith(".mp4", true)) continue
                    val stem = file.substringAfterLast('/').dropLast(4).removeSuffix(".ia")
                    val match = when (type) {
                        is Video.Type.Movie -> key(stem) in setOf(key(type.title), key(type.title + type.releaseDate.take(4)))
                        is Video.Type.Episode -> episodeCode.find(stem)?.destructured?.let { (s, e) ->
                            s.toInt() == type.season.number && e.toInt() == type.number
                        } == true
                    }
                    if (match) {
                        val url = "https://archive.org/download/$item/${Uri.encode(file, "/")}"
                        add(Video.Server(url, "Internet Archive ($item)", url).apply {
                            video = Video(source = url, type = MimeTypes.VIDEO_MP4)
                        })
                        break
                    }
                }
            }
        }
    }
}
