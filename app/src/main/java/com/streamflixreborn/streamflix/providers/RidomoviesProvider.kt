package com.streamflixreborn.streamflix.providers

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.RidomoviesRateLimit
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Url
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object RidomoviesProvider : Provider {

    const val URL = "https://ridomovies.su/"
    override val baseUrl = URL
    override val name = "Ridomovies"
    override val logo: String
        get() = "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.ic_ridomovies}"
    override val logoRes = R.drawable.ic_ridomovies
    override val language = "en"

    private const val HOME = "home-rd1"
    private val mediaPath = Regex("""/(movie|tv)/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val episodePath =
        Regex("""/tv/([^/?#]+)/season-(\d+)/episode-(\d+)""", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("""\b(?:19|20)\d{2}\b""")
    private val clearanceMutex = Mutex()
    private val tmdbIds = ConcurrentHashMap<String, Int>()
    private var resolver: WebViewResolver? = null

    private val genres = linkedMapOf(
        "Action" to "action", "Adventure" to "adventure", "Animation" to "animation",
        "Comedy" to "comedy", "Crime" to "crime", "Documentary" to "documentary",
        "Drama" to "drama", "Family" to "family", "Fantasy" to "fantasy",
        "History" to "history", "Horror" to "horror", "Music" to "music",
        "Mystery" to "mystery", "Romance" to "romance", "Sci-Fi" to "sci-fi",
        "Thriller" to "thriller", "TV Movie" to "tvmovie", "War" to "war",
        "Western" to "western",
    )

    private val service = Retrofit.Builder()
        .baseUrl(URL)
        .client(NetworkClient.default)
        .build()
        .create(Service::class.java)

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
        ): retrofit2.Response<ResponseBody>
    }

    private data class Card(
        val id: String,
        val title: String,
        val movie: Boolean,
        val released: String? = null,
        val runtime: Int? = null,
        val quality: String? = null,
        val rating: Double? = null,
        val poster: String? = null,
        val banner: String? = null,
        val overview: String? = null,
    )

    private data class Metadata(
        val title: String,
        val overview: String?,
        val released: String?,
        val runtime: Int?,
        val rating: Double?,
        val poster: String?,
        val imdbId: String?,
        val genres: List<Genre>,
    )

    override suspend fun getHome(): List<Category> = coroutineScope {
        val home = async { document("${URL}$HOME") }
        val movies = async { getMovies(1) }
        val tv = async { getTvShows(1) }

        buildList {
            home.await().selectFirst(".highlights-slider")?.let(::cards)
                ?.map(::item)
                ?.takeIf { it.isNotEmpty() }
                ?.let { add(Category(Category.FEATURED, it)) }
            movies.await().takeIf { it.isNotEmpty() }
                ?.let { add(Category("Latest Movies", it)) }
            tv.await().takeIf { it.isNotEmpty() }
                ?.let { add(Category("Latest TV Series", it)) }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return genres.map { (name, id) -> Genre(id, name) }
        }
        if (page > 1) return emptyList()

        val url = apiUrl("api/search", "q" to query.trim(), "lang" to "en", "limit" to "32")
        return json(url).array("data")
            .mapNotNull { it.obj()?.let(::card) }
            .map(::item)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = apiUrl(
            "api/movies/latest",
            "page" to page.coerceAtLeast(1).toString(),
            "limit" to "32",
            "lang" to "en",
        )
        return json(url).array("movies")
            .mapNotNull { it.obj()?.let(::card) }
            .filter { it.movie }
            .map(::movie)
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = apiUrl(
            "api/tv/latest",
            "page" to page.coerceAtLeast(1).toString(),
            "limit" to "32",
            "lang" to "en",
        )
        return json(url).array("series")
            .mapNotNull { it.obj()?.let(::card) }
            .filterNot { it.movie }
            .map(::tvShow)
    }

    override suspend fun getMovie(id: String): Movie {
        val slug = slug(id)
        val metadata = metadata(document("${URL}movie/$slug"), "Movie")
        if (metadata.title.isBlank()) throw Exception("Ridomovies movie details could not be loaded.")
        return Movie(
            id = slug,
            title = metadata.title,
            overview = metadata.overview,
            released = metadata.released,
            runtime = metadata.runtime,
            rating = metadata.rating,
            poster = metadata.poster,
            imdbId = metadata.imdbId,
            genres = metadata.genres,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val slug = slug(id)
        val doc = document("${URL}tv/$slug")
        val metadata = metadata(doc, "TVSeries")
        if (metadata.title.isBlank()) throw Exception("Ridomovies TV details could not be loaded.")
        val tmdbTvShow = ridoTmdbId(slug, metadata.title)?.let {
            TmdbUtils.getTvShowById(it, language = language)
        }

        return TvShow(
            id = slug,
            title = metadata.title,
            overview = metadata.overview,
            released = metadata.released,
            runtime = metadata.runtime,
            rating = metadata.rating,
            poster = metadata.poster ?: tmdbTvShow?.poster,
            banner = tmdbTvShow?.banner,
            imdbId = metadata.imdbId ?: tmdbTvShow?.imdbId,
            seasons = seasonNumbers(doc).map { number ->
                Season(
                    id = "$slug/$number",
                    number = number,
                    title = "Season $number",
                    poster = tmdbTvShow?.seasons
                        ?.firstOrNull { it.number == number }
                        ?.poster
                        ?: metadata.poster,
                )
            },
            genres = metadata.genres,
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val slug = seasonId.substringBeforeLast('/')
        val season = seasonId.substringAfterLast('/').toIntOrNull() ?: return emptyList()
        val showUrl = "${URL}tv/$slug"
        val seasonUrl = "$showUrl/season-$season"
        val showDoc = document(showUrl)
        val metadata = metadata(showDoc, "TVSeries")

        val ajax = runCatching {
            val html = json(
                seasonUrl,
                mapOf(
                    "Referer" to showUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Content-Mode" to "episodes_only",
                ),
            ).string("episodesHtml")
            html?.takeIf { it.isNotBlank() }?.let { Jsoup.parse(it, showUrl) }
        }.getOrNull()

        val seasonDoc = ajax ?: runCatching { document(seasonUrl) }.getOrDefault(showDoc)
        val tmdbEpisodePosters = ridoTmdbId(slug, metadata.title)?.let { tmdbId ->
            TmdbUtils.getEpisodesBySeason(tmdbId.toString(), season, language = language)
                .mapNotNull { episode -> episode.poster?.let { episode.number to it } }
                .toMap()
        }.orEmpty()
        return episodes(seasonDoc, slug, season, tmdbEpisodePosters)
    }

    override suspend fun getGenre(id: String, page: Int): Genre = coroutineScope {
        val genre = id.lowercase(Locale.ROOT)
        val suffix = if (page > 1) "/page-${page.coerceAtLeast(1)}" else ""
        val movies = async {
            cards(document("${URL}genre/$genre/movie$suffix"))
                .filter { it.movie }.map(::movie)
        }
        val tv = async {
            cards(document("${URL}genre/$genre/tv$suffix"))
                .filterNot { it.movie }.map(::tvShow)
        }
        Genre(
            id = genre,
            name = genres.entries.firstOrNull { it.value == genre }?.key ?: id,
            shows = movies.await() + tv.await(),
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        throw Exception("Not yet implemented")

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = when (videoType) {
            is Video.Type.Movie -> "${URL}movie/${slug(id)}"
            is Video.Type.Episode -> if (id.contains("/episode-", true)) {
                absolute(id)
            } else {
                "${URL}tv/${slug(videoType.tvShow.id)}/season-${videoType.season.number}/episode-${videoType.number}"
            }
        }

        val servers = servers(document(url))
        if (servers.isEmpty()) {
            throw Exception("Ridomovies doesn't currently have a video file for this title.")
        }
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    private suspend fun document(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Document = Jsoup.parse(
        text(url, extraHeaders + ("Accept" to "text/html,application/xhtml+xml,*/*;q=0.8")),
        url,
    ).apply { setBaseUri(URL) }

    private suspend fun json(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject {
        val raw = text(url, extraHeaders + ("Accept" to "application/json"))
        return runCatching { JsonParser.parseString(raw).asJsonObject }
            .getOrElse { throw Exception("Ridomovies returned invalid JSON.", it) }
    }

    private suspend fun text(url: String, extraHeaders: Map<String, String>): String {
        if (RidomoviesRateLimit.remainingCooldownNanos() > 0L) {
            throw Exception(RidomoviesRateLimit.message())
        }

        val headers = linkedMapOf(
            "User-Agent" to NetworkClient.USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to URL,
        ).apply { putAll(extraHeaders) }

        var response = service.get(url, headers)
        if (response.code() == 429) throw rateLimit(response)
        var body = responseBody(response)
        if (challenge(response.code(), response.headers()["Server"], body)) {
            clearChallenge()
            response = service.get(url, headers)
            if (response.code() == 429) throw rateLimit(response)
            body = responseBody(response)
        }

        if (!response.isSuccessful) throw HttpException(response)
        if (challenge(response.code(), response.headers()["Server"], body)) {
            throw Exception("Ridomovies Cloudflare challenge could not be cleared.")
        }
        return body
    }

    private fun rateLimit(response: retrofit2.Response<ResponseBody>): Exception {
        RidomoviesRateLimit.recordRetryAfter(response.headers()["Retry-After"])
        return Exception(RidomoviesRateLimit.message())
    }

    private fun responseBody(response: retrofit2.Response<ResponseBody>) =
        if (response.isSuccessful) response.body()?.string().orEmpty()
        else response.errorBody()?.string().orEmpty()

    private fun challenge(code: Int, server: String?, body: String): Boolean =
        body.contains("Just a moment", true) ||
            body.contains("cf-browser-verification", true) ||
            body.contains("challenge-running", true) ||
            body.contains("Checking your browser", true) ||
            (code in setOf(403, 503) && server?.contains("cloudflare", true) == true)

    private suspend fun clearChallenge() = clearanceMutex.withLock {
        val probeUrl = "${URL}$HOME"
        val probe = service.get(
            probeUrl,
            mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
            ),
        )
        if (probe.code() == 429) throw rateLimit(probe)
        val probeBody = responseBody(probe)
        if (probe.isSuccessful && !challenge(probe.code(), probe.headers()["Server"], probeBody)) {
            return@withLock
        }

        val result = webViewResolver().getResult(
            url = probeUrl,
            headers = mapOf("User-Agent" to NetworkClient.USER_AGENT),
            completion = { _, html, cookies ->
                !challenge(200, null, html) &&
                    (cookies.contains("cf_clearance") ||
                        html.contains("RIDOMOVIES", true) ||
                        html.contains("movie-card", true))
            },
        )
        if (challenge(200, null, result.html)) {
            throw Exception("Ridomovies Cloudflare challenge could not be cleared.")
        }
    }

    private fun webViewResolver() =
        resolver ?: WebViewResolver(StreamFlixApp.instance).also { resolver = it }

    private fun cards(container: Element): List<Card> {
        val isHighlights = container.hasClass("highlights-slider")
        return container.select(".movie-card, .highlight-card").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/movie/'], a[href*='/tv/']")
                ?: return@mapNotNull null
            val href = absolute(link.attr("href"))
            val match = mediaPath.find(URL(href).path) ?: return@mapNotNull null
            val title = item.selectFirst(".movie-title, .highlight-title")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: link.attr("aria-label").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val artwork = item.selectFirst("img")?.let {
                asset(it.attr("src").takeIf(String::isNotBlank) ?: it.attr("data-src"))
            }
            val isHighlight = isHighlights || item.hasClass("highlight-card")
            Card(
                id = match.groupValues[2],
                title = title,
                movie = match.groupValues[1].equals("movie", true),
                released = item.selectFirst(".movie-year")?.text()?.let(::year),
                runtime = if (isHighlight) highlightRuntime(item) else null,
                quality = item.selectFirst(".movie-quality, .quality")?.text()?.trim(),
                rating = item.selectFirst("[class*=rating]")?.text()?.let(::number),
                poster = artwork.takeUnless { isHighlight },
                banner = artwork.takeIf { isHighlight },
                overview = if (isHighlight) highlightOverview(item) else null,
            )
        }.distinctBy { "${it.movie}:${it.id}" }
    }

    private fun highlightOverview(item: Element): String? {
        val selectors = listOf(
            ".highlight-description",
            ".movie-overview",
            ".overview-text",
            "[class*=description]",
            "[class*=overview]",
            ".highlight-content p",
        )
        return selectors.asSequence()
            .flatMap { selector -> item.select(selector).asSequence() }
            .map { it.text().trim() }
            .filter { text ->
                text.length >= 30 &&
                    !text.startsWith("IMDb", true) &&
                    !text.startsWith("Genre:", true) &&
                    !text.startsWith("Duration:", true) &&
                    !text.equals("Watch Now", true)
            }
            .maxByOrNull { it.length }
    }

    private fun highlightRuntime(item: Element): Int? {
        item.selectFirst("[class*=duration], [class*=runtime]")
            ?.text()?.let(::runtime)?.let { return it }
        val value = Regex(
            """Duration:\s*((?:\d+\s*h\s*)?\d+\s*min)""",
            RegexOption.IGNORE_CASE,
        ).find(item.text())?.groupValues?.getOrNull(1)
        return runtime(value)
    }

    private fun card(row: JsonObject): Card? {
        val type = row.string("type")?.lowercase(Locale.ROOT) ?: return null
        if (type !in setOf("movie", "tv")) return null
        val slug = row.string("slug") ?: row.string("slug_en") ?: return null
        val title = row.string("title") ?: row.string("original_title") ?: return null
        if (type == "tv") {
            row.int("tmdb_id")?.let { tmdbIds[slug.lowercase(Locale.ROOT)] = it }
        }
        return Card(
            id = slug,
            title = title,
            movie = type == "movie",
            released = year(row.string("release_date") ?: row.string("first_air_date")),
            runtime = row.int("duration") ?: row.int("runtime"),
            quality = row.string("quality"),
            rating = row.double("imdb_rating") ?: row.double("rating"),
            poster = asset(row.string("poster_path") ?: row.string("poster")),
            overview = row.string("overview") ?: row.string("description"),
        )
    }

    private suspend fun ridoTmdbId(slug: String, title: String): Int? {
        val key = slug.lowercase(Locale.ROOT)
        tmdbIds[key]?.let { return it }
        if (title.isBlank()) return null

        val url = apiUrl("api/search", "q" to title, "lang" to "en", "limit" to "32")
        val row = runCatching {
            json(url).array("data")
                .mapNotNull { it.obj() }
                .firstOrNull { candidate ->
                    candidate.string("type")?.equals("tv", true) == true &&
                        listOfNotNull(
                            candidate.string("slug"),
                            candidate.string("slug_en"),
                        ).any { it.equals(slug, true) }
                }
        }.getOrNull() ?: return null

        return row.int("tmdb_id")?.also { tmdbIds[key] = it }
    }

    private fun item(card: Card): AppAdapter.Item = if (card.movie) movie(card) else tvShow(card)

    private fun movie(card: Card) = Movie(
        id = card.id, title = card.title, released = card.released,
        runtime = card.runtime, quality = card.quality, rating = card.rating,
        poster = card.poster, banner = card.banner, overview = card.overview,
    )

    private fun tvShow(card: Card) = TvShow(
        id = card.id, title = card.title, released = card.released,
        runtime = card.runtime, quality = card.quality, rating = card.rating,
        poster = card.poster, banner = card.banner, overview = card.overview,
    )

    private fun metadata(doc: Document, wantedType: String): Metadata {
        val items = jsonLd(doc)
        val primary = items.firstOrNull { it.type(wantedType) }
            ?: items.firstOrNull { it.type("Movie") || it.type("TVSeries") }
        val heading = doc.selectFirst(".movie-title-main, #hero-section h1, main h1, h1")
            ?.text()?.trim().orEmpty()
        val title = (primary?.string("name") ?: heading)
            .replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "").trim()
        val overview = doc.selectFirst(".movie-overview, .overview-text")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: primary?.string("description")
        val released = year(primary?.string("datePublished") ?: primary?.string("startDate") ?: heading)
        val runtime = runtime(primary?.string("duration"))
            ?: doc.select(".meta-info").firstOrNull { it.text().contains("Duration:", true) }
                ?.text()?.substringAfter(':')?.let(::runtime)
        val rating = primary?.objectValue("aggregateRating")?.double("ratingValue")
            ?: doc.selectFirst("[class*=imdb], [class*=rating]")?.text()?.let(::number)
        val poster = asset(
            doc.selectFirst(".movie-poster-img, .tv-poster")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: primary?.string("image"),
        )
        val imdb = doc.selectFirst("#detailConfig[data-imdb-id]")?.attr("data-imdb-id")
            ?.trim()?.takeIf { it.isNotBlank() }
        val genreNames = mutableListOf<String>()
        primary?.get("genre")?.let {
            if (it.isJsonArray) it.asJsonArray.forEach { value ->
                value.stringValue()?.takeIf(String::isNotBlank)?.let(genreNames::add)
            } else it.stringValue()?.split(',')?.map { name -> name.trim() }?.let(genreNames::addAll)
        }
        if (genreNames.isEmpty()) {
            doc.select("#hero-section a[href*='/genre/'], .movie-details a[href*='/genre/']")
                .map { it.text().trim() }.filter(String::isNotBlank).let(genreNames::addAll)
        }
        return Metadata(
            title, overview, released, runtime, rating, poster, imdb,
            genreNames.distinct().map {
                Genre(genres[it] ?: it.lowercase(Locale.ROOT).replace(' ', '-'), it)
            },
        )
    }

    private fun seasonNumbers(doc: Document): List<Int> {
        val values = doc.select(".season-tabs [data-season-number]")
            .mapNotNull { it.attr("data-season-number").toIntOrNull() }.filter { it > 0 }
            .toMutableSet()
        doc.select("a[href*='/season-']").forEach {
            episodePath.find(it.attr("href"))?.groupValues?.getOrNull(2)
                ?.toIntOrNull()?.takeIf { n -> n > 0 }?.let(values::add)
        }
        return values.sorted()
    }

    private fun episodes(
        doc: Document,
        expectedSlug: String,
        expectedSeason: Int,
        tmdbPosters: Map<Int, String>,
    ): List<Episode> =
        doc.select("a.episode-link[href], a[href*='/episode-']").mapNotNull { link ->
            if (link.selectFirst(".ep-no-video") != null) return@mapNotNull null
            val href = absolute(link.attr("href"))
            val match = episodePath.find(URL(href).path) ?: return@mapNotNull null
            if (!match.groupValues[1].equals(expectedSlug, true) ||
                match.groupValues[2].toIntOrNull() != expectedSeason
            ) return@mapNotNull null
            val number = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val episodePoster = link.selectFirst("img")?.let {
                asset(it.attr("src").takeIf(String::isNotBlank) ?: it.attr("data-src"))
            } ?: tmdbPosters[number]
            Episode(
                id = URL(href).path.trimStart('/'),
                number = number,
                title = link.selectFirst(".ep-name-row, .episode-title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "Episode $number",
                poster = episodePoster,
                overview = link.selectFirst(".episode-overview, .ep-overview, .episode-description")
                    ?.text()?.trim()?.takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.number }.sortedBy { it.number }

    private fun servers(doc: Document): List<Video.Server> {
        val imdb = doc.selectFirst("#detailConfig[data-imdb-id]")?.attr("data-imdb-id").orEmpty()
        val result = linkedMapOf<String, Video.Server>()

        doc.selectFirst("#detailConfig[data-videos]")?.attr("data-videos")
            ?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull()?.forEach { value ->
                    val row = value.obj() ?: return@forEach
                    val id = row.string("video_id") ?: row.string("video") ?: return@forEach
                    val template = row.string("template") ?: return@forEach
                    val url = embed(
                        template.replace("{{id}}", id).replace("{id}", id).replace("{url}", id),
                    )?.let { withImdb(it, imdb) } ?: return@forEach
                    addServer(result, url, row.string("service_name"), row.string("quality"))
                }
            }

        doc.select("#player-cover[data-embed], .server-dropdown-item[data-server-embed]").forEach {
            val raw = it.attr("data-embed").takeIf(String::isNotBlank)
                ?: it.attr("data-server-embed")
            embed(raw)?.let { url -> addServer(result, withImdb(url, imdb), null, null) }
        }
        return result.values.sortedBy { if (it.name.startsWith("Rapidrame")) 0 else 1 }
    }

    private fun addServer(
        result: MutableMap<String, Video.Server>,
        url: String,
        suppliedName: String?,
        quality: String?,
    ) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val host = parsed.host.lowercase(Locale.ROOT)
        if (
            host == "youtu.be" ||
            host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")
        ) return

        val key = "${parsed.host}${parsed.encodedPath.trimEnd('/')}"
        val detected = when {
            parsed.host.contains("ridorapid", true) -> "Rapidrame"
            parsed.host.contains("ridoo", true) -> "Ridoo"
            parsed.host.contains("closeload", true) -> "Closeload"
            else -> parsed.host.removePrefix("www.").substringBefore('.')
                .replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        val name = suppliedName?.takeUnless { it.startsWith("Server", true) } ?: detected
        val label = listOfNotNull(name, quality?.takeIf(String::isNotBlank)).joinToString(" ")
        val server = Video.Server(url, label.ifBlank { "Server" }, url)
        val existing = result[key]
        if (existing == null ||
            (parsed.querySize > 0 && existing.src.toHttpUrlOrNull()?.querySize == 0)
        ) result[key] = server
    }

    private fun embed(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("//")) {
            return absolute(value).takeIf { it.toHttpUrlOrNull() != null }
        }
        val iframe = Jsoup.parse(value).selectFirst("iframe") ?: return null
        val source = iframe.attr("src").takeIf(String::isNotBlank)
            ?: iframe.attr("data-src").takeIf(String::isNotBlank) ?: return null
        return absolute(source).takeIf { it.toHttpUrlOrNull() != null }
    }

    private fun withImdb(value: String, imdb: String): String {
        val url = value.toHttpUrlOrNull() ?: return value
        if (!url.host.contains("closeload", true) || imdb.isBlank() ||
            url.queryParameter("imdb_id") != null
        ) return value
        return url.newBuilder().addQueryParameter("imdb_id", imdb).build().toString()
    }

    private fun jsonLd(doc: Document): List<JsonObject> = buildList {
        doc.select("script[type=application/ld+json]").forEach { script ->
            val value = runCatching {
                JsonParser.parseString(script.data().ifBlank { script.html() })
            }.getOrNull() ?: return@forEach
            val roots = if (value.isJsonArray) value.asJsonArray else listOf(value)
            roots.forEach { root ->
                val obj = root.obj() ?: return@forEach
                val graph = obj.get("@graph")
                if (graph?.isJsonArray == true) {
                    graph.asJsonArray.forEach { it.obj()?.let { obj -> add(obj) } }
                } else add(obj)
            }
        }
    }

    private fun JsonObject.type(wanted: String): Boolean {
        val value = get("@type") ?: return false
        return if (value.isJsonArray) value.asJsonArray.any {
            it.stringValue()?.equals(wanted, true) == true
        } else value.stringValue()?.equals(wanted, true) == true
    }

    private fun apiUrl(path: String, vararg params: Pair<String, String>): String =
        URL.toHttpUrl().newBuilder().addPathSegments(path).apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()

    private fun absolute(value: String): String {
        val raw = value.trim()
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "${URL.trimEnd('/')}$raw"
            else -> "$URL$raw"
        }
    }

    private fun asset(value: String?): String? =
        value?.trim()?.takeIf(String::isNotBlank)?.let(::absolute)

    private fun slug(value: String): String {
        val raw = value.trim()
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return mediaPath.find(URL(raw).path)?.groupValues?.getOrNull(2)
                ?: URL(raw).path.trim('/').substringAfterLast('/')
        }
        return raw.substringBefore('?').substringBefore('#').trim('/').substringAfterLast('/')
    }

    private fun year(value: String?): String? = yearRegex.find(value.orEmpty())?.value

    private fun runtime(value: String?): Int? {
        val text = value?.trim().orEmpty()
        Regex("""^PT(?:(\d+)H)?(?:(\d+)M)?$""", RegexOption.IGNORE_CASE)
            .matchEntire(text)?.let {
                return ((it.groupValues[1].toIntOrNull() ?: 0) * 60 +
                    (it.groupValues[2].toIntOrNull() ?: 0)).takeIf { n -> n > 0 }
            }
        Regex("""(?:(\d+)\s*h)?\s*(\d+)\s*min""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                return ((it.groupValues[1].toIntOrNull() ?: 0) * 60 +
                    (it.groupValues[2].toIntOrNull() ?: 0)).takeIf { n -> n > 0 }
            }
        return text.toIntOrNull()
    }

    private fun number(value: String): Double? =
        Regex("""\d+(?:\.\d+)?""").find(value)?.value?.toDoubleOrNull()

    private fun JsonObject.array(name: String) =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: com.google.gson.JsonArray()

    private fun JsonObject.string(name: String): String? =
        get(name)?.stringValue()?.takeIf(String::isNotBlank)

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.double(name: String): Double? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }

    private fun JsonObject.objectValue(name: String): JsonObject? = get(name)?.obj()

    private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonElement.stringValue(): String? =
        if (isJsonPrimitive) runCatching { asString }.getOrNull() else null
}
