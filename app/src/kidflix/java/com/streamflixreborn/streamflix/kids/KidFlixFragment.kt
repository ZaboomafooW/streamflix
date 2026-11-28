package com.streamflixreborn.streamflix.kids

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.streamflixreborn.streamflix.utils.ServerAvailability
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.delay
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import androidx.activity.OnBackPressedCallback
import android.os.Bundle
import android.view.KeyEvent
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
                if (anchors.isEmpty()) {
                    channel = null
                    home()
                }
                jumpTo(entry)
            }
            item.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), tile(mint))
                addState(intArrayOf(android.R.attr.state_selected), tile(Color.rgb(34, 114, 109)))
                addState(intArrayOf(), tile(ink))
            }
            destinations[entry] = item
            rail.addView(item)
        }
        val stage = FrameLayout(requireContext())
        shell.addView(stage, LinearLayout.LayoutParams(0, -1, 1f))
        val content = column().apply { setPadding(dp(14), 0, dp(14), dp(10)) }
        val toolbar = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        content.addView(toolbar)
        searchField = EditText(requireContext()).apply {
            setSingleLine()
            hint = "Search KidFlix"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setText(query)
            doAfterTextChanged {
                if (!resettingSearch) {
                    val term = it.toString().trim()
                    if (term.isEmpty()) home() else open(null, term)
                }
            }
        }
        toolbar.addView(searchField, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(button("⚙") { settings() }.apply { contentDescription = "Settings" }, dp(56), dp(48))
        val screenHeight = resources.configuration.screenHeightDp
        val reserved = dp(screenHeight * 23 / 100)
        val fade = dp(screenHeight / 6)
        heroPanel = FrameLayout(requireContext()).apply { setBackgroundColor(ink) }
        stage.addView(heroPanel, FrameLayout.LayoutParams(-1, reserved + fade))
        browseHero = ImageView(requireContext()).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        heroPanel.addView(browseHero, -1, -1)
        heroPanel.addView(View(requireContext()).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(195, 18, 33, 54), Color.TRANSPARENT))
        }, -1, -1)
        heroPanel.addView(View(requireContext()).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.rgb(19, 38, 66)))
        }, FrameLayout.LayoutParams(-1, fade, Gravity.BOTTOM))
        stage.addView(content, -1, -1)
        val caption = column().apply { gravity = Gravity.CENTER_VERTICAL }
        heroName = label("", 24f).apply {
            setTypeface(null, Typeface.BOLD)
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        heroMeta = label("", 14f).apply { setTextColor(mint) }
        caption.addView(heroName)
        caption.addView(heroMeta)
        content.addView(caption, -1, (reserved - dp(48)).coerceAtLeast(dp(45)))
        status = label("", 18f)
        content.addView(status)
        body = column()
        scroll = ScrollView(requireContext()).apply {
            addView(body)
            body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> post { activateVisibleShelves() } }
            setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
                activateVisibleShelves()
                if (anchors.isNotEmpty() && body.height > 0) {
                    val entry = if (!canScrollVertically(1) && y > 0) Destination.CHANNELS
                        else anchors.entries.lastOrNull { it.value.top <= y + dp(4) }?.key
                    if (entry != null) highlight(entry)
                }
            }
        }
        content.addView(scroll)
        return FrameLayout(requireContext()).apply { addView(shell) }
    }

    private enum class Destination(val caption: String) {
        HOME("Home"), SHOWS("TV Shows"), MOVIES("Movies"), CHANNELS("Channels")
    }

    private lateinit var heroPanel: FrameLayout
    private lateinit var heroName: TextView
    private lateinit var heroMeta: TextView
    private lateinit var browseHero: ImageView
    private var heroJob: Job? = null
    private var heroKey: String? = null
    private val backdrops get() = requireContext().getSharedPreferences("kidflix_backdrops", Context.MODE_PRIVATE)
    private val channelArt = mutableMapOf<String, KidTitle>()
    private val anchors = linkedMapOf<Destination, View>()
    private var destination = Destination.HOME

    private fun markSection(entry: Destination) {
        val heading = label(entry.caption, 22f).apply { setTypeface(null, Typeface.BOLD) }
        body.addView(heading)
        anchors[entry] = heading
    }

    private fun highlight(entry: Destination) {
        if (destination == entry && status.text == entry.caption) return
        destination = entry
        destinations.forEach { (key, button) -> button.isSelected = key == entry }
        status.text = entry.caption
    }

    private fun jumpTo(entry: Destination) {
        val anchor = anchors[entry] ?: return
        anchor.post {
            if (anchors[entry] !== anchor) return@post
            val start = body.indexOfChild(anchor)
            val end = anchors[Destination.values().getOrNull(entry.ordinal + 1)]?.let(body::indexOfChild) ?: body.childCount
            for (i in start + 1 until end) {
                val first = body.getChildAt(i).getFocusables(View.FOCUS_FORWARD)
                    .firstOrNull { it !is ScrollView && it !is HorizontalScrollView && it.isShown }
                if (first?.requestFocus() == true) break
            }
            scroll.scrollTo(0, anchor.top)
            highlight(entry)
        }
    }
    private lateinit var searchField: EditText
    private var resettingSearch = false
    private var channelMovies = false
    private var channel: String? = null
    private val destinations = mutableMapOf<Destination, Button>()
    private lateinit var scroll: ScrollView
    private val canGoBack get() = shelf != null || query.isNotBlank() || channel != null

    private var currentMovie: Pair<KidTitle, TMDb3.Movie.Detail>? = null
    private var homeExpansion: Job? = null
    private var currentShow: Pair<KidTitle, TMDb3.Tv.Detail>? = null
    private var seasonNumber = 0
    private var showStatus: TextView? = null
    private var returnToHero: (() -> Unit)? = null
    private var browseFocus: View? = null
    private lateinit var browseBack: OnBackPressedCallback

    private val browseFocusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        if (focused != null && body.hasFocus()) {
            val title = when (val tag = focused.tag) {
                is KidTitle -> tag
                is String -> channelArt[tag]
                else -> null
            }
            if (title != null) showBrowseHero(title)
            var row: View = focused
            while (row.parent is View && row.parent !== body) row = row.parent as View
            if (row.parent === body) {
                anchors.entries.lastOrNull { body.indexOfChild(it.value) <= body.indexOfChild(row) }?.let { highlight(it.key) }
            }
        }
        browseBack.isEnabled = currentShow != null || currentMovie != null || canGoBack || body.hasFocus()
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        browseBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (currentShow == null && currentMovie == null) {
                    if (anchors.isNotEmpty()) {
                        destinations[destination]?.requestFocus()
                        return
                    }
                    val previousSection = destination
                    val previousChannel = channel.takeIf { shelf == null && query.isBlank() }
                    if (shelf == null && query.isBlank()) {
                        if (channel != null) channel = null else destination = Destination.HOME
                    }
                    home()
                    if (previousChannel == null && previousSection != Destination.HOME && anchors.isNotEmpty()) {
                        jumpTo(previousSection)
                        return
                    }
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
        view.viewTreeObserver.addOnGlobalFocusChangeListener(browseFocusListener)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,browseBack)
        browseBack.isEnabled = canGoBack
        if (shelf != null || query.isNotBlank()) render() else home()
        currentMovie?.let { (title, movie) -> moviePage(title, movie) }
        currentShow?.let { (title, tv) -> showPage(title, tv) }
    }
    override fun onDestroyView() {
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(browseFocusListener)
        job?.cancel()
        showStatus = null
        browseFocus = null
        returnToHero = null
        destinations.clear()
        clearBrowseHero()
        pendingShelves.clear()
        anchors.clear()
        super.onDestroyView()
    }
    private fun home() {
        job?.cancel()
        shelf = null
        query = ""
        if (searchField.text.isNotEmpty()) {
            resettingSearch = true
            searchField.text.clear()
            resettingSearch = false
        }
        pendingShelves.clear()
        anchors.clear()
        titles.clear()
        body.removeAllViews()
        scroll.scrollTo(0, 0)
        destinations.forEach { (entry, item) -> item.isSelected = entry == destination }
        status.text = channel ?: destination.caption
        browseBack.isEnabled = canGoBack
        homeExpansion?.cancel()
        val catalog = KidCatalog.catalog.filterNot { hidden.contains(it.key) }.sortedWith(KidCatalog.newestFirst)
        channel?.let { brand ->
            clearBrowseHero()
            channelPage(brand, catalog)
            return
        }
        heroPanel.visibility = View.VISIBLE
        catalog.firstOrNull()?.let(::showBrowseHero)
        markSection(Destination.HOME)
        highlight(Destination.HOME)
        val watching = column().apply { visibility = View.GONE }
        body.addView(watching)
        addShelf("Newest Releases", catalog.take(20))
        for (genre in KidCatalog.homeGenres) addShelf(genre, catalog.filter { genre in it.genres })


        val sections = KidCatalog.shelves.sortedWith(compareBy<Shelf> { it.movie }
            .thenByDescending { it.brand == "PBS KIDS" }
            .thenByDescending { section -> catalog.firstOrNull { it.belongsTo(section) }?.releaseDate.orEmpty() }
            .thenBy { it.label })
        sections.forEach { section ->
            val entry = if (section.movie) Destination.MOVIES else Destination.SHOWS
            if (entry !in anchors) markSection(entry)
            val preview = catalog.filter { it.belongsTo(section) }
            if (preview.isNotEmpty() || section.ids.isNotEmpty()) {
                val header = LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0,dp(12),0,dp(6))
                }
                header.addView(TextView(requireContext()).apply {
                    text = section.brand
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    setTypeface(null,Typeface.BOLD)
                },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
                body.addView(header)
                feedRow(preview.take(20), section)
            } else if (KidCatalog.catalog.none { it.belongsTo(section) }) {
                body.addView(button(section.label) { open(section, "") })
            }
        }
        markSection(Destination.CHANNELS)
        channelCards(catalog)
        homeExpansion = viewLifecycleOwner.lifecycleScope.launch { continueWatching(watching) }
    }

    private fun feedRow(initial: List<KidTitle>, section: Shelf? = null) {
        val items = initial.toMutableList()
        val list = object : RecyclerView(requireContext()) {
            override fun focusSearch(focused: View, direction: Int): View? {
                val target = super.focusSearch(focused, direction)
                if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
                    if (target == null || findContainingItemView(target) == null) return focused
                }
                return target
            }
        }
        val cards = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size + if (section == null) 0 else 1
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val frame = FrameLayout(requireContext()).apply {
                    layoutParams = RecyclerView.LayoutParams(dp(114), -1)
                }
                return object : RecyclerView.ViewHolder(frame) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val frame = holder.itemView as FrameLayout
                frame.removeAllViews()
                val view = items.getOrNull(position)?.let(::card) ?: button("More") {
                    open(section, "")
                }.apply { contentDescription = "More ${section?.label}" }
                frame.addView(view, -1, -2)
            }
        }
        list.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        list.isNestedScrollingEnabled = false
        list.itemAnimator = null
        list.setHasFixedSize(true)
        // A fixed-size shelf can participate in TV focus before its cards are needed.
        list.isFocusable = true
        fun activate() {
            if (list.adapter != null || !list.isAttachedToWindow) return
            list.adapter = cards
            if (list.isFocused) list.post { if (list.isFocused) list.getChildAt(0)?.requestFocus(View.FOCUS_FORWARD) }
            if (section != null) viewLifecycleOwner.lifecycleScope.launch {
                KidCatalog.expand(section) { title ->
                    if (list.isAttachedToWindow && !hidden.contains(title.key) && items.none { it.key == title.key }) {
                        val index = items.indexOfFirst { KidCatalog.newestFirst.compare(title, it) < 0 }
                            .let { if (it < 0) items.size else it }
                        if (index < 20) {
                            items.add(index, title)
                            cards.notifyItemInserted(index)
                            if (items.size > 20) { items.removeAt(20); cards.notifyItemRemoved(20) }
                        }
                    }
                }
            }
        }
        list.setOnFocusChangeListener { _, focused -> if (focused) activate() }
        pendingShelves[list] = ::activate
        body.addView(list, -1, dp(190))
    }

    private val pendingShelves = mutableMapOf<View, () -> Unit>()
    private fun activateVisibleShelves() {
        val top = scroll.scrollY - dp(190)
        val bottom = scroll.scrollY + scroll.height + dp(190)
        val iterator = pendingShelves.iterator()
        while (iterator.hasNext()) {
            val (row, activate) = iterator.next()
            if (row.parent !== body) iterator.remove()
            else if (row.height > 0 && row.bottom >= top && row.top <= bottom) {
                iterator.remove()
                activate()
            }
        }
    }

    private fun titleRow() = object : LinearLayout(requireContext()) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val step = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return super.dispatchKeyEvent(event)
            }
            val index = indexOfChild(focusedChild)
            if (index < 0) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                getChildAt(index + step)?.takeIf { it.isFocusable && it.isShown }?.requestFocus()
            }
            return true
        }
    }

    private fun addShelf(name: String, items: List<KidTitle>) {
        if (items.isEmpty()) return
        body.addView(label(name, 20f).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(6))
        })
        feedRow(items)
    }

    private fun channelName(shelf: Shelf) = channelGroup(shelf.brand)
    private fun channelGroup(brand: String) = when (brand) {
        "Fox Kids", "4Kids TV / FoxBox", "The CW4Kids / Toonzai" -> "Fox Kids / 4Kids TV"
        "Disney", "Disney Junior / Playhouse Disney", "Disney XD", "Jetix", "ABC Kids / One Saturday Morning" -> "Disney"
        "Nickelodeon", "Nick Jr.", "Nicktoons" -> "Nickelodeon"
        "Cartoon Network", "Boomerang", "Cartoonito", "Toonami" -> "Cartoon Network"
        "CBeebies", "CBBC" -> "BBC Kids"
        "Hulu / 20th Century" -> "Hulu"
        else -> brand
    }

    private fun channelPage(brand: String, catalog: List<KidTitle>) {
        val sections = KidCatalog.shelves.filter { channelName(it) == brand }
        val tabs = titleRow()
        body.addView(tabs)
        val grid = column()
        body.addView(grid)
        fun display(movies: Boolean) {
            job?.cancel()
            channelMovies = movies
            for (i in 0 until tabs.childCount) tabs.getChildAt(i).isSelected = tabs.getChildAt(i).tag == movies
            val items = catalog.filter { title -> title.shelf.movie == movies &&
                (channelName(title.shelf) == brand || title.channels.any { channelGroup(it) == brand }) }.associateBy { it.key }.toMutableMap()
            val pages = sections.filter { it.movie == movies && it.ids.isNotEmpty() }.associateWith { 1 }.toMutableMap()
            fun draw() {
                grid.removeAllViews()
                titleGrid(grid, items.values.sortedWith(KidCatalog.newestFirst))
                if (pages.isNotEmpty()) grid.addView(button("Load more") {
                    val trigger = grid.getChildAt(grid.childCount - 1)
                    trigger.isEnabled = false
                    job = viewLifecycleOwner.lifecycleScope.launch {
                        var failed = false
                        for ((section, n) in pages.toMap()) {
                            try {
                                val result = KidCatalog.page(section, "", n)
                                result.titles.filterNot { hidden.contains(it.key) }.forEach { items[it.key] = it }
                                if (result.more) pages[section] = n + 1 else pages.remove(section)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failed = true }
                        }
                        status.text = if (failed) "$brand · Some titles could not load" else brand
                        draw()
                    }
                })
                if (items.isEmpty() && pages.isEmpty()) grid.addView(label("No visible titles", 16f))
            }
            draw()
        }
        val types = sections.map { it.movie }.distinct().sorted()
        types.forEach { movie ->
            tabs.addView(button(if (movie) "Movies" else "TV Shows") { display(movie) }.apply {
                tag = movie
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), tile(mint))
                    addState(intArrayOf(android.R.attr.state_selected), tile(Color.rgb(34, 114, 109)))
                    addState(intArrayOf(), tile(ink))
                }
                isSelected = movie == channelMovies
            })
        }
        display(if (channelMovies in types) channelMovies else types.first())
    }

    private fun channelCards(catalog: List<KidTitle>) {
        // The first title for each brand is its newest visible release.
        val newest = mutableMapOf<String, KidTitle>()
        for (title in catalog) {
            for (brand in title.channels.map(::channelGroup) + channelName(title.shelf)) newest.getOrPut(brand) { title }
        }
        channelArt.clear()
        channelArt.putAll(newest)
        val brands = KidCatalog.shelves.map(::channelName).distinct().sortedWith(
            compareByDescending<String> { it == "PBS KIDS" }.thenByDescending { newest[it]?.releaseDate.orEmpty() }.thenBy { it })
        val row = titleRow()
        body.addView(HorizontalScrollView(requireContext()).apply { addView(row) })
        for (brand in brands) {
            val tile = column().apply {
                background = focusBackground()
                isFocusable = true
                setPadding(dp(4), dp(4), dp(4), dp(4))
                tag = brand
                contentDescription = brand
                setOnHoverListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_HOVER_ENTER) newest[brand]?.let(::showBrowseHero)
                    false
                }
                setOnClickListener {
                    channelMovies = false
                    channel = brand
                    home()
                    body.requestFocus(View.FOCUS_DOWN)
                }
            }
            val image = ImageView(requireContext()).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            tile.addView(image, -1, dp(150))
            artwork(image, newest[brand]?.poster?.w500)
            tile.addView(label(brand, 16f).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(ink)
            })
            row.addView(tile, LinearLayout.LayoutParams(dp(114), -2).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
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
        val watching = mutableListOf<Pair<KidTitle, Long>>()
        for (movie in movies) {
            val title = catalog["movie/${movie.id}"] ?: try {
                movie.id.toIntOrNull()?.let { KidCatalog.movie(it, null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { null }
            if (title != null) watching.add(title to (movie.watchHistory?.lastEngagementTimeUtcMillis ?: 0L))
        }
        for ((id, history) in episodes.groupBy { it.tvShow?.id }) {
            if (id == null || hidden.contains("tv/$id")) continue
            val title = catalog["tv/$id"] ?: try {
                id.toIntOrNull()?.let { KidCatalog.show(it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { null }
            if (title == null) continue
            val lastPlayed = history.maxOf { maxOf(it.watchHistory?.lastEngagementTimeUtcMillis ?: 0L, it.watchedDate?.timeInMillis ?: 0L) }
            if (history.any { !it.isWatched && it.watchHistory != null }) {
                watching.add(title to lastPlayed)
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
            if (latest != 0 && history.none { it.id == latest.toString() && it.isWatched }) watching.add(title to lastPlayed)
        }
        val visible = watching.sortedByDescending { it.second }.map { it.first }.filterNot { hidden.contains(it.key) }
        if (visible.isEmpty()) return
        section.addView(label("Continue Watching", 20f).apply { setTypeface(null, Typeface.BOLD) })
        val row = titleRow()
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
        clearBrowseHero()
        pendingShelves.clear()
        anchors.clear()
        job?.cancel()
        homeExpansion?.cancel()
        scroll.scrollTo(0, 0)
        shelf = selection
        query = term
        browseBack.isEnabled = true
        page = 0
        more = false
        titles.clear()
        titles.addAll(KidCatalog.catalog.filter { (selection == null || it.belongsTo(selection)) && it.name.contains(term,true) })
        if (selection != null && selection.ids.isEmpty()) {
            page = 1
            render()
        } else {
            load(debounce = term.isNotBlank())
        }
    }
    private fun load(debounce: Boolean = false) {
        job?.cancel()
        render()
        if (UserPreferences.tmdbApiKey.isBlank() && BuildConfig.TMDB_API_KEY in listOf("null","")) {
            status.text = "Add your TMDb API key in Settings to load titles."
            return
        }
        status.text = "Loading…"
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (debounce) delay(300)
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
        titleGrid(body, visible)
        if (more) body.addView(button("Load more") { load() })
    }

    private fun titleGrid(parent: LinearLayout, visible: List<KidTitle>) {
        val columns = ((resources.configuration.screenWidthDp - 172) / 114).coerceIn(1, 6)
        visible.chunked(columns).forEach { batch ->
            val row = titleRow()
            batch.forEach { title ->
                row.addView(card(title),dp(114),ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            parent.addView(row)
        }
    }

    private fun clearBrowseHero() {
        heroJob?.cancel()
        heroKey = null
        Glide.with(browseHero).clear(browseHero)
        heroName.text = ""
        heroMeta.text = ""
    }

    private fun showBrowseHero(title: KidTitle) {
        heroPanel.visibility = View.VISIBLE
        if (heroKey == title.key) return
        heroKey = title.key
        heroJob?.cancel()
        heroJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(180)
            browseHero.scaleType = ImageView.ScaleType.CENTER_CROP
            heroName.text = title.name
            heroMeta.text = listOf(title.releaseDate.take(4), title.shelf.brand).filter { it.isNotBlank() }.joinToString(" · ")
            val path = backdrops.getString(title.key, null) ?: try {
                val backdrop = if (title.shelf.movie) TMDb3.Movies.details(title.id).backdropPath
                    else TMDb3.TvSeries.details(title.id).backdropPath
                backdrop.orEmpty().also { backdrops.edit().putString(title.key, it).apply() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { "" }
            val url = if (path.isNotBlank()) "https://image.tmdb.org/t/p/w1280$path" else title.poster?.w500
            if (url.isNullOrBlank()) {
                Glide.with(browseHero).clear(browseHero)
            } else {
                val image = try { KidArtwork.localCopy(browseHero.context.applicationContext, url) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { url }
                if (heroKey == title.key) {
                    browseHero.scaleType = if (path.isBlank()) ImageView.ScaleType.FIT_END else ImageView.ScaleType.CENTER_CROP
                    Glide.with(browseHero).load(image).into(browseHero)
                }
            }
        }
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
            setOnHoverListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_HOVER_ENTER) showBrowseHero(title)
                false
            }
            setOnClickListener { select(title) }
            setOnLongClickListener {
                job?.cancel()
                hidden.edit().putString(title.key,title.name).apply()
                if (shelf == null && query.isEmpty()) home() else render()
                true
            }
        }
        val image = object : ImageView(requireContext()) {
            private var requested = false
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                if (!requested) {
                    requested = true
                    artwork(this, title.poster?.w500)
                }
            }
        }.apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        card.addView(image,dp(98),dp(138))
        card.addView(TextView(requireContext()).apply { text = title.name; maxLines = 2; gravity = Gravity.CENTER; setTextColor(Color.WHITE) })
        return card
    }
    private fun select(title: KidTitle) {
        if (hidden.contains(title.key)) return
        request {
            if (title.shelf.movie) {
                val m = TMDb3.Movies.details(title.id, listOf(TMDb3.Params.AppendToResponse.Movie.RELEASES_DATES))
                val db = AppDatabase.getInstance(requireContext())
                withContext(Dispatchers.IO) {
                    db.movieDao().save(Movie(id=m.id.toString(), title=m.title, overview=m.overview, released=m.releaseDate,
                        poster=m.posterPath?.w500, banner=m.backdropPath?.w500, imdbId=m.imdbId))
                }
                currentMovie = title to m
                moviePage(title, m)
            } else {
                val tv = TMDb3.TvSeries.details(title.id, listOf(TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS, TMDb3.Params.AppendToResponse.Tv.CONTENT_RATING))
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
        val releases = movie.releaseDates?.results.orEmpty().filter { it.iso3166 == "US" }
            .flatMap { it.releaseDates }.filter { !it.certification.isNullOrBlank() }
        val rating = (releases.firstOrNull { it.type == TMDb3.Movie.ReleaseType.THEATRICAL }
            ?: releases.firstOrNull())?.certification ?: "US rating unavailable"
        info.addView(label(listOfNotNull(movie.releaseDate?.take(4)?.ifBlank { null }, rating).joinToString(" · "), 15f))
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
        val rating = tv.contentRatings?.results.orEmpty()
            .firstOrNull { it.iso3166 == "US" && it.rating.isNotBlank() }?.rating ?: "US rating unavailable"
        val seasonCount = if (available.size == 1) "1 Season" else "${available.size} Seasons"
        info.addView(label(listOfNotNull(tv.firstAirDate?.take(4)?.ifBlank { null }, seasonCount, rating).joinToString(" · "), 15f))
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
