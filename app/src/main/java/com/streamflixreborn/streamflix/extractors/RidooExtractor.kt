package com.streamflixreborn.streamflix.extractors

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.RidomoviesProvider
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

class RidooExtractor : Extractor() {

    override val name = "Ridoo"
    override val mainUrl = "https://ridoo.net"
    override val rotatingDomain = listOf(Regex("""(^|\.)ridorapid\.""", RegexOption.IGNORE_CASE))

    override suspend fun extract(link: String): Video {
        val embedUrl = link.toHttpUrlOrNull()
            ?: throw Exception("Invalid Ridoo embed URL")

        if (embedUrl.host.contains("ridorapid", ignoreCase = true)) {
            return BrowserStreamResolver.resolve(
                link = link,
                referer = RidomoviesProvider.URL,
            ) { candidate ->
                isPlayableMediaUrl(candidate)
            }
        }

        val embedOrigin = "${embedUrl.scheme}://${embedUrl.host}"
        val document = Service.build(embedOrigin, RidomoviesProvider.URL).get(link)
        val m3u8Url = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            .find(document.toString())
            ?.groups?.get(1)?.value
            ?: throw Exception("Can't extract m3u8 URL from embed page")

        return Video(
            source = m3u8Url,
            headers = mapOf(
                "Referer" to "$embedOrigin/",
                "Origin" to embedOrigin,
                "User-Agent" to NetworkClient.USER_AGENT,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        )
    }

    private fun isPlayableMediaUrl(value: String): Boolean {
        val lower = value.lowercase()
        if (
            lower.contains("test-videos") ||
            lower.contains("sample-videos") ||
            lower.contains("bigbuckbunny") ||
            lower.contains("cdn.plyr.io")
        ) return false
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8") || path.endsWith(".mp4")
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String, referer: String): Service {
                val client = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", NetworkClient.USER_AGENT)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .header("Referer", referer)
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                return Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .client(client)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): Document
    }
}
