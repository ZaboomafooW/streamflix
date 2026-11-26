package com.streamflixreborn.streamflix.kids

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.streamflixreborn.streamflix.utils.ServerAvailability
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import androidx.activity.OnBackPressedCallback
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class KidFlixFragment : Fragment() {
    private val hidden get() = requireContext().getSharedPreferences("kidflix_hidden", Context.MODE_PRIVATE)
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var shelf: Shelf? = null
    private var query = ""
    private var page = 0
    private var more = false
    private var job: Job? = null
    private val titles = mutableListOf<KidTitle>()
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun column() = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
    private val ink = Color.rgb(18, 33, 54)
    private val mint = Color.rgb(139, 240, 205)

    private fun tile(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private fun focusBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), tile(mint))
        addState(intArrayOf(), tile(Color.rgb(38, 57, 81)))
    }

    private fun button(text: String, action: () -> Unit) = Button(requireContext()).apply {
        this.text = text
        isAllCaps = false
        minHeight = dp(36)
        minimumHeight = dp(36)
        setPadding(dp(15), 0, dp(15), 0)
        backgroundTintList = null
        background = focusBackground()
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(ink, Color.WHITE)))
        setOnClickListener { action() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val shell = LinearLayout(requireContext()).apply { setBackgroundColor(Color.rgb(19, 38, 66)) }
        val rail = column().apply {
            setBackgroundColor(ink)
            setPadding(dp(10), dp(18), dp(10), dp(12))
        }
        shell.addView(rail, dp(144), -1)
        rail.addView(label("KidFlix", 25f).apply {
            setTextColor(mint)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, 0, dp(16))
        })
        destinations.clear()
        for (entry in Destination.values()) {
            val item = button(entry.caption) {
                destination = entry
                channel = null
                home()
            }
            item.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), tile(mint))
                addState(intArrayOf(android.R.attr.state_selected), tile(Color.rgb(34, 114, 109)))
                addState(intArrayOf(), tile(ink))
            }
            destinations[entry] = item
            rail.addView(item)
        }
        rail.addView(button("Search") { input("Search KidFlix", query) { if (it.isNotBlank()) open(null, it.trim()) } })
        rail.addView(button("Settings") { settings() })

        val content = column().apply { setPadding(dp(14), dp(14), dp(14), dp(10)) }
        shell.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        status = label("", 20f).apply { setPadding(0, 0, 0, dp(8)) }
        content.addView(status)
        body = column()
        scroll = ScrollView(requireContext()).apply { addView(body) }
        content.addView(scroll)
        return FrameLayout(requireContext()).apply { addView(shell) }
    }

    private enum class Destination(val caption: String) {
        HOME("Home"), SHOWS("TV Shows"), MOVIES("Movies"), CHANNELS("Channels")
    }

    private var destination = Destination.HOME
    private var channel: String? = null
    private val destinations = mutableMapOf<Destination, Button>()
    private lateinit var scroll: ScrollView
    private val canGoBack get() = shelf != null || query.isNotBlank() || destination != Destination.HOME

    private var currentMovie: Pair<KidTitle, TMDb3.Movie.Detail>? = null
    private var homeExpansion: Job? = null
    private var currentShow: Pair<KidTitle, TMDb3.Tv.Detail>? = null
    private var seasonNumber = 0
    private var showStatus: TextView? = null
    private var returnToHero: (() -> Unit)? = null
    private var browseFocus: View? = null
    private lateinit var browseBack: OnBackPressedCallback

    override fun onViewCreated(view: View, state: Bundle?) {
        browseBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (currentShow == null && currentMovie == null) {
                    val previousChannel = channel.takeIf { shelf == null && query.isBlank() }
                    if (shelf == null && query.isBlank()) {
                        if (channel != null) channel = null else destination = Destination.HOME
                    }
                    home()
                    val target = previousChannel?.let { body.findViewWithTag<View>(it) } ?: destinations[destination]
                    target?.requestFocus()
                    return
                }
                returnToHero?.let { it(); return }
                job?.cancel()
                val frame = requireView() as FrameLayout
                frame.removeViewAt(1)
                frame.getChildAt(0).visibility = View.VISIBLE
                currentMovie = null
                currentShow = null
                showStatus = null
                browseFocus?.requestFocus()
                browseFocus = null
                isEnabled = canGoBack
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,browseBack)
        browseBack.isEnabled = canGoBack
        if (shelf != null || query.isNotBlank()) render() else home()
        currentMovie?.let { (title, movie) -> moviePage(title, movie) }
        currentShow?.let { (title, tv) -> showPage(title, tv) }
    }
    override fun onDestroyView() {
        job?.cancel()
        showStatus = null
        browseFocus = null
        returnToHero = null
        destinations.clear()
        super.onDestroyView()
    }
    private fun home() {
        job?.cancel()
        shelf = null
        query = ""
        titles.clear()
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        destinations.forEach { (entry, item) -> item.isSelected = entry == destination }
        status.text = channel ?: destination.caption
        browseBack.isEnabled = canGoBack
        homeExpansion?.cancel()
        val catalog = KidCatalog.catalog.filterNot { hidden.contains(it.key) }.sortedWith(KidCatalog.newestFirst)
        if (destination == Destination.HOME) {
            val watching = column().apply { visibility = View.GONE }
            body.addView(watching)
            addShelf("Newest Releases", catalog.take(20))
            for (genre in KidCatalog.homeGenres) {
                addShelf(genre, catalog.filter { genre in it.genres })
            }
            homeExpansion = viewLifecycleOwner.lifecycleScope.launch { continueWatching(watching) }
            return
        }
        if (destination == Destination.CHANNELS && channel == null) {
            channelCards(catalog)
            return
        }
        val rows = mutableMapOf<Shelf, LinearLayout>()
        val sections = KidCatalog.shelves.filter {
            when (destination) {
                Destination.SHOWS -> !it.movie
                Destination.MOVIES -> it.movie
                Destination.CHANNELS -> channelName(it) == channel
                Destination.HOME -> false
            }
        }.sortedWith(compareByDescending<Shelf> { it.brand == "PBS KIDS" }
            .thenByDescending { section -> catalog.firstOrNull { it.belongsTo(section) }?.releaseDate.orEmpty() }
            .thenBy { it.label })
        sections.forEach { section ->
            val preview = catalog.filter { it.belongsTo(section) }
            if (preview.isNotEmpty()) {
                val header = LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0,dp(12),0,dp(6))
                }
                header.addView(TextView(requireContext()).apply {
                    text = if (channel == null) section.brand else if (section.movie) "Movies" else "TV Shows"
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setTypeface(null,Typeface.BOLD)
                },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
                header.addView(button("Browse all") { open(section, "") }.apply {
                    contentDescription = "Browse all " + section.label
                })
                body.addView(header)
                val row = LinearLayout(requireContext())
                rows[section] = row
                preview.forEach { row.addView(card(it),dp(114),ViewGroup.LayoutParams.WRAP_CONTENT) }
                body.addView(HorizontalScrollView(requireContext()).apply { addView(row) })
            } else if (KidCatalog.catalog.none { it.belongsTo(section) }) {
                body.addView(button(section.label) { open(section, "") })
            }
        }
        if (channel != null && body.childCount == 0) body.addView(label("No visible titles", 16f))
        homeExpansion = viewLifecycleOwner.lifecycleScope.launch {
            rows.forEach { (section, row) ->
                launch {
                    KidCatalog.expand(section) { title ->
                        if (!hidden.contains(title.key)) {
                            val old = (0 until row.childCount).firstOrNull { (row.getChildAt(it).tag as KidTitle).key == title.key }
                            if (old != null) row.removeViewAt(old)
                            val index = (0 until row.childCount).firstOrNull {
                                KidCatalog.newestFirst.compare(title, row.getChildAt(it).tag as KidTitle) < 0
                            } ?: row.childCount
                            row.addView(card(title), index, LinearLayout.LayoutParams(dp(114), ViewGroup.LayoutParams.WRAP_CONTENT))
                        }
                    }
                }
            }
        }
    }
    private fun addShelf(name: String, items: List<KidTitle>) {
        if (items.isEmpty()) return
        body.addView(label(name, 20f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(6))
        })
        val row = LinearLayout(requireContext())
        items.forEach { row.addView(card(it), dp(114), -2) }
        body.addView(HorizontalScrollView(requireContext()).apply { addView(row) })
    }

    private fun channelName(shelf: Shelf) = if (shelf.brand == "Hulu / 20th Century") "Hulu" else shelf.brand

    private fun channelCards(catalog: List<KidTitle>) {
        // The first title for each brand is its newest visible release.
        val newest = mutableMapOf<String, KidTitle>()
        for (title in catalog) {
            for (brand in title.channels + channelName(title.shelf)) newest.getOrPut(brand) { title }
        }
        val brands = KidCatalog.shelves.map(::channelName).distinct().sortedWith(
            compareByDescending<String> { it == "PBS KIDS" }.thenByDescending { newest[it]?.releaseDate.orEmpty() }.thenBy { it })
        val columns = ((resources.configuration.screenWidthDp - 172) / 170).coerceIn(1, 4)
        for (batch in brands.chunked(columns)) {
            val row = LinearLayout(requireContext())
            body.addView(row)
            for (brand in batch) {
                val tile = column().apply {
                    background = focusBackground()
                    isFocusable = true
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    tag = brand
                    contentDescription = brand
                    setOnClickListener {
                        channel = brand
                        home()
                        body.requestFocus(View.FOCUS_DOWN)
                    }
                }
                val image = ImageView(requireContext()).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                tile.addView(image, -1, dp(84))
                artwork(image, newest[brand]?.poster?.w500)
                tile.addView(label(brand, 16f).apply {
                    gravity = Gravity.CENTER
                    setBackgroundColor(ink)
                })
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            repeat(columns - batch.size) { row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f)) }
        }
    }

    private val airedEpisodes get() = requireContext().getSharedPreferences("kidflix_last_aired", Context.MODE_PRIVATE)

    private fun rememberLastAired(id: String, episodeId: Int) {
        airedEpisodes.edit().putInt(id, episodeId)
            .putLong("checked/$id", System.currentTimeMillis()).apply()
    }

    private suspend fun continueWatching(section: LinearLayout) {
        val db = AppDatabase.getInstance(requireContext())
        val catalog = KidCatalog.catalog.associateBy { it.key }
        val movies = db.movieDao().getWatchingMovies().first().filter {
            !it.isWatched && (it.watchHistory?.lastPlaybackPositionMillis ?: 0L) > 0L
        }
        val episodes = withContext(Dispatchers.IO) { db.episodeDao().getPlaybackHistory() }
        val watching = movies.mapNotNull { catalog["movie/${it.id}"] }.toMutableList()
        for ((id, history) in episodes.groupBy { it.tvShow?.id }) {
            if (id == null || hidden.contains("tv/$id")) continue
            val title = catalog["tv/$id"] ?: try {
                id.toIntOrNull()?.let { KidCatalog.show(it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { null }
            if (title == null) continue
            if (history.any { !it.isWatched && it.watchHistory != null }) {
                watching.add(title)
                continue
            }
            var latest = airedEpisodes.getInt(id, 0)
            if (latest == 0 || System.currentTimeMillis() - airedEpisodes.getLong("checked/$id", 0L) >= 86400000L) {
                try {
                    latest = TMDb3.TvSeries.details(title.id).lastEpisodeToAir?.id ?: 0
                    if (latest != 0) rememberLastAired(id, latest)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (latest == 0) continue }
            }
            if (latest != 0 && history.none { it.id == latest.toString() && it.isWatched }) watching.add(title)
        }
        val visible = watching.filterNot { hidden.contains(it.key) }.sortedWith(KidCatalog.newestFirst)
        if (visible.isEmpty()) return
        section.addView(label("Continue Watching", 20f).apply { setTypeface(null, Typeface.BOLD) })
        val row = LinearLayout(requireContext())
        visible.forEach { row.addView(card(it), dp(114), ViewGroup.LayoutParams.WRAP_CONTENT) }
        section.addView(HorizontalScrollView(requireContext()).apply { addView(row) })
        section.visibility = View.VISIBLE
    }

    private fun input(label: String, value: String, save: (String) -> Unit) {
        val field = EditText(requireContext()).apply { setSingleLine(); setText(value) }
        AlertDialog.Builder(requireContext()).setTitle(label).setView(field)
            .setNegativeButton("Cancel",null).setPositiveButton("OK") { _,_ -> save(field.text.toString().trim()) }.show()
    }
    private fun settings() {
        AlertDialog.Builder(requireContext()).setTitle("KidFlix settings")
            .setItems(arrayOf("Hidden titles","TMDb API key")) { _, n ->
                if (n == 0) {
                    val items = hidden.all.entries.sortedBy { it.value.toString() }
                    if (items.isEmpty()) Toast.makeText(requireContext(),"No hidden titles",Toast.LENGTH_SHORT).show()
                    else AlertDialog.Builder(requireContext()).setTitle("Select a title to restore")
                        .setItems(items.map { it.value.toString() }.toTypedArray()) { _, i ->
                            hidden.edit().remove(items[i].key).apply()
                            if (shelf != null || query.isNotEmpty()) open(shelf,query) else home()
                        }.show()
                } else input("TMDb API key","") { UserPreferences.tmdbApiKey = it
                    if (shelf != null || query.isNotBlank()) open(shelf,query)
                }
            }.show()
    }
    private fun open(selection: Shelf?, term: String) {
        homeExpansion?.cancel()
        scroll.scrollTo(0, 0)
        shelf = selection
        query = term
        browseBack.isEnabled = true
        page = 0
        more = false
        titles.clear()
        titles.addAll(KidCatalog.catalog.filter { (selection == null || it.belongsTo(selection)) && it.name.contains(term,true) })
        if (selection != null && (selection.movie || selection.ids.isEmpty())) {
            page = 1
            render()
        } else {
            load()
        }
    }
    private fun load() {
        job?.cancel()
        render()
        if (UserPreferences.tmdbApiKey.isBlank() && BuildConfig.TMDB_API_KEY in listOf("null","")) {
            status.text = "Add your TMDb API key in Settings to load titles."
            return
        }
        status.text = "Loading…"
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = KidCatalog.page(shelf,query,page + 1)
                titles.addAll(result.titles)
                page++
                more = result.more
                render()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status.text = "Couldn’t load titles. Check the connection and TMDb key."
                body.addView(button("Retry") { load() })
            }
        }
    }
    private fun render() {
        body.removeAllViews()
        val visible = titles.distinctBy { it.key }.filterNot { hidden.contains(it.key) }.sortedWith(KidCatalog.newestFirst)
        status.text = (shelf?.label ?: "Search · $query") + if (visible.isEmpty() && page > 0) " · No matching titles on this page" else ""
        val columns = ((resources.configuration.screenWidthDp - 172) / 114).coerceIn(1, 6)
        visible.chunked(columns).forEach { batch ->
            val row = LinearLayout(requireContext())
            batch.forEach { title ->
                row.addView(card(title),dp(114),ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            body.addView(row)
        }
        if (more) body.addView(button("Load more") { load() })
    }
    private fun artwork(image: ImageView, url: String?) {
        if (url.isNullOrBlank()) return
        val context = image.context.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val model: Any = try {
                KidArtwork.localCopy(context, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                url
            }
            Glide.with(image).load(model).into(image)
        }
    }

    private fun card(title: KidTitle): View {
        val card = column().apply {
            tag = title
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(4),dp(4),dp(4),dp(4))
            setOnFocusChangeListener { _, focused ->
                background = tile(if (focused) Color.rgb(34, 114, 109) else Color.TRANSPARENT)
            }
            setOnClickListener { select(title) }
            setOnLongClickListener {
                job?.cancel()
                hidden.edit().putString(title.key,title.name).apply()
                if (shelf == null && query.isEmpty()) home() else render()
                true
            }
        }
        val image = ImageView(requireContext()).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(image,dp(98),dp(138))
        artwork(image, title.poster?.w500)
        card.addView(TextView(requireContext()).apply { text = title.name; maxLines = 2; gravity = Gravity.CENTER; setTextColor(Color.WHITE) })
        return card
    }
    private fun select(title: KidTitle) {
        if (hidden.contains(title.key)) return
        request {
            if (title.shelf.movie) {
                val m = TMDb3.Movies.details(title.id)
                val db = AppDatabase.getInstance(requireContext())
                withContext(Dispatchers.IO) {
                    db.movieDao().save(Movie(id=m.id.toString(), title=m.title, overview=m.overview, released=m.releaseDate,
                        poster=m.posterPath?.w500, banner=m.backdropPath?.w500, imdbId=m.imdbId))
                }
                currentMovie = title to m
                moviePage(title, m)
            } else {
                val tv = TMDb3.TvSeries.details(title.id,listOf(TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS))
                val db = AppDatabase.getInstance(requireContext())
                withContext(Dispatchers.IO) {
                    db.tvShowDao().save(TvShow(id=tv.id.toString(), title=tv.name, overview=tv.overview, released=tv.firstAirDate,
                        poster=tv.posterPath?.w500, banner=tv.backdropPath?.w500, imdbId=tv.externalIds?.imdbId))
                }
                tv.lastEpisodeToAir?.let { latest -> rememberLastAired(tv.id.toString(), latest.id) }
                seasonNumber = 0
                currentShow = title to tv
                showPage(title, tv)
            }
        }
    }
    private fun request(action: suspend () -> Unit) {
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { (showStatus ?: status).text = "Couldn’t load this title. Select it to retry." }
        }
    }
    private fun label(value: String, size: Float) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
    }

    private fun hero(name: String, backdrop: String?): Pair<FrameLayout, LinearLayout> {
        val frame = requireView() as FrameLayout
        browseFocus = frame.findFocus()
        frame.getChildAt(0).visibility = View.GONE
        browseBack.isEnabled = true
        val layer = FrameLayout(requireContext()).apply { setBackgroundColor(ink) }
        frame.addView(layer)
        val hero = FrameLayout(requireContext())
        layer.addView(hero)
        val artwork = ImageView(requireContext()).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        hero.addView(artwork, -1, -1)
        val artUrl = if (backdrop?.startsWith("/") == true) "https://image.tmdb.org/t/p/original$backdrop" else backdrop
        this.artwork(artwork, artUrl)
        hero.addView(View(requireContext()).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(235,18,33,54), Color.argb(175,18,33,54), Color.TRANSPARENT))
        }, -1, -1)
        val info = column().apply { setPadding(dp(40), 0, dp(20), dp(24)) }
        hero.addView(info, FrameLayout.LayoutParams(dp(420), -2, Gravity.BOTTOM or Gravity.START))
        info.addView(label(name, 36f).apply { setTypeface(null, Typeface.BOLD) })
        return layer to info
    }

    private fun moviePage(title: KidTitle, movie: TMDb3.Movie.Detail) {
        val (_, info) = hero(movie.title, movie.backdropPath ?: movie.posterPath)
        info.addView(label(movie.releaseDate.orEmpty().take(4), 15f))
        info.addView(label(movie.overview.orEmpty(), 16f).apply { maxLines = 5 })
        val notice = label("", 14f)
        showStatus = notice
        info.addView(notice)
        val type = Video.Type.Movie(movie.id.toString(), movie.title, movie.releaseDate.orEmpty(),
            movie.posterPath?.w500.orEmpty(), movie.imdbId)
        val actions = LinearLayout(requireContext())
        info.addView(actions)
        val primary = button("Play") { play(title, type) }
        actions.addView(primary)
        actions.addView(button("Restart") { play(title, type, true) })
        primary.requestFocus()
        request {
            val db = AppDatabase.getInstance(requireContext())
            val progress = withContext(Dispatchers.IO) { db.movieDao().getById(type.id)?.watchHistory }
            if ((progress?.lastPlaybackPositionMillis ?: 0L) > 0L) primary.text = "Continue"
        }
    }

    private fun showPage(title: KidTitle, tv: TMDb3.Tv.Detail) {
        val (layer, info) = hero(tv.name, tv.backdropPath ?: tv.posterPath)
        val hero = layer.getChildAt(0)
        val available = tv.seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
        info.addView(label(listOfNotNull(tv.firstAirDate?.take(4), "${available.size} seasons").joinToString(" · "), 15f))
        val episodeTitle = label("", 21f)
        val synopsis = label(tv.overview, 16f).apply { maxLines = 4 }
        val heroStatus = label("", 14f)
        info.addView(episodeTitle)
        info.addView(synopsis)
        info.addView(heroStatus)
        val primary = button("Play episode") {}
        val actions = LinearLayout(requireContext())
        info.addView(actions)
        actions.addView(primary)
        val restart = button("Restart") {}
        actions.addView(restart)
        val browse = button("Episodes") {}
        info.addView(browse, -2, -2)

        val browser = column().apply {
            visibility = View.GONE
            setPadding(dp(30), dp(16), dp(30), dp(10))
        }
        layer.addView(browser)
        browser.addView(label(tv.name, 24f))
        val notice = label("Episodes", 16f)
        browser.addView(notice)
        val panes = LinearLayout(requireContext())
        browser.addView(panes, -1, -1)
        val seasons = column()
        val episodes = column().apply { setPadding(dp(12), 0, 0, 0) }
        panes.addView(ScrollView(requireContext()).apply { addView(seasons) }, dp(165), -1)
        val episodeScroll = ScrollView(requireContext()).apply { addView(episodes) }
        panes.addView(episodeScroll, LinearLayout.LayoutParams(0, -1, 1f))
        val series = Video.Type.Episode.TvShow(tv.id.toString(), tv.name, tv.posterPath?.w500,
            tv.backdropPath?.w500, tv.firstAirDate, tv.externalIds?.imdbId)
        val cache = mutableMapOf<Int, List<TMDb3.Episode>>()
        suspend fun episodesFor(season: TMDb3.Season): List<TMDb3.Episode> =
            cache[season.seasonNumber] ?: TMDb3.TvSeasons.details(tv.id, season.seasonNumber)
                .episodes.orEmpty().sortedBy { it.episodeNumber }.also { cache[season.seasonNumber] = it }

        fun startEpisode(season: TMDb3.Season, episode: TMDb3.Episode, restart: Boolean = false) {
            seasonNumber = season.seasonNumber
            play(title, Video.Type.Episode(episode.id.toString(), episode.episodeNumber, episode.name,
                episode.stillPath?.w500, episode.overview, series, Video.Type.Episode.Season(season.seasonNumber, season.name)), restart)
        }

        fun loadSeason(season: TMDb3.Season) {
            for (i in available.indices) (seasons.getChildAt(i) as? LinearLayout)?.getChildAt(1)?.isActivated = available[i] == season
            if (seasonNumber == season.seasonNumber && episodes.childCount > 0) return
            seasonNumber = season.seasonNumber
            episodes.removeAllViews()
            episodeScroll.scrollTo(0, 0)
            notice.text = "${season.name} · Loading episodes…"
            request {
                val list = episodesFor(season)
                notice.text = "${season.name} · ${list.size} episodes"
                val stillCounts = list.mapNotNull { it.stillPath }.groupingBy { it }.eachCount()
                val missing = mutableListOf<Pair<TMDb3.Episode, ImageView>>()
                for (episode in list) {
                    val row = LinearLayout(requireContext()).apply {
                        isFocusable = true
                        gravity = Gravity.CENTER_VERTICAL
                        background = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), tile(Color.rgb(34, 114, 109)))
                            addState(intArrayOf(), tile(ink))
                        }
                        setPadding(dp(6), dp(4), dp(6), dp(4))
                    }
                    val still = ImageView(requireContext()).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    val preview = FrameLayout(requireContext())
                    preview.addView(label("S${season.seasonNumber} · E${episode.episodeNumber}", 17f).apply { gravity = Gravity.CENTER })
                    preview.addView(still)
                    row.addView(preview, dp(142), dp(80))
                    val path = episode.stillPath?.takeIf { stillCounts[it] == 1 && it != tv.backdropPath && it != tv.posterPath && it != season.posterPath }
                    val previewUrl = path?.w500 ?: EpisodeArtwork.image(tv.id, episode.name)
                    if (previewUrl == null) missing.add(episode to still) else artwork(still, previewUrl)
                    val summary = column().apply { setPadding(dp(12), 0, 0, 0) }
                    row.addView(summary)
                    summary.addView(label("${episode.episodeNumber}. ${episode.name.orEmpty()}", 17f))
                    summary.addView(label(episode.overview.orEmpty(), 14f))
                    row.setOnClickListener { startEpisode(season, episode) }
                    episodes.addView(row)
                }
                if (missing.isNotEmpty()) {
                    val images = EpisodeArtwork.lookup(tv.externalIds?.tvdbId, tv.externalIds?.imdbId)
                    for ((episode, image) in missing) artwork(image, images["${season.seasonNumber}/${EpisodeArtwork.key(episode.name)}"])
                }
            }
        }
        val tabs = available.map { season ->
            val entry = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
            val poster = ImageView(requireContext()).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            entry.addView(poster, dp(32), dp(48))
            artwork(poster, (season.posterPath ?: tv.posterPath)?.w500)
            seasons.addView(entry)
            button(season.name) { loadSeason(season) }.apply {
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), tile(mint))
                    addState(intArrayOf(android.R.attr.state_activated), tile(Color.rgb(34, 114, 109)))
                    addState(intArrayOf(), tile(ink))
                }
                setOnFocusChangeListener { _, focused ->
                    if (focused) loadSeason(season)
                }
                setOnHoverListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_HOVER_ENTER) requestFocusFromTouch()
                    false
                }
                entry.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }

        fun openEpisodes() {
            if (tabs.isEmpty() || browser.visibility == View.VISIBLE) return
            job?.cancel()
            episodes.removeAllViews()
            hero.visibility = View.GONE
            browser.visibility = View.VISIBLE
            showStatus = notice
            val index = available.indexOfFirst { it.seasonNumber == seasonNumber }.coerceAtLeast(0)
            tabs[index].requestFocus()
        }

        fun openHero() {
            returnToHero = null
            browser.visibility = View.GONE
            hero.visibility = View.VISIBLE
            showStatus = heroStatus
            primary.requestFocus()
            restart.isEnabled = false
            primary.text = "Play episode"
            primary.setOnClickListener { openHero() }
            heroStatus.text = "Loading episode…"
            request {
                val db = AppDatabase.getInstance(requireContext())
                val (lastId, history) = withContext(Dispatchers.IO) {
                    db.tvShowDao().getById(tv.id.toString())?.lastPlayedEpisodeId to
                        db.episodeDao().getByTvShowId(tv.id.toString()).associateBy { it.id }
                }
                val anchor = history[lastId] ?: history.values.maxByOrNull {
                    maxOf(it.watchHistory?.lastEngagementTimeUtcMillis ?: 0L, it.watchedDate?.timeInMillis ?: 0L)
                }?.takeIf { it.isWatched || it.watchHistory != null }
                var passedAnchor = anchor == null
                var first: Pair<TMDb3.Season, TMDb3.Episode>? = null
                var target: Pair<TMDb3.Season, TMDb3.Episode>? = null
                for (season in available) {
                    for (episode in episodesFor(season)) {
                        if (first == null) first = season to episode
                        if (episode.id.toString() == anchor?.id) passedAnchor = true
                        if (passedAnchor && history[episode.id.toString()]?.isWatched != true) {
                            target = season to episode
                            break
                        }
                    }
                    if (target != null) break
                }
                val choice = target ?: first
                if (choice == null) { heroStatus.text = "No episodes available"; return@request }
                val (season, episode) = choice
                val progress = history[episode.id.toString()]?.watchHistory
                val resume = (progress?.lastPlaybackPositionMillis ?: 0L) > 0 && target != null
                seasonNumber = season.seasonNumber
                episodeTitle.text = "S${season.seasonNumber} E${episode.episodeNumber} · ${episode.name.orEmpty()}"
                synopsis.text = episode.overview?.takeIf { it.isNotBlank() } ?: tv.overview
                val remaining = ((progress?.durationMillis ?: 0) - (progress?.lastPlaybackPositionMillis ?: 0))
                heroStatus.text = if (resume && remaining > 0) "${(remaining + 59999) / 60000} min remaining" else ""
                primary.text = when {
                    resume -> "Continue"
                    target == null -> "Watch again"
                    anchor != null -> "Play next episode"
                    else -> "Play first episode"
                }
                primary.setOnClickListener { startEpisode(season, episode) }
                restart.isEnabled = true
                restart.setOnClickListener { startEpisode(season, episode, true) }
            }
        }

        fun enterBrowser() {
            openEpisodes()
            if (browser.visibility == View.VISIBLE) returnToHero = ::openHero
        }
        browse.setOnClickListener { enterBrowser() }
        primary.setOnKeyListener { _, key, event ->
            if (key == android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
                enterBrowser()
                true
            } else false
        }
        tabs.firstOrNull()?.setOnKeyListener { _, key, event ->
            if (key == android.view.KeyEvent.KEYCODE_DPAD_UP && event.action == android.view.KeyEvent.ACTION_DOWN) {
                openHero()
                true
            } else false
        }
        openHero()
    }

    private fun play(title: KidTitle, type: Video.Type, restart: Boolean = false) {
        if (hidden.contains(title.key)) return
        request {
            val provider = requireNotNull(UserPreferences.currentProvider)
            val id = if (type is Video.Type.Episode) type.id else title.id.toString()
            (showStatus ?: status).text = "Finding a playable source for ${title.name}…"
            val discovery = ServerAvailability.prefetch(provider, id, type, refresh = true)
            val first = discovery.first { it.finished || it.servers.isNotEmpty() }
            val server = if (type is Video.Type.Episode && type.tvShow.id in setOf("888", "10926")) {
                // Give the English edition time to buffer if a dubbed source answers first.
                val result = withTimeoutOrNull(15_000L) {
                    discovery.first { it.finished || it.servers.any { s -> s.name == "Internet Archive (English)" } }
                } ?: discovery.value
                result.servers.firstOrNull { it.name == "Internet Archive (English)" }
                    ?: result.servers.firstOrNull()
            } else first.servers.firstOrNull()
            if (server == null) {
                (showStatus ?: status).text = "No playable source for ${title.name}. Select the title to try again."
                return@request
            }
            if (hidden.contains(title.key)) return@request
            val args = Bundle().apply {
                putString("id", id)
                putString("title", title.name)
                putString("subtitle", if (type is Video.Type.Episode) "S${type.season.number} E${type.number} · ${type.title.orEmpty()}" else "")
                putBoolean("restartFromBeginning", restart)
                putString("preferredServerName", server.name)
                putParcelable("videoType", type)
            }
            findNavController().navigate(R.id.player, args)
        }
    }
}
