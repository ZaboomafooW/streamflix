package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URI

class TwoEmbedExtractor : Extractor() {

    override val name = "2Embed"
    override val mainUrl = "https://www.2embed.cc"

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Episode -> "$mainUrl/embedtv/${videoType.tvShow.id}&s=${videoType.season.number}&e=${videoType.number}"
                is Video.Type.Movie -> "$mainUrl/embed/${videoType.id}"
            },
        )
    }

    override suspend fun extract(link: String): Video {
        val document = Service.build(mainUrl).get(link)
        val iframeSrc = sequenceOf(
            document.selectFirst("iframe[data-src]")?.attr("data-src"),
            document.selectFirst("iframe#player_iframe")?.attr("src"),
            document.selectFirst("iframe")?.attr("data-src"),
            document.selectFirst("iframe")?.attr("src"),
        ).firstOrNull { !it.isNullOrBlank() }
            ?: throw Exception("Can't retrieve iframe src")

        val iframeUrl = resolveUrl(link, iframeSrc)
        val iframeUri = Uri.parse(iframeUrl)
        val id = iframeUri.getQueryParameter("id")
            ?: iframeUrl.substringAfter("id=", "").substringBefore("&").takeIf { it.isNotBlank() }
            ?: throw Exception("Can't retrieve 2Embed stream id")

        val scheme = iframeUri.scheme ?: "https"
        val host = iframeUri.host ?: throw Exception("Can't retrieve 2Embed stream host")
        val origin = "$scheme://$host"
        val finalUrl = "$origin/e/$id"

        return DynamicStreamWishExtractor("$origin/").extract(finalUrl, "$origin/")
    }

    private fun resolveUrl(baseUrl: String, url: String): String {
        if (url.startsWith("//")) {
            val scheme = Uri.parse(baseUrl).scheme ?: "https"
            return "$scheme:$url"
        }
        return URI(baseUrl).resolve(url).toString()
    }

    private class DynamicStreamWishExtractor(
        override val mainUrl: String,
    ) : StreamWishExtractor() {
        override val name = "2Embed StreamWish"

        suspend fun extract(link: String, referer: String): Video {
            this.referer = referer
            return extract(link)
        }
    }

    private interface Service {

        companion object {
            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): Document
    }
}
