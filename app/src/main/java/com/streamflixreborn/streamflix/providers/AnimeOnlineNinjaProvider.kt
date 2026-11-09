package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.ArtworkRequestHeaders
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

object AnimeOnlineNinjaProvider : Provider {

    private const val SITE_BASE_URL = "https://ww3.animeonline.ninja"
    private const val TAG = "AnimeOnlineNinja"
    private const val MAIN_HOST = "ww3.animeonline.ninja"
    private const val DOCUMENT_CACHE_TTL_MS = 2 * 60 * 1000L

    override val name = "Anime Online Ninja"
    override val baseUrl = SITE_BASE_URL
    override val logo: String
        get() = artworkUrl("$baseUrl/wp-content/uploads/2019/09/cropped-avatar2-1-300x300.jpg")
            ?: "$baseUrl/wp-content/uploads/2019/09/cropped-avatar2-1-300x300.jpg"
    override val language = "es"

    private val documentCache = ConcurrentHashMap<String, CachedDocument>()

    // Kept because provider initialization is part of the shared provider lifecycle.
    // Anime Online Ninja no longer owns a browser/network engine to initialize.
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    fun reload() {
        documentCache.clear()
    }

    /**
     * HomeViewModel historically deferred cached Anime Online Ninja content until a
     * browser-created cf_clearance cookie existed. The provider is now deliberately
     * browser-free, so there is no clearance state to wait for.
     */
    fun hasCurrentClearanceCookie(): Boolean = true

    private fun pageHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to NetworkClient.USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer,
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Upgrade-Insecure-Requests" to "1",
    )

    private fun jsonHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to NetworkClient.USER_AGENT,
        "Accept" to "application/json,text/plain,*/*",
        "Accept-Language" to "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer,
        "Origin" to SITE_BASE_URL,
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin",
    )

    private fun getDocument(url: String): Document {
        cachedDocument(url)?.let { cached ->
            return cached.document.clone().apply { setBaseUri(cached.finalUrl) }
        }

        val document = fetchDocument(url)
        cacheDocument(url, document)
        return document.clone()
    }

    private fun fetchDocument(url: String): Document {
        val response = executeGet(url, pageHeaders(baseUrl))
        val body = response.body

        if (!response.isSuccessful) {
            throw IllegalStateException("Anime Online Ninja HTTP ${response.statusCode} for $url")
        }
        if (isCloudflareChallenge(body, response.finalUrl)) {
            throw IllegalStateException(
                "Anime Online Ninja returned a Cloudflare browser challenge for $url; browser execution is disabled"
            )
        }
        if (!hasUsableSiteContent(body, response.finalUrl)) {
            throw IllegalStateException(
                "Anime Online Ninja returned an incomplete page for $url (size=${body.length})"
            )
        }

        return Jsoup.parse(body, response.finalUrl).apply { setBaseUri(response.finalUrl) }
    }

    private fun fetchJson(url: String, referer: String): JSONObject {
        val response = executeGet(url, jsonHeaders(referer))
        val body = response.body.trim()

        if (!response.isSuccessful) {
            throw IllegalStateException("Anime Online Ninja JSON HTTP ${response.statusCode} for $url")
        }
        if (isCloudflareChallenge(body, response.finalUrl)) {
            throw IllegalStateException(
                "Anime Online Ninja returned a Cloudflare browser challenge for $url; browser execution is disabled"
            )
        }
        if (!body.startsWith("{") && !body.startsWith("[")) {
            throw IllegalStateException("Anime Online Ninja returned invalid JSON for $url")
        }
        return JSONObject(body)
    }

    private fun executeGet(url: String, headers: Map<String, String>): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()

        NetworkClient.default.newCall(request).execute().use { response ->
            return HttpResponse(
                statusCode = response.code,
                finalUrl = response.request.url.toString(),
                body = response.body?.string().orEmpty(),
            )
        }
    }

    private fun hasUsableSiteContent(html: String, currentUrl: String): Boolean {
        if (html.length < 1000) return false
        if (currentUrl.contains("/wp-json/", ignoreCase = true)) {
            return html.trimStart().startsWith("{") || html.trimStart().startsWith("[")
        }

        return html.contains("wp-content", ignoreCase = true) ||
                html.contains("dooplay", ignoreCase = true) ||
                html.contains("TPost", ignoreCase = true) ||
                html.contains("result-item", ignoreCase = true) ||
                html.contains("module", ignoreCase = true) ||
                html.contains("episodios", ignoreCase = true) ||
                html.contains("post-", ignoreCase = true)
    }

    private fun isCloudflareChallenge(body: String, finalUrl: String): Boolean {
        if (finalUrl.contains("/cdn-cgi/", ignoreCase = true)) return true
        return body.contains("cf-browser-verification", ignoreCase = true) ||
                body.contains("Just a moment...", ignoreCase = true) ||
                body.contains("Checking your browser", ignoreCase = true) ||
                body.contains("window._cf_chl_opt", ignoreCase = true) ||
                body.contains("challenge-form", ignoreCase = true)
    }

    private fun artworkUrl(url: String?, referer: String = baseUrl): String? {
        val image = url?.trim().orEmpty()
        if (image.isBlank()) return null

        val normalized = when {
            image.startsWith("//") -> "https:$image"
            image.startsWith("http", ignoreCase = true) -> image
            image.startsWith("/") -> "$baseUrl$image"
            else -> "$baseUrl/$image"
        }

        val isProviderArtwork = runCatching {
            URL(normalized).host.equals(MAIN_HOST, ignoreCase = true)
        }.getOrDefault(false)
        if (!isProviderArtwork) return normalized

        return ArtworkRequestHeaders.withHeaders(
            url = normalized,
            referer = referer,
            origin = SITE_BASE_URL,
            userAgent = NetworkClient.USER_AGENT,
            accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        )
    }

    override suspend fun getHome(): List<Category> {
        val document = getDocument("$baseUrl/inicio/")
        val categories = parseHomeCategories(document).takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Anime Online Ninja home page contained no recognizable categories")
        return resolveHomeEpisodeCards(categories)
    }

    private suspend fun resolveHomeEpisodeCards(categories: List<Category>): List<Category> = coroutineScope {
        val episodeCards = categories
            .flatMap { it.list }
            .filterIsInstance<TvShow>()
            .filter { it.id.contains("/episodio/", ignoreCase = true) }

        if (episodeCards.isEmpty()) return@coroutineScope categories

        val requestLimit = Semaphore(4)
        val resolvedByTitle = episodeCards
            .distinctBy { titleKey(it.title) }
            .map { episodeCard ->
                async {
                    val tvShowResult = runCatching {
                        requestLimit.withPermit { getTvShow(episodeCard.id) }
                    }
                    val movieResult = if (tvShowResult.isFailure) {
                        runCatching {
                            requestLimit.withPermit { resolveHomeEpisodeMovie(episodeCard) }
                        }
                    } else {
                        Result.success(null)
                    }
                    val resolved: Show? = tvShowResult.getOrNull() ?: movieResult.getOrNull()

                    if (resolved == null) {
                        Log.w(
                            TAG,
                            "Unable to resolve home episode ${episodeCard.id} to a movie or TV show",
                            tvShowResult.exceptionOrNull() ?: movieResult.exceptionOrNull(),
                        )
                    }
                    titleKey(episodeCard.title) to resolved
                }
            }
            .awaitAll()
            .toMap()

        categories.map { category ->
            category.copy(
                list = category.list
                    .map { item ->
                        if (item is TvShow && item.id.contains("/episodio/", ignoreCase = true)) {
                            resolvedByTitle[titleKey(item.title)] ?: item
                        } else {
                            item
                        }
                    }
                    .distinctBy(::itemKey),
            )
        }
    }

    private suspend fun resolveHomeEpisodeMovie(episodeCard: TvShow): Movie? {
        val query = URLEncoder.encode(episodeCard.title, "UTF-8")
        val searchDocument = getDocument("$baseUrl/?s=$query")
        val movieUrl = findMatchingShowUrl(searchDocument, episodeCard.title, "/pelicula/")
            ?: return null
        return getMovie(normalizeId(movieUrl, "/pelicula/"))
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("anime-castellano", "Audio castellano"),
                Genre("audio-latino", "Audio latino"),
                Genre("en-emision-1", "En emisión"),
                Genre("blu-ray-dvd-2", "BluRay-DVD"),
                Genre("live-action", "Live action"),
                Genre("tendencias", "Popular en la web"),
                Genre("ratings", "Mejores valorados"),
                Genre("award-winning-anime", "Ganadores de premios"),
                Genre("accion", "Accion"),
                Genre("aventura", "Aventura"),
                Genre("comedia", "Comedia"),
                Genre("shonen", "Shonen"),
                Genre("terror", "Terror"),
                Genre("ver-anime", "Ver Anime"),
                Genre("pelicula", "Peliculas"),
            )
        }

        if (page > 1) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        return parseSearchItems(getDocument("$baseUrl/?s=$encoded"))
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        getListing(listingUrl("pelicula", page)).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        getListing(listingUrl("online", page)).filterIsInstance<TvShow>()

    override suspend fun getMovie(id: String): Movie {
        val url = toAbsoluteUrl(id, "/pelicula/")
        val document = getDocument(url)
        val title = document.extractDetailTitle().ifBlank { id }
        val poster = extractDetailArtwork(document, url)

        return Movie(
            id = normalizeId(url, "/pelicula/"),
            title = cleanTitle(title),
            overview = extractOverview(document, title),
            released = document.selectFirst("meta[property='article:published_time']")?.attr("content")?.take(10),
            poster = poster,
            banner = poster,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val requestedUrl = toAbsoluteUrl(id, "/online/")
        val isParentLookup = requestedUrl.contains("/temporada/", ignoreCase = true) ||
                requestedUrl.contains("/episodio/", ignoreCase = true)
        val (url, document) = if (isParentLookup) {
            val sourceDocument = getDocument(requestedUrl)
            val parentUrl = resolveParentTvShowUrl(sourceDocument)
                ?: throw IllegalStateException("Anime Online Ninja parent TV show not found for $requestedUrl")
            parentUrl to getDocument(parentUrl)
        } else {
            requestedUrl to getDocument(requestedUrl)
        }

        val title = document.extractDetailTitle().ifBlank { id }
        val poster = extractDetailArtwork(document, url)
        val seasons = parseSeasons(document, url, poster)
        val recommendations = document.select("#single_relacionados article, #single_relacionados .item")
            .mapNotNull(::parseListingItem)
            .filterIsInstance<Show>()
            .distinctBy(::itemKey)

        return TvShow(
            id = normalizeId(url, "/online/"),
            title = cleanTitle(title),
            overview = extractOverview(document, title),
            released = document.selectFirst("meta[property='article:published_time']")?.attr("content")?.take(10),
            poster = poster,
            banner = poster,
            seasons = seasons,
            recommendations = recommendations,
        )
    }

    private suspend fun resolveParentTvShowUrl(sourceDocument: Document): String? {
        sourceDocument.select(".pag_episodes .item a[href]")
            .firstOrNull { link ->
                link.text().contains("lista de episodios", ignoreCase = true) &&
                        link.attr("href").contains("/online/", ignoreCase = true)
            }
            ?.let { link -> link.absUrl("href").ifBlank { link.attr("href") } }
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        val sourceTitle = sourceDocument.extractDetailTitle()
        val parentTitle = cleanTitle(sourceTitle)
            .replace(
                Regex(
                    """\s*[-–—:]?\s*(?:temporada|episodio|cap[ií]tulo|cap)\s*\d+.*$""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(Regex("""\s*[-–—:]?\s*\d+\s*[x×]\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { cleanTitle(sourceTitle) }

        findMatchingShowUrl(sourceDocument, parentTitle, "/online/")?.let { return it }
        val query = URLEncoder.encode(parentTitle, "UTF-8")
        return findMatchingShowUrl(getDocument("$baseUrl/?s=$query"), parentTitle, "/online/")
    }

    private fun findMatchingShowUrl(document: Document, parentTitle: String, path: String): String? {
        val targetKey = titleKey(parentTitle)
        if (targetKey.isBlank()) return null

        return document.select("a[href*='$path']")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                val labels = listOf(
                    link.text(),
                    link.attr("title"),
                    link.selectFirst("img[alt]")?.attr("alt").orEmpty(),
                    link.parent()?.text().orEmpty(),
                    runCatching {
                        URLDecoder.decode(normalizeId(href, path), "UTF-8").replace('-', ' ')
                    }.getOrDefault(""),
                )
                val score = labels.maxOf { label ->
                    val candidateKey = titleKey(label)
                    when {
                        candidateKey == targetKey -> 100
                        candidateKey.startsWith(targetKey) -> 80
                        targetKey.startsWith(candidateKey) && candidateKey.length >= 6 -> 70
                        else -> 0
                    }
                }
                href to score
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
            ?.first
    }

    private fun titleKey(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}|\p{Cf}"""), "")
            .replace(Regex("""[^a-z0-9]+"""), "")

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val pageUrl = seasonId.substringBefore("#season-").ifBlank { seasonId }
        val seasonNumber = seasonId.substringAfter("#season-", "1").toIntOrNull() ?: 1
        val document = getDocument(pageUrl)
        val seasonBlock = document.select("#seasons .se-c, .se-c")
            .getOrNull(seasonNumber - 1)
            ?: document.select("#seasons .se-c, .se-c").firstOrNull()
        val episodeElements = seasonBlock?.select("ul.episodios li, ul.episodios > li")
            ?: document.select("ul.episodios li, ul.episodios > li")

        return episodeElements.mapIndexedNotNull { index, element ->
            val link = element.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapIndexedNotNull null
            val number = parseEpisodeNumber(
                element.selectFirst(".numerando, .num, .numero")?.text()?.trim().orEmpty(),
                index + 1,
            )
            val title = link.text().trim().ifBlank {
                element.selectFirst(".episodiotitle, .title, h3")?.text()?.trim().orEmpty()
            }
            val poster = element.selectFirst("img")?.let { image ->
                image.absUrl("data-src").ifBlank { image.absUrl("src") }.ifBlank { image.attr("src") }
            }?.takeIf(String::isNotBlank)?.let { artworkUrl(it, href) }

            Episode(id = href, number = number, title = title.ifBlank { "Episodio $number" }, poster = poster)
        }.distinctBy { it.id }.sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val slug = id.trim().trim('/')
        val path = if (slug == "tendencias" || slug == "ratings") slug else "genero/$slug"
        val url = if (page <= 1) "$baseUrl/$path/" else "$baseUrl/$path/page/$page/"
        val document = getDocument(url)
        val title = document.selectFirst(".module .content.right header h1, .module .content.right h1")
            ?.text()?.trim()
            ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

        return Genre(
            id = id,
            name = title,
            shows = parseArchiveItems(document).mapNotNull { it as? Show },
        )
    }

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = id, filmography = emptyList())

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val pageUrl = when (videoType) {
            is Video.Type.Movie -> toAbsoluteUrl(id, "/pelicula/")
            is Video.Type.Episode -> toAbsoluteUrl(id, "/episodio/")
        }
        val document = getDocument(pageUrl)
        val fallbackType = when (videoType) {
            is Video.Type.Movie -> "movie"
            is Video.Type.Episode -> "tv"
        }
        val discoveredSources = parsePlayerSources(document, fallbackType)
        val postId = discoveredSources.firstOrNull()?.postId ?: resolvePostId(pageUrl, document)
            ?: throw IllegalStateException("Anime Online Ninja post id not found for $pageUrl")
        val sources = discoveredSources.ifEmpty {
            Log.w(TAG, "No player source metadata found; probing known DooPlay source slots -> post=$postId")
            (1..5).map { source ->
                PlayerSource(postId, fallbackType, source, "Server $source")
            }
        }

        val collected = linkedMapOf<String, Video.Server>()
        for (source in sources) {
            val apiUrl = "$baseUrl/wp-json/dooplayer/v1/post/${source.postId}?type=${source.type}&source=${source.number}"
            val json = runCatching { fetchJson(apiUrl, pageUrl) }
                .onFailure { logFailure("player source ${source.number}", apiUrl, it) }
                .getOrNull() ?: continue
            val embedUrl = normalizeExternalUrl(json.optString("embed_url"), baseUrl) ?: continue
            val servers = runCatching { resolveServers(embedUrl, source.number, pageUrl) }
                .onFailure { logFailure("embed resolution", embedUrl, it) }
                .getOrDefault(emptyList())

            if (servers.isEmpty()) {
                collected.putIfAbsent(
                    embedUrl,
                    Video.Server(
                        id = embedUrl,
                        name = source.label.ifBlank { hostLabel(embedUrl, source.number) },
                        src = embedUrl,
                    ),
                )
            } else {
                servers.forEach { collected.putIfAbsent(it.id, it) }
            }
        }

        if (collected.isEmpty()) {
            directPlayerEmbeds(document).forEachIndexed { index, embedUrl ->
                collected.putIfAbsent(
                    embedUrl,
                    Video.Server(embedUrl, hostLabel(embedUrl, index + 1), embedUrl),
                )
            }
        }
        return prioritizeServers(collected.values.toList())
    }

    private fun parsePlayerSources(document: Document, fallbackType: String): List<PlayerSource> =
        document.select(
            "#playeroptionsul [data-nume], li.dooplay_player_option[data-nume], [data-post][data-nume][data-type]"
        ).mapNotNull { element ->
            val number = element.attr("data-nume").toIntOrNull() ?: return@mapNotNull null
            val postId = element.attr("data-post").trim()
                .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?: return@mapNotNull null
            val type = element.attr("data-type").trim().ifBlank { fallbackType }
            val label = element.selectFirst(".title, span.title, .server, span")
                ?.text()?.trim().orEmpty().ifBlank { "Server $number" }
            PlayerSource(postId, type, number, label)
        }.distinctBy { "${it.postId}:${it.type}:${it.number}" }

    private fun resolvePostId(pageUrl: String, document: Document): String? {
        Regex("""[?&]p=(\d+)""").find(pageUrl)?.groupValues?.getOrNull(1)?.let { return it }
        val shortlink = document.selectFirst("link[rel=shortlink]")?.attr("href").orEmpty()
        Regex("""[?&]p=(\d+)""").find(shortlink)?.groupValues?.getOrNull(1)?.let { return it }
        val html = document.outerHtml()
        return listOf(
            Regex("""postid-(\d+)"""),
            Regex("""post-(\d+)"""),
            Regex("""data-post=["'](\d+)["']"""),
            Regex("""data-id=["'](\d+)["']"""),
        ).firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
    }

    private fun directPlayerEmbeds(document: Document): List<String> =
        document.select(
            "#dooplay_player_response iframe[src], #playeroptions iframe[src], .player_sist iframe[src], .playex iframe[src]"
        ).mapNotNull { element ->
            normalizeExternalUrl(
                element.absUrl("src").ifBlank { element.attr("src") },
                document.baseUri(),
            )
        }.distinct()

    private fun resolveServers(embedUrl: String, source: Int, pageUrl: String): List<Video.Server> {
        val response = executeGet(
            embedUrl,
            mapOf(
                "User-Agent" to NetworkClient.USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to pageUrl,
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
            ),
        )
        if (!response.isSuccessful) {
            throw IllegalStateException("Anime Online Ninja embed HTTP ${response.statusCode}: $embedUrl")
        }
        val document = Jsoup.parse(response.body, response.finalUrl)
        val servers = linkedMapOf<String, Video.Server>()

        document.select("li[onclick*='go_to_player']").forEachIndexed { index, element ->
            val serverUrl = Regex("""go_to_player\s*\(\s*['\"]([^'\"]+)['\"]\s*\)""")
                .find(element.attr("onclick"))?.groupValues?.getOrNull(1)
                ?.let { normalizeExternalUrl(it, embedUrl) }
                ?: return@forEachIndexed
            val label = element.selectFirst("span")?.text()?.trim().orEmpty()
                .ifBlank { hostLabel(serverUrl, index + 1) }
            val group = element.parents().firstOrNull { parent ->
                parent.classNames().any { it.startsWith("OD_") }
            }?.classNames()?.firstOrNull { it.startsWith("OD_") }?.removePrefix("OD_")
            val name = if (group.isNullOrBlank()) label else "$label ${group.uppercase()}"
            servers.putIfAbsent(serverUrl, Video.Server(serverUrl, name, serverUrl))
        }

        document.select("li[data-link], li[data-url], .server[data-link], .server[data-url]")
            .forEachIndexed { index, element ->
                val serverUrl = normalizeExternalUrl(
                    element.attr("data-link").ifBlank { element.attr("data-url") },
                    embedUrl,
                ) ?: return@forEachIndexed
                val label = element.selectFirst(".title, span")?.text()?.trim().orEmpty()
                    .ifBlank { hostLabel(serverUrl, index + 1) }
                servers.putIfAbsent(serverUrl, Video.Server(serverUrl, label, serverUrl))
            }

        if (servers.isEmpty()) {
            document.select("iframe[src]").forEachIndexed { index, element ->
                val serverUrl = normalizeExternalUrl(
                    element.absUrl("src").ifBlank { element.attr("src") },
                    embedUrl,
                ) ?: return@forEachIndexed
                servers.putIfAbsent(
                    serverUrl,
                    Video.Server(serverUrl, hostLabel(serverUrl, index + 1), serverUrl),
                )
            }
        }
        return servers.values.toList()
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    private fun prioritizeServers(servers: List<Video.Server>): List<Video.Server> {
        val preferred = UserPreferences
            .getProviderCache(this, UserPreferences.PROVIDER_PREFERRED_SERVER)
            .trim().uppercase()
        if (preferred.isBlank()) return servers
        val (preferredServers, fallbackServers) = servers.partition { server ->
            preferred in server.name.uppercase()
                .split(Regex("""[^A-Z0-9]+"""))
                .filter(String::isNotBlank)
                .toSet()
        }
        return if (preferredServers.isEmpty()) servers else preferredServers + fallbackServers
    }

    private fun getListing(url: String): List<AppAdapter.Item> =
        parseArchiveItems(getDocument(url))

    private fun listingUrl(path: String, page: Int): String =
        if (page <= 1) "$baseUrl/$path/" else "$baseUrl/$path/page/$page/"

    private fun parseHomeCategories(document: Document): List<Category> {
        val categories = linkedMapOf<String, Category>()
        parseHomeModules(document).forEach { categories.putIfAbsent(it.name, it) }
        listOfNotNull(
            parseHomeSection(document, "#featured-titles", Category.FEATURED),
            parseHomeSection(document, "#dt-episodes", "ÚLTIMOS EPISODIOS"),
            parseHomeSection(document, "#slider-movies-tvshows", "EN EMISIÓN 🔥 RECOMENDADOS"),
            parseHomeSection(document, "#slider-tvshows", "ÚLTIMOS ANIMES AGREGADOS 💥"),
            parseHomeSection(document, "#slider-movies", "ÚLTIMAS PELICULAS AGREGADAS 🎬"),
            parseHomeSection(document, "#dt-seasons", "TEMPORADAS 📺"),
        ).forEach { categories.putIfAbsent(it.name, it) }
        return categories.values.toList()
    }

    private fun parseHomeModules(document: Document): List<Category> =
        document.select(".module header").mapNotNull { header ->
            val title = header.selectFirst("h1, h2, h3")?.text()?.trim()?.let(::cleanTitle)
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val items = generateSequence(header.nextElementSibling()) { it.nextElementSibling() }
                .takeWhile { it.tagName() != "header" }
                .flatMap { parseListingItems(it).asSequence() }
                .distinctBy(::itemKey).toList()
            items.takeIf(List<AppAdapter.Item>::isNotEmpty)?.let { Category(title, it) }
        }

    private fun parseHomeSection(document: Document, selector: String, title: String): Category? {
        val root = document.selectFirst(selector) ?: return null
        val items = parseListingItems(root).distinctBy(::itemKey)
        return items.takeIf(List<AppAdapter.Item>::isNotEmpty)?.let { Category(title, it) }
    }

    private fun parseListingItems(root: Element): List<AppAdapter.Item> =
        listOf(
            ".search-page .result-item article",
            ".result-item article",
            "article.TPost",
            "li.TPostMv article",
            ".TPost",
            ".items .item",
            "article[class*='post-']",
            "article",
        ).flatMap { root.select(it) }
            .mapNotNull(::parseListingItem)
            .distinctBy(::itemKey)

    private fun parseSearchItems(document: Document): List<AppAdapter.Item> =
        document.select(".search-page > .result-item > article, .search-page .result-item article")
            .mapNotNull(::parseListingItem)
            .distinctBy(::itemKey)

    private fun parseArchiveItems(document: Document): List<AppAdapter.Item> {
        val grid = document.selectFirst("#archive-content")
            ?: document.selectFirst(".module > .content.right > .items:not(.featured)")
            ?: document.selectFirst(".module .content.right .items:not(.featured)")
            ?: return emptyList()
        return grid.children()
            .filter { it.hasClass("item") || it.tagName() == "article" }
            .mapNotNull(::parseListingItem)
            .distinctBy(::itemKey)
    }

    private fun parseListingItem(element: Element): AppAdapter.Item? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        if (href.isBlank()) return null
        val title = listOfNotNull(
            element.selectFirst(".details .title a, .data h3.title, .data h3 a, .data h3, h2 a, h2, h3 a, h3, .Title, .name, .title")?.text()?.trim(),
            link.text().trim().takeIf(String::isNotBlank),
            link.attr("title").trim().takeIf(String::isNotBlank),
            element.selectFirst("img[alt]")?.attr("alt")?.trim(),
        ).firstOrNull()?.let(::cleanTitle).orEmpty()
        val poster = element.selectFirst("img")?.let { image ->
            image.absUrl("data-src").ifBlank { image.absUrl("src") }.ifBlank { image.attr("src") }
        }?.takeIf(String::isNotBlank)
            ?.let { artworkUrl(it, element.ownerDocument()?.location().orEmpty().ifBlank { baseUrl }) }

        return when {
            href.contains("/pelicula/", true) -> Movie(
                id = normalizeId(href, "/pelicula/"),
                title = title.ifBlank { href.substringAfterLast('/').replace('-', ' ') },
                poster = poster,
            )
            href.contains("/online/", true) -> TvShow(
                id = normalizeId(href, "/online/"),
                title = title.ifBlank { href.substringAfterLast('/').replace('-', ' ') },
                poster = poster,
            )
            href.contains("/episodio/", true) || href.contains("/temporada/", true) -> {
                val parentTitle = listOfNotNull(
                    element.selectFirst("img[alt]")?.attr("alt")?.substringBefore(" Temporada")?.substringBefore(" Cap")?.trim(),
                    element.selectFirst(".data h3")?.text()?.trim(),
                    element.selectFirst(".season_m .c")?.text()?.trim(),
                ).firstOrNull { it.isNotBlank() }?.let(::cleanTitle) ?: return null
                TvShow(id = href, title = parentTitle, poster = poster)
            }
            else -> null
        }
    }

    private fun extractDetailArtwork(document: Document, referer: String): String? {
        val pagePoster = document.selectFirst(".sheader .poster img, #single .poster img, article.post .poster img")
            ?.let { image ->
                image.absUrl("data-src").ifBlank { image.attr("data-src") }
                    .ifBlank { image.absUrl("src") }.ifBlank { image.attr("src") }
            }?.takeIf { it.isNotBlank() && !it.startsWith("data:", true) }
        val openGraphPoster = document.selectFirst("meta[property='og:image']")
            ?.attr("content")?.trim()?.takeIf(String::isNotBlank)
        return artworkUrl(pagePoster ?: openGraphPoster, referer)
    }

    private fun cleanTitle(value: String): String = value
        .substringBefore("|").removePrefix("▷")
        .replace(Regex("""\s*【.*?】\s*"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun extractOverview(document: Document, title: String?): String? =
        extractSynopsisText(document, title)?.takeIf(String::isNotBlank)
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.cleanOverviewText(title)

    private fun extractSynopsisText(document: Document, title: String?): String? {
        val heading = document.select("*").firstOrNull { element ->
            val text = element.ownText().trim()
            text.equals("Sinopsis", true) || text.startsWith("Sinopsis", true)
        } ?: return null
        val container = when {
            heading.nextElementSibling()?.classNames()?.contains("wp-content") == true -> heading.nextElementSibling()
            heading.parent()?.classNames()?.contains("wp-content") == true -> heading.parent()
            else -> heading.nextElementSibling()?.selectFirst(".wp-content") ?: heading.parent()?.selectFirst(".wp-content")
        }
        container?.select("p, li")?.mapNotNull { it.text().trim().cleanOverviewText(title) }
            ?.distinct()?.takeIf(List<String>::isNotEmpty)?.let { return it.joinToString("\n\n") }
        return null
    }

    private fun String.cleanOverviewText(title: String?): String? {
        val normalized = replace(Regex("""\s+"""), " ").trim()
        if (normalized.length < 10) return null
        val lower = normalized.lowercase()
        if (Regex("""^ver\s+.+\s+(online|mega|sub español|audio español)""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) return null
        if (lower.contains("sakura mail") && lower.contains("online") && lower.contains("descargar") && lower.contains("mega")) return null
        if (lower == title?.lowercase()) return null
        return normalized
    }

    private fun Document.extractDetailTitle(): String =
        selectFirst(".sheader .data h1, .sheader h1, #single h1, main h1, h1[itemprop='name'], meta[property='og:title'], meta[name='twitter:title']")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content").trim() else it.text().trim() }
            .orEmpty()

    private fun parseSeasons(document: Document, pageUrl: String, poster: String?): List<Season> {
        val blocks = document.select("#seasons .se-c, .se-c")
        if (blocks.isEmpty()) {
            val episodes = parseSeasonEpisodes(document, 1)
            return if (episodes.isEmpty()) emptyList() else listOf(
                Season("$pageUrl#season-1", 1, "Temporada 1", poster = poster, episodes = episodes)
            )
        }
        return blocks.mapIndexed { index, block ->
            val number = block.attr("data-season").toIntOrNull()
                ?: Regex("""\d+""").find(block.selectFirst(".se-t, .title, .season-title")?.text().orEmpty())?.value?.toIntOrNull()
                ?: index + 1
            val title = block.selectFirst(".se-t, .title, .season-title")?.text()?.trim() ?: "Temporada $number"
            Season(
                id = "$pageUrl#season-$number",
                number = number,
                title = title,
                poster = poster,
                episodes = parseSeasonEpisodes(block, number),
            )
        }.sortedBy { it.number }
    }

    private fun parseSeasonEpisodes(root: Element, @Suppress("UNUSED_PARAMETER") seasonNumber: Int): List<Episode> =
        root.select("ul.episodios li, ul.episodios > li").mapIndexedNotNull { index, element ->
            val link = element.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapIndexedNotNull null
            val number = parseEpisodeNumber(element.selectFirst(".numerando, .num, .numero")?.text().orEmpty(), index + 1)
            val title = link.text().trim().ifBlank { "Episodio $number" }
            val episodePoster = element.selectFirst("img")?.let { image ->
                image.absUrl("data-src").ifBlank { image.absUrl("src") }.ifBlank { image.attr("src") }
            }?.takeIf(String::isNotBlank)?.let { artworkUrl(it, href) }
            Episode(href, number, title, poster = episodePoster)
        }.distinctBy { it.id }.sortedBy { it.number }

    private fun parseEpisodeNumber(value: String, fallback: Int): Int =
        Regex("""\d+""").findAll(value).mapNotNull { it.value.toIntOrNull() }.lastOrNull()?.takeIf { it > 0 } ?: fallback

    private fun toAbsoluteUrl(id: String, preferredPrefix: String? = null): String = when {
        id.startsWith("http", true) -> id
        preferredPrefix != null -> {
            val prefix = preferredPrefix.trim('/')
            val cleanId = id.trim('/')
            if (cleanId.startsWith(prefix)) "$baseUrl/$cleanId" else "$baseUrl/$prefix/$cleanId"
        }
        id.startsWith("/") -> "$baseUrl$id"
        else -> "$baseUrl/$id"
    }

    private fun normalizeId(url: String, prefix: String): String =
        url.substringAfter(prefix, url).trim('/').removeSuffix("/")

    private fun normalizeExternalUrl(value: String?, referer: String): String? {
        val url = value?.trim().orEmpty()
        if (url.isBlank() || url.equals("about:blank", true)) return null
        return runCatching {
            when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("http://", true) || url.startsWith("https://", true) -> url
                else -> URL(URL(referer), url).toString()
            }
        }.getOrNull()
    }

    private fun hostLabel(url: String, source: Int): String = runCatching {
        URL(url).host.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
    }.getOrNull() ?: "Server $source"

    private fun itemKey(item: AppAdapter.Item): String = when (item) {
        is Movie -> "movie:${item.id}"
        is TvShow -> "tv:${item.id}"
        is Genre -> "genre:${item.id}"
        else -> item.toString()
    }

    private fun cacheDocument(requestUrl: String, document: Document) {
        documentCache[requestUrl] = CachedDocument(
            document = document.clone(),
            finalUrl = document.baseUri(),
            expiresAt = System.currentTimeMillis() + DOCUMENT_CACHE_TTL_MS,
        )
    }

    private fun cachedDocument(url: String): CachedDocument? {
        val cached = documentCache[url] ?: return null
        if (cached.expiresAt < System.currentTimeMillis()) {
            documentCache.remove(url)
            return null
        }
        return cached
    }

    private fun logFailure(operation: String, url: String, error: Throwable) {
        Log.w(TAG, "$operation failed -> url=$url type=${error.javaClass.simpleName} message=${error.message}")
    }

    private data class PlayerSource(
        val postId: String,
        val type: String,
        val number: Int,
        val label: String,
    )

    private data class HttpResponse(
        val statusCode: Int,
        val finalUrl: String,
        val body: String,
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299
    }

    private data class CachedDocument(
        val document: Document,
        val finalUrl: String,
        val expiresAt: Long,
    )
}
