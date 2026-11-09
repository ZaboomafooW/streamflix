package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.net.URI

class MoviesapiExtractor : Extractor() {

    override val name = "Moviesapi"
    override val mainUrl = "https://moviesapi.to/"
    override val aliasUrls = listOf(
        "https://moviesapi.club/",
        "https://vidspark.to/",
    )

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Movie -> "${mainUrl}movie/${videoType.id}"
                is Video.Type.Episode -> "${mainUrl}tv/${videoType.tvShow.id}/${videoType.season.number}/${videoType.number}"
            },
        )
    }

    override suspend fun extract(link: String): Video {
        val document = Service.build(mainUrl).get(
            url = link,
            referer = mainUrl,
            userAgent = USER_AGENT,
        )

        val iframeSrc = document.selectFirst("iframe[src]")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Can't retrieve MoviesAPI iframe")

        val iframeUrl = URI(link).resolve(iframeSrc).toString()
        return Extractor.extract(iframeUrl)
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
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String,
        ): Document
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    }
}
