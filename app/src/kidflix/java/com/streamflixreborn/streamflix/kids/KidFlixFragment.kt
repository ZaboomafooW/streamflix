package com.streamflixreborn.streamflix.kids

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
        val root = column().apply {
            setBackgroundColor(Color.rgb(19,38,66))
            setPadding(dp(30),dp(18),dp(30),dp(12))
        }
        val tools = LinearLayout(requireContext())
        tools.gravity = Gravity.CENTER_VERTICAL
        tools.addView(TextView(requireContext()).apply {
            text = "KidFlix"
            textSize = 27f
            setTextColor(mint)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(button("Home") { home() })
        tools.addView(button("Search") { input("Search KidFlix", query) { if (it.isNotBlank()) open(null,it.trim()) } })
        tools.addView(button("Settings") { settings() })
        root.addView(tools)
        status = TextView(requireContext()).apply { setPadding(0,dp(12),0,dp(8)); textSize = 17f; setTextColor(Color.WHITE) }
        root.addView(status)
        body = column()
        root.addView(ScrollView(requireContext()).apply { addView(body) })
        return FrameLayout(requireContext()).apply { addView(root) }
    }
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
                if (currentShow == null && currentMovie == null) { home(); return }
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
                isEnabled = shelf != null || query.isNotBlank()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,browseBack)
        browseBack.isEnabled = shelf != null || query.isNotBlank()
        if (shelf != null || query.isNotBlank()) render() else home()
        currentMovie?.let { (title, movie) -> moviePage(title, movie) }
        currentShow?.let { (title, tv) -> showPage(title, tv) }
    }
    override fun onDestroyView() {
        job?.cancel()
        showStatus = null
        browseFocus = null
        returnToHero = null
        super.onDestroyView()
    }
    private fun home() {
        job?.cancel()
        shelf = null
        query = ""
        titles.clear()
        body.removeAllViews()
        status.text = "Browse by brand  ·  Hold OK on a title to hide it"
        browseBack.isEnabled = false
        homeExpansion?.cancel()
        val rows = mutableMapOf<Shelf, LinearLayout>()
        KidCatalog.shelves.forEach { section ->
            val preview = KidCatalog.catalog.filter { it.shelf == section && !hidden.contains(it.key) }
            if (preview.isNotEmpty()) {
                val header = LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0,dp(12),0,dp(6))
                }
                header.addView(TextView(requireContext()).apply {
                    text = section.label
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
            } else if (KidCatalog.catalog.none { it.shelf == section }) {
                body.addView(button(section.label) { open(section, "") })
            }
        }
        homeExpansion = viewLifecycleOwner.lifecycleScope.launch {
            rows.forEach { (section, row) ->
                launch {
                    KidCatalog.expand(section) { title ->
                        if (!hidden.contains(title.key)) row.addView(card(title), dp(114), ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                }
            }
        }
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
        shelf = selection
        query = term
        browseBack.isEnabled = true
        page = 0
        more = false
        titles.clear()
        titles.addAll(KidCatalog.catalog.filter { (selection == null || it.shelf == selection) && it.name.contains(term,true) })
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
        val visible = titles.distinctBy { it.key }.filterNot { hidden.contains(it.key) }
        status.text = (shelf?.label ?: "Search · $query") + if (visible.isEmpty() && page > 0) " · No matching titles on this page" else ""
        visible.chunked(5).forEach { batch ->
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
            val seasonArt = season.posterPath ?: tv.posterPath
            request {
                val list = episodesFor(season)
                notice.text = "${season.name} · ${list.size} episodes"
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
                    row.addView(still, dp(142), dp(80))
                    artwork(still, (episode.stillPath ?: tv.backdropPath ?: seasonArt)?.w500)
                    val summary = column().apply { setPadding(dp(12), 0, 0, 0) }
                    row.addView(summary)
                    summary.addView(label("${episode.episodeNumber}. ${episode.name.orEmpty()}", 17f))
                    summary.addView(label(episode.overview.orEmpty(), 14f))
                    row.setOnClickListener { startEpisode(season, episode) }
                    episodes.addView(row)
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
            val result = ServerAvailability.prefetch(provider, id, type, refresh = true).first { it.finished || it.servers.isNotEmpty() }
            val server = result.servers.firstOrNull()
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
