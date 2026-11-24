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
        return root
    }
    private lateinit var browseBack: OnBackPressedCallback

    override fun onViewCreated(view: View, state: Bundle?) {
        browseBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = home()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,browseBack)
        browseBack.isEnabled = shelf != null || query.isNotBlank()
        if (shelf != null || query.isNotBlank()) render() else home()
    }
    override fun onDestroyView() {
        job?.cancel()
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
                preview.forEach { row.addView(card(it),dp(114),ViewGroup.LayoutParams.WRAP_CONTENT) }
                body.addView(HorizontalScrollView(requireContext()).apply { addView(row) })
            } else if (KidCatalog.catalog.none { it.shelf == section }) {
                body.addView(button(section.label) { open(section, "") })
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
        shelf = selection
        query = term
        browseBack.isEnabled = true
        page = 0
        more = false
        titles.clear()
        titles.addAll(KidCatalog.catalog.filter { (selection == null || it.shelf == selection) && it.name.contains(term,true) })
        load()
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
        Glide.with(this).load(title.poster?.w500).override(dp(98),dp(138)).into(image)
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
                play(title, Video.Type.Movie(m.id.toString(),m.title,m.releaseDate.orEmpty(),m.posterPath?.w500.orEmpty(),m.imdbId))
            } else {
                val tv = TMDb3.TvSeries.details(title.id,listOf(TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS))
                val db = AppDatabase.getInstance(requireContext())
                withContext(Dispatchers.IO) {
                    db.tvShowDao().save(TvShow(id=tv.id.toString(), title=tv.name, overview=tv.overview, released=tv.firstAirDate,
                        poster=tv.posterPath?.w500, banner=tv.backdropPath?.w500, imdbId=tv.externalIds?.imdbId))
                }
                val seasons = tv.seasons.filter { it.seasonNumber > 0 }
                choose(tv.name,seasons.map { it.name }) { i ->
                    val s = seasons[i]
                    request {
                        val episodes = TMDb3.TvSeasons.details(tv.id,s.seasonNumber).episodes.orEmpty()
                        choose(s.name,episodes.map { "${it.episodeNumber}. ${it.name.orEmpty()}" }) { n ->
                            val e = episodes[n]
                            play(title,Video.Type.Episode(e.id.toString(),e.episodeNumber,e.name,e.stillPath?.w500,e.overview,
                                Video.Type.Episode.TvShow(tv.id.toString(),tv.name,tv.posterPath?.w500,tv.backdropPath?.w500,tv.firstAirDate,tv.externalIds?.imdbId),
                                Video.Type.Episode.Season(s.seasonNumber,s.name)))
                        }
                    }
                }
            }
        }
    }
    private fun request(action: suspend () -> Unit) {
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Couldn’t load this title. Select it to retry." }
        }
    }
    private fun choose(label: String, options: List<String>, select: (Int) -> Unit) {
        if (options.isEmpty()) { status.text = "No episodes available for $label"; return }
        AlertDialog.Builder(requireContext()).setTitle(label).setItems(options.toTypedArray()) { _, i -> select(i) }.show()
    }
    private fun play(title: KidTitle, type: Video.Type) {
        if (hidden.contains(title.key)) return
        request {
            val provider = requireNotNull(UserPreferences.currentProvider)
            val id = if (type is Video.Type.Episode) type.id else title.id.toString()
            status.text = "Finding a playable source for ${title.name}…"
            val result = ServerAvailability.prefetch(provider, id, type, refresh = true).first { it.finished || it.servers.isNotEmpty() }
            val server = result.servers.firstOrNull()
            if (server == null) {
                status.text = "No playable source for ${title.name}. Select the title to try again."
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
