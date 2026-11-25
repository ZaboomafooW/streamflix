package com.streamflixreborn.streamflix.kids

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ContentMovieTvBinding
import com.streamflixreborn.streamflix.databinding.ContentTvShowTvBinding
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.ServerAvailability
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KidFlixFragment : Fragment(R.layout.fragment_home_tv) {
    private val hidden get() = requireContext().getSharedPreferences("kidflix_hidden", Context.MODE_PRIVATE)
    private val grid get() = requireView().findViewById<VerticalGridView>(R.id.vgv_home)
    private val background get() = requireView().findViewById<ImageView>(R.id.iv_home_background)
    private val titles = mutableListOf<KidTitle>()
    private var shelf: Shelf? = null
    private var query = ""
    private var page = 0
    private var more = false
    private var message = ""
    private var job: Job? = null
    private lateinit var back: OnBackPressedCallback
    private val screens = mutableListOf<() -> Unit>()
    private val positions = mutableListOf<Int>()

    private class Cell(val layout: Int = 0, val bind: (View) -> Unit)
    private class Holder(view: View) : RecyclerView.ViewHolder(view)
    private class Cells(private val cells: List<Cell>) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = cells.size
        override fun getItemViewType(position: Int) = cells[position].layout
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = when (viewType) {
                0 -> Button(parent.context).apply {
                    isAllCaps = false
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundResource(R.drawable.bg_btn_exoplayer_tv)
                }
                R.layout.fragment_search_tv -> {
                    val layout = LayoutInflater.from(parent.context).inflate(viewType, parent, false) as ViewGroup
                    val bar = layout.findViewById<View>(R.id.cl_search)
                    layout.removeView(bar)
                    bar.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    bar
                }
                1 -> TextView(parent.context)
                else -> LayoutInflater.from(parent.context).inflate(viewType, parent, false)
            }
            return Holder(view)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) = cells[position].bind(holder.itemView)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.is_loading).visibility = View.GONE
        back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                job?.cancel()
                screens.removeAt(screens.lastIndex)
                val position = positions.removeAt(positions.lastIndex)
                display()
                grid.selectedPosition = position.coerceIn(0, ((grid.adapter?.itemCount ?: 1) - 1).coerceAtLeast(0))
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        if (screens.isEmpty()) screens.add(::home)
        display()
    }

    private fun display() {
        Glide.with(this).clear(background)
        message = ""
        back.isEnabled = screens.size > 1
        screens.last().invoke()
    }

    private fun push(screen: () -> Unit) {
        job?.cancel()
        positions.add(grid.selectedPosition)
        screens.add(screen)
        display()
        grid.selectedPosition = 0
    }

    override fun onDestroyView() {
        job?.cancel()
        grid.adapter = null
        super.onDestroyView()
    }

    private fun show(cells: List<Cell>) {
        grid.adapter = Cells(cells)
    }

    private fun action(label: String, click: () -> Unit) = Cell { view ->
        (view as Button).text = label
        view.setOnClickListener { click() }
    }

    private fun caption(label: String) = Cell(1) { view ->
        (view as TextView).apply {
            text = label
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 8, 32, 8)
        }
    }

    private fun row(label: String, cards: List<Cell>) = Cell(R.layout.item_category_tv) { view ->
        view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        view.findViewById<TextView>(R.id.tv_category_title).text = label
        view.findViewById<HorizontalGridView>(R.id.hgv_category).adapter = Cells(cards)
    }

    private fun home() {
        val rows = mutableListOf(row("KidFlix", listOf(
            action("Search") { push { input("Search KidFlix", query) { open(null, it) } } },
            action("Settings") { push(::settings) }
        )))
        for (section in KidCatalog.shelves) {
            val cards = KidCatalog.catalog.filter { it.shelf == section && !hidden.contains(it.key) }.map(::card)
            rows.add(row(section.label, cards + action("Browse all") { open(section, "") }))
        }
        show(rows)
    }

    private fun input(label: String, initial: String, submit: (String) -> Unit) {
        var field: EditText? = null
        show(listOf(caption(label), Cell(R.layout.fragment_search_tv) { bar ->
            val editor = bar.findViewById<EditText>(R.id.et_search)
            field = editor
            editor.hint = label
            editor.setText(initial)
            editor.imeOptions = EditorInfo.IME_ACTION_DONE
            editor.setOnEditorActionListener { _, _, _ -> submit(editor.text.toString().trim()); true }
            bar.findViewById<View>(R.id.btn_search_voice).visibility = View.GONE
            bar.findViewById<View>(R.id.btn_search_clear).setOnClickListener { editor.text.clear() }
        }, action("OK") { field?.let { submit(it.text.toString().trim()) } }))
    }

    private fun settings() {
        show(listOf(caption("KidFlix settings"), action("Hidden titles") { push(::hiddenTitles) },
            action("TMDb API key") { push { input("TMDb API key", "") {
                UserPreferences.tmdbApiKey = it
                back.handleOnBackPressed()
            } } }))
    }

    private fun hiddenTitles() {
        val entries = hidden.all.entries.sortedBy { it.value.toString() }
        show(listOf(caption(if (entries.isEmpty()) "No hidden titles" else "Select a title to restore")) +
            entries.map { entry -> action(entry.value.toString()) {
                hidden.edit().remove(entry.key).apply()
                hiddenTitles()
            } })
    }

    private fun open(selection: Shelf?, term: String) {
        if (selection == null && term.isBlank()) return
        shelf = selection
        query = term
        page = 0
        more = true
        titles.clear()
        titles.addAll(KidCatalog.catalog.filter { (selection == null || it.shelf == selection) && it.name.contains(term, true) })
        push(::results)
        load()
    }

    private fun results() {
        val label = shelf?.label ?: "Search · $query"
        val cards = titles.distinctBy { it.key }.filterNot { hidden.contains(it.key) }.map(::card)
        val rows = cards.chunked(5).mapIndexed { index, items -> row(if (index == 0) label else "", items) }.toMutableList()
        if (cards.isEmpty()) rows.add(caption(label))
        if (message.isNotEmpty()) rows.add(caption(message))
        if (!more && cards.isEmpty()) rows.add(caption("No matching titles"))
        if (more) rows.add(action(if (page == 0) "Load titles" else "Load more", ::load))
        show(rows)
    }

    private fun load() {
        if (UserPreferences.tmdbApiKey.isBlank() && BuildConfig.TMDB_API_KEY in listOf("null", "")) {
            message = "Add your TMDb API key in Settings to load titles."
            results()
            return
        }
        request("Couldn’t load titles.", ::load) {
            val result = KidCatalog.page(shelf, query, page + 1)
            titles.addAll(result.titles)
            page++
            more = result.more
            message = ""
            results()
        }
    }

    private fun image(view: ImageView, path: String?) {
        Glide.with(view).load(path?.w500).into(view)
    }

    private fun card(title: KidTitle): Cell {
        val movie = title.shelf.movie
        return Cell(if (movie) R.layout.item_movie_tv else R.layout.item_tv_show_tv) { view ->
            view.findViewById<TextView>(if (movie) R.id.tv_movie_title else R.id.tv_tv_show_title).text = title.name
            image(view.findViewById(if (movie) R.id.iv_movie_poster else R.id.iv_tv_show_poster), title.poster)
            view.setOnClickListener { select(title) }
            view.setOnLongClickListener {
                job?.cancel()
                hidden.edit().putString(title.key, title.name).apply()
                screens.last().invoke()
                true
            }
        }
    }

    private fun select(title: KidTitle) {
        if (hidden.contains(title.key)) return
        request("Couldn’t load ${title.name}.", { select(title) }) {
            if (title.shelf.movie) {
                val m = TMDb3.Movies.details(title.id)
                val db = AppDatabase.getInstance(requireContext())
                withContext(Dispatchers.IO) {
                    db.movieDao().save(Movie(id=m.id.toString(), title=m.title, overview=m.overview, released=m.releaseDate,
                        poster=m.posterPath?.w500, banner=m.backdropPath?.w500, imdbId=m.imdbId))
                }
                val type = Video.Type.Movie(m.id.toString(), m.title, m.releaseDate.orEmpty(), m.posterPath?.w500.orEmpty(), m.imdbId)
                push {
                    image(background, m.backdropPath)
                    show(listOf(Cell(R.layout.content_movie_tv) { view ->
                        ContentMovieTvBinding.bind(view).apply {
                            tvMovieTitle.text = m.title
                            tvMovieOverview.text = m.overview
                            tvMovieReleased.text = m.releaseDate
                            image(ivMoviePoster, m.posterPath)
                            ivMovieRatingIcon.visibility = View.GONE
                            btnMovieTrailer.visibility = View.GONE
                            btnMovieFavorite.visibility = View.GONE
                            btnMovieWatchNow.setOnClickListener { play(title, type) }
                        }
                    }))
                }
            } else {
                val tv = TMDb3.TvSeries.details(title.id,listOf(TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS))
                val db = AppDatabase.getInstance(requireContext())
                withContext(Dispatchers.IO) {
                    db.tvShowDao().save(TvShow(id=tv.id.toString(), title=tv.name, overview=tv.overview, released=tv.firstAirDate,
                        poster=tv.posterPath?.w500, banner=tv.backdropPath?.w500, imdbId=tv.externalIds?.imdbId))
                }
                push {
                    image(background, tv.backdropPath)
                    val seasons = tv.seasons.filter { it.seasonNumber > 0 }.map { season ->
                        Cell(R.layout.item_season_tv) { view ->
                            view.findViewById<TextView>(R.id.tv_season_title).text = season.name
                            image(view.findViewById(R.id.iv_season_poster), season.posterPath ?: tv.posterPath)
                            view.setOnClickListener {
                                episodes(title, tv.id, season.seasonNumber, season.name,
                                    Video.Type.Episode.TvShow(tv.id.toString(), tv.name, tv.posterPath?.w500,
                                        tv.backdropPath?.w500, tv.firstAirDate, tv.externalIds?.imdbId))
                            }
                        }
                    }
                    val details = Cell(R.layout.content_tv_show_tv) { view ->
                        ContentTvShowTvBinding.bind(view).apply {
                            tvTvShowTitle.text = tv.name
                            tvTvShowOverview.text = tv.overview
                            tvTvShowReleased.text = tv.firstAirDate
                            image(ivTvShowPoster, tv.posterPath)
                            ivTvShowRatingIcon.visibility = View.GONE
                            btnTvShowTrailer.visibility = View.GONE
                            btnTvShowFavorite.visibility = View.GONE
                            btnTvShowWatchNow.visibility = if (seasons.isEmpty()) View.GONE else View.VISIBLE
                            btnTvShowWatchNow.text = "Seasons"
                            btnTvShowWatchNow.setOnClickListener { grid.selectedPosition = 1 }
                        }
                    }
                    show(listOf(details, if (seasons.isEmpty()) caption("No seasons available") else row("Seasons", seasons)))
                }
            }
        }
    }

    private fun episodes(title: KidTitle, id: Int, number: Int, season: String, showInfo: Video.Type.Episode.TvShow) {
        request("Couldn’t load $season.", { episodes(title, id, number, season, showInfo) }) {
            val episodes = TMDb3.TvSeasons.details(id, number).episodes.orEmpty()
            push {
                val cards = episodes.map { episode ->
                    Cell(R.layout.item_episode_tv) { view ->
                        image(view.findViewById(R.id.iv_episode_poster), episode.stillPath)
                        view.findViewById<TextView>(R.id.tv_episode_info).text = "S$number E${episode.episodeNumber}"
                        view.findViewById<TextView>(R.id.tv_episode_title).text = episode.name
                        view.findViewById<TextView>(R.id.tv_episode_overview).text = episode.overview
                        view.setOnClickListener {
                            play(title, Video.Type.Episode(episode.id.toString(), episode.episodeNumber, episode.name,
                                episode.stillPath?.w500, episode.overview, showInfo, Video.Type.Episode.Season(number, season)))
                        }
                    }
                }
                show(listOf(if (cards.isEmpty()) caption("No episodes available for $season") else row(season, cards)))
            }
        }
    }

    private fun request(error: String, retry: () -> Unit, task: suspend () -> Unit) {
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try { task() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { show(listOf(caption(error), action("Retry", retry))) }
        }
    }

    private fun play(title: KidTitle, type: Video.Type) {
        if (hidden.contains(title.key)) return
        request("Couldn’t start ${title.name}.", { play(title, type) }) {
            val provider = requireNotNull(UserPreferences.currentProvider)
            val id = if (type is Video.Type.Episode) type.id else title.id.toString()
            show(listOf(caption("Finding a playable source for ${title.name}…")))
            val result = ServerAvailability.prefetch(provider, id, type, refresh = true).first { it.finished || it.servers.isNotEmpty() }
            val server = result.servers.firstOrNull()
            if (server == null) {
                show(listOf(caption("No playable source for ${title.name}."), action("Retry") { play(title, type) }))
                return@request
            }
            if (hidden.contains(title.key)) return@request
            val args = Bundle().apply {
                putString("id", id)
                putString("title", title.name)
                putString("subtitle", if (type is Video.Type.Episode) "S${type.season.number} E${type.number} · ${type.title.orEmpty()}" else "")
                putString("preferredServerName", server.name)
                putParcelable("videoType", type)
            }
            findNavController().navigate(R.id.player, args)
        }
    }
}
